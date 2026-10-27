package dev.k0t1k.overlay.impl;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** The chat tab: the whole stored history, newest at the bottom, with a full-text search. */
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
        ui.field(search, x, y, Math.min(360, w - 160), barH, false, strings.get("chat.search"));
        String status = results != null || searching
                ? (searching ? strings.get("chat.searching") : strings.format("chat.found", results.size()))
                : strings.format("chat.count", history.lines.size()) + " · " + strings.get("chat.copyHint");
        ui.textFit(ui.regular, 12, x + Math.min(360, w - 160) + 14, y + (barH - ui.lineHeight(ui.regular, 12)) / 2,
                w - Math.min(360, w - 160) - 14, Ui.FAINT, status);

        float listY = y + barH + 12;
        float listH = h - barH - 12;
        ui.panel(x, listY, w, listH, 10, Ui.BASE, Ui.LINE);
        float padX = 14;
        float innerX = x + padX;
        float innerW = w - padX * 2 - 10;
        float innerY = listY + 6;
        float innerH = listH - 12;

        layout(ui, innerW);

        // Scrolling: the wheel moves three lines at a time.
        float lh = ui.lineHeight(ui.regular, TEXT);
        scroll += ui.takeScroll(x, listY, w, listH) * lh * 3;
        float maxScroll = Math.max(0, total - innerH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        // Content shorter than the view sits at the top; otherwise the bottom follows scroll.
        float viewTop = total <= innerH ? 0 : total - innerH - scroll;
        if (results == null && !searching && viewTop < innerH && history.hasOlder) {
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
        if (hot) {
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
            overlay.toast(strings.get("chat.copied"), text);
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
        ui.round(x, thumbY, 5, thumbH, 2.5f, draggingBar || hot ? 0xff4a3f6e : 0xff332b4d);
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
            return;
        }
        searching = true;
        history.search(activeQuery, found -> {
            searching = false;
            results = found;
        });
    }

    /**
     * Rebuilds the rows when the messages, the width or the UI scale changed. New messages at the end
     * are laid out on their own, so a long history costs nothing per message; pages of older messages
     * rebuild everything, which keeps the distance from the bottom and so the reading position.
     */
    private void layout(Ui ui, float width) {
        List<ChatHistory.Line> source = results != null ? results : searching ? NONE : history.lines;
        int version = results != null ? 0 : history.version;
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
            String top = searching ? null
                    : results != null ? (results.isEmpty() ? strings.get("chat.notFound") : strings.format("chat.found", results.size()))
                    : history.hasOlder ? strings.get("chat.older")
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
