package com.modsync;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class SyncComparator {
    private SyncComparator() {
    }

    public static List<ManifestEntry> findMissingOrOutdated(List<ManifestEntry> localEntries, ManifestData manifestData) {
        Map<String, ManifestEntry> localMap = new HashMap<>();
        for (ManifestEntry entry : localEntries) {
            if (isComparableEntry(entry)) {
                localMap.put(entry.getIdentityKey(), entry);
            }
        }

        return manifestData.getEntries().stream()
                .filter(SyncComparator::isComparableEntry)
                .filter(serverEntry -> compare(serverEntry, localMap.get(serverEntry.getIdentityKey())).requiresDownload())
                .collect(Collectors.toList());
    }

    public static List<ManifestEntry> findMissingOrOutdated(List<ManifestEntry> localEntries, List<ManifestEntry> requiredEntries) {
        Map<String, ManifestEntry> localMap = new HashMap<>();
        for (ManifestEntry entry : localEntries) {
            if (isComparableEntry(entry)) {
                localMap.put(entry.getIdentityKey(), entry);
            }
        }

        return requiredEntries.stream()
                .filter(SyncComparator::isComparableEntry)
                .filter(serverEntry -> compare(serverEntry, localMap.get(serverEntry.getIdentityKey())).requiresDownload())
                .collect(Collectors.toList());
    }

    static ComparisonResult compare(ManifestEntry serverEntry, ManifestEntry clientEntry) {
        if (!isComparableEntry(serverEntry)) {
            return ComparisonResult.MATCHES;
        }
        if (clientEntry == null) {
            return ComparisonResult.MISSING;
        }
        if (serverEntry.isMatchByNameOnly()) {
            return ComparisonResult.MATCHES;
        }
        if (clientEntry.getFileSize() != serverEntry.getFileSize()) {
            return ComparisonResult.SIZE_CHANGED;
        }
        if (!clientEntry.getSha256().equalsIgnoreCase(serverEntry.getSha256())) {
            return ComparisonResult.HASH_CHANGED;
        }
        return ComparisonResult.MATCHES;
    }

    static boolean isComparableEntry(ManifestEntry entry) {
        return entry != null
                && entry.getCategory() != null
                && entry.getRelativePath() != null
                && !entry.getRelativePath().isBlank()
                && entry.getSha256() != null
                && !entry.getSha256().isBlank();
    }

    enum ComparisonResult {
        MATCHES(false),
        MISSING(true),
        SIZE_CHANGED(true),
        HASH_CHANGED(true);

        private final boolean requiresDownload;

        ComparisonResult(boolean requiresDownload) {
            this.requiresDownload = requiresDownload;
        }

        boolean requiresDownload() {
            return requiresDownload;
        }
    }
}
