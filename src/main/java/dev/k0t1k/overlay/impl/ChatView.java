package dev.k0t1k.overlay.impl;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The chat tab: the whole stored history, newest at the bottom, with a full-text search and filters
 * for mentions, private messages and one player's messages.
 */
final class ChatView {
    private static final float TEXT = 14;
    private static final float TIME_COLUMN = 46;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final ChatHistory history;
    private final Strings strings;
    private final Overlay overlay;
    private final ZoneId zone = ZoneId.systemDefault();

    private final TextEdit search = new TextEdit();
    private String typedQuery = "";
    private long typedAt;
    private String activeQuery = "";
    private boolean searching;
    private List<ChatHistory.Line> results;
    private long activeMatchStart = -1;
    private long pendingMatchStart = -1;
    private ChatHistory.Line pendingMatchLine;

    /** 0 everything, 1 mentions of the player, 2 private messages. */
    private int filter;
    private final TextEdit player = new TextEdit();
    private static final String[] FILTERS = {"chat.all", "chat.mentions", "chat.private"};
    /** Markers of private messages in vanilla and on common servers (EssentialsX, CMI, Hypixel...). */
    private static final String[] PRIVATE = {
        "whispers to you", "you whisper to", "шепчет вам", "вы шепчете", "-> me]", "-> you]", "-> я]",
        "[pm]", "[msg]", "(msg)", "[лс]", "from ", "to ",
    };
    private List<ChatHistory.Line> filtered;
    private List<ChatHistory.Line> filteredBase;
    private int filteredVersion = -1;
    private int filteredSize = -1;
    private String filteredKey = "";
    private List<ChatHistory.Line> searchFiltered;
    private List<ChatHistory.Line> searchFilteredBase;
    private int searchFilteredSize = -1;
    private String searchFilteredKey = "";

    /** Distance of the view's bottom from the bottom of the content; 0 follows new messages. */
    private float scroll;
    private boolean draggingBar;
    private float dragOffset;

    private static final class Row {
        ChatHistory.Line line;
        String header;
        String time;
        List<int[]> wrapped;
        float y;
        float h;
    }

    private final List<Row> rows = new ArrayList<>();
    private float total;
    private List<ChatHistory.Line> laidOut;
    private int laidOutVersion = -1;
    private float laidOutWidth = -1;
    private float laidOutScale = -1;
    private int laidOutCount;
    private int laidOutPrepends = -1;
    private String lastServer;
    private LocalDate lastDay;
    private static final List<ChatHistory.Line> NONE = new ArrayList<>();

    ChatView(ChatHistory history, Strings strings, Overlay overlay) {
        this.history = history;
        this.strings = strings;
        this.overlay = overlay;
    }

    void draw(Ui ui, float x, float y, float w, float h) {
        updateSearch();

        float barH = 34;
        float toolbarH = searching || results != null ? barH * 2 : barH;
        float searchW = Math.min(300, w * 0.3f);
        ui.field(search, x, y, searchW, barH, false, strings.get("chat.search"));
        float playerW = Math.min(170, w * 0.17f);
        ui.field(player, x + searchW + 8, y, playerW, barH, false, strings.get("chat.player"));
        float fx = x + searchW + playerW + 20;
        for (int i = 0; i < FILTERS.length; i++) {
            String label = strings.get(FILTERS[i]);
            float pw = ui.measure(ui.bold, 13, label) + 24;
            if (ui.pill(fx, y + 2, pw, barH - 4, label, filter == i)) {
                filter = i;
                scroll = 0;
            }
            fx += pw + 4;
        }
        List<ChatHistory.Line> matches = visibleMatches();
        if (results != null) {
            if (matches.isEmpty()) {
                activeMatchStart = -1;
                pendingMatchStart = -1;
                pendingMatchLine = null;
                history.retainingForSearch = false;
            } else if (!containsStart(matches, activeMatchStart)) {
                selectMatch(matches.get(matches.size() - 1));
            }
        }
        if (searching || results != null) {
            float controlsX = x + 12;
            if (!matches.isEmpty()) {
                String previous = strings.get("chat.previousMatch");
                float previousW = ui.measure(ui.bold, 13, previous) + 20;
                if (ui.pill(controlsX, y + barH + 2, previousW, barH - 4, previous, false)) {
                    navigateMatch(-1, matches);
                }
                controlsX += previousW + 4;
                String next = strings.get("chat.nextMatch");
                float nextW = ui.measure(ui.bold, 13, next) + 20;
                if (ui.pill(controlsX, y + barH + 2, nextW, barH - 4, next, false)) {
                    navigateMatch(1, matches);
                }
                controlsX += nextW + 8;
            }
            String status = searching ? strings.get("chat.searching") : strings.format("chat.found", matches.size());
            ui.textFit(ui.regular, 12, controlsX, y + barH + (barH - ui.lineHeight(ui.regular, 12)) / 2,
                    x + w - controlsX - 12, Ui.FAINT, status);
        } else {
            String status = strings.format("chat.count", history.lines.size()) + " · " + strings.get("chat.copyHint");
            ui.textFit(ui.regular, 12, fx + 10, y + (barH - ui.lineHeight(ui.regular, 12)) / 2,
                    x + w - fx - 10, Ui.FAINT, status);
        }

        float listY = y + toolbarH + 12;
        float listH = h - toolbarH - 12;
        ui.panel(x, listY, w, listH, 10, Ui.BASE, Ui.LINE);
        float padX = 14;
        float innerX = x + padX;
        float innerW = w - padX * 2 - 10;
        float innerY = listY + 6;
        float innerH = listH - 12;

        layout(ui, innerW);
        if (pendingMatchStart >= 0) {
            jumpToPendingMatch(innerH);
        }

        // Scrolling: the wheel moves three lines at a time.
        float lh = ui.lineHeight(ui.regular, TEXT);
        scroll += ui.takeScroll(x, listY, w, listH) * lh * 3;
        float maxScroll = Math.max(0, total - innerH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        history.readingOlder = scroll > 0 || pendingMatchStart >= 0;
        history.retainingForSearch = pendingMatchStart >= 0 || activeMatchStart >= 0 && scroll > 0;

        // Content shorter than the view sits at the top; otherwise the bottom follows scroll.
        float viewTop = total <= innerH ? 0 : total - innerH - scroll;
        if (viewTop < innerH && history.hasOlder) {
            history.loadOlder();
        }

        boolean inList = ui.hover(x, innerY, w - 12, innerH);
        ui.clip(x + 1, innerY, w - 2, innerH);
        int first = firstVisible(viewTop);
        for (int i = first; i < rows.size(); i++) {
            Row row = rows.get(i);
            float ry = innerY + row.y - viewTop;
            if (ry > innerY + innerH) {
                break;
            }
            drawRow(ui, row, innerX, ry, innerW, lh, inList);
        }
        ui.unclip();

        drawScrollbar(ui, x + w - 10, innerY, innerH, viewTop, maxScroll);

        if (scroll > lh * 3) {
            String label = strings.get("chat.toBottom");
            float bw = ui.measure(ui.bold, 13, label) + 24;
            if (ui.pill(x + w - bw - 22, listY + listH - 40, bw, 28, label, true)) {
                scroll = 0;
            }
        }
    }

    private void drawRow(Ui ui, Row row, float x, float y, float w, float lh, boolean inList) {
        if (row.header != null) {
            float th = ui.lineHeight(ui.bold, 12);
            float tw = ui.measure(ui.bold, 12, row.header);
            float cy = y + row.h / 2;
            ui.rect(x, cy, (w - tw) / 2 - 10, 1, Ui.LINE);
            ui.rect(x + (w + tw) / 2 + 10, cy, (w - tw) / 2 - 10, 1, Ui.LINE);
            ui.text(ui.bold, 12, x + (w - tw) / 2, cy - th / 2, row.line == null ? Ui.FAINT : Ui.MUTED, row.header);
            return;
        }
        boolean hot = inList && ui.hover(x - 6, y, w + 12, row.h);
        boolean activeMatch = row.line.start >= 0 && row.line.start == activeMatchStart;
        if (activeMatch) {
            ui.round(x - 6, y, w + 12, row.h, 5, Ui.ACCENT_SOFT);
        } else if (hot) {
            ui.round(x - 6, y, w + 12, row.h, 5, Ui.RAISED);
        }
        ui.text(ui.regular, 12, x, y + (lh - ui.lineHeight(ui.regular, 12)) / 2 + 1, Ui.FAINT, row.time);
        String text = row.line.text;
        for (int i = 0; i < row.wrapped.size(); i++) {
            int[] span = row.wrapped.get(i);
            ui.text(ui.regular, TEXT, x + TIME_COLUMN, y + 2 + i * lh, Ui.FG, text, span[0], span[1]);
        }
        if (hot && ui.clickedIn(x - 6, y, w + 12, row.h)) {
            ui.platform.setClipboard(text);
            overlay.toast(ToastKind.OTHER, strings.get("chat.copied"), text);
        }
    }

    private void drawScrollbar(Ui ui, float x, float y, float h, float viewTop, float maxScroll) {
        if (maxScroll <= 0) {
            draggingBar = false;
            return;
        }
        float thumbH = Math.max(28, h * h / total);
        float travel = h - thumbH;
        float thumbY = y + travel * (viewTop / maxScroll);
        boolean hot = ui.hover(x - 4, thumbY, 12, thumbH);
        if (hot && ui.clicked) {
            ui.clickedIn(x - 4, thumbY, 12, thumbH);
            draggingBar = true;
            dragOffset = ui.mouseY - thumbY;
        }
        if (draggingBar) {
            if (!ui.mouseDown) {
                draggingBar = false;
            } else {
                float t = Math.max(0, Math.min(1, (ui.mouseY - dragOffset - y) / travel));
                scroll = maxScroll - t * maxScroll;
            }
        }
        ui.round(x, thumbY, 5, thumbH, 2.5f, draggingBar || hot ? Ui.THUMB_HOT : Ui.THUMB);
    }

    private int firstVisible(float viewTop) {
        int lo = 0;
        int hi = rows.size() - 1;
        int found = rows.size();
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Row row = rows.get(mid);
            if (row.y + row.h >= viewTop) {
                found = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return found;
    }

    private void updateSearch() {
        String typed = search.value().trim();
        long now = System.currentTimeMillis();
        if (!typed.equals(typedQuery)) {
            typedQuery = typed;
            typedAt = now;
        }
        if (typedQuery.equals(activeQuery) || now - typedAt < 250) {
            return;
        }
        activeQuery = typedQuery;
        scroll = 0;
        if (activeQuery.isEmpty()) {
            history.cancelSearch();
            searching = false;
            results = null;
            activeMatchStart = -1;
            pendingMatchStart = -1;
            pendingMatchLine = null;
            history.retainingForSearch = false;
            return;
        }
        results = null;
        activeMatchStart = -1;
        pendingMatchStart = -1;
        pendingMatchLine = null;
        history.retainingForSearch = false;
        searching = true;
        history.search(activeQuery, found -> {
            searching = false;
            results = found;
            List<ChatHistory.Line> matches = visibleMatches();
            if (matches.isEmpty()) {
                activeMatchStart = -1;
            } else {
                selectMatch(matches.get(matches.size() - 1));
            }
        });
    }

    private List<ChatHistory.Line> visibleMatches() {
        if (results == null) {
            return NONE;
        }
        String name = player.value().trim().toLowerCase(Locale.ROOT);
        if (filter == 0 && name.isEmpty()) {
            return results;
        }
        String key = filter + "\n" + name;
        if (results == searchFilteredBase && results.size() == searchFilteredSize && key.equals(searchFilteredKey)) {
            return searchFiltered;
        }
        List<ChatHistory.Line> out = new ArrayList<>();
        String me = overlay.username();
        for (ChatHistory.Line line : results) {
            if (passesFilters(line, name, me)) {
                out.add(line);
            }
        }
        searchFiltered = out;
        searchFilteredBase = results;
        searchFilteredSize = results.size();
        searchFilteredKey = key;
        return out;
    }

    private static boolean containsStart(List<ChatHistory.Line> lines, long start) {
        for (ChatHistory.Line line : lines) {
            if (line.start == start) {
                return true;
            }
        }
        return false;
    }

    private void navigateMatch(int direction, List<ChatHistory.Line> matches) {
        int current = -1;
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i).start == activeMatchStart) {
                current = i;
                break;
            }
        }
        int next = current < 0 ? (direction < 0 ? matches.size() - 1 : 0)
                : Math.floorMod(current + direction, matches.size());
        selectMatch(matches.get(next));
    }

    private void selectMatch(ChatHistory.Line line) {
        activeMatchStart = line.start;
        pendingMatchStart = line.start;
        pendingMatchLine = line;
        history.retainingForSearch = true;
    }

    private void jumpToPendingMatch(float innerH) {
        for (Row row : rows) {
            if (row.header == null && row.line.start == pendingMatchStart) {
                float viewTop = Math.max(0, row.y + row.h / 2 - innerH / 2);
                scroll = Math.max(0, total - innerH - viewTop);
                pendingMatchStart = -1;
                pendingMatchLine = null;
                return;
            }
        }
        if (!history.lines.isEmpty() && pendingMatchStart < history.lines.get(0).start && history.hasOlder) {
            history.readingOlder = true;
            history.loadOlder();
        } else if (pendingMatchLine != null) {
            history.loadMatch(pendingMatchLine);
        } else {
            pendingMatchStart = -1;
        }
    }

    /**
     * Rebuilds the rows when the messages, the width or the UI scale changed. New messages at the end
     * are laid out on their own, so a long history costs nothing per message; pages of older messages
     * rebuild everything, which keeps the distance from the bottom and so the reading position.
     */
    private void layout(Ui ui, float width) {
        List<ChatHistory.Line> source = history.lines;
        int version = history.version;
        source = filter(source, version);
        // The header above the first message ("no messages yet") changes with it, so start over then.
        boolean sameShape = source == laidOut && width == laidOutWidth && ui.scale == laidOutScale && laidOutCount > 0;
        if (sameShape && version == laidOutVersion) {
            return;
        }
        if (sameShape && source == history.lines && history.prepends == laidOutPrepends
                && source.size() >= laidOutCount) {
            float before = total;
            appendRows(ui, source, laidOutCount, width);
            if (scroll > 0) {
                // Scrolled up to read: stay on the same messages while new ones arrive below.
                scroll += total - before;
            }
        } else {
            rows.clear();
            total = 0;
            lastServer = null;
            lastDay = null;
            String top = history.hasOlder ? strings.get("chat.older")
                    : history.lines.isEmpty() ? strings.get("chat.empty") : strings.get("chat.start");
            if (top != null) {
                addHeader(top, null);
            }
            appendRows(ui, source, 0, width);
        }
        laidOut = source;
        laidOutVersion = version;
        laidOutWidth = width;
        laidOutScale = ui.scale;
        laidOutCount = source.size();
        laidOutPrepends = history.prepends;
    }

    private void appendRows(Ui ui, List<ChatHistory.Line> source, int from, float width) {
        float lh = ui.lineHeight(ui.regular, TEXT);
        for (int i = from; i < source.size(); i++) {
            ChatHistory.Line line = source.get(i);
            LocalDate day = Instant.ofEpochMilli(line.time).atZone(zone).toLocalDate();
            if (!line.server.equals(lastServer) || !day.equals(lastDay)) {
                lastServer = line.server;
                lastDay = day;
                addHeader(serverLabel(line.server) + "  ·  " + DATE.format(day), line);
            }
            Row row = new Row();
            row.line = line;
            row.time = TIME.format(Instant.ofEpochMilli(line.time).atZone(zone));
            row.wrapped = ui.wrap(ui.regular, TEXT, line.text, width - TIME_COLUMN);
            row.y = total;
            row.h = row.wrapped.size() * lh + 4;
            rows.add(row);
            total += row.h;
        }
    }

    /**
     * The lines that pass the filters, as a list that stays the same object until the lines or the
     * filters change, so the layout is only rebuilt then.
     */
    private List<ChatHistory.Line> filter(List<ChatHistory.Line> base, int version) {
        String name = player.value().trim().toLowerCase(Locale.ROOT);
        if (filter == 0 && name.isEmpty()) {
            return base;
        }
        String key = filter + "\n" + name;
        if (base == filteredBase && version == filteredVersion && base.size() == filteredSize && key.equals(filteredKey)) {
            return filtered;
        }
        List<ChatHistory.Line> out = new ArrayList<>();
        String me = overlay.username();
        for (ChatHistory.Line line : base) {
            if (passesFilters(line, name, me)) {
                out.add(line);
            }
        }
        filtered = out;
        filteredBase = base;
        filteredVersion = version;
        filteredSize = base.size();
        filteredKey = key;
        return out;
    }

    private boolean passesFilters(ChatHistory.Line line, String name, String me) {
        return (filter != 1 || Overlay.mentions(line.text, me))
                && (filter != 2 || isPrivate(line.text))
                && (name.isEmpty() || fromPlayer(line.text, name));
    }

    static boolean isPrivate(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : PRIVATE) {
            if (marker.endsWith(" ") ? lower.startsWith(marker) : lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a line was written by the player: "&lt;Name&gt; text" in vanilla, "[Rank] Name: text" or
     * "Name » text" on most servers. Looks only at the part before the message itself.
     */
    static boolean fromPlayer(String text, String lowerName) {
        String lower = text.toLowerCase(Locale.ROOT);
        int end = lower.indexOf("> ");
        for (String separator : new String[] {": ", " » ", " > ", " -> "}) {
            int at = lower.indexOf(separator);
            if (at >= 0 && (end < 0 || at < end)) {
                end = at;
            }
        }
        // Without a sender part the line is a system message (deaths, joins), not something the player wrote.
        return end >= 0 && lower.substring(0, end + 1).contains(lowerName);
    }

    private void addHeader(String text, ChatHistory.Line line) {
        Row row = new Row();
        row.header = text;
        row.line = line;
        row.y = total;
        row.h = 30;
        rows.add(row);
        total += row.h;
    }

    private String serverLabel(String server) {
        if (server.isEmpty()) {
            return strings.get("chat.unknownServer");
        }
        return ChatHistory.SINGLEPLAYER.equals(server) ? strings.get("chat.singleplayer") : server;
    }
}
