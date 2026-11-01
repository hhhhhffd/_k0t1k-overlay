package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * What the browser remembers apart from Chromium's own profile: the bookmarks and the tabs that were
 * open. Both are plain UTF-8 files of {@code title<TAB>url} lines next to the profile; the render thread
 * owns the lists and the disk work runs on {@code io} from a snapshot.
 */
final class BrowserData {
    static final class Entry {
        String title;
        String url;

        Entry(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    private static final String[][] DEFAULTS = {
        {"YouTube", "https://www.youtube.com/"},
        {"YouTube Music", "https://music.youtube.com/"},
        {"Google", "https://www.google.com/"},
    };

    final List<Entry> bookmarks = new ArrayList<>();
    /** The tabs of the last game, in order; empty on the first start. */
    final List<Entry> tabs = new ArrayList<>();
    int activeTab;

    private final Path bookmarksFile;
    private final Path tabsFile;
    private final Executor io;

    BrowserData(Path dataDir, Executor io, Consumer<Throwable> onError) {
        Path dir = dataDir.resolve("browser");
        this.bookmarksFile = dir.resolve("bookmarks.tsv");
        this.tabsFile = dir.resolve("tabs.tsv");
        this.io = io;
        try {
            if (Files.exists(bookmarksFile)) {
                bookmarks.addAll(read(bookmarksFile));
            } else {
                for (String[] entry : DEFAULTS) {
                    bookmarks.add(new Entry(entry[0], entry[1]));
                }
            }
            if (Files.exists(tabsFile)) {
                List<Entry> lines = read(tabsFile);
                // The first line holds the active tab's index in its url column.
                if (!lines.isEmpty()) {
                    try {
                        activeTab = Integer.parseInt(lines.remove(0).url);
                    } catch (NumberFormatException e) {
                        activeTab = 0;
                    }
                    tabs.addAll(lines);
                    activeTab = Math.max(0, Math.min(activeTab, tabs.size() - 1));
                }
            }
        } catch (IOException e) {
            onError.accept(e);
        }
    }

    int indexOf(String url) {
        for (int i = 0; i < bookmarks.size(); i++) {
            if (bookmarks.get(i).url.equals(url)) {
                return i;
            }
        }
        return -1;
    }

    void saveBookmarks() {
        write(bookmarksFile, snapshot(bookmarks, null));
    }

    void saveTabs(List<Entry> open, int active) {
        write(tabsFile, snapshot(open, Integer.toString(active)));
    }

    private static List<String> snapshot(List<Entry> entries, String header) {
        List<String> lines = new ArrayList<>();
        if (header != null) {
            lines.add("active\t" + header);
        }
        for (Entry e : entries) {
            lines.add(clean(e.title) + "\t" + clean(e.url));
        }
        return lines;
    }

    private static String clean(String text) {
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private void write(Path file, List<String> lines) {
        io.execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                Path part = file.resolveSibling(file.getFileName() + ".part");
                Files.write(part, lines, StandardCharsets.UTF_8);
                Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("could not save " + file, e);
            }
        });
    }

    private static List<Entry> read(Path file) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int tab = line.indexOf('\t');
            if (tab >= 0) {
                entries.add(new Entry(line.substring(0, tab), line.substring(tab + 1)));
            }
        }
        return entries;
    }
}
