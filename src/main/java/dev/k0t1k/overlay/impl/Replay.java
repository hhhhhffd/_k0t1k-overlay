package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The replay buffer, as OBS does it: the encoder writes a rolling series of two-second Matroska
 * segments into a temporary folder, the oldest are deleted as new ones arrive, and saving joins the
 * finished ones with {@code ffmpeg -c copy}, which takes no re-encoding and so no time. Also turns a
 * saved clip into a GIF.
 */
final class Replay {
    static final int SEGMENT_SECONDS = 2;
    static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private final Options options;
    private final Ffmpeg ffmpeg;
    private final Path root;
    private final ScheduledExecutorService io;
    private final Consumer<Throwable> onError;
    private ScheduledFuture<?> pruning;
    private volatile Path folder;
    private volatile boolean saving;
    private volatile boolean hevc;

    Replay(Options options, Ffmpeg ffmpeg, ScheduledExecutorService io, Consumer<Throwable> onError) {
        this.options = options;
        this.ffmpeg = ffmpeg;
        this.root = options.dataDir.resolve("replay-buffer");
        this.io = io;
        this.onError = onError;
    }

    boolean saving() {
        return saving;
    }

    /** Starts a buffer: clears what an earlier game left behind and returns the folder for the segments. */
    synchronized Path begin(boolean hevcEncoder) throws IOException {
        deleteTree(root);
        folder = Files.createDirectories(root);
        hevc = hevcEncoder;
        pruning = io.scheduleWithFixedDelay(this::prune, 1, 1, TimeUnit.SECONDS);
        return folder;
    }

    /** The ffmpeg output pattern for the segments of the buffer {@link #begin} made. */
    static String pattern(Path folder) {
        return folder.resolve("seg%05d.mkv").toString();
    }

    /** Stops pruning and removes the segments; called on the io thread. */
    synchronized void end() {
        if (pruning != null) {
            pruning.cancel(false);
            pruning = null;
        }
        folder = null;
        try {
            deleteTree(root);
        } catch (IOException e) {
            onError.accept(e);
        }
    }

    /** The finished segments, oldest first; the newest file is still being written and is left out. */
    private List<Path> finished(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "seg*.mkv")) {
            for (Path file : stream) {
                files.add(file);
            }
        }
        Collections.sort(files);
        if (!files.isEmpty()) {
            files.remove(files.size() - 1);
        }
        return files;
    }

    /** Keeps the last N seconds of segments, plus one for the cut that rarely falls on a segment edge. */
    private void prune() {
        Path dir = folder;
        if (dir == null || saving) {
            return;
        }
        try {
            List<Path> files = finished(dir);
            int keep = (options.replaySeconds + SEGMENT_SECONDS - 1) / SEGMENT_SECONDS + 1;
            for (int i = 0; i < files.size() - keep; i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException | RuntimeException e) {
            onError.accept(e);
        }
    }

    /**
     * Joins the buffered segments into one file in the recordings folder, on the calling (io) thread.
     * Returns null when the buffer holds nothing yet.
     */
    Path save() throws IOException {
        Path dir = folder;
        String binary = ffmpeg.path();
        if (dir == null || binary == null) {
            return null;
        }
        saving = true;
        try {
            List<Path> files = finished(dir);
            if (files.isEmpty()) {
                return null;
            }
            StringBuilder list = new StringBuilder();
            for (Path file : files) {
                list.append("file '").append(file.toAbsolutePath().toString().replace("\\", "/").replace("'", "'\\''")).append("'\n");
            }
            Path listFile = dir.resolve("list.txt");
            Files.write(listFile, list.toString().getBytes(StandardCharsets.UTF_8));
            Path target = options.recordingsFolder();
            Files.createDirectories(target);
            String container = options.container;
            Path out = target.resolve("Replay_" + LocalDateTime.now().format(NAME) + "." + Ffmpeg.extension(container));
            List<String> command = new ArrayList<>(Arrays.asList(binary, "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "concat", "-safe", "0", "-i", listFile.toString(), "-map", "0", "-c", "copy"));
            if (!"mkv".equals(container)) {
                command.addAll(Arrays.asList("-movflags", "+faststart"));
                if (hevc) {
                    command.addAll(Arrays.asList("-tag:v", "hvc1"));
                }
            }
            command.add(out.toString());
            Ffmpeg.run(command);
            return out;
        } finally {
            saving = false;
        }
    }

    /** Turns the last seconds of {@code clip} into a GIF next to it, with a palette made for this clip. */
    Path gif(Path clip) throws IOException {
        String binary = ffmpeg.path();
        if (binary == null) {
            throw new IOException("ffmpeg is not installed");
        }
        String name = clip.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path out = clip.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".gif");
        String filter = String.format(Locale.ROOT,
                "fps=%d,scale=%d:-1:flags=lanczos,split[a][b];[a]palettegen=stats_mode=diff:max_colors=256[p];"
                        + "[b][p]paletteuse=dither=sierra2_4a:diff_mode=rectangle", options.gifFps, options.gifWidth);
        Ffmpeg.run(Arrays.asList(binary, "-hide_banner", "-loglevel", "error", "-y",
                "-sseof", "-" + options.gifSeconds, "-i", clip.toString(), "-an", "-vf", filter, "-loop", "0", out.toString()));
        return out;
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
}
