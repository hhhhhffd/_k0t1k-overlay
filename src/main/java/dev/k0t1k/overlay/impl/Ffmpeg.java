package dev.k0t1k.overlay.impl;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The ffmpeg that encodes recordings: where it is (a build downloaded on request into
 * {@code <dataDir>/ffmpeg}, one on PATH, or a file the player points to), which encoders it can really
 * use here (each is tried once and the answer cached), and the command lines for encoding.
 */
final class Ffmpeg {
    enum State { MISSING, DOWNLOADING, READY, FAILED, UNSUPPORTED }

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    private static final boolean LINUX = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
    private static final String NULL_DEVICE = WINDOWS ? "NUL" : "/dev/null";

    /** Pinned builds per platform: {url, SHA-256, size in bytes, member to extract}. */
    private static final String[] WINDOWS_BUILD = {
        "https://github.com/GyanD/codexffmpeg/releases/download/9.0.2/ffmpeg-9.0.2-essentials_build.zip",
        "60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba",
        "114768076",
        "ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe"};
    private static final String[] LINUX_BUILD = {
        "https://github.com/BtbN/FFmpeg-Builds/releases/download/autobuild-2026-10-31-12-59/ffmpeg-n8.1.3-27-g8dca1586f4-linux64-gpl-8.1.tar.xz",
        "41e9c565594c390622cf968d82fa4135d9e9a11d06c1515644288cbc844e3380",
        "152246308",
        "ffmpeg-n8.1.3-27-g8dca1586f4-linux64-gpl-8.1/bin/ffmpeg"};

    /** Encoders worth trying, best first within a codec; the first H.264 one that works is the default. */
    private static final List<String> CANDIDATES = Collections.unmodifiableList(Arrays.asList(
            "h264_nvenc", "hevc_nvenc", "av1_nvenc", "h264_amf", "hevc_amf", "av1_amf",
            "h264_qsv", "hevc_qsv", "av1_qsv", "h264_vaapi", "libx264"));

    volatile State state = State.MISSING;
    /** 0..1 while downloading. */
    volatile float progress;
    volatile String error = "";
    /** The ffmpeg in use, or null when there is none. */
    private volatile String path;
    /** The encoders that worked in a test run, in {@link #CANDIDATES} order; empty until probed. */
    private volatile List<String> encoders = Collections.emptyList();
    private volatile boolean probing;
    /** Whether this ffmpeg has the animated WebP encoder (not every build includes libwebp). */
    private volatile boolean webp;

    private final Options options;
    private final Path folder;
    private final Executor io;
    private final String[] build;

    Ffmpeg(Options options, Executor io) {
        this.options = options;
        this.io = io;
        this.folder = options.dataDir.resolve("ffmpeg");
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        build = x64 && WINDOWS ? WINDOWS_BUILD : x64 && LINUX ? LINUX_BUILD : null;
        io.execute(this::refresh);
    }

    /** The ffmpeg to run, or null. */
    String path() {
        return path;
    }

    List<String> encoders() {
        return encoders;
    }

    boolean hasWebp() {
        return webp;
    }

    boolean probing() {
        return probing;
    }

    /** Download size in MB for the button; empty when there is no build for this platform. */
    String size() {
        return build == null ? "" : Long.toString(Long.parseLong(build[2]) / 1_000_000);
    }

    boolean canDownload() {
        return build != null;
    }

    /** Looks for the ffmpeg again on the worker thread, after a setting changed. */
    void recheck() {
        io.execute(this::refresh);
    }

    /** Finds the ffmpeg again after the setting or the files changed, and tests its encoders. Runs on the io thread. */
    void refresh() {
        if (state == State.DOWNLOADING) {
            return;
        }
        String found = locate();
        path = found;
        if (found == null) {
            encoders = Collections.emptyList();
            webp = false;
            state = build == null && "auto".equals(options.ffmpegMode) ? State.UNSUPPORTED : State.MISSING;
            return;
        }
        state = State.READY;
        probe(found);
    }

    private String locate() {
        if ("custom".equals(options.ffmpegMode)) {
            if (!Options.validFolder(options.ffmpegPath)) {
                return null;
            }
            Path custom = Paths.get(options.ffmpegPath.trim());
            if (Files.isDirectory(custom)) {
                custom = custom.resolve(WINDOWS ? "ffmpeg.exe" : "ffmpeg");
            }
            return Files.isRegularFile(custom) ? custom.toAbsolutePath().toString() : null;
        }
        Path downloaded = folder.resolve(WINDOWS ? "ffmpeg.exe" : "ffmpeg");
        if (Files.isRegularFile(downloaded)) {
            return downloaded.toString();
        }
        String onPath = System.getenv("PATH");
        if (onPath != null) {
            for (String dir : onPath.split(File.pathSeparator)) {
                File candidate = new File(dir, WINDOWS ? "ffmpeg.exe" : "ffmpeg");
                if (candidate.isFile() && candidate.canExecute()) {
                    return candidate.getAbsolutePath();
                }
            }
        }
        return null;
    }

    // ---- encoders ----

    private void probe(String ffmpeg) {
        Path cache = folder.resolve("encoders.properties");
        File binary = new File(ffmpeg);
        String key = ffmpeg + "|" + binary.length() + "|" + binary.lastModified();
        Properties saved = new Properties();
        try (InputStream in = Files.newInputStream(cache)) {
            saved.load(in);
        } catch (IOException e) {
            // No cache yet: probe.
        }
        if (key.equals(saved.getProperty("key")) && saved.getProperty("webp") != null) {
            encoders = split(saved.getProperty("encoders", ""));
            webp = Boolean.parseBoolean(saved.getProperty("webp"));
            return;
        }
        probing = true;
        try {
            List<String> working = new ArrayList<>();
            for (String encoder : CANDIDATES) {
                if (works(ffmpeg, encoder)) {
                    working.add(encoder);
                }
            }
            encoders = Collections.unmodifiableList(working);
            webp = works(ffmpeg, "libwebp_anim");
            Properties out = new Properties();
            out.setProperty("key", key);
            out.setProperty("encoders", String.join(",", working));
            out.setProperty("webp", Boolean.toString(webp));
            Files.createDirectories(folder);
            try (OutputStream file = Files.newOutputStream(cache)) {
                out.store(file, "ffmpeg encoders that worked in a test run");
            }
        } catch (IOException e) {
            Overlay.log("could not cache the ffmpeg encoders: " + e);
        } finally {
            probing = false;
        }
    }

    private static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        for (String name : text.split(",")) {
            if (!name.trim().isEmpty()) {
                out.add(name.trim());
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Whether a few black frames encode with {@code encoder} on this machine. */
    private static boolean works(String ffmpeg, String encoder) {
        List<String> command = new ArrayList<>(Arrays.asList(ffmpeg, "-hide_banner", "-loglevel", "error"));
        boolean vaapi = encoder.endsWith("_vaapi");
        if (vaapi) {
            if (!LINUX) {
                return false;
            }
            command.addAll(Arrays.asList("-vaapi_device", "/dev/dri/renderD128"));
        }
        command.addAll(Arrays.asList("-f", "lavfi", "-i", "color=c=black:s=1280x720:r=30", "-frames:v", "5"));
        if (vaapi) {
            command.addAll(Arrays.asList("-vf", "format=nv12,hwupload"));
        }
        command.addAll(Arrays.asList("-c:v", encoder, "-f", "null", "-"));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(new File(NULL_DEVICE)).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The encoder to use for the setting: the chosen one if it works, else the best hardware H.264, else libx264. */
    String chooseEncoder(String wanted) {
        List<String> working = encoders;
        if (working.contains(wanted)) {
            return wanted;
        }
        for (String name : CANDIDATES) {
            if (name.startsWith("h264_") && working.contains(name)) {
                return name;
            }
        }
        return working.isEmpty() ? "libx264" : working.get(0);
    }

    // ---- download ----

    /** Downloads the pinned build and unpacks ffmpeg into the data folder; progress shows in {@link #state}. */
    void download() {
        if (build == null || state == State.DOWNLOADING) {
            return;
        }
        state = State.DOWNLOADING;
        progress = 0;
        error = "";
        io.execute(() -> {
            try {
                install();
                state = State.READY;
                refresh();
            } catch (IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
                state = State.FAILED;
                Overlay.log("ffmpeg download failed: " + e);
            }
        });
    }

    private void install() throws IOException {
        Files.createDirectories(folder);
        Path archive = folder.resolve(WINDOWS ? "ffmpeg-download.zip" : "ffmpeg-download.tar.xz");
        Path target = folder.resolve(WINDOWS ? "ffmpeg.exe" : "ffmpeg");
        Path unpacked = folder.resolve("ffmpeg.unpacked");
        try {
            download(build[0], build[1], Long.parseLong(build[2]), archive);
            if (WINDOWS) {
                try (ZipFile zip = new ZipFile(archive.toFile())) {
                    ZipEntry entry = zip.getEntry(build[3]);
                    if (entry == null) {
                        throw new IOException(build[3] + " is not in the downloaded archive");
                    }
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, unpacked, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } else {
                Path out = Files.createTempDirectory(folder, "unpack");
                try {
                    run(Arrays.asList("tar", "-xJf", archive.toString(), "-C", out.toString(), "--strip-components=2", build[3]));
                    Files.move(out.resolve("ffmpeg"), unpacked, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    deleteTree(out);
                }
            }
            if (!unpacked.toFile().setExecutable(true, false) && !WINDOWS) {
                throw new IOException("cannot make " + unpacked + " executable");
            }
            Files.move(unpacked, target, StandardCopyOption.REPLACE_EXISTING);
            progress = 1;
        } finally {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(unpacked);
        }
    }

    private void download(String address, String sha256, long expected, Path target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        if (connection.getResponseCode() != 200) {
            throw new IOException(address + " answered " + connection.getResponseCode());
        }
        long total = connection.getContentLengthLong() > 0 ? connection.getContentLengthLong() : expected;
        MessageDigest digest = sha256();
        try (InputStream in = new DigestInputStream(new BufferedInputStream(connection.getInputStream()), digest);
                OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[1 << 16];
            long done = 0;
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
                done += n;
                progress = 0.95f * done / total;
            }
        }
        StringBuilder actual = new StringBuilder();
        for (byte b : digest.digest()) {
            actual.append(String.format("%02x", b));
        }
        if (!actual.toString().equals(sha256)) {
            throw new IOException("the download does not match its pinned SHA-256 (got " + actual + ")");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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

    // ---- running ----

    /** Runs a command to its end; fails with its last line of output when it exits with an error. */
    static void run(List<String> command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String last = "";
        try (BufferedReader in = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null; ) {
                if (!line.trim().isEmpty()) {
                    last = line.trim();
                }
            }
        }
        try {
            int code = process.waitFor();
            if (code != 0) {
                throw new IOException(last.isEmpty() ? command.get(0) + " exited with code " + code : last);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while " + command.get(0) + " ran", e);
        }
    }

    // ---- command lines ----

    /** The encoder's name without the hardware suffix: "h264", "hevc" or "av1". */
    private static String codec(String encoder) {
        return "libx264".equals(encoder) ? "h264" : encoder.substring(0, encoder.indexOf('_'));
    }

    static boolean hevc(String encoder) {
        return "hevc".equals(codec(encoder));
    }

    /** What the encoder needs to read NV12 frames and write video of the chosen quality. */
    static List<String> encoderArgs(String encoder, String quality, int bitrate) {
        int level = "low".equals(quality) ? 0 : "high".equals(quality) ? 2 : 1;
        boolean custom = "custom".equals(quality);
        List<String> args = new ArrayList<>(Arrays.asList("-c:v", encoder));
        String rate = bitrate + "M";
        if ("libx264".equals(encoder)) {
            args.addAll(Arrays.asList("-preset", "veryfast", "-pix_fmt", "yuv420p"));
            if (!custom) {
                args.addAll(Arrays.asList("-crf", String.valueOf(new int[] {26, 22, 18}[level])));
            }
        } else if (encoder.endsWith("_nvenc")) {
            args.addAll(Arrays.asList("-preset", level == 2 ? "p5" : "p4", "-tune", "hq", "-rc", "vbr"));
            if (!custom) {
                args.addAll(Arrays.asList("-cq", String.valueOf(new int[] {28, 23, 19}[level]), "-b:v", "0"));
            }
        } else if (encoder.endsWith("_amf")) {
            args.addAll(Arrays.asList("-usage", "transcoding", "-quality", "balanced"));
            if (custom) {
                args.addAll(Arrays.asList("-rc", "vbr_peak"));
            } else {
                args.addAll(Arrays.asList("-rc", "cqp", "-qp_i", String.valueOf(new int[] {28, 23, 19}[level]),
                        "-qp_p", String.valueOf(new int[] {30, 25, 21}[level])));
            }
        } else if (encoder.endsWith("_qsv")) {
            args.addAll(Arrays.asList("-preset", "medium", "-look_ahead", "0"));
            if (!custom) {
                args.addAll(Arrays.asList("-global_quality", String.valueOf(new int[] {28, 23, 19}[level])));
            }
        } else if (!custom) { // vaapi
            args.addAll(Arrays.asList("-qp", String.valueOf(new int[] {28, 23, 19}[level])));
        }
        if (custom) {
            args.addAll(Arrays.asList("-b:v", rate, "-maxrate", rate, "-bufsize", 2 * bitrate + "M"));
        }
        return args;
    }

    /**
     * The command for an animated WebP of the last {@code seconds} of {@code clip}. {@code width} 0 keeps the
     * clip's size. Quality is the encoder's 0 to 100 (in lossless mode it is the effort, not the fidelity).
     */
    static List<String> webpArgs(String binary, Path clip, Path out, int seconds, int fps, int width, int quality,
            boolean lossless) {
        String filter = "fps=" + fps + (width > 0 ? ",scale='min(" + width + ",iw)':-2:flags=lanczos" : "");
        return Arrays.asList(binary, "-hide_banner", "-loglevel", "error", "-y", "-sseof", "-" + seconds,
                "-i", clip.toString(), "-an", "-vf", filter, "-c:v", "libwebp_anim", "-lossless", lossless ? "1" : "0",
                "-quality", String.valueOf(quality), "-compression_level", "4", "-loop", "0", "-preset", "default",
                out.toString());
    }

    /** The extension of a container setting. */
    static String extension(String container) {
        return "mp4".equals(container) ? "mp4" : "mov".equals(container) ? "mov" : "mkv";
    }

    /** Muxer options for a container; MP4 and MOV are fragmented so a crash leaves a playable file. */
    static List<String> containerArgs(String container, String encoder) {
        List<String> args = new ArrayList<>();
        if (!"mkv".equals(container)) {
            args.addAll(Arrays.asList("-movflags", "+frag_keyframe+empty_moov+default_base_moof"));
            if (hevc(encoder)) {
                args.addAll(Arrays.asList("-tag:v", "hvc1"));
            }
        }
        return args;
    }
}
