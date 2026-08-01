package com.modsync;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManifestUrlResolverTest {
    @Test
    void extractHostRemovesPortFromIpv4StyleAddress() {
        assertEquals("mc.example.com", ManifestUrlResolver.extractHost("mc.example.com:25565"));
    }

    @Test
    void extractHostKeepsBareIpv6Address() {
        assertEquals("2001:db8::42", ManifestUrlResolver.extractHost("2001:db8::42"));
    }

    @Test
    void extractHostReadsBracketedIpv6WithPort() {
        assertEquals("2001:db8::42", ManifestUrlResolver.extractHost("[2001:db8::42]:25565"));
    }

    @Test
    void normalizeHostWrapsIpv6ButNotIpv4() {
        assertEquals("[2001:db8::42]", ManifestUrlResolver.normalizeHost("2001:db8::42"));
        assertEquals("mc.example.com", ManifestUrlResolver.normalizeHost("mc.example.com"));
    }

    @Test
    void resolvePublicBaseUrlPrefersConfiguredValueAndTrimsTrailingSlash() {
        assertEquals(
                "https://cdn.example.com/modsync",
                ManifestUrlResolver.resolvePublicBaseUrl("https://cdn.example.com/modsync///", "ignored.example.com", 8080)
        );
    }

    @Test
    void resolvePublicBaseUrlFallsBackToDirectHttpAddress() {
        assertEquals(
                "http://[2001:db8::42]:8080",
                ManifestUrlResolver.resolvePublicBaseUrl("", "2001:db8::42", 8080)
        );
    }

    @Test
    void buildManifestCandidateUrlsKeepsDeterministicFallbackOrder() {
        assertEquals(
                List.of(
                        "http://mc.example.com:9000/manifest",
                        "http://mc.example.com:8080/manifest",
                        "https://mc.example.com/manifest",
                        "http://mc.example.com/manifest"
                ),
                ManifestUrlResolver.buildManifestCandidateUrls("mc.example.com", 9000, 8080)
        );
    }

    @Test
    void buildManifestCandidateUrlsAvoidsDuplicatesWhenPortsMatch() {
        assertEquals(
                List.of(
                        "http://mc.example.com:8080/manifest",
                        "https://mc.example.com/manifest",
                        "http://mc.example.com/manifest"
                ),
                ManifestUrlResolver.buildManifestCandidateUrls("mc.example.com", 8080, 8080)
        );
    }

    @Test
    void buildDownloadCandidateUrlsAddsServerHostFallbackForDeadPublicBaseUrl() {
        ManifestEntry entry = new ManifestEntry(
                CategoryType.MOD,
                "mods/example.jar",
                "example.jar",
                1L,
                "abc",
                true,
                true,
                "http://62.221.122.64:26590/files/mod/mods/example.jar"
        );

        assertEquals(
                List.of(
                        "http://62.221.122.64:26590/files/mod/mods/example.jar",
                        "http://95.163.234.85:26590/files/mod/mods/example.jar"
                ),
                ManifestUrlResolver.buildDownloadCandidateUrls(
                        entry,
                        entry.getDownloadUrl(),
                        "95.163.234.85:26683",
                        26590
                )
        );
    }

    @Test
    void resolveDownloadCandidateUrlsTriesLocalServerBeforeModrinthFallback() {
        ManifestEntry entry = new ManifestEntry(
                CategoryType.MOD,
                "mods/example.jar",
                "example.jar",
                1L,
                "abc",
                true,
                true,
                "http://95.163.234.85:26590/files/mod/mods/example.jar",
                "sha1abc"
        );

        assertEquals(
                List.of(
                        "http://95.163.234.85:26590/files/mod/mods/example.jar",
                        "http://127.0.0.1:26590/files/mod/mods/example.jar",
                        "https://cdn.modrinth.com/data/example/example.jar"
                ),
                DownloadManager.resolveDownloadCandidateUrls(
                        entry,
                        entry.getDownloadUrl(),
                        "127.0.0.1:26683",
                        26590,
                        ignored -> "https://cdn.modrinth.com/data/example/example.jar"
                )
        );
    }
}
