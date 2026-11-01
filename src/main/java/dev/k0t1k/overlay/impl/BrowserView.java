package dev.k0t1k.overlay.impl;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * The browser tab: real Chromium pages (see {@link Browser}) in tabs, with back, forward, reload, an
 * address bar that also searches, and a bookmarks bar. Mouse and keyboard over the page go to Chromium;
 * the page's pixels are drawn as the batch's image, so this works where the renderer draws images.
 * Menus (the page's right-click menu, the bookmarks') are drawn here too, since native ones cannot show
 * off screen.
 */
final class BrowserView {
    private static final String DEFAULT_HOME = "https://www.google.com/";
    /** Search engines for the address bar: {key, name, address the query is appended to}. */
    static final String[][] ENGINES = {
        {"google", "Google", "https://www.google.com/search?q="},
        {"yandex", "Yandex", "https://yandex.com/search/?text="},
        {"duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q="},
        {"bing", "Bing", "https://www.bing.com/search?q="},
    };

    private static final float TAB_H = 32;
    private static final float TOOL_H = 34;
    private static final float MARK_H = 28;
    private static final int MAX_TABS = 30;
    private static final int[] ICON_COLORS = {
        0xffc0504f, 0xff2f80b8, 0xff2e9e6b, 0xffc0802f, 0xffb84f6f, 0xff5a6bc8, 0xff8a9a2f, 0xff3a9aa8,
    };
    // GLFW key codes the view reacts to that Keys does not name.
    private static final int KEY_F5 = 294;
    private static final int KEY_F1 = 290;
    private static final int KEY_INSERT = 260;
    private static final int KEY_KP_0 = 320;
    private static final int KEY_KP_DECIMAL = 330;
    private static final int KEY_KP_DIVIDE = 331;
    private static final int KEY_KP_MULTIPLY = 332;
    private static final int KEY_KP_SUBTRACT = 333;
    private static final int KEY_KP_ADD = 334;

    /** One page. Its engine is created when the tab first needs one, so restored tabs cost nothing until used. */
    private static final class Tab {
        private static int ids;

        final int id = ++ids;
        BrowserEngine engine;
        String url;
        String title;
        final Browser.Frame frame = new Browser.Frame();
        double zoom;

        Tab(String url, String title) {
            this.url = url;
            this.title = title;
        }

        String url() {
            return engine != null && !engine.url().isEmpty() ? engine.url() : url;
        }

        String title() {
            String live = engine != null ? engine.title() : "";
            return live.isEmpty() ? title : live;
        }
    }

    private final Browser browser;
    private final Protection protection;
    private final Strings strings;
    private final Overlay overlay;
    private final Options options;
    private final BrowserData data;
    private final Queue<Runnable> pendingWrites = new ConcurrentLinkedQueue<>();
    private final Object writeLock = new Object();
    private final List<Tab> tabs = new ArrayList<>();
    private int active;
    private final TextEdit address = new TextEdit();
    /** Stands for the page in {@link Ui#focus}: the page has the keyboard while it is focused. */
    private final Object pageFocus = new Object();
    private String shownUrl = "";
    /** The address the first engine was started with, to tell whether the tab that gets it wants another. */
    private String startUrl;
    private boolean firstTaken;
    private boolean refocus;
    private final boolean[] down = new boolean[3];
    private float lastX = -1;
    private float lastY = -1;

    // Menu: one at a time, drawn above everything.
    private float menuX;
    private float menuY;
    private String[] menuLabels;
    private Runnable[] menuActions;

    // Bookmarks bar.
    private final TextEdit renameEdit = new TextEdit();
    private BrowserData.Entry renaming;
    private BrowserData.Entry dragged;
    private float dragFromX;
    private boolean dragMoved;

    private final Favicons favicons;
    /** The frame whose icon rows hold the icons for this draw, or null when there are none to draw. */
    private Browser.Frame iconFrame;

    private String savedSession = "";
    private long sessionCheck;
    private long zoomShownUntil;

    BrowserView(Browser browser, Strings strings, Overlay overlay, Executor io, Protection protection) {
        this.browser = browser;
        this.protection = protection;
        this.strings = strings;
        this.overlay = overlay;
        this.options = overlay.options();
        this.favicons = new Favicons(options.dataDir, overlay::report);
        this.data = new BrowserData(options.dataDir, task -> {
            pendingWrites.add(task);
            io.execute(this::flushWrites);
        }, overlay::report);
        for (BrowserData.Entry saved : data.tabs) {
            tabs.add(new Tab(saved.url, saved.title));
        }
        if (tabs.isEmpty()) {
            tabs.add(new Tab(home(), ""));
        } else {
            active = data.activeTab;
        }
        savedSession = sessionKey();
    }

    // ---- drawing ----

    synchronized void draw(Ui ui, float x, float y, float w, float h) {
        shieldInput(ui, tabs.get(active));
        menuInput(ui);
        Tab tab = tabs.get(active);
        boolean ready = browser.state == Browser.State.READY && overlay.imagesSupported();
        BrowserEngine engine = ready ? ensureEngine(tab) : tab.engine;
        boolean full = engine != null && engine.fullscreen();
        if (ready) {
            for (Tab t : tabs) {
                if (t.engine != null) {
                    t.engine.visible(t == tab);
                }
            }
            openRequests();
        }
        shortcuts(ui, engine, full);
        // Tabs may have been opened or closed above.
        tab = tabs.get(active);
        engine = ready ? ensureEngine(tab) : tab.engine;
        full = engine != null && engine.fullscreen();
        if (refocus) {
            refocus = false;
            ui.focus = pageFocus;
            if (engine != null) {
                engine.focus(true);
            }
        }

        iconFrame = null;
        if (engine != null && browser.state == Browser.State.READY && overlay.imagesSupported()) {
            fetchFrame(tab, engine);
        }
        float py = y;
        if (!full) {
            drawTabs(ui, x, y, w);
            drawTools(ui, tab, engine, x, y + TAB_H + 4, w);
            py = y + TAB_H + 4 + TOOL_H + 6;
            if (options.bookmarksBar) {
                drawBookmarks(ui, x, py, w);
                py += MARK_H + 6;
            }
        }
        float ph = y + h - py;
        if (full) {
            ui.rect(x, py, w, ph, 0xff000000);
        } else {
            ui.panel(x, py, w, ph, 10, Ui.BASE, Ui.LINE);
        }
        if (browser.state == Browser.State.UNSUPPORTED) {
            drawSetup(ui, tab, x, py, w, ph);
        } else if (!overlay.imagesSupported()) {
            // Nothing to offer here, not even the download: the page could not be drawn.
            center(ui, x, py, w, ph, strings.get("browser.unsupportedRenderer"), Ui.FAINT);
        } else if (engine == null || browser.state != Browser.State.READY) {
            drawSetup(ui, tab, x, py, w, ph);
        } else {
            float inset = full ? 0 : 1;
            drawPage(ui, tab, engine, x + inset, py + inset, w - inset * 2, ph - inset * 2);
        }
        saveSession(false);
        drawMenu(ui, x, y, w, h);
        drawShield(ui, tab, engine);
    }

    private void drawPage(Ui ui, Tab tab, BrowserEngine engine, float vx, float vy, float vw, float vh) {
        // The page is painted in whole physical pixels and drawn 1:1 at a whole pixel position; any
        // fraction would resample it and blur the text.
        int pixelX = Math.round(vx * ui.scale);
        int pixelY = Math.round(vy * ui.scale);
        int pixelW = Math.max(1, Math.round(vw * ui.scale));
        int pixelH = Math.max(1, Math.round(vh * ui.scale));
        engine.scale(ui.scale);
        engine.resize(pixelW, pixelH);
        Browser.Frame frame = tab.frame;
        if (frame.version > 0) {
            Batch batch = ui.batch;
            batch.image = frame.pixels;
            batch.imageWidth = frame.size[0];
            batch.imageHeight = frame.size[1] + Batch.ICON_ROWS;
            // Frame versions count per engine; the tab id keeps a switch from looking like an unchanged picture.
            batch.imageVersion = ((long) tab.id << 48) + ((long) frame.iconStamp << 32) + frame.version;
            // CEF rounds the view up to whole device independent pixels, so the frame can be a little
            // larger than the area (the clip trims it) or, while resizing, smaller.
            batch.pushClip(pixelX, pixelY, pixelW, pixelH);
            batch.image(pixelX, pixelY, frame.size[0], frame.size[1], 0xffffffff);
            batch.popClip();
        } else {
            center(ui, vx, vy, vw, vh, strings.get("browser.loading"), Ui.FAINT);
        }
        String[] requested = engine.pollContextMenu();
        if (requested != null) {
            showPageMenu(ui, tab, engine, requested, vx + Integer.parseInt(requested[0]),
                    vy + Integer.parseInt(requested[1]));
        }
        input(ui, tab, engine, vx, vy, vw, vh);
        if (engine.loading()) {
            ui.rect(vx, vy, vw * (0.3f + 0.4f * (float) Math.abs(Math.sin(System.currentTimeMillis() / 400.0))), 2, Ui.ACCENT);
        }
        if (System.currentTimeMillis() < zoomShownUntil) {
            String label = zoomLabel(tab);
            float lw = ui.measure(ui.bold, 13, label) + 24;
            ui.panel(vx + vw - lw - 12, vy + 12, lw, 28, 8, Ui.RAISED, Ui.LINE);
            ui.text(ui.bold, 13, vx + vw - lw, vy + 12 + (28 - ui.lineHeight(ui.bold, 13)) / 2, Ui.FG, label);
        }
    }

    /**
     * Takes the engine's newest frame, and lays the site icons out in the rows below it: they travel to
     * the renderer inside the same picture, so tabs and bookmarks need no texture of their own.
     */
    private void fetchFrame(Tab tab, BrowserEngine engine) {
        for (Tab t : tabs) {
            String host = host(t.url());
            favicons.want(host);
            if (t.engine != null) {
                favicons.page(host, t.engine.icon(), t.engine.loading());
            }
        }
        for (BrowserData.Entry e : data.bookmarks) {
            String host = host(e.url);
            favicons.want(host);
            // A bookmark has an icon from its first start, before its site was ever opened.
            favicons.page(host, "", false);
        }
        Browser.Frame frame = tab.frame;
        long version = engine.frame(frame.version, frame.pixels, frame.size);
        int needed = frame.size[0] * (frame.size[1] + Batch.ICON_ROWS) * 4;
        if (frame.size[0] > 0 && frame.pixels.capacity() < needed) {
            frame.pixels = java.nio.ByteBuffer.allocateDirect(needed);
            frame.iconWidth = 0;
            version = engine.frame(0, frame.pixels, frame.size);
        }
        if (version > 0) {
            frame.version = version;
        }
        int w = frame.size[0];
        int h = frame.size[1];
        if (frame.version == 0 || w < Favicons.CELL) {
            return;
        }
        int stamp = favicons.stamp();
        int perRow = w / Favicons.CELL;
        if (frame.iconWidth != w || frame.iconHeight != h || frame.iconStamp != stamp) {
            frame.iconCount = Math.min(favicons.count(), perRow * Batch.ICON_ROWS / Favicons.CELL);
            java.nio.ByteBuffer bytes = frame.pixels.duplicate();
            bytes.clear();
            java.nio.IntBuffer ints = bytes.order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            for (int slot = 0; slot < frame.iconCount; slot++) {
                int[] cell = favicons.pixels(slot);
                int x = slot % perRow * Favicons.CELL;
                int y = h + slot / perRow * Favicons.CELL;
                for (int row = 0; row < Favicons.CELL; row++) {
                    ints.position((y + row) * w + x);
                    ints.put(cell, row * Favicons.CELL, Favicons.CELL);
                }
            }
            frame.iconWidth = w;
            frame.iconHeight = h;
            frame.iconStamp = stamp;
        }
        iconFrame = frame;
    }

    /** Draws the host's icon in a square, or returns false (nothing drawn) when it has none yet. */
    private boolean drawFavicon(Ui ui, String host, float x, float y, float size) {
        Browser.Frame f = iconFrame;
        int slot = host.isEmpty() || f == null ? -1 : favicons.slot(host);
        if (slot < 0 || slot >= f.iconCount) {
            return false;
        }
        int w = f.size[0];
        float total = f.size[1] + Batch.ICON_ROWS;
        int perRow = w / Favicons.CELL;
        float left = slot % perRow * Favicons.CELL;
        float top = f.size[1] + slot / perRow * Favicons.CELL;
        // Half a texel in from each edge keeps a neighbour's colour out of the filtered edge.
        ui.imagePart(x, y, size, (left + 0.5f) / w, (top + 0.5f) / total,
                (left + Favicons.CELL - 0.5f) / w, (top + Favicons.CELL - 0.5f) / total);
        return true;
    }

    private void drawSetup(Ui ui, Tab tab, float x, float y, float w, float h) {
        Browser.State state = browser.state;
        float cx = x + w / 2;
        float cy = y + h / 2;
        switch (state) {
            case UNSUPPORTED:
                center(ui, x, y, w, h, strings.get("browser.unsupported"), Ui.FAINT);
                return;
            case MISSING:
            case FAILED: {
                String text = state == Browser.State.FAILED ? strings.format("browser.failed", browser.error)
                        : strings.format("browser.install", browser.size());
                float tw = Math.min(ui.measure(ui.regular, 14, text), w - 60);
                ui.textFit(ui.regular, 14, cx - tw / 2, cy - 40, tw, state == Browser.State.FAILED ? Ui.DANGER : Ui.MUTED, text);
                String label = strings.get(state == Browser.State.FAILED ? "browser.retry" : "browser.download");
                float bw = ui.buttonWidth(label);
                if (ui.button(cx - bw / 2, cy - 6, bw, 34, label, true)) {
                    startUrl = tab.url;
                    browser.start(startUrl);
                }
                return;
            }
            case STARTING:
                if (browser.engine() == null) {
                    if (startUrl == null) {
                        startUrl = tab.url;
                    }
                    browser.start(startUrl);
                }
                center(ui, x, y, w, h, strings.get("browser.starting"), Ui.FAINT);
                return;
            case INSTALLING:
            default: {
                String text = strings.format("browser.downloading", Math.round(browser.progress * 100));
                float tw = ui.measure(ui.regular, 14, text);
                ui.text(ui.regular, 14, cx - tw / 2, cy - 30, Ui.MUTED, text);
                float bw = Math.min(360, w - 80);
                ui.round(cx - bw / 2, cy, bw, 8, 4, Ui.RAISED);
                ui.round(cx - bw / 2, cy, bw * browser.progress, 8, 4, Ui.ACCENT);
            }
        }
    }

    private static void center(Ui ui, float x, float y, float w, float h, String text, int color) {
        float tw = Math.min(ui.measure(ui.regular, 14, text), w - 40);
        ui.textFit(ui.regular, 14, x + (w - tw) / 2, y + h / 2 - 10, tw, color, text);
    }

    // ---- tabs ----

    private void drawTabs(Ui ui, float x, float y, float w) {
        int count = tabs.size();
        float plus = 30;
        float tabW = Math.max(44, Math.min(200, (w - plus - 4) / count - 2));
        int closeAt = -1;
        int selectAt = -1;
        float tx = x;
        float lineY = y + (TAB_H - ui.lineHeight(ui.regular, 13)) / 2;
        for (int i = 0; i < count; i++) {
            Tab t = tabs.get(i);
            boolean current = i == active;
            boolean hot = ui.hover(tx, y, tabW, TAB_H);
            if (current) {
                ui.panel(tx, y, tabW, TAB_H, 8, Ui.RAISED, Ui.LINE);
            } else if (hot) {
                ui.round(tx, y, tabW, TAB_H, 8, Ui.HOVER);
            }
            drawIcon(ui, t, tx + 8, y + (TAB_H - 16) / 2);
            float right = tx + tabW - 6;
            if (current || tabW >= 90) {
                float cx = tx + tabW - 24;
                boolean overClose = ui.hover(cx, y + 5, 20, 22);
                if (overClose) {
                    ui.round(cx, y + 5, 20, 22, 6, Ui.HOVER);
                }
                float gw = ui.measure(ui.bold, 14, "×");
                ui.text(ui.bold, 14, cx + (20 - gw) / 2, y + (TAB_H - ui.lineHeight(ui.bold, 14)) / 2,
                        overClose ? Ui.FG : Ui.MUTED, "×");
                if (ui.clickedIn(cx, y + 5, 20, 22)) {
                    closeAt = i;
                }
                right = cx - 2;
            }
            if (t.engine != null && t.engine.playing()) {
                right -= 16;
                long now = System.currentTimeMillis();
                for (int bar = 0; bar < 3; bar++) {
                    float bh = 4 + 8 * (float) Math.abs(Math.sin(now / 220.0 + bar * 1.7));
                    ui.rect(right + 2 + bar * 4, y + (TAB_H + 12) / 2 - bh, 2.5f, bh, Ui.ACCENT);
                }
            }
            float textX = tx + 30;
            if (right - textX > 12) {
                ui.textFit(ui.regular, 13, textX, lineY, right - textX, current ? Ui.FG : Ui.MUTED, label(t));
            }
            if (closeAt < 0 && ui.clickedIn(tx, y, tabW, TAB_H)) {
                selectAt = i;
            }
            if (hot && ui.middleClicked) {
                closeAt = i;
            }
            tx += tabW + 2;
        }
        if (ui.pill(tx + 2, y + 2, 28, 28, "+", false)) {
            addTab(home(), count, true);
            focusAddress(ui, true);
        }
        if (closeAt >= 0) {
            closeTab(closeAt);
        } else if (selectAt >= 0) {
            selectTab(selectAt);
        }
    }

    private String label(Tab t) {
        String title = t.title();
        if (!title.isEmpty()) {
            return title;
        }
        String host = host(t.url());
        return host.isEmpty() ? strings.get("browser.newTab") : host;
    }

    /**
     * A turning ring while the page loads, else the site's icon, else a round badge with its first letter.
     */
    private void drawIcon(Ui ui, Tab t, float x, float y) {
        if (t.engine != null && t.engine.loading()) {
            int phase = (int) (System.currentTimeMillis() / 90 % 8);
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                int alpha = 60 + 195 * (((i - phase) % 8 + 8) % 8) / 7;
                ui.round(x + 6.5f + 6.5f * (float) Math.cos(angle), y + 6.5f + 6.5f * (float) Math.sin(angle), 3, 3, 1.5f,
                        (alpha << 24) | (Ui.ACCENT & 0xffffff));
            }
            return;
        }
        String host = host(t.url());
        if (drawFavicon(ui, host, x, y, 16)) {
            return;
        }
        int color = ICON_COLORS[Math.abs(host.hashCode()) % ICON_COLORS.length];
        ui.round(x, y, 16, 16, 8, color);
        if (!host.isEmpty()) {
            String letter = host.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
            float lw = ui.measure(ui.bold, 10, letter);
            ui.text(ui.bold, 10, x + (16 - lw) / 2, y + (16 - ui.lineHeight(ui.bold, 10)) / 2, 0xffffffff, letter);
        }
    }

    private static String host(String url) {
        int scheme = url.indexOf("://");
        String rest = scheme >= 0 ? url.substring(scheme + 3) : url;
        int end = rest.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int at = rest.indexOf(c);
            if (at >= 0) {
                end = Math.min(end, at);
            }
        }
        rest = rest.substring(0, end);
        return rest.startsWith("www.") ? rest.substring(4) : rest;
    }

    /** Opens a tab at {@code index}; null at the tab limit. */
    private Tab addTab(String url, int index, boolean select) {
        if (tabs.size() >= MAX_TABS) {
            return null;
        }
        Tab t = new Tab(url, "");
        tabs.add(index, t);
        if (select) {
            active = index;
            shownUrl = null;
            refocus = true;
        } else if (index <= active) {
            active++;
        }
        if (browser.state == Browser.State.READY) {
            ensureEngine(t);
        }
        return t;
    }

    private void closeTab(int index) {
        Tab t = tabs.remove(index);
        if (t.engine != null) {
            t.engine.close();
        }
        if (tabs.isEmpty()) {
            tabs.add(new Tab(home(), ""));
            active = 0;
        } else if (index < active || active == tabs.size()) {
            active--;
        }
        shownUrl = null;
        refocus = true;
    }

    private void selectTab(int index) {
        if (index != active) {
            active = index;
            shownUrl = null;
            refocus = true;
        }
    }

    private BrowserEngine ensureEngine(Tab t) {
        if (t.engine == null && browser.engine() != null) {
            if (!firstTaken) {
                firstTaken = true;
                t.engine = browser.engine();
                if (!t.url.equals(startUrl)) {
                    t.engine.load(t.url);
                }
            } else {
                t.engine = browser.open(t.url);
            }
            if (t.zoom != 0) {
                t.engine.zoom(t.zoom);
            }
        }
        return t.engine;
    }

    /** Links that pages want in a new tab; only web addresses are taken. */
    private void openRequests() {
        List<Tab> openers = new ArrayList<>(tabs);
        for (Tab opener : openers) {
            for (String request; opener.engine != null && (request = opener.engine.pollOpenRequest()) != null; ) {
                boolean background = request.startsWith("background:");
                String url = request.substring(request.indexOf(':') + 1);
                int at = tabs.indexOf(opener);
                if (at >= 0 && (url.startsWith("http://") || url.startsWith("https://"))) {
                    addTab(url, at + 1, !background);
                }
            }
        }
    }

    private String sessionKey() {
        StringBuilder key = new StringBuilder().append(active);
        for (Tab t : tabs) {
            key.append('\n').append(t.url());
        }
        return key.toString();
    }

    /** Remembers the open tabs for the next game, at most twice a second. */
    private void saveSession(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - sessionCheck < 500) {
            return;
        }
        sessionCheck = now;
        String key = sessionKey();
        if (!key.equals(savedSession)) {
            savedSession = key;
            List<BrowserData.Entry> open = new ArrayList<>();
            for (Tab t : tabs) {
                open.add(new BrowserData.Entry(t.title(), t.url()));
            }
            data.saveTabs(open, active);
        }
    }

    /** The overlay was closed: the tabs stop being drawn, so let them paint rarely. */
    synchronized void hide() {
        for (Tab t : tabs) {
            if (t.engine != null) {
                t.engine.visible(false);
            }
        }
    }

    synchronized void flushSession() {
        saveSession(true);
    }

    void saveOnExit() {
        flushSession();
        // The daemon IO thread may stop with older snapshots queued; drain them in order here.
        flushWrites();
    }

    private void flushWrites() {
        synchronized (writeLock) {
            for (Runnable task; (task = pendingWrites.poll()) != null; ) {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    overlay.report(e);
                }
            }
        }
    }

    // ---- address bar ----

    private void drawTools(Ui ui, Tab tab, BrowserEngine engine, float x, float y, float w) {
        float bx = x;
        String[] icons = {"←", "→", "↻"};
        for (int i = 0; i < icons.length; i++) {
            if (ui.button(bx, y, TOOL_H, TOOL_H, icons[i], false) && engine != null) {
                if (i == 0) {
                    engine.back();
                } else if (i == 1) {
                    engine.forward();
                } else {
                    engine.reload();
                }
            }
            bx += TOOL_H + 6;
        }
        String url = tab.url();
        if (ui.focus != address && !url.equals(shownUrl)) {
            shownUrl = url;
            address.set(url);
        }
        float right = x + w;
        float starX = right - TOOL_H;
        float zoomW = 0;
        if (tab.zoom != 0) {
            String label = zoomLabel(tab);
            zoomW = ui.measure(ui.bold, 13, label) + 20;
            if (ui.pill(starX - zoomW - 4, y + 4, zoomW, TOOL_H - 8, label, true)) {
                tab.zoom = 0;
                applyZoom(tab);
            }
            zoomW += 4;
        }
        boolean web = url.startsWith("http://") || url.startsWith("https://");
        if (web) {
            String label = strings.format("browser.shield", engine == null ? 0 : engine.blocked());
            float sw = ui.measure(ui.bold, 13, label) + 20;
            shieldX = starX - zoomW - 4 - sw;
            if (ui.pill(shieldX, y + 4, sw, TOOL_H - 8, label, shieldOpen)) {
                shieldOpen = true;
            }
            shieldX = Math.min(shieldX, right - SHIELD_W);
            shieldY = y + TOOL_H + 2;
            zoomW += sw + 4;
        } else {
            shieldOpen = false;
        }
        boolean marked = data.indexOf(url) >= 0;
        if (ui.button(starX, y, TOOL_H, TOOL_H, marked ? "★" : "☆", marked)) {
            toggleBookmark(tab);
        }
        float fieldW = starX - zoomW - 6 - bx;
        ui.field(address, bx, y, fieldW, TOOL_H, false, strings.get("browser.address"));
        if (ui.focus == address && pressed(ui, Keys.ENTER, Keys.KP_ENTER)) {
            open(target(address.value()));
            ui.focus = pageFocus;
        }
    }

    // ---- site shield ----

    private static final float SHIELD_W = 310;
    private static final float SHIELD_ROW = 28;
    private static final int[] SHIELD_FEATURES = {Protection.ADS, Protection.COOKIES, Protection.DARK};
    private static final String[] SHIELD_LABELS = {"browser.shieldAds", "browser.shieldCookies", "browser.shieldDark"};
    private boolean shieldOpen;
    private float shieldX;
    private float shieldY;

    /** The popover's clicks, taken before the rest of the view sees them: a switch, or a click outside closes it. */
    private void shieldInput(Ui ui, Tab tab) {
        if (!shieldOpen) {
            return;
        }
        for (Iterator<int[]> it = ui.keys.iterator(); it.hasNext(); ) {
            if (it.next()[0] == Keys.ESCAPE) {
                it.remove();
                shieldOpen = false;
                return;
            }
        }
        if (!(ui.clicked || ui.rightClicked || ui.middleClicked)) {
            return;
        }
        float height = 16 + (SHIELD_FEATURES.length + 1) * SHIELD_ROW;
        boolean inside = ui.hover(shieldX, shieldY, SHIELD_W, height);
        if (inside && ui.clicked) {
            for (int i = 0; i < SHIELD_FEATURES.length; i++) {
                if (ui.hover(shieldX + 8, shieldY + 8 + i * SHIELD_ROW, SHIELD_W - 16, SHIELD_ROW)) {
                    protection.toggleSite(NetFilter.host(tab.url()), SHIELD_FEATURES[i]);
                    // The page is prepared while it loads, so a change shows after a reload.
                    if (tab.engine != null) {
                        tab.engine.reload();
                    }
                }
            }
        }
        if (!inside) {
            shieldOpen = false;
        }
        ui.clicked = false;
        ui.rightClicked = false;
        ui.middleClicked = false;
    }

    private void drawShield(Ui ui, Tab tab, BrowserEngine engine) {
        if (!shieldOpen) {
            return;
        }
        String site = NetFilter.host(tab.url());
        float height = 16 + (SHIELD_FEATURES.length + 1) * SHIELD_ROW;
        ui.panel(shieldX, shieldY, SHIELD_W, height, 8, Ui.SURFACE, Ui.LINE);
        for (int i = 0; i < SHIELD_FEATURES.length; i++) {
            ui.checkbox(shieldX + 14, shieldY + 8 + i * SHIELD_ROW + 6, strings.get(SHIELD_LABELS[i]),
                    protection.siteOn(site, SHIELD_FEATURES[i]));
        }
        float ty = shieldY + 8 + SHIELD_FEATURES.length * SHIELD_ROW + 6;
        ui.textFit(ui.regular, 12, shieldX + 14, ty, SHIELD_W - 28, Ui.FAINT,
                strings.format("browser.shieldBlocked", engine == null ? 0 : engine.blocked(), site));
    }

    /** A web address the game opened: a new tab of its own, selected; at the tab limit the current tab is used. */
    synchronized void openExternal(String url) {
        Tab t = addTab(url, tabs.size(), true);
        if (t == null) {
            open(url);
        }
    }

    private void toggleBookmark(Tab tab) {
        String url = tab.url();
        int at = data.indexOf(url);
        if (at >= 0) {
            data.bookmarks.remove(at);
        } else {
            String title = tab.title();
            data.bookmarks.add(new BrowserData.Entry(title.isEmpty() ? host(url) : title, url));
        }
        data.saveBookmarks();
    }

    private void focusAddress(Ui ui, boolean empty) {
        ui.focus = address;
        refocus = false;
        if (empty) {
            address.set("");
            shownUrl = tabs.get(active).url();
        } else {
            address.selectAll();
        }
    }

    private void open(String url) {
        Tab tab = tabs.get(active);
        if (tab.engine != null) {
            tab.engine.load(url);
        } else {
            tab.url = url;
        }
        address.set(url);
        shownUrl = tab.url();
    }

    String home() {
        String home = options.browserHome.trim();
        return home.isEmpty() ? DEFAULT_HOME : target(home);
    }

    /** An address as typed: a URL (with or without https://), or else a web search. */
    String target(String typed) {
        String value = typed.trim();
        if (value.isEmpty()) {
            return home();
        }
        if (value.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            return value;
        }
        if (!value.contains(" ") && value.matches("^[^/]+\\.[a-zA-Z]{2,}(/.*)?$")) {
            return "https://" + value;
        }
        return searchUrl(value);
    }

    private String searchUrl(String query) {
        String base = ENGINES[0][2];
        for (String[] engine : ENGINES) {
            if (engine[0].equals(options.browserSearch)) {
                base = engine[2];
            }
        }
        try {
            return base + URLEncoder.encode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String zoomLabel(Tab tab) {
        return Math.round(Math.pow(1.2, tab.zoom) * 100) + "%";
    }

    private void applyZoom(Tab tab) {
        if (tab.engine != null) {
            tab.engine.zoom(tab.zoom);
        }
        zoomShownUntil = System.currentTimeMillis() + 1500;
    }

    // ---- bookmarks bar ----

    private void drawBookmarks(Ui ui, float x, float y, float w) {
        List<BrowserData.Entry> list = data.bookmarks;
        float limit = x + w - 30;
        float bx = x;
        float[] starts = new float[list.size() + 1];
        float[] widths = new float[list.size()];
        int shown = 0;
        for (BrowserData.Entry e : list) {
            float iw = e == renaming ? 170 : Math.min(160, ui.measure(ui.regular, 13, e.title) + 36);
            if (bx + iw > limit) {
                break;
            }
            starts[shown] = bx;
            widths[shown] = iw;
            bx += iw + 2;
            shown++;
        }
        starts[shown] = bx;

        int target = -1;
        if (dragged != null) {
            if (Math.abs(ui.mouseX - dragFromX) > 6) {
                dragMoved = true;
            }
            target = 0;
            for (int i = 0; i < shown; i++) {
                if (ui.mouseX > starts[i] + widths[i] / 2) {
                    target = i + 1;
                }
            }
        }
        for (int i = 0; i < shown; i++) {
            BrowserData.Entry e = list.get(i);
            float ix = starts[i];
            if (e == renaming) {
                ui.field(renameEdit, ix, y, widths[i], MARK_H, false, "");
                continue;
            }
            boolean hot = ui.hover(ix, y, widths[i], MARK_H);
            if (e == dragged && dragMoved) {
                ui.round(ix, y, widths[i], MARK_H, 8, Ui.RAISED);
            } else if (hot) {
                ui.round(ix, y, widths[i], MARK_H, 8, Ui.HOVER);
            }
            if (!drawFavicon(ui, host(e.url), ix + 7, y + (MARK_H - 16) / 2, 16)) {
                ui.round(ix + 8, y + (MARK_H - 14) / 2, 14, 14, 7, ICON_COLORS[Math.abs(host(e.url).hashCode()) % ICON_COLORS.length]);
            }
            ui.textFit(ui.regular, 13, ix + 28, y + (MARK_H - ui.lineHeight(ui.regular, 13)) / 2, widths[i] - 34, Ui.FG, e.title);
            if (ui.clickedIn(ix, y, widths[i], MARK_H)) {
                dragged = e;
                dragFromX = ui.mouseX;
                dragMoved = false;
            }
            if (hot && ui.middleClicked) {
                addTab(e.url, active + 1, false);
            }
            if (hot && ui.rightClicked) {
                bookmarkMenu(ui, e, ix, y + MARK_H);
            }
        }
        if (renaming != null) {
            if (pressed(ui, Keys.ENTER, Keys.KP_ENTER) || ui.focus != renameEdit) {
                String title = renameEdit.value().trim();
                if (!title.isEmpty()) {
                    renaming.title = title;
                    data.saveBookmarks();
                }
                renaming = null;
            }
        }
        if (dragged != null) {
            if (dragMoved) {
                ui.rect(starts[target] - 2, y + 2, 2, MARK_H - 4, Ui.ACCENT);
            }
            if (!ui.mouseDown) {
                if (dragMoved) {
                    int from = list.indexOf(dragged);
                    if (from >= 0) {
                        list.remove(from);
                        list.add(target > from ? target - 1 : target, dragged);
                        data.saveBookmarks();
                    }
                } else {
                    open(dragged.url);
                    ui.focus = pageFocus;
                }
                dragged = null;
            }
        }
        if (shown < list.size() && ui.pill(x + w - 28, y, 28, MARK_H, "»", false)) {
            int hidden = list.size() - shown;
            String[] labels = new String[hidden];
            Runnable[] actions = new Runnable[hidden];
            for (int i = 0; i < hidden; i++) {
                BrowserData.Entry e = list.get(shown + i);
                labels[i] = e.title;
                actions[i] = () -> open(e.url);
            }
            showMenu(x + w - 200, y + MARK_H, labels, actions);
        }
    }

    private void bookmarkMenu(Ui ui, BrowserData.Entry e, float mx, float my) {
        showMenu(mx, my,
                new String[] {strings.get("browser.open"), strings.get("browser.openTab"), strings.get("browser.rename"),
                    strings.get("browser.delete")},
                new Runnable[] {
                    () -> open(e.url),
                    () -> addTab(e.url, active + 1, true),
                    () -> {
                        renaming = e;
                        renameEdit.set(e.title);
                        renameEdit.selectAll();
                        ui.focus = renameEdit;
                    },
                    () -> {
                        data.bookmarks.remove(e);
                        data.saveBookmarks();
                    },
                });
    }

    // ---- menus ----

    private void showMenu(float x, float y, String[] labels, Runnable[] actions) {
        menuX = x;
        menuY = y;
        menuLabels = labels;
        menuActions = actions;
    }

    private void showPageMenu(Ui ui, Tab tab, BrowserEngine engine, String[] p, float mx, float my) {
        String link = p[2];
        String image = p[3];
        String selection = p[4];
        boolean editable = "1".equals(p[5]);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!link.isEmpty()) {
            labels.add(strings.get("browser.linkTab"));
            actions.add(() -> addTab(link, active + 1, false));
            labels.add(strings.get("browser.linkCopy"));
            actions.add(() -> ui.platform.setClipboard(link));
        }
        if (!image.isEmpty()) {
            labels.add(strings.get("browser.imageTab"));
            actions.add(() -> addTab(image, active + 1, false));
            labels.add(strings.get("browser.imageCopy"));
            actions.add(() -> ui.platform.setClipboard(image));
        }
        if (editable) {
            if (!selection.isEmpty()) {
                labels.add(strings.get("browser.cut"));
                actions.add(() -> engine.command(BrowserEngine.CUT));
                labels.add(strings.get("browser.copy"));
                actions.add(() -> engine.command(BrowserEngine.COPY));
            }
            labels.add(strings.get("browser.paste"));
            actions.add(() -> engine.command(BrowserEngine.PASTE));
            labels.add(strings.get("browser.selectAll"));
            actions.add(() -> engine.command(BrowserEngine.SELECT_ALL));
        } else if (!selection.isEmpty()) {
            labels.add(strings.get("browser.copy"));
            actions.add(() -> engine.command(BrowserEngine.COPY));
            String shortText = selection.length() > 24 ? selection.substring(0, 24) + "…" : selection;
            labels.add(strings.format("browser.search", shortText.replace('\n', ' ')));
            actions.add(() -> addTab(searchUrl(selection), active + 1, true));
        }
        if (link.isEmpty() && image.isEmpty() && !editable && selection.isEmpty()) {
            labels.add(strings.get("browser.back"));
            actions.add(engine::back);
            labels.add(strings.get("browser.forward"));
            actions.add(engine::forward);
            labels.add(strings.get("browser.reload"));
            actions.add(engine::reload);
        }
        showMenu(mx, my, labels.toArray(new String[0]), actions.toArray(new Runnable[0]));
    }

    private float menuWidth(Ui ui) {
        float width = 160;
        for (String label : menuLabels) {
            width = Math.max(width, ui.measure(ui.regular, 13, label) + 32);
        }
        return Math.min(width, 360);
    }

    private static final float ITEM_H = 28;

    /** Runs at the start of a frame so a click on an item never reaches the page or the rows under it. */
    private void menuInput(Ui ui) {
        if (menuLabels == null) {
            return;
        }
        boolean click = ui.clicked || ui.rightClicked || ui.middleClicked;
        for (Iterator<int[]> it = ui.keys.iterator(); it.hasNext(); ) {
            if (it.next()[0] == Keys.ESCAPE) {
                it.remove();
                menuLabels = null;
                return;
            }
        }
        if (!click) {
            return;
        }
        float[] at = menuPlacement(ui);
        Runnable action = null;
        if (ui.clicked) {
            for (int i = 0; i < menuLabels.length; i++) {
                if (ui.hover(at[0], at[1] + 4 + i * ITEM_H, menuWidth(ui), ITEM_H)) {
                    action = menuActions[i];
                }
            }
        }
        menuLabels = null;
        menuActions = null;
        ui.clicked = false;
        ui.rightClicked = false;
        ui.middleClicked = false;
        if (action != null) {
            action.run();
        }
    }

    private float lastAreaX;
    private float lastAreaY;
    private float lastAreaW;
    private float lastAreaH;

    /** The menu's corner, moved so it stays inside the browser area. */
    private float[] menuPlacement(Ui ui) {
        float width = menuWidth(ui);
        float height = menuLabels.length * ITEM_H + 8;
        float mx = Math.max(lastAreaX, Math.min(menuX, lastAreaX + lastAreaW - width));
        float my = Math.max(lastAreaY, Math.min(menuY, lastAreaY + lastAreaH - height));
        return new float[] {mx, my};
    }

    private void drawMenu(Ui ui, float x, float y, float w, float h) {
        lastAreaX = x;
        lastAreaY = y;
        lastAreaW = w;
        lastAreaH = h;
        if (menuLabels == null) {
            return;
        }
        float[] at = menuPlacement(ui);
        float width = menuWidth(ui);
        ui.panel(at[0], at[1], width, menuLabels.length * ITEM_H + 8, 8, Ui.SURFACE, Ui.LINE);
        for (int i = 0; i < menuLabels.length; i++) {
            float iy = at[1] + 4 + i * ITEM_H;
            if (ui.hover(at[0] + 4, iy, width - 8, ITEM_H)) {
                ui.round(at[0] + 4, iy, width - 8, ITEM_H, 6, Ui.HOVER);
            }
            ui.textFit(ui.regular, 13, at[0] + 14, iy + (ITEM_H - ui.lineHeight(ui.regular, 13)) / 2, width - 28, Ui.FG, menuLabels[i]);
        }
    }

    // ---- keyboard shortcuts ----

    /** Reloads the active tab, if it has an engine; used after cookies are imported into it. */
    void reloadActive() {
        BrowserEngine engine = tabs.get(active).engine;
        if (engine != null) {
            engine.reload();
        }
    }

    /** Whether Esc is for the view (a menu, a rename, a fullscreen video) rather than closing the overlay. */
    boolean wantsEscape() {
        BrowserEngine engine = tabs.get(active).engine;
        return menuLabels != null || renaming != null || (engine != null && engine.fullscreen());
    }

    /** Browser-wide keys, handled before the rows draw; a key used here is removed from {@code ui.keys}. */
    private void shortcuts(Ui ui, BrowserEngine engine, boolean full) {
        for (Iterator<int[]> it = ui.keys.iterator(); it.hasNext(); ) {
            int[] pair = it.next();
            int key = pair[0];
            int mods = pair[1];
            boolean used = true;
            boolean ctrl = Ui.shortcut(mods);
            boolean shift = (mods & Keys.MOD_SHIFT) != 0;
            boolean alt = (mods & Keys.MOD_ALT) != 0;
            if (key == Keys.ESCAPE && renaming != null) {
                renaming = null;
                ui.focus = null;
            } else if (key == Keys.ESCAPE && full) {
                engine.exitFullscreen();
            } else if (ctrl && key == 'T') {
                addTab(home(), tabs.size(), true);
                focusAddress(ui, true);
            } else if (ctrl && key == 'W') {
                closeTab(active);
            } else if (ctrl && key == Keys.TAB) {
                selectTab(((active + (shift ? -1 : 1)) % tabs.size() + tabs.size()) % tabs.size());
            } else if (ctrl && key >= '1' && key <= '9') {
                selectTab(key == '9' ? tabs.size() - 1 : Math.min(key - '1', tabs.size() - 1));
            } else if (ctrl && key == 'L') {
                focusAddress(ui, false);
            } else if (key == KEY_F5 || (ctrl && key == 'R')) {
                if (engine != null) {
                    engine.reload();
                }
            } else if (alt && key == Keys.LEFT) {
                if (engine != null) {
                    engine.back();
                }
            } else if (alt && key == Keys.RIGHT) {
                if (engine != null) {
                    engine.forward();
                }
            } else {
                used = false;
            }
            if (used) {
                it.remove();
            }
        }
    }

    // ---- input to the page ----

    private void input(Ui ui, Tab tab, BrowserEngine engine, float x, float y, float w, float h) {
        boolean over = ui.hover(x, y, w, h);
        // Device independent pixels, which are the overlay's units.
        int px = Math.round(ui.mouseX - x);
        int py = Math.round(ui.mouseY - y);
        int held = (ui.buttonDown(Keys.MOUSE_LEFT) ? InputEvent.BUTTON1_DOWN_MASK : 0)
                | (ui.buttonDown(Keys.MOUSE_MIDDLE) ? InputEvent.BUTTON2_DOWN_MASK : 0)
                | (ui.buttonDown(Keys.MOUSE_RIGHT) ? InputEvent.BUTTON3_DOWN_MASK : 0);
        int keyMods = awtMods(ui.mods);
        if ((over || held != 0) && (ui.mouseX != lastX || ui.mouseY != lastY)) {
            lastX = ui.mouseX;
            lastY = ui.mouseY;
            boolean dragging = held != 0 && (down[0] || down[1] || down[2]);
            engine.mouse(dragging ? MouseEvent.MOUSE_DRAGGED : MouseEvent.MOUSE_MOVED, px, py, held | keyMods, 0,
                    MouseEvent.NOBUTTON);
        }
        if (over) {
            if (ui.backClicked) {
                engine.back();
            }
            if (ui.forwardClicked) {
                engine.forward();
            }
            boolean[] pressedNow = {ui.clickedIn(x, y, w, h), ui.middleClicked, ui.rightClicked};
            for (int i = 0; i < 3; i++) {
                if (pressedNow[i]) {
                    ui.focus = pageFocus;
                    engine.focus(true);
                    down[i] = true;
                    int mask = BUTTON_MASKS[i];
                    engine.mouse(MouseEvent.MOUSE_PRESSED, px, py, mask | keyMods,
                            i == 0 && ui.doubleClicked ? 2 : 1, BUTTONS[i]);
                }
            }
        }
        for (int i = 0; i < 3; i++) {
            if (down[i] && !ui.buttonDown(i == 0 ? Keys.MOUSE_LEFT : i == 1 ? Keys.MOUSE_MIDDLE : Keys.MOUSE_RIGHT)) {
                down[i] = false;
                engine.mouse(MouseEvent.MOUSE_RELEASED, px, py, keyMods, 1, BUTTONS[i]);
            }
        }
        float scroll = ui.takeZoomScroll(x, y, w, h);
        if (scroll != 0) {
            if (Ui.shortcut(ui.mods)) {
                tab.zoom = Math.max(-4, Math.min(6, tab.zoom + (scroll > 0 ? 0.5 : -0.5)));
                applyZoom(tab);
            } else {
                int lines = Math.round(scroll);
                engine.wheel(px, py, (ui.mods & Keys.MOD_SHIFT) != 0 ? InputEvent.SHIFT_DOWN_MASK : 0,
                        -(lines != 0 ? lines : scroll > 0 ? 1 : -1));
            }
        }
        if (ui.focus != pageFocus) {
            return;
        }
        for (int[] key : ui.keys) {
            int code = awtKey(key[0]);
            if (code == KeyEvent.VK_UNDEFINED) {
                continue;
            }
            int mods = key[1];
            if (Ui.shortcut(mods) && clipboardKey(engine, key[0], (mods & Keys.MOD_SHIFT) != 0)) {
                continue;
            }
            int awtMods = awtMods(mods);
            // Chromium on Linux reads Ctrl combinations from the character, which has no event of its own.
            char chr = Ui.shortcut(mods) && (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z || code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9)
                    ? Character.toLowerCase((char) code) : KeyEvent.CHAR_UNDEFINED;
            engine.key(KeyEvent.KEY_PRESSED, code, chr, awtMods);
            if (code == KeyEvent.VK_ENTER) {
                engine.key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, '\r', awtMods);
            }
            engine.key(KeyEvent.KEY_RELEASED, code, chr, awtMods);
        }
        ui.keys.clear();
        for (int i = 0; i < ui.chars.length(); i++) {
            engine.key(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, ui.chars.charAt(i), 0);
        }
        ui.chars.setLength(0);
    }

    private static final int[] BUTTON_MASKS = {InputEvent.BUTTON1_DOWN_MASK, InputEvent.BUTTON2_DOWN_MASK, InputEvent.BUTTON3_DOWN_MASK};
    private static final int[] BUTTONS = {MouseEvent.BUTTON1, MouseEvent.BUTTON2, MouseEvent.BUTTON3};

    /** Ctrl+A/C/X/V/Z/Y act on the focused frame directly; key events for them do not reliably reach Chromium. */
    private static boolean clipboardKey(BrowserEngine engine, int key, boolean shift) {
        switch (key) {
            case 'A':
                engine.command(BrowserEngine.SELECT_ALL);
                return true;
            case 'C':
                engine.command(BrowserEngine.COPY);
                return true;
            case 'X':
                engine.command(BrowserEngine.CUT);
                return true;
            case 'V':
                engine.command(BrowserEngine.PASTE);
                return true;
            case 'Z':
                engine.command(shift ? BrowserEngine.REDO : BrowserEngine.UNDO);
                return true;
            case 'Y':
                engine.command(BrowserEngine.REDO);
                return true;
            default:
                return false;
        }
    }

    private static boolean pressed(Ui ui, int... wanted) {
        for (int[] key : ui.keys) {
            for (int k : wanted) {
                if (key[0] == k) {
                    return true;
                }
            }
        }
        return false;
    }

    /** GLFW key codes to AWT's. Letters and digits share ASCII codes; the rest are mapped. */
    private static int awtKey(int key) {
        if (key >= '0' && key <= '9' || key >= 'A' && key <= 'Z') {
            return key;
        }
        if (key >= KEY_F1 && key < KEY_F1 + 12) {
            return KeyEvent.VK_F1 + key - KEY_F1;
        }
        if (key >= KEY_KP_0 && key < KEY_KP_0 + 10) {
            return KeyEvent.VK_NUMPAD0 + key - KEY_KP_0;
        }
        switch (key) {
            case ' ': return KeyEvent.VK_SPACE;
            case '\'': return KeyEvent.VK_QUOTE;
            case ',': return KeyEvent.VK_COMMA;
            case '-': return KeyEvent.VK_MINUS;
            case '.': return KeyEvent.VK_PERIOD;
            case '/': return KeyEvent.VK_SLASH;
            case ';': return KeyEvent.VK_SEMICOLON;
            case '=': return KeyEvent.VK_EQUALS;
            case '[': return KeyEvent.VK_OPEN_BRACKET;
            case '\\': return KeyEvent.VK_BACK_SLASH;
            case ']': return KeyEvent.VK_CLOSE_BRACKET;
            case '`': return KeyEvent.VK_BACK_QUOTE;
            case Keys.ESCAPE: return KeyEvent.VK_ESCAPE;
            case Keys.ENTER:
            case Keys.KP_ENTER:
                return KeyEvent.VK_ENTER;
            case Keys.TAB: return KeyEvent.VK_TAB;
            case Keys.BACKSPACE: return KeyEvent.VK_BACK_SPACE;
            case KEY_INSERT: return KeyEvent.VK_INSERT;
            case Keys.DELETE: return KeyEvent.VK_DELETE;
            case Keys.LEFT: return KeyEvent.VK_LEFT;
            case Keys.RIGHT: return KeyEvent.VK_RIGHT;
            case Keys.UP: return KeyEvent.VK_UP;
            case Keys.DOWN: return KeyEvent.VK_DOWN;
            case Keys.HOME: return KeyEvent.VK_HOME;
            case Keys.END: return KeyEvent.VK_END;
            case Keys.PAGE_UP: return KeyEvent.VK_PAGE_UP;
            case Keys.PAGE_DOWN: return KeyEvent.VK_PAGE_DOWN;
            case KEY_KP_DECIMAL: return KeyEvent.VK_DECIMAL;
            case KEY_KP_DIVIDE: return KeyEvent.VK_DIVIDE;
            case KEY_KP_MULTIPLY: return KeyEvent.VK_MULTIPLY;
            case KEY_KP_SUBTRACT: return KeyEvent.VK_SUBTRACT;
            case KEY_KP_ADD: return KeyEvent.VK_ADD;
            default: return KeyEvent.VK_UNDEFINED;
        }
    }

    private static int awtMods(int mods) {
        int out = 0;
        if ((mods & Keys.MOD_SHIFT) != 0) {
            out |= InputEvent.SHIFT_DOWN_MASK;
        }
        if ((mods & Keys.MOD_CONTROL) != 0) {
            out |= InputEvent.CTRL_DOWN_MASK;
        }
        if ((mods & Keys.MOD_ALT) != 0) {
            out |= InputEvent.ALT_DOWN_MASK;
        }
        if ((mods & Keys.MOD_SUPER) != 0) {
            out |= InputEvent.META_DOWN_MASK;
        }
        return out;
    }
}
