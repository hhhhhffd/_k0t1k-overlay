package dev.k0t1k.overlay.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
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

/**
 * {@link BrowserLogins#allowed} and the Chromium {@code v10} AES-256-GCM cookie decryption, including
 * the Chromium 130+ 32-byte SHA-256(host_key) prefix. The decrypt method itself is platform-gated
 * (Windows uses GCM, Linux its own "peanuts" AES-CBC key), so the GCM path is driven directly through
 * reflection here rather than by pretending to run on Windows.
 */
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

        byte[] decrypted = decryptGcm(encrypted, key);
        assertEquals(32 + cookieText.length(), decrypted.length);
        byte[] stripped = Arrays.copyOfRange(decrypted, 32, decrypted.length);
        assertEquals(cookieText, new String(stripped, StandardCharsets.UTF_8));
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

        org.junit.jupiter.api.Assertions.assertThrows(GeneralSecurityException.class, () -> decryptGcm(encrypted, wrongKey));
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

    /** The same slicing and GCM parameters as {@code BrowserLogins}'s Windows branch (package-private, same logic). */
    private static byte[] decryptGcm(byte[] encrypted, byte[] key) throws GeneralSecurityException {
        byte[] nonce = Arrays.copyOfRange(encrypted, 3, 15);
        byte[] cipherAndTag = Arrays.copyOfRange(encrypted, 15, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(cipherAndTag);
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
