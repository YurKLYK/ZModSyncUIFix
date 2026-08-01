package com.modsync;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChunkedPayloadCodecTest {
    @Test
    void splitReturnsSingleEmptyChunkForEmptyPayload() {
        assertEquals(List.of(""), ChunkedPayloadCodec.split("", 4));
    }

    @Test
    void splitPreservesExactChunkBoundaries() {
        assertEquals(List.of("abcd", "efgh"), ChunkedPayloadCodec.split("abcdefgh", 4));
    }

    @Test
    void splitHandlesTailShorterThanChunkSize() {
        assertEquals(List.of("abcd", "ef"), ChunkedPayloadCodec.split("abcdef", 4));
    }

    @Test
    void accumulatorReassemblesMultiChunkPayload() {
        ChunkedPayloadCodec.ChunkAccumulator accumulator = new ChunkedPayloadCodec.ChunkAccumulator();

        assertNull(accumulator.accept(0, 3, "abc"));
        assertNull(accumulator.accept(1, 3, "def"));
        assertEquals("abcdefghi", accumulator.accept(2, 3, "ghi"));
    }

    @Test
    void accumulatorResetsWhenNewSequenceStarts() {
        ChunkedPayloadCodec.ChunkAccumulator accumulator = new ChunkedPayloadCodec.ChunkAccumulator();

        assertNull(accumulator.accept(0, 2, "stale"));
        assertNull(accumulator.accept(0, 2, "fresh-"));
        assertEquals("fresh-payload", accumulator.accept(1, 2, "payload"));
    }

    @Test
    void accumulatorRejectsOutOfOrderChunkSequence() {
        ChunkedPayloadCodec.ChunkAccumulator accumulator = new ChunkedPayloadCodec.ChunkAccumulator();

        assertNull(accumulator.accept(0, 3, "abc"));
        assertNull(accumulator.accept(2, 3, "ghi"));
        assertNull(accumulator.accept(0, 2, "fresh-"));
        assertEquals("fresh-payload", accumulator.accept(1, 2, "payload"));
    }

    @Test
    void accumulatorRejectsMismatchedTotalChunkCount() {
        ChunkedPayloadCodec.ChunkAccumulator accumulator = new ChunkedPayloadCodec.ChunkAccumulator();

        assertNull(accumulator.accept(0, 3, "abc"));
        assertNull(accumulator.accept(1, 2, "def"));
        assertNull(accumulator.accept(0, 2, "fresh-"));
        assertEquals("fresh-payload", accumulator.accept(1, 2, "payload"));
    }

    @Test
    void accumulatorRejectsAbsurdlyLargeDeclaredChunkCount() {
        ChunkedPayloadCodec.ChunkAccumulator accumulator = new ChunkedPayloadCodec.ChunkAccumulator();

        assertNull(accumulator.accept(0, Integer.MAX_VALUE, "abc"));
        // The accumulator must not have latched onto the bogus sequence; a legitimate
        // sequence should still work afterwards.
        assertNull(accumulator.accept(0, 2, "fresh-"));
        assertEquals("fresh-payload", accumulator.accept(1, 2, "payload"));
    }
}
