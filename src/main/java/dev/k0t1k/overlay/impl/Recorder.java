package dev.k0t1k.overlay.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Records the game with ffmpeg, like OBS: frames come from the renderer as NV12 (OpenGL converts them on
 * the GPU) or RGBA (Vulkan; converted here), go through a small pool to a writer thread, and from there
 * into the standard input of an ffmpeg process that encodes them with the audio. The render thread
 * never waits: with no free frame buffer the frame is dropped and counted.
 *
 * <p>A recording (hotkey) and the replay buffer (always on, see {@link Replay}) are two sessions with
 * a process each, fed the same frames, so they can run together at the price of encoding twice.
 */
final class Recorder {
    /** A frame buffer on its way from the renderer to the writer and back to the pool. */
    static final class Frame {
        final byte[] data;
        int width;
        int height;
        /** RGBA rows top to bottom, else NV12. */
        boolean rgba;
        /** The NV12 bytes the sessions write: {@link #data} itself, or the writer's conversion of it. */
        private byte[] ready;
        private byte[] converted;
        /** Sessions that still have to write this frame; the last one to finish returns it to the pool. */
        private final AtomicInteger users = new AtomicInteger();
        private final ArrayBlockingQueue<Frame> home;

        private Frame(int bytes, ArrayBlockingQueue<Frame> home) {
            this.data = new byte[bytes];
            this.home = home;
        }

        private void release() {
            if (users.decrementAndGet() == 0) {
                home.offer(this);
            }
        }
    }

    private enum State { WAITING, STARTING, RUNNING, STOPPING, ENDED }

    private static final int POOL = 3;
    /** How long a stop waits for ffmpeg to finish the file once its input has ended. */
    private static final int FINISH_SECONDS = 15;
    /** How long exiting waits for a save or a recording that is being finished. */
    private static final int EXIT_WAIT_SECONDS = 10;
    /** How long a stop waits for a frame write that does not return before it kills ffmpeg. */
    private static final int STUCK_WRITE_SECONDS = 2;
    /** How often {@link #tick} looks for changed recording settings. */
    private static final int SIGNATURE_CHECK_MILLIS = 250;
    private static final String COLOR_TAGS = "setparams=colorspace=bt709:color_primaries=bt709:color_trc=bt709:range=tv";
    private static final String[] AUDIO_NAMES = {"Game", "Desktop", "Microphone"};

    private final Options options;
    /** Shows a toast of the given kind. */
    interface Notifier {
        void toast(ToastKind kind, String title, String body);
    }

    private final Strings strings;
    private final Ffmpeg ffmpeg;
    private final Replay replay;
    private final ScheduledExecutorService io;
    private final Executor render;
    private final Notifier notify;
    private final Consumer<Throwable> onError;

    private volatile Session recording;
    private volatile Session buffer;
    /** The buffer failed or was stopped on purpose; it starts again when the settings change or are toggled. */
    private String bufferBlockedBy;
    private String bufferSignature = "";
    private long signatureChangedAt;
    private long signatureCheckedAt;
    private boolean bufferWasWanted;
    /** The last file saved, for the WebP button. */
    private volatile Path lastClip;
    private volatile boolean busy;
    private volatile boolean broken;
    /** The JVM is exiting: nothing may start (or restart) after {@link #shutdown}. */
    private volatile boolean shuttingDown;

    /** The size every session encodes at; set when the first one starts, 0 with none. */
    private volatile int width;
    private volatile int height;
    private long nextCapture;
    private volatile int dropped;

    private ArrayBlockingQueue<Frame> pool;
    private int poolBytes;
    private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(POOL);
    private Thread writer;

    Recorder(Options options, Ffmpeg ffmpeg, ScheduledExecutorService io, Executor render,
            Notifier notify, Consumer<Throwable> onError) {
        this.options = options;
        this.strings = options.strings;
        this.ffmpeg = ffmpeg;
        this.io = io;
        this.render = render;
        this.notify = notify;
        this.onError = onError;
        this.replay = new Replay(options, ffmpeg, io, onError);
    }

    // ---- state, for the interface ----

    /** Whether any session wants frames. */
    boolean wanted() {
        return recording != null || buffer != null;
    }

    boolean recording() {
        Session session = recording;
        return session != null && session.state != State.ENDED;
    }

    boolean buffering() {
        Session session = buffer;
        return session != null && session.state == State.RUNNING;
    }

    /** Whether a save, stop or conversion is under way. */
    boolean busy() {
        return busy;
    }

    long recordingMillis() {
        Session session = recording;
        return session != null && session.state == State.RUNNING ? System.currentTimeMillis() - session.startedAt : 0;
    }

    int dropped() {
        return dropped;
    }

    boolean hasClip() {
        return lastClip != null;
    }

    /** Why the replay buffer is not running although it is on, or empty. */
    String bufferProblem() {
        return bufferBlockedBy == null ? "" : bufferBlockedBy;
    }

    /** A renderer could not read frames; nothing records for the rest of this game. */
    void failed(Throwable error) {
        broken = true;
        onError.accept(error);
        io.execute(() -> {
            stopSession(recording);
            stopSession(buffer);
        });
    }

    // ---- commands, on the render thread ----

    /** Starts a recording, or stops the running one. */
    void toggleRecording() {
        Session session = recording;
        if (session != null && session.state != State.ENDED) {
            if (session.state == State.RUNNING) {
                io.execute(() -> stopSession(session));
            }
            return;
        }
        if (shuttingDown || !ready()) {
            return;
        }
        recording = new Session(false);
    }

    /** Writes the replay buffer's last seconds to a file. */
    void saveReplay() {
        if (shuttingDown) {
            return;
        }
        Session session = buffer;
        if (session == null || session.state != State.RUNNING) {
            notify.toast(ToastKind.RECORDING, strings.get("replay.title"), strings.get(options.replayBuffer
                    ? bufferProblem().isEmpty() ? "replay.starting" : "replay.stopped" : "replay.off"));
            return;
        }
        if (busy) {
            return;
        }
        busy = true;
        notify.toast(ToastKind.RECORDING, strings.get("replay.title"), strings.get("replay.saving"));
        io.execute(() -> {
            try {
                Path file = replay.save();
                if (file == null) {
                    notify.toast(ToastKind.ERROR, strings.get("replay.title"), strings.get("replay.failed"));
                } else {
                    lastClip = file;
                    notify.toast(ToastKind.RECORDING, strings.get("replay.saved"), file.getFileName() + "\n" + file.getParent());
                }
            } catch (IOException e) {
                notify.toast(ToastKind.ERROR, strings.get("rec.failed"), e.getMessage());
                onError.accept(e);
            } finally {
                busy = false;
            }
        });
    }

    /** Makes an animated WebP from the end of the last saved clip. */
    void makeWebp() {
        Path clip = lastClip;
        if (clip == null) {
            notify.toast(ToastKind.RECORDING, strings.get("webp.title"), strings.get("webp.noClip"));
            return;
        }
        if (busy || !ready()) {
            return;
        }
        if (!ffmpeg.hasWebp()) {
            notify.toast(ToastKind.RECORDING, strings.get("webp.title"), strings.get("webp.missing"));
            return;
        }
        busy = true;
        notify.toast(ToastKind.RECORDING, strings.get("webp.title"), strings.get("webp.making"));
        io.execute(() -> {
            try {
                Path file = replay.webp(clip);
                notify.toast(ToastKind.RECORDING, strings.get("webp.saved"), file.getFileName() + "\n" + file.getParent());
            } catch (IOException e) {
                notify.toast(ToastKind.ERROR, strings.get("rec.failed"), e.getMessage());
                onError.accept(e);
            } finally {
                busy = false;
            }
        });
    }

    /** Whether recording can start now; says why not when it cannot. */
    private boolean ready() {
        if (broken) {
            notify.toast(ToastKind.RECORDING, strings.get("rec.title"), strings.get("rec.unsupported"));
            return false;
        }
        if (ffmpeg.path() == null) {
            notify.toast(ToastKind.RECORDING, strings.get("rec.title"), strings.get("rec.noFfmpeg"));
            return false;
        }
        if (ffmpeg.probing()) {
            notify.toast(ToastKind.RECORDING, strings.get("rec.title"), strings.get("rec.probing"));
            return false;
        }
        return true;
    }

    /** Called every frame on the render thread: starts and stops the replay buffer, forgets finished sessions. */
    void tick() {
        if (shuttingDown) {
            return;
        }
        Session session = recording;
        if (session != null && session.state == State.ENDED) {
            recording = null;
        }
        session = buffer;
        if (session != null && session.state == State.ENDED) {
            buffer = null;
        }
        if (recording == null && buffer == null) {
            width = height = 0;
        }
        boolean wanted = options.replayBuffer && !broken && ffmpeg.path() != null && !ffmpeg.probing();
        if (wanted != bufferWasWanted) {
            bufferWasWanted = wanted;
            bufferBlockedBy = null;
        }
        long now = System.currentTimeMillis();
        // Building the signature takes a dozen string conversions; settings are not checked every frame.
        if (now - signatureCheckedAt >= SIGNATURE_CHECK_MILLIS) {
            signatureCheckedAt = now;
            String signature = signature();
            if (!signature.equals(bufferSignature)) {
                bufferSignature = signature;
                signatureChangedAt = now;
                bufferBlockedBy = null;
            }
        }
        session = buffer;
        if (session != null && (!wanted || !session.signature.equals(bufferSignature) && now - signatureChangedAt > 1500)) {
            // Settings changed (after the player stopped dragging a slider) or the buffer was turned off.
            Session stopping = session;
            if (session.state == State.WAITING) {
                buffer = null;
            } else if (session.state == State.RUNNING) {
                io.execute(() -> stopSession(stopping));
            }
        } else if (session == null && wanted && bufferBlockedBy == null) {
            buffer = new Session(true);
        }
    }

    /** The settings a running buffer was started with; a change restarts it. */
    private String signature() {
        return String.join("|", options.encoder, options.fps + "", options.resolution, options.quality, options.bitrate + "",
                options.audio + "", options.audioGame + "", options.audioRest + "", options.audioMic + "",
                options.volumeGame + "", options.volumeRest + "", options.volumeMic + "", ffmpeg.path());
    }

    // ---- frames, from the renderers ----

    /** Whether the renderer should read back this frame; also starts sessions that wait for the frame's size. */
    boolean due(int frameWidth, int frameHeight) {
        Session r = recording;
        Session b = buffer;
        if (broken || shuttingDown || r == null && b == null || frameWidth < 2 || frameHeight < 2) {
            return false;
        }
        int fps = Math.max(prepare(r, frameWidth, frameHeight), prepare(b, frameWidth, frameHeight));
        long now = System.nanoTime();
        if (fps == 0 || now < nextCapture) {
            return false;
        }
        nextCapture = Math.max(nextCapture + 1_000_000_000L / fps, now);
        return true;
    }

    /** Starts a session that waits for the frame size; returns its frame rate when it runs, else 0. */
    private int prepare(Session session, int frameWidth, int frameHeight) {
        if (session == null) {
            return 0;
        }
        if (session.state == State.WAITING) {
            if (width == 0) {
                int[] size = target(frameWidth, frameHeight);
                width = size[0];
                height = size[1];
            }
            session.state = State.STARTING;
            session.width = width;
            session.height = height;
            io.execute(session::launch);
            return 0;
        }
        return session.state == State.RUNNING ? session.fps : 0;
    }

    /** The encoded size for a frame size and the resolution setting, both even. */
    private int[] target(int frameWidth, int frameHeight) {
        int limit = "1080".equals(options.resolution) ? 1080 : "720".equals(options.resolution) ? 720 : Integer.MAX_VALUE;
        int h = Math.min(frameHeight, limit);
        int w = Math.round(frameWidth * (h / (float) frameHeight));
        return new int[] {Math.max(2, w & ~1), Math.max(2, h & ~1)};
    }

    /** The size frames must have: what {@link #due} settled on. */
    int width() {
        return width;
    }

    int height() {
        return height;
    }

    /** A free frame buffer for a frame of the session's size, or null (and counted as dropped) when none is free. */
    Frame acquire(boolean rgba) {
        int w = width;
        int h = height;
        if (w == 0) {
            return null;
        }
        int bytes = rgba ? w * h * 4 : w * h * 3 / 2;
        if (pool == null || poolBytes != bytes) {
            pool = new ArrayBlockingQueue<>(POOL);
            poolBytes = bytes;
            for (int i = 0; i < POOL; i++) {
                pool.add(new Frame(bytes, pool));
            }
        }
        Frame frame = pool.poll();
        if (frame == null) {
            dropped++;
            return null;
        }
        frame.width = w;
        frame.height = h;
        frame.rgba = rgba;
        return frame;
    }

    /** Hands a filled frame to the writer; it is encoded for every running session. */
    void submit(Frame frame) {
        if (!queue.offer(frame)) {
            dropped++;
            frame.home.offer(frame);
            return;
        }
        if (writer == null) {
            writer = new Thread(this::write, "k0t1k-overlay-writer");
            writer.setDaemon(true);
            writer.start();
        }
    }

    /**
     * Converts the frame once and hands it to every running session; each session has a thread of its
     * own for the pipe, so one stuck ffmpeg does not hold back the other.
     */
    private void write() {
        try {
            for (;;) {
                Frame frame = queue.take();
                // Frames come at the higher rate of the two sessions; each takes only those its own rate is due for.
                long now = System.nanoTime();
                Session r = recording;
                Session b = buffer;
                Session first = r != null && r.takes(frame, now) ? r : null;
                Session second = b != null && b.takes(frame, now) ? b : null;
                if (first == null) {
                    first = second;
                    second = null;
                }
                if (first == null) {
                    frame.home.offer(frame);
                    continue;
                }
                frame.ready = frame.data;
                if (frame.rgba) {
                    int size = frame.width * frame.height * 3 / 2;
                    if (frame.converted == null || frame.converted.length != size) {
                        frame.converted = new byte[size];
                    }
                    toNv12(frame.data, frame.width, frame.height, frame.converted);
                    frame.ready = frame.converted;
                }
                frame.users.set(second == null ? 1 : 2);
                first.offer(frame);
                if (second != null) {
                    second.offer(frame);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** RGBA rows top to bottom to NV12 (BT.709, limited range), chroma averaged over 2 x 2 pixels. */
    static void toNv12(byte[] rgba, int w, int h, byte[] out) {
        int uv = w * h;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int sumR = 0;
                int sumG = 0;
                int sumB = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int i = ((y + dy) * w + x + dx) * 4;
                        int r = rgba[i] & 255;
                        int g = rgba[i + 1] & 255;
                        int b = rgba[i + 2] & 255;
                        out[(y + dy) * w + x + dx] = (byte) (16 + ((47 * r + 157 * g + 16 * b) >> 8));
                        sumR += r;
                        sumG += g;
                        sumB += b;
                    }
                }
                int r = (sumR + 2) >> 2;
                int g = (sumG + 2) >> 2;
                int b = (sumB + 2) >> 2;
                int o = uv + (y / 2) * w + x;
                out[o] = (byte) (128 + ((-26 * r - 86 * g + 112 * b) >> 8));
                out[o + 1] = (byte) (128 + ((112 * r - 102 * g - 10 * b) >> 8));
            }
        }
    }

    // ---- sessions ----

    /**
     * Stops everything and waits for the files to be finished; the JVM is exiting. Commands are refused
     * from here on. A replay save or a recording being finished (remux included) gets {@link #EXIT_WAIT_SECONDS}
     * in total before the buffer is torn down, which would delete the segments the save reads.
     */
    void shutdown() {
        shuttingDown = true;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(EXIT_WAIT_SECONDS);
        awaitUntil(deadline, () -> !busy);
        Session[] sessions = {recording, buffer};
        for (Session session : sessions) {
            if (session != null) {
                session.finish(false);
            }
        }
        // A launch still under way sees the cancellation, closes what it made and ends.
        for (Session session : sessions) {
            if (session != null) {
                session.awaitLaunch();
            }
        }
        // A session already being stopped on the io thread is not ended before its files are complete.
        awaitUntil(deadline, () -> (recording == null || recording.state == State.ENDED)
                && (buffer == null || buffer.state == State.ENDED));
    }

    private static void awaitUntil(long deadline, BooleanSupplier done) {
        try {
            while (!done.getAsBoolean() && System.nanoTime() - deadline < 0) {
                Thread.sleep(25);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs on the io thread. */
    private void stopSession(Session session) {
        if (session != null) {
            session.finish(true);
        }
    }

    /** One ffmpeg process: a recording to a file, or the replay buffer's segments. */
    private final class Session {
        final boolean isBuffer;
        volatile State state = State.WAITING;
        volatile int width;
        volatile int height;
        final String signature = signature();
        final String encoder;
        final int fps = options.fps;
        long startedAt;
        private Path file;
        private Process process;
        private OutputStream stdin;
        private final List<AudioCapture.Feed> feeds = new ArrayList<>();
        private final ArrayDeque<String> lines = new ArrayDeque<>();
        private Thread errors;
        /** Guards the state and the fields above; never held during pipe or process I/O. */
        private final Object lock = new Object();
        private boolean failedAlready;
        /** Set when the session is stopped before {@link #launch} published it; launch then undoes itself. */
        private volatile boolean cancelled;
        private final CountDownLatch launchDone = new CountDownLatch(1);
        /** Frames for this session's own thread, which does the blocking pipe writes. */
        private final ArrayBlockingQueue<Frame> pending = new ArrayBlockingQueue<>(1);
        private Thread feeder;
        private volatile boolean feeding;
        /** When this session wants its next frame, in {@link System#nanoTime} terms; writer thread only. */
        private long nextFrame;
        /** Whether the audio helpers were told when the first frame went out. */
        private boolean aligned;

        Session(boolean isBuffer) {
            this.isBuffer = isBuffer;
            this.encoder = ffmpeg.chooseEncoder(options.encoder);
        }

        /** Starts the audio helpers, then ffmpeg. Runs on the io thread. */
        void launch() {
            try {
                if (cancelled) {
                    abandon();
                    return;
                }
                List<String> audioNames = startAudio();
                if (cancelled) {
                    abandon();
                    return;
                }
                List<String> command = command(audioNames);
                Process started = new ProcessBuilder(command).start();
                boolean publish;
                synchronized (lock) {
                    process = started;
                    publish = !cancelled;
                    if (publish) {
                        stdin = started.getOutputStream();
                        errors = new Thread(() -> readErrors(started), "k0t1k-overlay-ffmpeg");
                        errors.setDaemon(true);
                        errors.start();
                        feeding = true;
                        feeder = new Thread(this::feed, "k0t1k-overlay-writer-" + (isBuffer ? "buffer" : "recording"));
                        feeder.setDaemon(true);
                        feeder.start();
                        startedAt = System.currentTimeMillis();
                        state = State.RUNNING;
                    }
                }
                if (!publish) {
                    abandon();
                } else if (!isBuffer) {
                    notify.toast(ToastKind.RECORDING, strings.get("rec.title"), strings.get("rec.started"));
                }
            } catch (IOException | RuntimeException e) {
                onError.accept(e);
                fail(String.valueOf(e.getMessage() != null ? e.getMessage() : e));
            } finally {
                launchDone.countDown();
            }
        }

        /** Undoes a launch that was stopped before it finished: closes the helpers and ffmpeg, removes what was made. */
        private void abandon() {
            if (process != null) {
                process.destroyForcibly();
            }
            teardown();
            state = State.ENDED;
        }

        /** Waits briefly for a launch under way to notice that it was cancelled; the JVM is exiting. */
        void awaitLaunch() {
            try {
                launchDone.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private List<String> startAudio() {
            List<String> names = new ArrayList<>();
            if (!options.audio) {
                return names;
            }
            boolean[] wanted = {options.audioGame, options.audioRest, options.audioMic};
            AudioCapture.Kind[] kinds = AudioCapture.Kind.values();
            AudioCapture.Feed[] results = new AudioCapture.Feed[kinds.length];
            String[] problems = new String[kinds.length];
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < kinds.length; i++) {
                if (!wanted[i] || !AudioCapture.supported() || AudioCapture.failed(kinds[i])) {
                    continue;
                }
                int index = i;
                Thread thread = new Thread(() -> {
                    try {
                        results[index] = AudioCapture.start(kinds[index], options.dataDir);
                    } catch (IOException e) {
                        problems[index] = e.getMessage();
                    }
                }, "k0t1k-overlay-audio-start");
                thread.setDaemon(true);
                thread.start();
                threads.add(thread);
            }
            for (Thread thread : threads) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            for (int i = 0; i < kinds.length; i++) {
                if (results[i] != null) {
                    feeds.add(results[i]);
                    names.add(AUDIO_NAMES[i]);
                } else if (problems[i] != null) {
                    notify.toast(ToastKind.ERROR, strings.get("rec.title"), strings.format("rec.audioFailed", AUDIO_NAMES[i], problems[i]));
                }
            }
            return names;
        }

        private List<String> command(List<String> audioNames) throws IOException {
            String binary = ffmpeg.path();
            if (binary == null) {
                throw new IOException(strings.get("rec.noFfmpeg"));
            }
            boolean vaapi = encoder.endsWith("_vaapi");
            List<String> c = new ArrayList<>(Arrays.asList(binary, "-hide_banner", "-loglevel", "error", "-nostats", "-y"));
            if (vaapi) {
                c.addAll(Arrays.asList("-vaapi_device", "/dev/dri/renderD128"));
            }
            c.addAll(Arrays.asList("-thread_queue_size", "8", "-use_wallclock_as_timestamps", "1", "-f", "rawvideo",
                    "-pix_fmt", "nv12", "-video_size", width + "x" + height, "-framerate", String.valueOf(fps), "-i", "-"));
            for (AudioCapture.Feed feed : feeds) {
                c.addAll(Arrays.asList("-thread_queue_size", "512", "-f", "s16le", "-ar", "48000", "-ac", "2", "-i", feed.input()));
            }
            if (!feeds.isEmpty()) {
                c.addAll(audioMaps());
                for (int i = 0; i < audioTracks(); i++) {
                    String title = audioTracks() == 1 || i > 0 ? audioNames.get(audioTracks() == 1 ? 0 : i - 1) : "Mix";
                    c.addAll(Arrays.asList("-metadata:s:a:" + i, "title=" + title));
                }
            }
            // The raw input carries no colour tags; setparams (first, before any upload) makes the encoder write them.
            c.addAll(Arrays.asList("-vf", COLOR_TAGS + (vaapi ? ",format=nv12,hwupload" : "")));
            c.addAll(Arrays.asList("-fps_mode", "cfr", "-r", String.valueOf(fps), "-g", String.valueOf(fps * 2)));
            c.addAll(Ffmpeg.encoderArgs(encoder, options.quality, options.bitrate));
            if (!feeds.isEmpty()) {
                c.addAll(Arrays.asList("-c:a", "aac", "-b:a", "160k"));
            }
            if (isBuffer) {
                Path folder = replay.begin(Ffmpeg.hevc(encoder));
                c.addAll(Arrays.asList("-force_key_frames", "expr:gte(t,n_forced*" + Replay.SEGMENT_SECONDS + ")",
                        "-f", "segment", "-segment_time", String.valueOf(Replay.SEGMENT_SECONDS),
                        "-segment_format", "matroska", "-reset_timestamps", "1", Replay.pattern(folder)));
            } else {
                Path folder = options.recordingsFolder();
                Files.createDirectories(folder);
                file = Replay.reserve(folder, LocalDateTime.now().format(Replay.NAME), Ffmpeg.extension(options.container));
                c.addAll(Ffmpeg.containerArgs(options.container, encoder));
                c.add(file.toString());
            }
            return c;
        }

        /** One track when there is one source, else the mix followed by a track for each source. */
        private int audioTracks() {
            return feeds.size() == 1 ? 1 : feeds.size() + 1;
        }

        private List<String> audioMaps() {
            int[] volumes = {options.volumeGame, options.volumeRest, options.volumeMic};
            StringBuilder graph = new StringBuilder();
            List<String> maps = new ArrayList<>(Arrays.asList("-map", "0:v"));
            if (feeds.size() == 1) {
                graph.append("[1:a]volume=").append(volume(volumes[feeds.get(0).kind.ordinal()])).append("[mix]");
                maps.addAll(Arrays.asList("-map", "[mix]"));
            } else {
                StringBuilder mixInputs = new StringBuilder();
                for (int i = 0; i < feeds.size(); i++) {
                    graph.append('[').append(i + 1).append(":a]volume=").append(volume(volumes[feeds.get(i).kind.ordinal()]))
                            .append(",asplit=2[m").append(i).append("][t").append(i).append("];");
                    mixInputs.append("[m").append(i).append(']');
                }
                graph.append(mixInputs).append("amix=inputs=").append(feeds.size()).append(":normalize=0[mix]");
                maps.addAll(Arrays.asList("-map", "[mix]"));
                for (int i = 0; i < feeds.size(); i++) {
                    maps.addAll(Arrays.asList("-map", "[t" + i + "]"));
                }
            }
            List<String> out = new ArrayList<>(Arrays.asList("-filter_complex", graph.toString()));
            out.addAll(maps);
            return out;
        }

        private String volume(int percent) {
            return String.format(Locale.ROOT, "%.2f", percent / 100f);
        }

        private void readErrors(Process started) {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(started.getErrorStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) {
                    if (!line.trim().isEmpty()) {
                        synchronized (lines) {
                            lines.addLast(line.trim());
                            if (lines.size() > 8) {
                                lines.removeFirst();
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // The process is gone.
            }
        }

        private String lastError() {
            try {
                if (errors != null) {
                    errors.join(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (lines) {
                return lines.isEmpty() ? "" : lines.getLast();
            }
        }

        /**
         * Whether this frame is wanted: the session runs, the size matches and the session's own frame
         * rate is due. Called on the dispatching writer thread only, which owns {@link #nextFrame}.
         */
        boolean takes(Frame frame, long now) {
            if (state != State.RUNNING || frame.width != width || frame.height != height) {
                return false;
            }
            long interval = 1_000_000_000L / fps;
            // A quarter interval of slack, so a frame that arrives a little early is not skipped for a late one.
            if (now < nextFrame - interval / 4) {
                return false;
            }
            nextFrame = Math.max(nextFrame + interval, now - interval / 4);
            return true;
        }

        /** Queues a frame for the session's thread; a frame it cannot take is dropped and returned. */
        void offer(Frame frame) {
            if (state != State.RUNNING || !pending.offer(frame)) {
                dropped++;
                frame.release();
                return;
            }
            if (state != State.RUNNING) {
                drainPending(); // stopped meanwhile: do not keep the buffer
            }
        }

        private void drainPending() {
            for (Frame frame; (frame = pending.poll()) != null; ) {
                frame.release();
            }
        }

        /**
         * The session's own thread: writes frames to ffmpeg. A broken pipe means ffmpeg died. Once a stop
         * clears {@link #feeding}, the frame still queued is written too before the thread ends, so the
         * video does not lose its last frame.
         */
        private void feed() {
            try {
                for (;;) {
                    Frame frame;
                    if (feeding) {
                        try {
                            frame = pending.take();
                        } catch (InterruptedException e) {
                            continue; // woken by a stop: write what is left
                        }
                    } else {
                        frame = pending.poll();
                        if (frame == null) {
                            return;
                        }
                    }
                    try {
                        if (!aligned) {
                            // The first frame is the start of the video; the audio helpers cut what came before.
                            aligned = true;
                            long start = System.nanoTime();
                            for (AudioCapture.Feed feed : feeds) {
                                feed.alignAt(start);
                            }
                        }
                        stdin.write(frame.ready, 0, frame.width * frame.height * 3 / 2);
                        stdin.flush();
                    } catch (IOException e) {
                        if (feeding) {
                            synchronized (lock) {
                                if (state == State.RUNNING) {
                                    state = State.STOPPING;
                                }
                            }
                            io.execute(() -> fail(lastError()));
                        }
                        return;
                    } finally {
                        frame.release();
                    }
                }
            } finally {
                drainPending();
            }
        }

        /** Ends the session after an error: stops the process, tells the player why. */
        void fail(String reason) {
            synchronized (lock) {
                if (failedAlready) {
                    return;
                }
                failedAlready = true;
                state = State.STOPPING;
            }
            teardown();
            if (isBuffer) {
                bufferBlockedBy = reason.isEmpty() ? "?" : reason;
            }
            notify.toast(ToastKind.ERROR, strings.get("rec.failed"), reason.isEmpty() ? strings.get("rec.unknown") : reason);
            state = State.ENDED;
        }

        /** Closes the audio and the video, waits for ffmpeg to write the file, and reports it. */
        void finish(boolean report) {
            synchronized (lock) {
                if (state == State.ENDED || state == State.STOPPING) {
                    return;
                }
                if (state != State.RUNNING) {
                    // Never got going: a launch under way sees this, undoes itself and ends the session.
                    cancelled = true;
                    if (state == State.WAITING) {
                        state = State.ENDED;
                        launchDone.countDown();
                    } else {
                        state = State.STOPPING;
                    }
                    return;
                }
                state = State.STOPPING;
            }
            boolean ok = teardown();
            String reason = lastError();
            try {
                if (!isBuffer && report) {
                    publish(ok, reason);
                }
            } finally {
                // Only now: until the file is complete (remux included) the session is still being tracked.
                state = State.ENDED;
            }
        }

        /** Reports a finished recording and remuxes it to MP4 when asked to. */
        private void publish(boolean ok, String reason) {
            if (!ok && (file == null || !Files.exists(file))) {
                notify.toast(ToastKind.ERROR, strings.get("rec.failed"), reason.isEmpty() ? strings.get("rec.unknown") : reason);
                return;
            }
            Path result = file;
            busy = true;
            try {
                if (options.remux && "mkv".equals(options.container)) {
                    notify.toast(ToastKind.RECORDING, strings.get("rec.title"), strings.get("rec.remuxing"));
                    String name = file.getFileName().toString();
                    Path mp4 = Replay.reserve(file.getParent(), name.substring(0, name.length() - ".mkv".length()), "mp4");
                    try {
                        Ffmpeg.run(Arrays.asList(ffmpeg.path(), "-hide_banner", "-loglevel", "error", "-y", "-i", file.toString(),
                                "-map", "0", "-c", "copy", "-movflags", "+faststart", mp4.toString()));
                    } catch (IOException e) {
                        Files.deleteIfExists(mp4);
                        throw e;
                    }
                    Files.delete(file);
                    result = mp4;
                }
                lastClip = result;
                notify.toast(ToastKind.RECORDING, strings.get("rec.saved"), result.getFileName() + "\n" + result.getParent());
            } catch (IOException e) {
                notify.toast(ToastKind.ERROR, strings.get("rec.failed"), e.getMessage());
                lastClip = file;
                onError.accept(e);
            } finally {
                busy = false;
            }
        }

        /**
         * Stops audio first (ffmpeg ends when all its inputs do), then video. True when ffmpeg exited cleanly.
         * Nothing here waits without a limit: a frame write that does not return (ffmpeg stopped reading)
         * is ended by killing the process, which breaks the pipe.
         */
        private boolean teardown() {
            for (AudioCapture.Feed feed : feeds) {
                feed.close();
            }
            Process started;
            Thread writing;
            OutputStream pipe;
            synchronized (lock) {
                started = process;
                writing = feeder;
                pipe = stdin;
            }
            feeding = false;
            boolean stuck = false;
            if (writing != null) {
                writing.interrupt();
                stuck = !join(writing, STUCK_WRITE_SECONDS);
                if (stuck && started != null) {
                    started.destroyForcibly();
                    join(writing, STUCK_WRITE_SECONDS);
                }
            }
            drainPending();
            boolean ok = false;
            if (started != null) {
                try {
                    // Closing the pipe while the writer was still inside it would block on its monitor.
                    if (pipe != null && (writing == null || !writing.isAlive())) {
                        pipe.close();
                    }
                } catch (IOException e) {
                    // ffmpeg is already gone.
                }
                try {
                    if (started.waitFor(FINISH_SECONDS, TimeUnit.SECONDS)) {
                        ok = !stuck && started.exitValue() == 0;
                    } else {
                        started.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    started.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
            if (isBuffer) {
                replay.end();
            } else if (file != null) {
                try {
                    if (Files.size(file) == 0) {
                        Files.delete(file); // the reserved name of a recording that never wrote anything
                    }
                } catch (IOException e) {
                    // Not there, or still in use: leave it.
                }
            }
            return ok;
        }

        private boolean join(Thread thread, int seconds) {
            try {
                thread.join(seconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return !thread.isAlive();
        }
    }

    // ---- the red dot ----

    /** Where the recording indicator is, {x, y, width, height}, or null when nothing is recording. */
    float[] indicatorBox(Ui ui, float w) {
        Session session = recording;
        if (session == null || session.state != State.RUNNING) {
            return null;
        }
        long seconds = recordingMillis() / 1000;
        String widest = seconds >= 3600 ? "0:00:00" : "00:00";
        // Top right, unless a shown HUD sits in the top right half: then top left.
        boolean left = options.hud && options.hudX >= 0.5f && options.hudY < 0.5f;
        float bw = ui.measure(ui.bold, 13, widest) + 38;
        return new float[] {left ? 12 : w - bw - 12, 12, bw, 26};
    }

    /** The recording indicator, drawn after the frame was captured so it is not in the video. */
    void drawIndicator(Ui ui, float[] box) {
        long seconds = recordingMillis() / 1000;
        String time = seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
        float x = box[0];
        float bw = box[2];
        ui.round(x, 12, bw, 26, 13, Theme.alpha(Ui.SURFACE, 0xcc));
        if (System.currentTimeMillis() / 600 % 2 == 0) {
            ui.round(x + 10, 21, 8, 8, 4, 0xffed4245);
        }
        ui.text(ui.bold, 13, x + 26, 12 + (26 - ui.lineHeight(ui.bold, 13)) / 2, Ui.FG, time);
    }
}
