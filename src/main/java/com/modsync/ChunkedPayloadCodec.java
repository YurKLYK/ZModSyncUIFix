package com.modsync;

import java.util.ArrayList;
import java.util.List;

final class ChunkedPayloadCodec {
    private ChunkedPayloadCodec() {
    }

    static List<String> split(String payload, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (payload == null || payload.isEmpty()) {
            chunks.add("");
            return chunks;
        }

        for (int index = 0; index < payload.length(); index += chunkSize) {
            chunks.add(payload.substring(index, Math.min(payload.length(), index + chunkSize)));
        }
        return chunks;
    }

    static final class ChunkAccumulator {
        // Bounds are generous for any real manifest/file-list payload but stop a malicious or
        // buggy peer from claiming an unbounded chunk count/size and exhausting heap memory.
        private static final int MAX_CHUNKS = 20_000;
        private static final int MAX_TOTAL_LENGTH = 64 * 1024 * 1024;

        private int expectedChunks;
        private int nextChunkIndex;
        private final StringBuilder builder = new StringBuilder();

        String accept(int chunkIndex, int totalChunks, String payload) {
            if (totalChunks <= 0 || totalChunks > MAX_CHUNKS || chunkIndex < 0 || chunkIndex >= totalChunks) {
                reset();
                return null;
            }

            if (chunkIndex == 0) {
                reset();
                expectedChunks = totalChunks;
                nextChunkIndex = 0;
            } else if (expectedChunks != totalChunks || chunkIndex != nextChunkIndex) {
                reset();
                return null;
            }

            int payloadLength = payload == null ? 0 : payload.length();
            if (builder.length() + payloadLength > MAX_TOTAL_LENGTH) {
                reset();
                return null;
            }

            builder.append(payload);
            nextChunkIndex++;
            if (chunkIndex + 1 < expectedChunks) {
                return null;
            }

            String result = builder.toString();
            reset();
            return result;
        }

        private void reset() {
            builder.setLength(0);
            expectedChunks = 0;
            nextChunkIndex = 0;
        }
    }
}
