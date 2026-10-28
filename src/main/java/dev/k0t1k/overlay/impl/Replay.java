package dev.k0t1k.overlay.impl;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * The replay buffer: the last {@link #SECONDS} seconds of the game, kept as JPEG frames in memory
 * (about a megabyte a second), saved on request as a GIF, an MJPEG AVI or, when ffmpeg is installed,
 * an MP4. Frames are read back on the render thread by {@link GlCapture}; compressing and saving run on
 * a worker thread of their own so the game never waits for them.
 */
final class Replay {
    static final int SECONDS = 30;
    static final int FPS = 15;
    /** Captured frames are at most this wide; GIFs are scaled down further. */
    static final int MAX_WIDTH = 854;
    private static final int GIF_WIDTH = 480;
    private static final int GIF_FPS = 10;
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private static final class Shot {
        final byte[] jpeg;
        final long time;
        final int width;
        final int height;

        Shot(byte[] jpeg, long time, int width, int height) {
            this.jpeg = jpeg;
            this.time = time;
            this.width = width;
            this.height = height;
        }
    }

    private final Path folder;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "k0t1k-overlay-replay");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    /** Touched only by the worker thread. */
    private final ArrayDeque<Shot> shots = new ArrayDeque<>();
    private long nextCapture;
    /** Frames read back but not compressed yet; past a few, capturing pauses. */
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean saving;
    private final Consumer<Throwable> onError;

    Replay(Path gameDir, Consumer<Throwable> onError) {
        this.folder = gameDir.resolve("replays");
        this.onError = onError;
    }

    /** Whether the render thread should read back a frame now; keeps to {@link #FPS}. */
    boolean due(long now) {
        if (now < nextCapture || pending.get() > 4) {
            return false;
        }
        nextCapture = Math.max(nextCapture + 1000 / FPS, now);
        return true;
    }

    /** Takes a read-back frame (render thread) and compresses it on the worker. */
    void add(GlCapture.Frame frame) {
        pending.incrementAndGet();
        worker.execute(() -> {
            try {
                byte[] jpeg = jpeg(frame);
                shots.addLast(new Shot(jpeg, frame.time, frame.width, frame.height));
                long oldest = frame.time - SECONDS * 1000L;
                while (!shots.isEmpty() && shots.peekFirst().time < oldest) {
                    shots.removeFirst();
                }
            } catch (IOException | RuntimeException e) {
                onError.accept(e);
            } finally {
                pending.decrementAndGet();
            }
        });
    }

    boolean saving() {
        return saving;
    }

    /**
     * Saves what the buffer holds as "gif" or "video" (MP4 with ffmpeg, else AVI) and reports the
     * file, or null with nothing recorded yet, to {@code done} on the worker thread.
     */
    void save(String format, Consumer<Path> done) {
        saving = true;
        worker.execute(() -> {
            try {
                List<Shot> copy = new ArrayList<>(shots);
                if (copy.isEmpty()) {
                    done.accept(null);
                    return;
                }
                Files.createDirectories(folder);
                String base = "replay_" + LocalDateTime.now().format(NAME);
                Path file;
                if ("gif".equals(format)) {
                    file = folder.resolve(base + ".gif");
                    writeGif(copy, file);
                } else {
                    String ffmpeg = findFfmpeg();
                    if (ffmpeg != null) {
                        file = folder.resolve(base + ".mp4");
                        writeMp4(copy, file, ffmpeg);
                    } else {
                        file = folder.resolve(base + ".avi");
                        writeAvi(copy, file);
                    }
                }
                done.accept(file);
            } catch (IOException | RuntimeException e) {
                onError.accept(e);
                done.accept(null);
            } finally {
                saving = false;
            }
        });
    }

    void stop() {
        worker.shutdownNow();
    }

    // ---- encoding ----

    /** Bottom-up RGBA to a top-down JPEG. */
    private static byte[] jpeg(GlCapture.Frame frame) throws IOException {
        BufferedImage image = new BufferedImage(frame.width, frame.height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] bgr = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        ByteBuffer rgba = frame.pixels;
        int w = frame.width;
        for (int y = 0; y < frame.height; y++) {
            int src = (frame.height - 1 - y) * w * 4;
            int dst = y * w * 3;
            for (int x = 0; x < w; x++) {
                bgr[dst + x * 3] = rgba.get(src + x * 4 + 2);
                bgr[dst + x * 3 + 1] = rgba.get(src + x * 4 + 1);
                bgr[dst + x * 3 + 2] = rgba.get(src + x * 4);
            }
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("no JPEG encoder in this Java");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.82f);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static BufferedImage decode(Shot shot) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(shot.jpeg));
        if (image == null) {
            throw new IOException("unreadable replay frame");
        }
        return image;
    }

    private static void writeGif(List<Shot> shots, Path file) throws IOException {
        Shot first = shots.get(0);
        int w = Math.min(GIF_WIDTH, first.width);
        int h = Math.max(2, Math.round(first.height * (w / (float) first.width)));
        try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(file))) {
            GifWriter gif = new GifWriter(out, w, h);
            long step = 1000 / GIF_FPS;
            long next = first.time;
            for (Shot shot : shots) {
                if (shot.time < next) {
                    continue;
                }
                next = shot.time + step;
                gif.frame(decode(shot), (int) (step / 10));
            }
            gif.finish();
        }
    }

    private static void writeAvi(List<Shot> shots, Path file) throws IOException {
        Shot first = shots.get(0);
        float seconds = Math.max(1, shots.get(shots.size() - 1).time - first.time) / 1000f;
        int fps = Math.max(1, Math.round((shots.size() - 1) / seconds));
        List<byte[]> frames = new ArrayList<>(shots.size());
        for (Shot shot : shots) {
            frames.add(shot.jpeg);
        }
        AviWriter.write(file, first.width, first.height, fps, frames);
    }

    private static void writeMp4(List<Shot> shots, Path file, String ffmpeg) throws IOException {
        Shot first = shots.get(0);
        float seconds = Math.max(1, shots.get(shots.size() - 1).time - first.time) / 1000f;
        String fps = String.format(Locale.ROOT, "%.3f", Math.max(1, (shots.size() - 1) / seconds));
        // Even sizes for yuv420p; the frames are fed as one MJPEG stream.
        ProcessBuilder builder = new ProcessBuilder(ffmpeg, "-y", "-loglevel", "error", "-f", "mjpeg",
                "-framerate", fps, "-i", "-", "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
                file.toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File(System.getProperty("os.name", "").startsWith("Windows") ? "NUL" : "/dev/null"));
        Process process = builder.start();
        try (OutputStream in = process.getOutputStream()) {
            for (Shot shot : shots) {
                in.write(shot.jpeg);
            }
        }
        try {
            int code = process.waitFor();
            if (code != 0) {
                throw new IOException("ffmpeg failed with exit code " + code);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while ffmpeg ran", e);
        }
    }

    /** ffmpeg on PATH, if any. */
    private static String findFfmpeg() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        for (String dir : path.split(File.pathSeparator)) {
            File candidate = new File(dir, windows ? "ffmpeg.exe" : "ffmpeg");
            if (candidate.isFile() && candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }
}
