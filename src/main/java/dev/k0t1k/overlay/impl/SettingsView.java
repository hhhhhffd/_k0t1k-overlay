package dev.k0t1k.overlay.impl;

import java.util.List;

/**
 * The settings tab: a list of sections on the left and one scrolling column on the right. To add a
 * section, put its title key in {@link #SECTIONS}, write a method {@code float section(Ui ui, float x,
 * float y, float w)} that starts with {@link #heading} and returns the y below its last row, and call
 * it from {@link #draw} in the same order.
 */
final class SettingsView {
    private static final String[] SECTIONS = {"settings.interface", "settings.hud", "settings.notify", "settings.cat",
            "settings.recording", "settings.hotkeys", "settings.browser", "settings.hardware"};
    private static final int INTERFACE = 0;
    private static final int HUD = 1;
    private static final int NOTIFY = 2;
    private static final int CAT = 3;
    private static final int RECORDING = 4;
    private static final int HOTKEYS = 5;
    private static final int BROWSER = 6;
    private static final int HARDWARE = 7;

    private final Overlay overlay;
    private final Options options;
    private final Strings strings;
    private final RecordingView recordingView;
    /** The percent shown while the scale slider is dragged; the scale itself changes on release. */
    private int draggedScale;
    private final Object hudSizeSlider = new Object();
    private final Object toastSlider = new Object();
    private final Object[] toastKindSliders = new Object[ToastKind.values().length];
    private boolean toastSliderMoved;
    private final Object hudOpacitySlider = new Object();
    /** A HUD slider moved and the settings are not saved yet; they are on release or close. */
    private boolean hudSliderMoved;
    private final TextEdit fakeCpu;
    private final TextEdit fakeThreads;
    private final TextEdit fakeGpu;
    private final TextEdit fakeVendor;
    private final TextEdit fakeMemory;
    private float scroll;
    private final TextEdit homeEdit = new TextEdit();
    private long clearedAt;
    private boolean clearFailed;
    private final TextEdit importAllowlist = new TextEdit();
    /** Null until the io thread answers; empty once it has, with nothing found. */
    private List<BrowserLogins.Source> importSources;
    private boolean importSourcesRequested;
    private BrowserLogins.Source importSource;
    private BrowserLogins.Profile importProfile;
    private boolean importRunning;
    private String importMessage = "";
    private long importMessageAt;
    /** The y of the top of the column, so a section can tell where it starts within it. */
    private float viewTop;
    /** Where each section starts, as an offset from the top of the column; set while drawing. */
    private final float[] tops = new float[SECTIONS.length];

    SettingsView(Overlay overlay) {
        this.overlay = overlay;
        this.options = overlay.options();
        this.strings = options.strings;
        homeEdit.set(options.browserHome);
        importAllowlist.set(options.browserImportAllowlist);
        fakeCpu = new TextEdit(options.fakeCpu);
        fakeThreads = new TextEdit(Integer.toString(options.fakeThreads));
        fakeGpu = new TextEdit(options.fakeGpu);
        fakeVendor = new TextEdit(options.fakeVendor);
        fakeMemory = new TextEdit(Integer.toString(options.fakeMemory));
        this.recordingView = new RecordingView(overlay);
    }

    void draw(Ui ui, float x, float y, float w, float h) {
        float listW = 150;
        float gap = 18;
        float cx = x + listW + gap;
        float cw = w - listW - gap;

        viewTop = y;
        float cy = y - scroll;
        ui.clip(cx, y, cw, h);
        cy = interfaceSection(ui, cx, cy, cw);
        cy = hudSection(ui, cx, cy, cw);
        cy = notifySection(ui, cx, cy, cw);
        cy = catSection(ui, cx, cy, cw);
        cy = recordingSection(ui, cx, cy, cw);
        cy = hotkeysSection(ui, cx, cy, cw);
        cy = browserSection(ui, cx, cy, cw);
        cy = hardwareSection(ui, cx, cy, cw);
        ui.unclip();
        float content = cy + scroll - y;

        scroll -= ui.takeScroll(x, y, w, h) * 40;
        float max = Math.max(0, content - h);
        scroll = Math.max(0, Math.min(scroll, max));

        // A pending hotkey capture belongs to its row; once that scrolls out of view it must not linger.
        if (overlay.capturingKey() && (tops[HOTKEYS + 1] <= scroll || tops[HOTKEYS] >= scroll + h)) {
            overlay.cancelKeyCapture();
        }

        ui.rect(x + listW + gap / 2 - 0.5f, y, 1, h, Ui.LINE);
        int current = 0;
        for (int i = 0; i < SECTIONS.length; i++) {
            if (tops[i] <= scroll + 8) {
                current = i;
            }
        }
        for (int i = 0; i < SECTIONS.length; i++) {
            if (ui.pill(x, y + i * 34, listW, 30, strings.get(SECTIONS[i]), i == current)) {
                scroll = Math.min(tops[i], max);
            }
        }
    }

    /** Records where a section starts and draws its title; returns the y of its first row. */
    private float heading(Ui ui, int section, float x, float y, float w) {
        tops[section] = y - viewTop + scroll;
        ui.text(ui.bold, 15, x, y, Ui.FG, strings.get(SECTIONS[section]));
        ui.rect(x, y + 26, w, 1, Ui.LINE);
        return y + 40;
    }

    private void changed() {
        overlay.saveSettings();
    }

    void flush() {
        if (hudSliderMoved) {
            changed();
            hudSliderMoved = false;
        }
    }

    private float interfaceSection(Ui ui, float x, float y, float w) {
        y = heading(ui, INTERFACE, x, y, w);
        int auto = overlay.autoScalePercent();
        ui.text(ui.regular, 13, x, y + 4, Ui.MUTED, strings.get("settings.scale"));
        y += 28;
        // Changing the scale moves the slider under the pointer, so while dragging only the label
        // follows the pointer and the scale is applied on release.
        boolean dragging = ui.dragging(this);
        int shown = dragging ? draggedScale : Math.max(Options.MIN_SCALE,
                Math.min(Options.MAX_SCALE, options.uiScale > 0 ? options.uiScale : auto));
        String autoLabel = strings.format("settings.scaleAuto", auto);
        float aw = ui.measure(ui.bold, 13, autoLabel) + 24;
        if (ui.pill(x, y, aw, 28, autoLabel, options.uiScale == 0 && !dragging)) {
            overlay.setUiScale(0);
        }
        float sx = x + aw + 16;
        float sw = Math.min(260, w - aw - 16 - 56);
        int value = Math.round(ui.slider(this, sx, y + 6, sw,
                Options.MIN_SCALE, Options.MAX_SCALE, 5, shown));
        if (ui.dragging(this)) {
            draggedScale = value;
        } else if (dragging) {
            overlay.setUiScale(draggedScale);
        } else if (value != shown) {
            overlay.setUiScale(value);
        }
        ui.text(ui.bold, 13, sx + sw + 12, y + 6, Ui.FG, ui.dragging(this) ? draggedScale + "%" : shown + "%");
        ui.textFit(ui.regular, 12, x, y + 40, w, Ui.FAINT, strings.get("settings.scaleHint"));
        return y + 72;
    }

    private float hudSection(Ui ui, float x, float y, float w) {
        y = heading(ui, HUD, x, y, w);
        boolean hud = ui.checkbox(x, y + 4, strings.get("perf.hud"), options.hud);
        if (hud != options.hud) {
            options.hud = hud;
            changed();
        }
        String presets = strings.get("hud.presets");
        float toggleW = 24 + ui.measure(ui.regular, 13, strings.get("perf.hud"));
        float presetsW = ui.measure(ui.regular, 13, presets) + 10 + 3 * 34 + 30;
        float cx = x + toggleW + 40;
        if (cx + presetsW > x + w) {
            cx = x;
            y += 34;
        }
        ui.text(ui.regular, 13, cx, y + 5, Ui.MUTED, presets);
        cx += ui.measure(ui.regular, 13, presets) + 10;
        String[] arrows = {"↖", "↗", "↙", "↘"};
        for (int i = 0; i < 4; i++) {
            boolean here = options.hudX == i % 2 && options.hudY == i / 2;
            if (ui.pill(cx + i * 34, y, 30, 26, arrows[i], here) && !here) {
                options.hudX = i % 2;
                options.hudY = i / 2;
                changed();
            }
        }
        String edit = strings.get("hud.edit");
        float ew = ui.buttonWidth(edit);
        float ex = cx + 3 * 34 + 30 + 16;
        if (ex + ew > x + w) {
            ex = x;
            y += 34;
        } else {
            ex = x + w - ew;
        }
        if (ui.button(ex, y - 1, ew, 28, edit, false)) {
            overlay.startHudEdit();
        }
        y += 44;

        float labelW = 150;
        float sw = Math.min(260, w - labelW - 60);
        ui.text(ui.regular, 13, x, y + 1, Ui.MUTED, strings.get("hud.size"));
        options.hudSize = hudSlider(ui, hudSizeSlider, x + labelW, y, sw, Options.MIN_HUD_SIZE, Options.MAX_HUD_SIZE,
                5, options.hudSize);
        y += 30;
        ui.text(ui.regular, 13, x, y + 1, Ui.MUTED, strings.get("hud.opacity"));
        options.hudOpacity = hudSlider(ui, hudOpacitySlider, x + labelW, y, sw, Options.MIN_HUD_OPACITY, 100, 5,
                options.hudOpacity);
        y += 30;
        if (hudSliderMoved && !ui.dragging(hudSizeSlider) && !ui.dragging(hudOpacitySlider)) {
            flush();
        }

        ui.text(ui.regular, 13, x, y + 1, Ui.MUTED, strings.get("hud.metrics"));
        float mx = x + labelW;
        float my = y;
        String[] keys = {"perf.fps", "perf.frame", "perf.low", "perf.heap", "perf.cpu", "perf.gc", "perf.graphShort"};
        boolean[] values = {options.hudFps, options.hudFrame, options.hudLow, options.hudHeap, options.hudCpu,
            options.hudGc, options.hudGraph};
        boolean[] next = values.clone();
        for (int i = 0; i < keys.length; i++) {
            String label = strings.get(keys[i]);
            float bw = 24 + ui.measure(ui.regular, 13, label) + 20;
            if (mx + bw > x + w && mx > x + labelW) {
                mx = x + labelW;
                my += 26;
            }
            next[i] = ui.checkbox(mx, my, label, values[i]);
            mx += bw;
        }
        if (!java.util.Arrays.equals(values, next)) {
            options.hudFps = next[0];
            options.hudFrame = next[1];
            options.hudLow = next[2];
            options.hudHeap = next[3];
            options.hudCpu = next[4];
            options.hudGc = next[5];
            options.hudGraph = next[6];
            changed();
        }
        return my + 36;
    }

    /** A percent slider with its value to the right; sets {@link #hudSliderMoved} when it changes. */
    private int hudSlider(Ui ui, Object id, float x, float y, float w, int min, int max, int step, int value) {
        int moved = Math.round(ui.slider(id, x, y, w, min, max, step, value));
        if (moved != value) {
            hudSliderMoved = true;
        }
        ui.text(ui.bold, 13, x + w + 12, y, Ui.FG, moved + "%");
        return moved;
    }

    /** A seconds slider with its value to the right; sets {@link #toastSliderMoved} when it changes. */
    private int toastSlider(Ui ui, Object id, float x, float y, float w, int min, int max, int value) {
        int moved = Math.round(ui.slider(id, x, y, w, min, max, 1, value));
        if (moved != value) {
            toastSliderMoved = true;
        }
        ui.text(ui.bold, 13, x + w + 12, y, Ui.FG, moved + " " + strings.get("toast.sec"));
        return moved;
    }

    /** One notification type: a checkbox, "same as above" and its own lifetime slider. */
    private float toastKindRow(Ui ui, ToastKind kind, float x, float y, float w) {
        int i = kind.ordinal();
        boolean shown = ui.checkbox(x, y + 2, strings.get("toast.kind." + kind.id), options.toastShown[i]);
        if (shown != options.toastShown[i]) {
            options.toastShown[i] = shown;
            changed();
        }
        float px = x + 190;
        String same = strings.get("toast.same");
        float pw = ui.measure(ui.bold, 13, same) + 24;
        int own = options.toastKindSeconds[i];
        if (ui.pill(px, y, pw, 28, same, own == 0) && own != 0) {
            options.toastKindSeconds[i] = 0;
            changed();
        }
        if (toastKindSliders[i] == null) {
            toastKindSliders[i] = new Object();
        }
        float sx = px + pw + 12;
        float sw = Math.max(60, Math.min(160, x + w - sx - 56));
        int shownSeconds = own > 0 ? own : options.toastSeconds;
        int moved = toastSlider(ui, toastKindSliders[i], sx, y + 6, sw, 1, Options.MAX_KIND_TOAST_SECONDS,
                shownSeconds);
        if (moved != shownSeconds) {
            options.toastKindSeconds[i] = moved;
        }
        return y + 32;
    }

    private float notifySection(Ui ui, float x, float y, float w) {
        y = heading(ui, NOTIFY, x, y, w);
        float labelW = 150;
        float sw = Math.min(260, w - labelW - 60);
        ui.text(ui.regular, 13, x, y + 1, Ui.MUTED, strings.get("toast.duration"));
        options.toastSeconds = toastSlider(ui, toastSlider, x + labelW, y, sw, Options.MIN_TOAST_SECONDS,
                Options.MAX_TOAST_SECONDS, options.toastSeconds);
        y += 36;
        ui.text(ui.regular, 13, x, y + 4, Ui.MUTED, strings.get("toast.kinds"));
        y += 30;
        for (ToastKind kind : ToastKind.values()) {
            if (kind != ToastKind.TEST) {
                y = toastKindRow(ui, kind, x, y, w);
            }
        }
        y += 10;

        ui.text(ui.regular, 13, x, y + 4, Ui.MUTED, strings.get("toast.position"));
        y += 30;
        String[] keys = {"toast.center", "toast.topLeft", "toast.topRight", "toast.bottomLeft", "toast.bottomRight"};
        float px = x;
        float py = y;
        for (int i = 0; i < keys.length; i++) {
            String label = strings.get(keys[i]);
            float pw = ui.measure(ui.bold, 13, label) + 24;
            if (px + pw > x + w && px > x) {
                px = x;
                py += 32;
            }
            if (ui.pill(px, py, pw, 28, label, options.toastPos == i) && options.toastPos != i) {
                options.toastPos = i;
                changed();
            }
            px += pw + 4;
        }
        y = py + 40;

        // A small screen with a mark where the toasts go.
        float dw = 112;
        float dh = 64;
        ui.panel(x, y, dw, dh, 6, Ui.BASE, Ui.LINE);
        float[][] marks = {{dw / 2 - 11, dh / 2 + 8}, {6, 6}, {dw - 28, 6}, {6, dh - 20}, {dw - 28, dh - 20}};
        for (int i = 0; i < marks.length; i++) {
            ui.round(x + marks[i][0], y + marks[i][1], 22, 14, 3, i == options.toastPos ? Ui.ACCENT : Ui.LINE);
        }
        String test = strings.get("toast.test");
        if (ui.button(x + dw + 20, y + (dh - 30) / 2, ui.buttonWidth(test), 30, test, false)) {
            overlay.toast(ToastKind.TEST, strings.get("toast.test"), strings.get("toast.testBody"));
        }
        // Sliders save once they are let go, not on every step of the drag.
        if (toastSliderMoved && !ui.dragging(toastSlider)) {
            boolean dragging = false;
            for (Object id : toastKindSliders) {
                dragging |= id != null && ui.dragging(id);
            }
            if (!dragging) {
                toastSliderMoved = false;
                changed();
            }
        }
        return y + dh + 24;
    }

    private float catSection(Ui ui, float x, float y, float w) {
        y = heading(ui, CAT, x, y, w);
        boolean cat = ui.checkbox(x, y + 4, strings.get("cat.toggle"), options.cat);
        if (cat != options.cat) {
            options.cat = cat;
            changed();
        }
        return y + 48;
    }

    private float recordingSection(Ui ui, float x, float y, float w) {
        y = heading(ui, RECORDING, x, y, w);
        return recordingView.recording(ui, x, y, w) + 16;
    }

    private float hotkeysSection(Ui ui, float x, float y, float w) {
        y = heading(ui, HOTKEYS, x, y, w);
        return recordingView.hotkeys(ui, x, y, w) + 16;
    }

    private float browserSection(Ui ui, float x, float y, float w) {
        y = heading(ui, BROWSER, x, y, w);
        ui.text(ui.regular, 13, x, y + 4, Ui.MUTED, strings.get("settings.browserHome"));
        y += 26;
        if (ui.field(homeEdit, x, y, Math.min(420, w), 32, false, "https://www.google.com/")) {
            options.browserHome = homeEdit.value();
            changed();
        }
        y += 44;
        ui.text(ui.regular, 13, x, y + 4, Ui.MUTED, strings.get("settings.browserSearch"));
        float px = x + ui.measure(ui.regular, 13, strings.get("settings.browserSearch")) + 16;
        for (String[] engine : BrowserView.ENGINES) {
            float pw = ui.measure(ui.bold, 13, engine[1]) + 20;
            if (ui.pill(px, y, pw, 26, engine[1], engine[0].equals(options.browserSearch))) {
                options.browserSearch = engine[0];
                changed();
            }
            px += pw + 4;
        }
        y += 40;
        boolean bar = ui.checkbox(x, y, strings.get("settings.browserBar"), options.bookmarksBar);
        if (bar != options.bookmarksBar) {
            options.bookmarksBar = bar;
            changed();
        }
        y += 32;
        String clear = strings.get("settings.browserClear");
        float bw = ui.buttonWidth(clear);
        if (ui.button(x, y, bw, 32, clear, false)) {
            clearFailed = !overlay.clearBrowserCookies();
            clearedAt = System.currentTimeMillis();
        }
        if (clearedAt != 0 && System.currentTimeMillis() - clearedAt < 4000) {
            ui.text(ui.regular, 13, x + bw + 12, y + 7, clearFailed ? Ui.DANGER : Ui.OK,
                    strings.get(clearFailed ? "settings.browserStopped" : "settings.browserCleared"));
        }
        y += 48;
        y = linksAndProtection(ui, x, y, w);
        return browserImportSection(ui, x, y, w);
    }

    /** Where the game's links open, and the page protection switches with the state of the filter lists. */
    private float linksAndProtection(Ui ui, float x, float y, float w) {
        String links = strings.get("settings.browserLinks");
        ui.text(ui.regular, 13, x, y + 4, Ui.MUTED, links);
        float px = x + ui.measure(ui.regular, 13, links) + 16;
        String[] names = {strings.get("settings.linksOverlay"), strings.get("settings.linksSystem")};
        for (int i = 0; i < names.length; i++) {
            float pw = ui.measure(ui.bold, 13, names[i]) + 20;
            if (ui.pill(px, y, pw, 26, names[i], (i == 0) == options.linksInOverlay)) {
                options.linksInOverlay = i == 0;
                changed();
            }
            px += pw + 4;
        }
        y += 48;
        ui.text(ui.bold, 14, x, y, Ui.FG, strings.get("settings.protection"));
        y += 24;
        ui.textFit(ui.regular, 12, x, y, w, Ui.FAINT, strings.get("settings.protectHint"));
        y += 26;
        boolean block = ui.checkbox(x, y, strings.get("settings.protectBlock"), options.protectBlock);
        y += 28;
        boolean cosmetic = ui.checkbox(x, y, strings.get("settings.protectCosmetic"), options.protectCosmetic);
        y += 28;
        boolean cookies = ui.checkbox(x, y, strings.get("settings.protectCookies"), options.protectCookies);
        y += 28;
        boolean youtube = ui.checkbox(x, y, strings.get("settings.protectYoutube"), options.protectYoutube);
        y += 28;
        boolean dark = ui.checkbox(x, y, strings.get("settings.protectDark"), options.protectDark);
        y += 32;
        if (block != options.protectBlock || cosmetic != options.protectCosmetic || cookies != options.protectCookies
                || youtube != options.protectYoutube || dark != options.protectDark) {
            options.protectBlock = block;
            options.protectCosmetic = cosmetic;
            options.protectCookies = cookies;
            options.protectYoutube = youtube;
            options.protectDark = dark;
            changed();
        }
        Protection protection = overlay.protection();
        for (Protection.Info info : protection.status()) {
            String state = info.updated == 0 ? strings.get("settings.protectMissing") : Protection.date(info.updated);
            String line = strings.format("settings.protectList", info.name, info.network, info.cosmetic, info.scriptlets, state);
            ui.textFit(ui.regular, 12, x, y, w, info.error.isEmpty() ? Ui.FAINT : Ui.DANGER, line);
            y += 18;
        }
        y += 8;
        boolean updating = protection.updating();
        String update = strings.get(updating ? "settings.protectUpdating" : "settings.protectUpdate");
        if (ui.button(x, y, ui.buttonWidth(update), 32, update, false) && !updating) {
            protection.updateNow();
        }
        return y + 48;
    }

    /** "Import sign-ins": an allowlist, the detected browsers and profiles as pills, and an import button. */
    private float browserImportSection(Ui ui, float x, float y, float w) {
        ui.rect(x, y, w, 1, Ui.LINE);
        y += 20;
        ui.text(ui.bold, 14, x, y, Ui.FG, strings.get("settings.browserImport"));
        y += 24;
        ui.textFit(ui.regular, 12, x, y, w, Ui.FAINT, strings.get("settings.browserImportHint"));
        y += 24;
        ui.text(ui.regular, 13, x, y + 4, Ui.MUTED, strings.get("settings.browserImportAllowlist"));
        y += 26;
        if (ui.field(importAllowlist, x, y, Math.min(420, w), 32, false, "")) {
            options.browserImportAllowlist = importAllowlist.value();
            changed();
        }
        y += 44;

        if (!importSourcesRequested) {
            importSourcesRequested = true;
            overlay.detectBrowserLogins(sources -> {
                importSources = sources;
                if (!sources.isEmpty()) {
                    importSource = sources.get(0);
                    importProfile = importSource.profiles.get(0);
                }
            });
        }
        if (importSources == null) {
            ui.textFit(ui.regular, 13, x, y, w, Ui.FAINT, strings.get("settings.browserImportScanning"));
            return y + 32;
        }
        if (importSources.isEmpty()) {
            ui.textFit(ui.regular, 13, x, y, w, Ui.FAINT, strings.get("settings.browserImportNone"));
            return y + 32;
        }

        float px = x;
        for (BrowserLogins.Source source : importSources) {
            float pw = ui.measure(ui.bold, 13, source.displayName) + 20;
            if (px + pw > x + w && px > x) {
                px = x;
                y += 32;
            }
            if (ui.pill(px, y, pw, 28, source.displayName, source == importSource) && source != importSource) {
                importSource = source;
                importProfile = source.profiles.get(0);
            }
            px += pw + 4;
        }
        y += 36;
        if (importSource.profiles.size() > 1) {
            px = x;
            for (BrowserLogins.Profile profile : importSource.profiles) {
                float pw = ui.measure(ui.bold, 13, profile.name) + 20;
                if (px + pw > x + w && px > x) {
                    px = x;
                    y += 30;
                }
                if (ui.pill(px, y, pw, 26, profile.name, profile == importProfile) && profile != importProfile) {
                    importProfile = profile;
                }
                px += pw + 4;
            }
            y += 36;
        }

        String label = strings.get(importRunning ? "settings.browserImportRunning" : "settings.browserImportButton");
        float bw = ui.buttonWidth(label);
        if (ui.button(x, y, bw, 32, label, false) && !importRunning) {
            importRunning = true;
            BrowserLogins.Source source = importSource;
            BrowserLogins.Profile profile = importProfile;
            overlay.importBrowserLogins(source, profile, message -> {
                importRunning = false;
                importMessage = message;
                importMessageAt = System.currentTimeMillis();
            });
        }
        if (!importMessage.isEmpty() && System.currentTimeMillis() - importMessageAt < 8000) {
            ui.textFit(ui.regular, 13, x + bw + 12, y + 7, w - bw - 12, Ui.MUTED, importMessage);
        }
        return y + 48;
    }

    private float hardwareSection(Ui ui, float x, float y, float w) {
        y = heading(ui, HARDWARE, x, y, w);
        boolean on = ui.checkbox(x, y + 2, strings.get("hw.toggle"), options.fakeHardware);
        if (on != options.fakeHardware) {
            options.fakeHardware = on;
            changed();
        }
        y += 40;
        float labelW = 150;
        float fw = Math.min(320, w - labelW);
        if (hardwareRow(ui, x, y, labelW, fw, "hw.cpu", fakeCpu)) {
            options.fakeCpu = fakeCpu.value();
            changed();
        }
        y += 40;
        if (hardwareRow(ui, x, y, labelW, fw, "hw.threads", fakeThreads)) {
            options.fakeThreads = number(fakeThreads, options.fakeThreads, 1024);
            changed();
        }
        y += 40;
        if (hardwareRow(ui, x, y, labelW, fw, "hw.gpu", fakeGpu)) {
            options.fakeGpu = fakeGpu.value();
            changed();
        }
        y += 40;
        if (hardwareRow(ui, x, y, labelW, fw, "hw.vendor", fakeVendor)) {
            options.fakeVendor = fakeVendor.value();
            changed();
        }
        y += 40;
        if (hardwareRow(ui, x, y, labelW, fw, "hw.memory", fakeMemory)) {
            options.fakeMemory = number(fakeMemory, options.fakeMemory, 16777216);
            changed();
        }
        y += 40;
        ui.textFit(ui.regular, 12, x, y, w, Ui.FAINT, strings.get("hw.hint"));
        return y + 40;
    }

    private boolean hardwareRow(Ui ui, float x, float y, float labelW, float fieldW, String label, TextEdit edit) {
        ui.text(ui.regular, 13, x, y + 8, Ui.MUTED, strings.get(label));
        return ui.field(edit, x + labelW, y, fieldW, 30, false, "");
    }

    /** The field's number when it is a whole number from 1 to {@code max}; otherwise the old value. */
    private static int number(TextEdit edit, int old, int max) {
        try {
            int value = Integer.parseInt(edit.value().trim());
            return value >= 1 && value <= max ? value : old;
        } catch (NumberFormatException e) {
            return old;
        }
    }
}
