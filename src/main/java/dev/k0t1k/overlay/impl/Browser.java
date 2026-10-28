package dev.k0t1k.overlay.impl;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The in-game browser's engine: Chromium through JCEF, which is too big to ship in the agent (about
 * 170 MB per platform). The first time the player asks for it, JCEF and its natives are downloaded from
 * Maven Central (jcefmaven's builds), checked against the SHA-256 pinned here, and unpacked into
 * {@code <dataDir>/browser}; later games start it from there. The bridge classes ({@code src/cef}) ship
 * inside the agent as {@code cef-bridge.jar} and load next to JCEF in a class loader of their own.
 */
final class Browser {
    enum State { MISSING, INSTALLING, STARTING, READY, FAILED, UNSUPPORTED }

    private static final String VERSION = "jcef-17e805a+cef-152.0.6+g708dc14+chromium-152.0.7977.83";
    private static final String MAVEN = "https://repo1.maven.org/maven2/me/friwi/";
    private static final String API_SHA256 = "c95083e2dd36272d98db4b08ba7b4963bcab02f06c52e7c921fab17728834aeb";
    /** Natives per platform: {artifact, SHA-256, approximate size in MB}. */
    private static final String[][] NATIVES = {
        {"windows-amd64", "0dc2ce28766d267f551398e24af7a9bec244b888e651bd97a14ac2d128550875", "171"},
        {"linux-amd64", "83f427992cd783fb6f3d499fdf275675187818aa7ca9c4a2a4d43af534a8bdfc", "152"},
    };

    volatile State state;
    /** 0..1 while installing. */
    volatile float progress;
    volatile String error = "";
    private volatile BrowserEngine engine;
    /** An install or start is under way on the io thread. */
    private volatile boolean busy;

    private final Path root;
    private final Path natives;
    private final Executor io;
    private final String language;
    private final String[] platform;

    Browser(Path dataDir, Executor io, String language) {
        this.root = dataDir.resolve("browser");
        this.natives = root.resolve("cef-152.0.6");
        this.io = io;
        this.language = language;
        this.platform = platform();
        if (platform == null) {
            state = State.UNSUPPORTED;
        } else {
            state = Files.exists(natives.resolve(".installed")) ? State.STARTING : State.MISSING;
        }
    }

    private static String[] platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!arch.equals("amd64") && !arch.equals("x86_64")) {
            return null;
        }
        String name = os.startsWith("windows") ? "windows-amd64" : os.startsWith("linux") ? "linux-amd64" : null;
        for (String[] entry : NATIVES) {
            if (entry[0].equals(name)) {
                return entry;
            }
        }
        return null;
    }

    /** Download size in MB for the install button. */
    String size() {
        return platform == null ? "" : platform[2];
    }

    BrowserEngine engine() {
        return engine;
    }

    /** Installs if needed and starts the engine at {@code url}; progress shows in {@link #state}. */
    void start(String url) {
        if (busy || engine != null || state == State.UNSUPPORTED) {
            return;
        }
        busy = true;
        boolean install = !Files.exists(natives.resolve(".installed"));
        state = install ? State.INSTALLING : State.STARTING;
        progress = 0;
        io.execute(() -> {
            try {
                if (install) {
                    install();
                }
                state = State.STARTING;
                engine = load(url);
                state = State.READY;
            } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError e) {
                Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
                error = String.valueOf(cause);
                state = State.FAILED;
                System.err.println("[k0t1k-overlay] browser failed: " + cause);
                cause.printStackTrace();
            } finally {
                busy = false;
            }
        });
    }

    void close() {
        BrowserEngine current = engine;
        if (current != null) {
            current.close();
        }
    }

    // ---- install ----

    private void install() throws IOException {
        Files.createDirectories(root);
        Path api = root.resolve("jcef-api.jar");
        if (!Files.exists(api)) {
            download("jcef-api", API_SHA256, api, 0, 0.02f);
        }
        Path nativeJar = root.resolve("natives-download.jar");
        download("jcef-natives-" + platform[0], platform[1], nativeJar, 0.02f, 0.8f);
        Path unpacking = root.resolve("cef-unpacking");
        deleteTree(unpacking);
        Files.createDirectories(unpacking);
        unpack(nativeJar, unpacking);
        deleteTree(natives);
        Files.move(unpacking, natives);
        Files.delete(nativeJar);
        Files.write(natives.resolve(".installed"), VERSION.getBytes(StandardCharsets.UTF_8));
        progress = 1;
    }

    /** Downloads one Maven Central jar of this JCEF version and checks its SHA-256. */
    private void download(String artifact, String sha256, Path target, float from, float to) throws IOException {
        String version = java.net.URLEncoder.encode(VERSION, "UTF-8");
        URL url = new URL(MAVEN + artifact + "/" + version + "/" + artifact + "-" + version + ".jar");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        if (connection.getResponseCode() != 200) {
            throw new IOException(url + " answered " + connection.getResponseCode());
        }
        long total = connection.getContentLengthLong();
        MessageDigest digest = sha256();
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream in = new DigestInputStream(new BufferedInputStream(connection.getInputStream()), digest);
                OutputStream out = Files.newOutputStream(part)) {
            byte[] buffer = new byte[1 << 16];
            long done = 0;
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
                done += n;
                if (total > 0) {
                    progress = from + (to - from) * done / total;
                }
            }
        }
        String actual = hex(digest.digest());
        if (!actual.equals(sha256)) {
            Files.deleteIfExists(part);
            throw new IOException(artifact + " does not match its pinned SHA-256 (got " + actual + ")");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The natives jar holds one .tar.gz; unpacks it with plain java.util.zip and a small tar reader. */
    private void unpack(Path jar, Path into) throws IOException {
        long total = Files.size(jar);
        try (InputStream file = Files.newInputStream(jar); ZipInputStream zip = new ZipInputStream(new BufferedInputStream(file))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (entry.getName().endsWith(".tar.gz")) {
                    untar(new GZIPInputStream(zip, 1 << 16), into, total);
                    return;
                }
            }
        }
        throw new IOException("no .tar.gz in " + jar);
    }

    private void untar(InputStream in, Path into, long total) throws IOException {
        byte[] header = new byte[512];
        String longName = null;
        long unpacked = 0;
        Path base = into.toAbsolutePath().normalize();
        while (readFully(in, header)) {
            if (header[0] == 0) {
                return; // end of archive
            }
            String name = longName != null ? longName : string(header, 0, 100);
            longName = null;
            String prefix = string(header, 345, 155);
            if (!prefix.isEmpty()) {
                name = prefix + "/" + name;
            }
            long size = Long.parseLong(string(header, 124, 12).trim().isEmpty() ? "0" : string(header, 124, 12).trim(), 8);
            int mode = Integer.parseInt(string(header, 100, 8).trim().isEmpty() ? "0" : string(header, 100, 8).trim(), 8);
            char type = (char) header[156];
            long padded = (size + 511) / 512 * 512;
            if (type == 'L') {
                byte[] data = new byte[(int) padded];
                readFully(in, data);
                longName = new String(data, 0, (int) size, StandardCharsets.UTF_8).replace("\0", "");
                continue;
            }
            Path target = base.resolve(name).normalize();
            if (!target.startsWith(base)) {
                throw new IOException("archive entry escapes the install folder: " + name);
            }
            if (type == '5') {
                Files.createDirectories(target);
                skip(in, padded);
            } else if (type == '0' || type == 0) {
                Files.createDirectories(target.getParent());
                try (OutputStream out = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[1 << 16];
                    long left = size;
                    while (left > 0) {
                        int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                        if (n < 0) {
                            throw new IOException("archive ends inside " + name);
                        }
                        out.write(buffer, 0, n);
                        left -= n;
                    }
                }
                skip(in, padded - size);
                if ((mode & 0100) != 0) {
                    File f = target.toFile();
                    if (!f.setExecutable(true, false)) {
                        throw new IOException("cannot make " + target + " executable");
                    }
                }
                unpacked += size;
                // The archive is about twice the jar's size unpacked.
                progress = Math.min(0.99f, 0.8f + 0.19f * unpacked / (2f * total));
            } else {
                skip(in, padded); // links and pax headers: none in JCEF's archives that matter
            }
        }
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int n = in.read(buffer, read, buffer.length - read);
            if (n < 0) {
                if (read == 0) {
                    return false;
                }
                throw new IOException("archive is cut off");
            }
            read += n;
        }
        return true;
    }

    private static void skip(InputStream in, long bytes) throws IOException {
        long left = bytes;
        byte[] buffer = new byte[8192];
        while (left > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n < 0) {
                throw new IOException("archive is cut off");
            }
            left -= n;
        }
    }

    private static String string(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    // ---- start ----

    /** Loads JCEF and the bridge in a class loader of their own and opens the first page. */
    private BrowserEngine load(String url) throws IOException, ReflectiveOperationException {
        Path bridge = root.resolve("cef-bridge.jar");
        try (InputStream in = Browser.class.getResourceAsStream("cef-bridge.jar")) {
            if (in == null) {
                throw new IOException("cef-bridge.jar is missing from the overlay jar");
            }
            // Rewritten every start so an updated overlay brings its own bridge.
            Files.copy(in, bridge, StandardCopyOption.REPLACE_EXISTING);
        }
        URL[] jars = {root.resolve("jcef-api.jar").toUri().toURL(), bridge.toUri().toURL()};
        @SuppressWarnings("resource") // lives as long as the game: CEF cannot be started twice
        URLClassLoader loader = new URLClassLoader(jars, Browser.class.getClassLoader());
        Path cache = root.resolve("profile");
        Files.createDirectories(cache);
        Object engine = Class.forName("dev.k0t1k.overlay.cef.CefEngine", true, loader)
                .getMethod("create", File.class, File.class, String.class, String.class)
                .invoke(null, natives.toFile(), cache.toFile(), language, url);
        return (BrowserEngine) engine;
    }

    /** Pixels of the browser's newest frame, for the renderer. */
    static final class Frame {
        ByteBuffer pixels = ByteBuffer.allocateDirect(4);
        final int[] size = new int[2];
        long version;
    }
}
