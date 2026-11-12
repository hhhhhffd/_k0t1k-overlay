package dev.k0t1k.overlay.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class BrowserTest {
    @Test
    void selectsIntelMacNativesForTheJvmIncludingRosetta() {
        assertPlatform("macosx-amd64", "Mac OS X", "x86_64");
        assertPlatform("macosx-amd64", "Mac OS X", "amd64");
    }

    @Test
    void selectsAppleSiliconNativesForBothArmAliases() {
        assertPlatform("macosx-arm64", "Mac OS X", "aarch64");
        assertPlatform("macosx-arm64", "macOS", "arm64");
        assertPlatform("macosx-arm64", "MAC OS X", "AARCH64");
    }

    @Test
    void keepsWindowsAndLinuxX64Support() {
        assertPlatform("windows-amd64", "Windows 11", "amd64");
        assertPlatform("windows-amd64", "Windows 10", "x86_64");
        assertPlatform("linux-amd64", "Linux", "amd64");
        assertPlatform("linux-amd64", "Linux", "x86_64");
    }

    @Test
    void rejectsPlatformsWithoutPinnedNativeArtifacts() {
        assertNull(Browser.platform("Windows 11", "aarch64"));
        assertNull(Browser.platform("Linux", "arm64"));
        assertNull(Browser.platform("Mac OS X", "x86"));
        assertNull(Browser.platform("FreeBSD", "amd64"));
        assertNull(Browser.platform("", ""));
    }

    private static void assertPlatform(String expected, String os, String arch) {
        String[] platform = Browser.platform(os, arch);
        assertNotNull(platform);
        assertEquals(expected, platform[0]);
    }
}
