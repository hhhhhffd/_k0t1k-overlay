package dev.k0t1k.overlay.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

/** Exercises cookie filtering, platform-specific decryption, and helper lifetime. */
class BrowserLoginsTest {

    @Test
    void allowlistMatchesDomainsAndSubdomainsOnly() {
        Set<String> allowlist = BrowserLogins.parseAllowlist("youtube.com, Google.com ,twitch.tv");
        assertTrue(BrowserLogins.allowed("youtube.com", allowlist));
        assertTrue(BrowserLogins.allowed(".youtube.com", allowlist)); // Firefox/Chromium's leading-dot form
        assertTrue(BrowserLogins.allowed("accounts.google.com", allowlist));
        assertTrue(BrowserLogins.allowed("www.twitch.tv", allowlist));
        assertFalse(BrowserLogins.allowed("notgoogle.com", allowlist));
        assertFalse(BrowserLogins.allowed("evilyoutube.com", allowlist));
        assertFalse(BrowserLogins.allowed("example.com", allowlist));
        assertFalse(BrowserLogins.allowed(null, allowlist));
    }

    @Test
    void defaultAllowlistCoversTheRequestedSites() {
        Set<String> allowlist = BrowserLogins.parseAllowlist(String.join(",", BrowserLogins.DEFAULT_ALLOWLIST));
        for (String site : new String[] {"youtube.com", "google.com", "tiktok.com", "twitch.tv"}) {
            assertTrue(BrowserLogins.allowed(site, allowlist), site);
        }
    }

    @Test
    void decryptsAnAesGcmV10ValueAndStripsTheHostHashPrefix() throws Exception {
        byte[] key = randomAes256Key();
        byte[] nonce = randomBytes(12);
        String cookieText = "sign-in-token-value";

        // Chromium >= 130 prepends SHA-256(host_key) (32 bytes) to the plaintext before encrypting it.
        byte[] hostHash = java.security.MessageDigest.getInstance("SHA-256").digest("accounts.google.com".getBytes(StandardCharsets.UTF_8));
        byte[] plainWithPrefix = concat(hostHash, cookieText.getBytes(StandardCharsets.UTF_8));

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] cipherAndTag = cipher.doFinal(plainWithPrefix);

        byte[] encrypted = concat("v10".getBytes(StandardCharsets.US_ASCII), concat(nonce, cipherAndTag));

        assertCookieDecryption("Windows", encrypted, key, 24, "accounts.google.com", cookieText);
        assertCookieDecryption("Windows", encrypted, key, 24, ".google.com", null);
    }

    @Test
    void wrongKeyFailsInsteadOfReturningGarbage() throws Exception {
        byte[] key = randomAes256Key();
        byte[] wrongKey = randomAes256Key();
        byte[] nonce = randomBytes(12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] cipherAndTag = cipher.doFinal("value".getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = concat("v10".getBytes(StandardCharsets.US_ASCII), concat(nonce, cipherAndTag));

        assertCookieDecryption("Windows", encrypted, wrongKey, 0, "accounts.google.com", null);
    }

    /**
     * Exercises {@code BrowserLogins}'s real, platform-gated Linux path end to end: a value encrypted
     * the way Chromium's "peanuts" fallback does (AES-128-CBC, IV of 16 spaces, "v10" prefix) comes back
     * out through the production {@code linuxPeanutsKey} and {@code decryptChromiumValue} methods,
     * reached by reflection since both are private. Skipped off Linux, where that branch is unreachable.
     */
    @Test
    void decryptsTheLinuxFallbackCookieValueThroughTheRealMethod() throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("linux")) {
            return;
        }
        Method keyMethod = BrowserLogins.class.getDeclaredMethod("linuxPeanutsKey");
        keyMethod.setAccessible(true);
        byte[] key = (byte[]) keyMethod.invoke(null);
        assertEquals(16, key.length);

        byte[] iv = new byte[16];
        Arrays.fill(iv, (byte) ' ');
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] cipherText = cipher.doFinal("linux-sign-in".getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = concat("v10".getBytes(StandardCharsets.US_ASCII), cipherText);

        Method decryptMethod = BrowserLogins.class.getDeclaredMethod("decryptChromiumValue", byte[].class, byte[].class);
        decryptMethod.setAccessible(true);
        byte[] plain = (byte[]) decryptMethod.invoke(null, encrypted, key);
        assertEquals("linux-sign-in", new String(plain, StandardCharsets.UTF_8));
    }

    @Test
    void ciphertextIsNeverImportedAsPlaintext() throws Exception {
        for (String platform : new String[] {"Windows", "Linux"}) {
            assertCookieDecryption(platform, new byte[] {1, 2, 3}, null, 0, "google.com", null);
            assertCookieDecryption(platform, "v20ciphertext".getBytes(StandardCharsets.UTF_8), null,
                    0, "google.com", null);
        }
    }

    @Test
    void linuxRejectsKeyringValuesAndValidatesTheHostHash() throws Exception {
        byte[] key = new byte[16];
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest("google.com".getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[16];
        Arrays.fill(iv, (byte) ' ');
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] encrypted = concat("v10".getBytes(StandardCharsets.US_ASCII),
                cipher.doFinal(concat(hash, "token".getBytes(StandardCharsets.UTF_8))));
        assertCookieDecryption("Linux", encrypted, key, 24, "google.com", "token");
        assertCookieDecryption("Linux", encrypted, key, 24, ".google.com", null);
        encrypted[2] = '1';
        assertCookieDecryption("Linux", encrypted, key, 24, "google.com", null);
        encrypted = concat("v10".getBytes(StandardCharsets.US_ASCII),
                cipher.doFinal("short".getBytes(StandardCharsets.UTF_8)));
        assertCookieDecryption("Linux", encrypted, key, 24, "google.com", null);
        assertCookieDecryption("Linux", encrypted, key, 23, "google.com", "short");
    }

    @Test
    void windowsRejectsShortHostPrefixes() throws Exception {
        byte[] key = randomAes256Key();
        byte[] nonce = randomBytes(12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        byte[] encrypted = concat("v10".getBytes(StandardCharsets.US_ASCII),
                concat(nonce, cipher.doFinal("short".getBytes(StandardCharsets.UTF_8))));
        assertCookieDecryption("Windows", encrypted, key, 24, "google.com", null);
        assertCookieDecryption("Windows", encrypted, key, 23, "google.com", "short");
        assertCookieDecryption("Windows", "v10".getBytes(StandardCharsets.US_ASCII), key, 0, "google.com", null);
    }

    private static void assertCookieDecryption(String platform, byte[] encrypted, byte[] key,
            int version, String host, String expected) throws Exception {
        String originalPlatform = System.getProperty("os.name");
        URL classes = BrowserLogins.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes}, null)) {
            // An isolated class loader initializes the platform flags without changing the main class.
            System.setProperty("os.name", platform);
            Class<?> logins = Class.forName(BrowserLogins.class.getName(), true, loader);
            Class<?> resultClass = Class.forName(BrowserLogins.Result.class.getName(), true, loader);
            java.lang.reflect.Constructor<?> constructor = resultClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object result = constructor.newInstance();
            Map<String, Object> row = new HashMap<>();
            row.put("host_key", host);
            row.put("encrypted_value", encrypted);
            row.put("value", "plaintext-column");
            Method decrypt = logins.getDeclaredMethod("decryptCookieValue", Map.class, byte[].class,
                    int.class, resultClass);
            decrypt.setAccessible(true);
            byte[] plain = (byte[]) decrypt.invoke(null, row, key, version, result);
            assertEquals(expected, plain == null ? null : new String(plain, StandardCharsets.UTF_8));
            java.lang.reflect.Field skipped = resultClass.getDeclaredField("undecryptable");
            skipped.setAccessible(true);
            assertEquals(expected == null ? 1 : 0, skipped.getInt(result));
            row.put("encrypted_value", new byte[0]);
            assertEquals("plaintext-column", new String((byte[]) decrypt.invoke(null, row, key, version, result),
                    StandardCharsets.UTF_8));
        } finally {
            System.setProperty("os.name", originalPlatform);
        }
    }

    @Test
    void helperTimeoutKillsAProcessWithOpenOutputStreams() throws Exception {
        Process process = helper("hang");
        long started = System.nanoTime();
        try {
            IOException error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                    () -> readHelper(process, 200));
            assertTrue(error.getMessage().contains("timed out"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000);
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void helperDrainsBothStreamsAndChecksExitStatus() throws Exception {
        assertEquals("ok", new String(readHelper(helper("flood"), 5000), StandardCharsets.UTF_8));
        IOException error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> readHelper(helper("fail"), 5000));
        assertEquals("DPAPI unprotect failed", error.getMessage());
    }

    @Test
    void cookieTempDirectoryIsPrivateAndRemoved() throws Exception {
        Method create = BrowserLogins.class.getDeclaredMethod("createCookieTempDirectory");
        create.setAccessible(true);
        Path dir = (Path) create.invoke(null);
        try {
            if (Files.getFileStore(dir).supportsFileAttributeView("posix")) {
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(dir));
            }
            Files.write(dir.resolve("Cookies"), new byte[] {1});
        } finally {
            Method delete = BrowserLogins.class.getDeclaredMethod("deleteTreeQuietly", Path.class);
            delete.setAccessible(true);
            delete.invoke(null, dir);
        }
        assertFalse(Files.exists(dir));
    }

    private static Process helper(String mode) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", Paths.get(Helper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Helper.class.getName(), mode).start();
    }

    private static byte[] readHelper(Process process, long timeoutMillis) throws Exception {
        Method read = BrowserLogins.class.getDeclaredMethod("readDpapiProcess", Process.class, byte[].class, long.class);
        read.setAccessible(true);
        try {
            return (byte[]) read.invoke(null, process, new byte[] {1}, timeoutMillis);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    public static class Helper {
        public static void main(String[] args) throws Exception {
            new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            if ("hang".equals(args[0])) {
                Thread.sleep(60_000);
            } else if ("fail".equals(args[0])) {
                System.out.println("b2s=");
                System.exit(1);
            } else {
                byte[] noise = new byte[8192];
                Arrays.fill(noise, (byte) ' ');
                for (int i = 0; i < 32; i++) {
                    System.out.write(noise);
                    System.err.write(noise);
                }
                System.out.println("b2s=");
            }
        }
    }

    private static byte[] randomAes256Key() throws NoSuchAlgorithmException {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        return key.getEncoded();
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
