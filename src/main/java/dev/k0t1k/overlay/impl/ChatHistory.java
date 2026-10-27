package dev.k0t1k.overlay.impl;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Chat history without a mod: the game logs every chat line to {@code logs/latest.log} as
 * {@code ... [CHAT] text}, so the overlay tails that file and appends each new line to its own store,
 * which keeps everything ever seen across sessions and instances.
 *
 * <p>The store is a text file, one {@code time TAB server TAB text} line per message (backslash
 * escapes tab, newline and itself). Only the newest messages are kept in memory; older ones are read
 * back from the end of the file on demand and searches stream over the whole file.
 *
 * <p>File work runs on {@link #io}; results come back to the render thread through {@link #render}.
 */
final class ChatHistory {
    static final String SINGLEPLAYER = "@singleplayer";
    private static final int PAGE = 2000;
    private static final int SEARCH_LIMIT = 2000;

    static final class Line {
        final long time;
        final String server;
        final String text;

        Line(long time, String server, String text) {
            this.time = time;
            this.server = server;
            this.text = text;
        }
    }

    private final Path log;
    private final Path store;
    private final ScheduledExecutorService io;
    private final ExecutorService searcher;
    private final Executor render;
    private final Consumer<Throwable> onError;

    // ---- render thread ----
    /** Loaded messages, oldest first. */
    final List<Line> lines = new ArrayList<>();
    /** Bumped whenever {@link #lines} changes, so views can tell when to lay out again. */
    int version;
    /** Bumped whenever older messages are put in front of {@link #lines}. */
    int prepends;
    boolean hasOlder;
    boolean loadingOlder;
    private boolean loaded;
    private long olderEnd;
    private volatile int searchGeneration;
    /** Called on the render thread for every new message seen in this session. */
    Consumer<Line> onNew = line -> { };

    // ---- io thread ----
    private long logPosition;
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private String server = "";

    ChatHistory(Path gameDir, Path dataDir, ScheduledExecutorService io, ExecutorService searcher, Executor render,
            Consumer<Throwable> onError) {
        this.log = gameDir.resolve("logs").resolve("latest.log");
        this.store = dataDir.resolve("chat-history.tsv");
        this.io = io;
        this.searcher = searcher;
        this.render = render;
        this.onError = onError;
    }

    void start() {
        io.execute(() -> {
            try {
                startOnIo();
            } catch (RuntimeException e) {
                onError.accept(e);
            }
        });
    }

    private void startOnIo() {
        try {
            Files.createDirectories(store.getParent());
            long length = Files.exists(store) ? Files.size(store) : 0;
            Chunk chunk = readBefore(length, PAGE);
            render.execute(() -> {
                lines.addAll(0, chunk.lines);
                prepends++;
                olderEnd = chunk.start;
                hasOlder = chunk.start > 0;
                loaded = true;
                version++;
            });
        } catch (IOException e) {
            onError.accept(e);
        }
        // The game already rotated latest.log before its window opened, so the whole file is
        // this session and nothing in it has been stored yet.
        io.scheduleWithFixedDelay(this::pollNow, 0, 250, TimeUnit.MILLISECONDS);
    }

    /** Asks for the page of messages before the oldest loaded one. */
    void loadOlder() {
        if (!loaded || !hasOlder || loadingOlder) {
            return;
        }
        loadingOlder = true;
        long end = olderEnd;
        io.execute(() -> {
            Chunk chunk = null;
            try {
                chunk = readBefore(end, PAGE);
            } catch (IOException | RuntimeException e) {
                onError.accept(e);
            } finally {
                Chunk loaded = chunk;
                render.execute(() -> {
                    loadingOlder = false;
                    if (loaded != null) {
                        lines.addAll(0, loaded.lines);
                        prepends++;
                        olderEnd = loaded.start;
                        hasOlder = loaded.start > 0;
                        version++;
                    }
                });
            }
        });
    }

    /**
     * Searches the whole store for {@code query} (case-insensitive, text or server) and hands the newest
     * {@value #SEARCH_LIMIT} matches, oldest first, to {@code done} on the render thread. A newer search
     * supersedes a running one.
     */
    void search(String query, Consumer<List<Line>> done) {
        int generation = ++searchGeneration;
        String needle = query.toLowerCase(Locale.ROOT);
        searcher.execute(() -> {
            ArrayDeque<Line> found = new ArrayDeque<>();
            try (InputStream in = Files.exists(store) ? Files.newInputStream(store) : null) {
                if (in != null) {
                    ByteArrayOutputStream current = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1 << 16];
                    int counter = 0;
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        for (int i = 0; i < n; i++) {
                            if (buffer[i] != '\n') {
                                current.write(buffer[i]);
                                continue;
                            }
                            Line line = decode(new String(current.toByteArray(), StandardCharsets.UTF_8));
                            current.reset();
                            if (line != null && (line.text.toLowerCase(Locale.ROOT).contains(needle)
                                    || line.server.toLowerCase(Locale.ROOT).contains(needle))) {
                                found.addLast(line);
                                if (found.size() > SEARCH_LIMIT) {
                                    found.removeFirst();
                                }
                            }
                        }
                        // Stop early when a newer search has started.
                        if (++counter % 16 == 0 && generation != searchGeneration) {
                            return;
                        }
                    }
                }
            } catch (IOException e) {
                onError.accept(e);
            }
            List<Line> result = new ArrayList<>(found);
            render.execute(() -> {
                if (generation == searchGeneration) {
                    done.accept(result);
                }
            });
        });
    }

    /** Cancels the result of a running search. */
    void cancelSearch() {
        searchGeneration++;
    }

    // ---- io thread ----

    /**
     * Moves new chat lines from the log into the store. Runs on the io thread, and once more from the
     * exit hook, hence synchronized. The log position only advances once the lines are stored, so a
     * failed write is retried on the next poll instead of losing them.
     */
    synchronized void pollNow() {
        try {
            LogRead read = readLog();
            List<Line> fresh = read.lines;
            if (fresh.isEmpty()) {
                commit(read);
                return;
            }
            StringBuilder out = new StringBuilder();
            for (Line line : fresh) {
                out.append(line.time).append('\t').append(escape(line.server)).append('\t')
                        .append(escape(line.text)).append('\n');
            }
            // One write per batch: other games sharing the data folder append to the same file.
            try (OutputStream file = new FileOutputStream(store.toFile(), true)) {
                file.write(out.toString().getBytes(StandardCharsets.UTF_8));
            }
            commit(read);
            render.execute(() -> {
                lines.addAll(fresh);
                version++;
                for (Line line : fresh) {
                    onNew.accept(line);
                }
            });
        } catch (IOException | RuntimeException e) {
            onError.accept(e);
        }
    }

    /** What one poll read: the lines, and the log state to keep once they are stored. */
    private static final class LogRead {
        final List<Line> lines = new ArrayList<>();
        long position;
        byte[] partial;
        String server;
    }

    private void commit(LogRead read) {
        logPosition = read.position;
        partial.reset();
        partial.write(read.partial, 0, read.partial.length);
        server = read.server;
    }

    private LogRead readLog() throws IOException {
        LogRead read = new LogRead();
        read.position = logPosition;
        read.partial = partial.toByteArray();
        read.server = server;
        if (!Files.exists(log)) {
            return read;
        }
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            long length = file.length();
            if (length < read.position) {
                // Truncated or replaced: start over.
                read.position = 0;
                read.partial = new byte[0];
            }
            if (length == read.position) {
                return read;
            }
            file.seek(read.position);
            byte[] bytes = new byte[(int) Math.min(length - read.position, 1 << 22)];
            file.readFully(bytes);
            read.position += bytes.length;
            long now = System.currentTimeMillis();
            ByteArrayOutputStream current = new ByteArrayOutputStream();
            current.write(read.partial, 0, read.partial.length);
            for (byte b : bytes) {
                if (b != '\n') {
                    current.write(b);
                    continue;
                }
                String raw = new String(current.toByteArray(), StandardCharsets.UTF_8);
                current.reset();
                Line line = parseLogLine(raw, now, read);
                if (line != null) {
                    read.lines.add(line);
                }
            }
            read.partial = current.toByteArray();
        }
        return read;
    }

    /** Turns a log line into a chat message, tracking the server on the way; null for other lines. */
    private static Line parseLogLine(String raw, long now, LogRead read) {
        if (raw.isEmpty() || raw.charAt(0) != '[') {
            return null; // stack traces and wrapped output
        }
        if (raw.endsWith("\r")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        int chat = raw.indexOf("[CHAT] ");
        if (chat >= 0) {
            // The game writes newlines inside a message as a literal backslash-n.
            String text = stripFormatting(raw.substring(chat + 7)).replace("\\n", "\n");
            return text.trim().isEmpty() ? null : new Line(now, read.server, text);
        }
        int connecting = raw.indexOf("]: Connecting to ");
        if (connecting >= 0) {
            String target = raw.substring(connecting + 17).trim();
            int comma = target.lastIndexOf(", ");
            if (comma >= 0) {
                String port = target.substring(comma + 2);
                target = target.substring(0, comma) + ("25565".equals(port) ? "" : ":" + port);
            }
            read.server = target;
        } else if (raw.contains("]: Starting integrated minecraft server")) {
            read.server = SINGLEPLAYER;
        }
        return null;
    }

    /** Drops legacy section-sign colour codes some servers still put into messages. */
    static String stripFormatting(String text) {
        if (text.indexOf('§') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static final class Chunk {
        final List<Line> lines;
        final long start;

        Chunk(List<Line> lines, long start) {
            this.lines = lines;
            this.start = start;
        }
    }

    /** Reads up to {@code max} whole lines that end at or before byte {@code end} of the store. */
    private Chunk readBefore(long end, int max) throws IOException {
        List<Line> result = new ArrayList<>();
        if (end == 0) {
            return new Chunk(result, 0);
        }
        try (RandomAccessFile file = new RandomAccessFile(store.toFile(), "r")) {
            long start = end;
            byte[] acc = new byte[0];
            int newlines = 0;
            while (start > 0 && newlines <= max) {
                int n = (int) Math.min(1 << 16, start);
                start -= n;
                byte[] block = new byte[n + acc.length];
                file.seek(start);
                file.readFully(block, 0, n);
                for (int i = 0; i < n; i++) {
                    if (block[i] == '\n') {
                        newlines++;
                    }
                }
                System.arraycopy(acc, 0, block, n, acc.length);
                acc = block;
            }
            int from = 0;
            if (start > 0) {
                // The first piece is the tail of a line that belongs to the next page.
                while (acc[from] != '\n') {
                    from++;
                }
                from++;
            }
            List<int[]> spans = new ArrayList<>();
            for (int i = from, lineStart = from; i < acc.length; i++) {
                if (acc[i] == '\n') {
                    spans.add(new int[] {lineStart, i});
                    lineStart = i + 1;
                }
            }
            int skip = Math.max(0, spans.size() - max);
            long chunkStart = skip > 0 ? start + spans.get(skip)[0] : start + from;
            for (int i = skip; i < spans.size(); i++) {
                int[] span = spans.get(i);
                Line line = decode(new String(acc, span[0], span[1] - span[0], StandardCharsets.UTF_8));
                if (line != null) {
                    result.add(line);
                }
            }
            return new Chunk(result, chunkStart);
        }
    }

    private static Line decode(String row) {
        int a = row.indexOf('\t');
        int b = a < 0 ? -1 : row.indexOf('\t', a + 1);
        if (b < 0) {
            return null;
        }
        try {
            return new Line(Long.parseLong(row.substring(0, a)), unescape(row.substring(a + 1, b)),
                    unescape(row.substring(b + 1)));
        } catch (NumberFormatException e) {
            return null; // a torn write from a crash; skip the row
        }
    }

    static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') {
                out.append("\\\\");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c != '\r') {
                out.append(c);
            }
        }
        return out.toString();
    }

    static String unescape(String value) {
        if (value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                out.append(next == 't' ? '\t' : next == 'n' ? '\n' : next);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
