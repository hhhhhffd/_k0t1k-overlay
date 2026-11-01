package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The replay buffer, as OBS does it: the encoder writes a rolling series of two-second Matroska
 * segments into a temporary folder, the oldest are deleted as new ones arrive, and saving joins the
 * finished ones with {@code ffmpeg -c copy}, which takes no re-encoding and so no time. Also turns a
 * saved clip into an animated WebP.
 *
 * <p>Games that share a data folder must not touch each other's segments, so every buffer lives in its
 * own subfolder of {@code replay-buffer} and holds a lock on a file in it for as long as it runs. A
 * folder whose lock is free belongs to a dead game and is cleaned up by the next one to start.
 */
final class Replay {
    static final int SEGMENT_SECONDS = 2;
    private static final String LOCK = "owner.lock";
    /** Directory-level lock file in the buffer root; {@link #GUARD} covers the threads of this JVM. */
    private static final String GUARD_FILE = ".lock";
    private static final Object GUARD = new Object();
    static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private final Options options;
    private final Ffmpeg ffmpeg;
    private final Path root;
    private final ScheduledExecutorService io;
    private final Consumer<Throwable> onError;
    private ScheduledFuture<?> pruning;
    private volatile Path folder;
    /** Holds the lock that tells other games this buffer's folder is in use. */
    private FileChannel lock;
    private volatile boolean saving;
    private volatile boolean hevc;

    Replay(Options options, Ffmpeg ffmpeg, ScheduledExecutorService io, Consumer<Throwable> onError) {
        this.options = options;
        this.ffmpeg = ffmpeg;
        this.root = options.dataDir.resolve("replay-buffer");
        this.io = io;
        this.onError = onError;
    }

    /**
     * Creates an empty file {@code base.ext} in {@code folder}, or {@code base_2.ext}, {@code base_3.ext}... when
     * that exists, so two clips made within the same second never overwrite each other (ffmpeg runs with
     * {@code -y} and then writes into the reserved file).
     */
    static Path reserve(Path folder, String base, String extension) throws IOException {
        for (int n = 1; ; n++) {
            Path candidate = folder.resolve(base + (n == 1 ? "" : "_" + n) + "." + extension);
            try {
                return Files.createFile(candidate);
            } catch (FileAlreadyExistsException e) {
                // Taken: try the next number.
            }
        }
    }

    boolean saving() {
        return saving;
    }

    /** Starts a buffer: clears what dead games left behind and returns this buffer's own folder for the segments. */
    synchronized Path begin(boolean hevcEncoder) throws IOException {
        Files.createDirectories(root);
        Path own;
        // Creating+locking a folder and cleaning up must not interleave across games, or a cleanup
        // could see a folder that is created but not yet locked as abandoned.
        synchronized (GUARD) {
            try (FileChannel guard = FileChannel.open(root.resolve(GUARD_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                guard.lock();
                own = Files.createDirectory(root.resolve(processId() + "-" + System.currentTimeMillis()));
                lock = lock(own);
                folder = own;
                removeStale(root, own);
            }
        }
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
        Path own = folder;
        folder = null;
        try {
            if (own != null) {
                deleteTree(own);
            }
        } catch (IOException e) {
            onError.accept(e);
        }
        if (lock != null) {
            try {
                lock.close();
            } catch (IOException e) {
                onError.accept(e);
            }
            lock = null;
        }
    }

    private static String processId() {
        String name = ManagementFactory.getRuntimeMXBean().getName(); // "pid@host" on the JVMs this runs on
        int at = name.indexOf('@');
        return at > 0 ? name.substring(0, at) : "0";
    }

    /** Creates {@code folder}'s lock file and locks it; the lock lasts until the returned channel is closed. */
    static FileChannel lock(Path folder) throws IOException {
        FileChannel channel = FileChannel.open(folder.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            if (channel.tryLock() == null) {
                throw new IOException("replay buffer folder is locked: " + folder);
            }
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
        return channel;
    }

    /**
     * Deletes the buffer folders in {@code root} whose game is gone (their lock is free), except
     * {@code keep}, and loose files from before buffers had folders. A folder with no lock file yet is
     * a game that is just starting; it is left alone unless it has been there for a minute. The caller
     * holds the root's {@code .lock} (see {@link #begin}), so no other game is mid-creation.
     */
    static void removeStale(Path root, Path keep) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path entry : stream) {
                if (entry.equals(keep) || entry.getFileName().toString().equals(GUARD_FILE)) {
                    continue;
                }
                try {
                    if (!Files.isDirectory(entry)) {
                        Files.deleteIfExists(entry);
                    } else if (isStale(entry)) {
                        deleteTree(entry);
                    }
                } catch (IOException | RuntimeException e) {
                    // In use or not ours to remove: the next start tries again.
                }
            }
        } catch (IOException e) {
            // No buffer folder to clean.
        }
    }

    private static boolean isStale(Path dir) throws IOException {
        Path lockFile = dir.resolve(LOCK);
        if (!Files.exists(lockFile)) {
            return System.currentTimeMillis() - Files.getLastModifiedTime(dir).toMillis() > 60000;
        }
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
            // The lock is released when the channel closes, just before the folder is deleted.
            return channel.tryLock() != null;
        } catch (OverlappingFileLockException e) {
            return false; // held by this JVM
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
            Path out = reserve(target, "Replay_" + LocalDateTime.now().format(NAME), Ffmpeg.extension(container));
            List<String> command = new ArrayList<>(Arrays.asList(binary, "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "concat", "-safe", "0", "-i", listFile.toString(), "-map", "0", "-c", "copy"));
            if (!"mkv".equals(container)) {
                command.addAll(Arrays.asList("-movflags", "+faststart"));
                if (hevc) {
                    command.addAll(Arrays.asList("-tag:v", "hvc1"));
                }
            }
            command.add(out.toString());
            try {
                Ffmpeg.run(command);
            } catch (IOException e) {
                Files.deleteIfExists(out);
                throw e;
            }
            return out;
        } finally {
            saving = false;
        }
    }

    /** Turns the last seconds of {@code clip} into an animated WebP next to it. */
    Path webp(Path clip) throws IOException {
        String binary = ffmpeg.path();
        if (binary == null) {
            throw new IOException("ffmpeg is not installed");
        }
        String name = clip.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path out = reserve(clip.getParent(), dot > 0 ? name.substring(0, dot) : name, "webp");
        try {
            Ffmpeg.run(Ffmpeg.webpArgs(binary, clip, out, options.webpSeconds, options.webpFps, options.webpWidth,
                    options.webpQuality, options.webpLossless));
        } catch (IOException e) {
            Files.deleteIfExists(out);
            throw e;
        }
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
