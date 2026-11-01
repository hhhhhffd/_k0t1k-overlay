package dev.k0t1k.overlay.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pulls sign-in cookies out of the player's other browsers, so they do not have to log in to the sites
 * they already use a second time. Nothing here ever logs or stores a cookie's value anywhere but the
 * returned list, which the caller feeds straight into {@link BrowserEngine#setCookie}. Runs on the io
 * thread; everything is best effort and reports what it could not do instead of throwing.
 *
 * <p>Firefox keeps its cookies in a WAL-mode SQLite database ({@code cookies.sqlite}), read in plain
 * text through {@link SqliteReader}. Chromium-family browsers (Chrome, Edge, Brave, Yandex Browser,
 * Opera, Opera GX, Vivaldi) encrypt their values; the key comes from each browser's {@code Local State}
 * file, unwrapped with Windows DPAPI (a small PowerShell helper, since the JDK has no DPAPI binding) or,
 * on Linux without a keyring, a fixed fallback key ("peanuts"). Chromium 130+'s app-bound encryption
 * ({@code v20} values) cannot be decrypted at all; those cookies are just counted.
 */
final class BrowserLogins {
    static final String[] DEFAULT_ALLOWLIST = {"youtube.com", "google.com", "tiktok.com", "twitch.tv"};

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
    /** Microseconds-since-1601 to milliseconds-since-1970: the gap between the two epochs, in ms. */
    private static final long WEBKIT_EPOCH_OFFSET_MS = 11_644_473_600_000L;
    private static final Pattern ENCRYPTED_KEY = Pattern.compile("\"encrypted_key\"\\s*:\\s*\"([^\"]+)\"");

    private BrowserLogins() {
    }

    /** One cookie ready for {@link BrowserEngine#setCookie}; never logged, never written to disk. */
    static final class Cookie {
        final String host;
        final String name;
        final String value;
        final String path;
        final boolean secure;
        final boolean httpOnly;
        /** Milliseconds since the epoch, or 0 for a session cookie. */
        final long expiresMillis;

        Cookie(String host, String name, String value, String path, boolean secure, boolean httpOnly, long expiresMillis) {
            this.host = host;
            this.name = name;
            this.value = value;
            this.path = path;
            this.secure = secure;
            this.httpOnly = httpOnly;
            this.expiresMillis = expiresMillis;
        }
    }

    /** One browser profile, found on disk. */
    static final class Profile {
        final String name;
        final Path dir;
        final boolean isDefault;

        Profile(String name, Path dir, boolean isDefault) {
            this.name = name;
            this.dir = dir;
            this.isDefault = isDefault;
        }
    }

    /** One detected browser, with its profiles (the default profile, if any, listed first). */
    static final class Source {
        final String id;
        final String displayName;
        final boolean firefox;
        /** The Chromium "User Data" root (holds {@code Local State}); null for Firefox. */
        final Path root;
        final List<Profile> profiles;

        Source(String id, String displayName, boolean firefox, Path root, List<Profile> profiles) {
            this.id = id;
            this.displayName = displayName;
            this.firefox = firefox;
            this.root = root;
            this.profiles = profiles;
        }
    }

    static final class Result {
        int imported;
        /** Cookies that were found but could not be decrypted (app-bound v20, or a Linux keyring one). */
        int undecryptable;
        /** Set when nothing could be imported: "locked", "no cookies database", or an exception message. */
        String error;
    }

    /** The allowlist text (comma separated) as a lowercase set of bare domains. */
    static Set<String> parseAllowlist(String csv) {
        Set<String> allowlist = new HashSet<>();
        for (String entry : csv.split(",")) {
            String domain = entry.trim().toLowerCase(Locale.ROOT);
            if (!domain.isEmpty()) {
                allowlist.add(domain);
            }
        }
        return allowlist;
    }

    /** Whether {@code host} (a cookie's domain, maybe with a leading dot) is in or under the allowlist. */
    static boolean allowed(String host, Set<String> allowlist) {
        if (host == null) {
            return false;
        }
        String bare = (host.startsWith(".") ? host.substring(1) : host).toLowerCase(Locale.ROOT);
        for (String domain : allowlist) {
            if (bare.equals(domain) || bare.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    // ---- detection ----

    static List<Source> detect() {
        List<Source> sources = new ArrayList<>();
        try {
            Path ini = firefoxProfilesIni();
            if (ini != null && Files.exists(ini)) {
                List<Profile> profiles = firefoxProfiles(ini);
                if (!profiles.isEmpty()) {
                    sources.add(new Source("firefox", "Firefox", true, null, profiles));
                }
            }
        } catch (IOException e) {
            // Best effort: a broken profiles.ini just means Firefox is not offered.
        }
        for (String[] browser : chromiumBrowsers()) {
            Path root = Paths.get(browser[1]);
            List<Profile> profiles = chromiumProfiles(root);
            if (!profiles.isEmpty()) {
                sources.add(new Source(browser[0], browser[2], false, root, profiles));
            }
        }
        return sources;
    }

    private static Path firefoxProfilesIni() {
        if (WINDOWS) {
            String appData = System.getenv("APPDATA");
            return appData == null ? null : Paths.get(appData, "Mozilla", "Firefox", "profiles.ini");
        }
        String home = System.getProperty("user.home", "");
        return Paths.get(home, ".mozilla", "firefox", "profiles.ini");
    }

    private static List<Profile> firefoxProfiles(Path ini) throws IOException {
        Path dir = ini.getParent();
        List<Map<String, String>> sections = new ArrayList<>();
        Map<String, String> section = null;
        for (String raw : Files.readAllLines(ini, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.startsWith("[") && line.endsWith("]")) {
                section = new LinkedHashMap<>();
                sections.add(section);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0 && section != null) {
                section.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        List<Profile> profiles = new ArrayList<>();
        for (Map<String, String> s : sections) {
            String path = s.get("Path");
            if (path == null) {
                continue;
            }
            boolean relative = !"0".equals(s.get("IsRelative"));
            Path profileDir = relative ? dir.resolve(path) : Paths.get(path);
            if (Files.isDirectory(profileDir)) {
                profiles.add(new Profile(s.getOrDefault("Name", path), profileDir, "1".equals(s.get("Default"))));
            }
        }
        // A stable sort: the default profile first, the rest in the order profiles.ini listed them.
        profiles.sort((a, b) -> Boolean.compare(!a.isDefault, !b.isDefault));
        return profiles;
    }

    /** {id, "User Data"-style root, display name} for every Chromium browser this platform might have. */
    private static String[][] chromiumBrowsers() {
        if (WINDOWS) {
            String appData = System.getenv("APPDATA");
            String localAppData = System.getenv("LOCALAPPDATA");
            if (appData == null || localAppData == null) {
                return new String[0][];
            }
            return new String[][] {
                {"chrome", localAppData + "\\Google\\Chrome\\User Data", "Chrome"},
                {"edge", localAppData + "\\Microsoft\\Edge\\User Data", "Edge"},
                {"brave", localAppData + "\\BraveSoftware\\Brave-Browser\\User Data", "Brave"},
                {"yandex", localAppData + "\\Yandex\\YandexBrowser\\User Data", "Yandex Browser"},
                {"opera", appData + "\\Opera Software\\Opera Stable", "Opera"},
                {"operagx", appData + "\\Opera Software\\Opera GX Stable", "Opera GX"},
                {"vivaldi", localAppData + "\\Vivaldi\\User Data", "Vivaldi"},
            };
        }
        if (LINUX) {
            String home = System.getProperty("user.home", "");
            return new String[][] {
                {"chrome", home + "/.config/google-chrome", "Chrome"},
                {"edge", home + "/.config/microsoft-edge", "Edge"},
                {"brave", home + "/.config/BraveSoftware/Brave-Browser", "Brave"},
                {"yandex", home + "/.config/yandex-browser", "Yandex Browser"},
                {"opera", home + "/.config/opera", "Opera"},
                {"vivaldi", home + "/.config/vivaldi", "Vivaldi"},
            };
        }
        return new String[0][];
    }

    private static List<Profile> chromiumProfiles(Path root) {
        List<Profile> profiles = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return profiles;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path dir : stream) {
                if (Files.isDirectory(dir) && Files.exists(dir.resolve("Preferences"))) {
                    String name = dir.getFileName().toString();
                    profiles.add(new Profile(name, dir, "Default".equals(name)));
                }
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
        profiles.sort((a, b) -> Boolean.compare(!a.isDefault, !b.isDefault));
        return profiles;
    }

    // ---- import ----

    static Result importCookies(Source source, Profile profile, Set<String> allowlist, List<Cookie> out) {
        Result result = new Result();
        if (source.firefox) {
            importFirefox(profile.dir, allowlist, out, result);
        } else {
            importChromium(source.root, profile.dir, allowlist, out, result);
        }
        return result;
    }

    private static void importFirefox(Path profileDir, Set<String> allowlist, List<Cookie> out, Result result) {
        Path db = profileDir.resolve("cookies.sqlite");
        if (!Files.exists(db)) {
            result.error = "no cookies database";
            return;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("k0t1k-cookies");
            Path tmpDb = tmp.resolve("cookies.sqlite");
            Path wal = profileDir.resolve("cookies.sqlite-wal");
            Path tmpWal = null;
            try {
                Files.copy(db, tmpDb, StandardCopyOption.REPLACE_EXISTING);
                if (Files.exists(wal)) {
                    tmpWal = tmp.resolve("cookies.sqlite-wal");
                    Files.copy(wal, tmpWal, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                result.error = "locked";
                return;
            }
            List<Map<String, Object>> rows;
            try {
                rows = SqliteReader.readTable(tmpDb, tmpWal, "moz_cookies");
            } catch (IOException e) {
                result.error = e.getMessage();
                return;
            }
            long now = System.currentTimeMillis();
            for (Map<String, Object> row : rows) {
                String host = str(row.get("host"));
                if (!allowed(host, allowlist)) {
                    continue;
                }
                long expiresMillis = num(row.get("expiry")) * 1000L;
                if (expiresMillis != 0 && expiresMillis < now) {
                    continue;
                }
                String path = str(row.get("path"));
                out.add(new Cookie(host, str(row.get("name")), str(row.get("value")), path == null ? "/" : path,
                        num(row.get("isSecure")) != 0, num(row.get("isHttpOnly")) != 0, expiresMillis));
                result.imported++;
            }
        } catch (IOException e) {
            result.error = e.getMessage();
        } finally {
            deleteTreeQuietly(tmp);
        }
    }

    private static void importChromium(Path root, Path profileDir, Set<String> allowlist, List<Cookie> out, Result result) {
        Path cookiesFile = profileDir.resolve("Network").resolve("Cookies");
        if (!Files.exists(cookiesFile)) {
            cookiesFile = profileDir.resolve("Cookies");
        }
        if (!Files.exists(cookiesFile)) {
            result.error = "no cookies database";
            return;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("k0t1k-cookies");
            Path tmpDb = tmp.resolve("Cookies");
            try {
                Files.copy(cookiesFile, tmpDb, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                result.error = "locked";
                return;
            }
            int version = metaVersion(tmpDb);
            byte[] key;
            try {
                key = chromiumKey(root);
            } catch (IOException | GeneralSecurityException e) {
                key = null;
            }
            List<Map<String, Object>> rows;
            try {
                rows = SqliteReader.readTable(tmpDb, null, "cookies");
            } catch (IOException e) {
                result.error = e.getMessage();
                return;
            }
            long now = System.currentTimeMillis();
            for (Map<String, Object> row : rows) {
                String host = str(row.get("host_key"));
                if (!allowed(host, allowlist)) {
                    continue;
                }
                byte[] plain = decryptCookieValue(row, key, version, result);
                if (plain == null) {
                    continue;
                }
                long expiresUtc = num(row.get("expires_utc"));
                long expiresMillis = expiresUtc == 0 ? 0 : expiresUtc / 1000 - WEBKIT_EPOCH_OFFSET_MS;
                if (expiresMillis != 0 && expiresMillis < now) {
                    continue;
                }
                String path = str(row.get("path"));
                out.add(new Cookie(host, str(row.get("name")), new String(plain, StandardCharsets.UTF_8),
                        path == null ? "/" : path, num(row.get("is_secure")) != 0, num(row.get("is_httponly")) != 0,
                        expiresMillis));
                result.imported++;
            }
        } catch (IOException e) {
            result.error = e.getMessage();
        } finally {
            deleteTreeQuietly(tmp);
        }
    }

    /** The cookie's plaintext value, or null when it is skipped (and counted in {@code result}). */
    private static byte[] decryptCookieValue(Map<String, Object> row, byte[] key, int version, Result result) {
        byte[] encrypted = bytes(row.get("encrypted_value"));
        if (encrypted == null || encrypted.length == 0) {
            String value = str(row.get("value"));
            return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        }
        if (hasPrefix(encrypted, "v20")) {
            result.undecryptable++; // Chromium 127+'s app-bound encryption: there is no key to recover
            return null;
        }
        if (!hasPrefix(encrypted, "v10") && !hasPrefix(encrypted, "v11")) {
            return encrypted; // an old, unprefixed plaintext value
        }
        if (key == null) {
            result.undecryptable++; // e.g. Linux without the "peanuts" fallback (a real keyring, v11)
            return null;
        }
        byte[] plain;
        try {
            plain = decryptChromiumValue(encrypted, key);
        } catch (GeneralSecurityException e) {
            result.undecryptable++;
            return null;
        }
        if (version >= 24 && plain.length >= 32) {
            // Chromium >= 130 prepends SHA-256(host_key) to the plaintext.
            plain = Arrays.copyOfRange(plain, 32, plain.length);
        }
        return plain;
    }

    /** The {@code meta} table's {@code version}, or 0 when it is missing (an older Chromium build). */
    private static int metaVersion(Path db) {
        try {
            for (Map<String, Object> row : SqliteReader.readTable(db, null, "meta")) {
                if ("version".equals(str(row.get("key")))) {
                    try {
                        return Integer.parseInt(str(row.get("value")));
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }
            }
        } catch (IOException e) {
            // no meta table: treat as version 0, so the SHA-256 prefix is not stripped
        }
        return 0;
    }

    /** AES-256-GCM on Windows (the DPAPI-unwrapped key); AES-128-CBC with the "peanuts" key on Linux. */
    private static byte[] decryptChromiumValue(byte[] encrypted, byte[] key) throws GeneralSecurityException {
        if (WINDOWS) {
            byte[] nonce = Arrays.copyOfRange(encrypted, 3, 15);
            byte[] cipherAndTag = Arrays.copyOfRange(encrypted, 15, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            return cipher.doFinal(cipherAndTag);
        }
        byte[] cipherText = Arrays.copyOfRange(encrypted, 3, encrypted.length);
        byte[] iv = new byte[16];
        Arrays.fill(iv, (byte) ' ');
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(cipherText);
    }

    /** The master key: DPAPI-unwrapped on Windows (from {@code Local State}), fixed on Linux. */
    private static byte[] chromiumKey(Path root) throws IOException, GeneralSecurityException {
        if (LINUX) {
            return linuxPeanutsKey();
        }
        if (!WINDOWS || root == null) {
            return null;
        }
        Path localState = root.resolve("Local State");
        if (!Files.exists(localState)) {
            return null;
        }
        String json = new String(Files.readAllBytes(localState), StandardCharsets.UTF_8);
        Matcher matcher = ENCRYPTED_KEY.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        byte[] encryptedKey = Base64.getDecoder().decode(matcher.group(1));
        if (encryptedKey.length <= 5) {
            return null;
        }
        byte[] withoutPrefix = Arrays.copyOfRange(encryptedKey, 5, encryptedKey.length); // strip "DPAPI"
        return dpapiUnprotect(withoutPrefix);
    }

    /** Chromium's Linux fallback key when there is no keyring: PBKDF2-SHA1("peanuts", "saltysalt", 1, 128). */
    private static byte[] linuxPeanutsKey() throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec("peanuts".toCharArray(), "saltysalt".getBytes(StandardCharsets.UTF_8), 1, 128);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
    }

    /** Runs {@code dpapi.ps1} as a short-lived PowerShell child to undo {@code CryptUnprotectData}. */
    private static byte[] dpapiUnprotect(byte[] data) throws IOException {
        String script = resource("dpapi.ps1");
        String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encodedScript);
        Process process = builder.start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write((Base64.getEncoder().encodeToString(data) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        String out;
        try (InputStream in = process.getInputStream()) {
            out = read(in);
        }
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String line = out.trim();
        if (line.isEmpty()) {
            throw new IOException("DPAPI unprotect failed");
        }
        return Base64.getDecoder().decode(line);
    }

    // ---- small helpers ----

    private static boolean hasPrefix(byte[] data, String prefix) {
        if (data.length < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (data[i] != (byte) prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof byte[] ? new String((byte[]) value, StandardCharsets.UTF_8) : value.toString();
    }

    private static long num(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return value == null ? 0 : Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static byte[] bytes(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof byte[] ? (byte[]) value : value.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = BrowserLogins.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException(name + " is missing from the overlay jar");
            }
            return read(in);
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void deleteTreeQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            // best effort: a leftover temp file under the system temp dir is harmless
        }
    }
}
