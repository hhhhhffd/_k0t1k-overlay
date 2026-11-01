package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The recording and hotkey sections of the settings tab; {@link SettingsView} places them. Choices are
 * rows of pills, saved when picked; sliders save when released.
 */
final class RecordingView {
    private static final String[] CONTAINERS = {"mkv", "mp4", "mov"};
    private static final String[] RESOLUTIONS = {"source", "1080", "720"};
    private static final String[] QUALITIES = {"low", "medium", "high", "custom"};
    private static final int[] FPS = {30, 60};
    private static final int[] WEBP_FPS = {15, 20, 25, 30, 50, 60};
    /** 0 keeps the clip's width. */
    private static final int[] WEBP_WIDTH = {480, 640, 960, 1280, 0};
    private static final int[] WEBP_SECONDS = {5, 10, 15, 30};

    private final Overlay overlay;
    private final Options options;
    private final Strings strings;
    private final TextEdit folderEdit;
    private final TextEdit pathEdit;
    private final Object bitrateSlider = new Object();
    private final Object lengthSlider = new Object();
    private final Object webpSlider = new Object();
    private final Object[] volumeSliders = {new Object(), new Object(), new Object()};
    /** A setting changed and is saved once no slider is being dragged. */
    private boolean dirty;
    private boolean dragged;
    /** How tall the last row of pills was. */
    private float rowHeight;
    /** Which hotkey waits for a key: 0 open, 1 record, 2 save, -1 none. */
    private int capturing = -1;
    /** Why the last rebinding was refused; shown under the hotkeys until the next attempt. */
    private String hotkeyProblem = "";

    RecordingView(Overlay overlay) {
        this.overlay = overlay;
        this.options = overlay.options();
        this.strings = options.strings;
        this.folderEdit = new TextEdit(options.outputDir);
        this.pathEdit = new TextEdit(options.ffmpegPath);
    }

    private void changed() {
        dirty = true;
    }

    // ---- recording ----

    float recording(Ui ui, float x, float y, float w) {
        dragged = false;
        Ffmpeg ffmpeg = overlay.ffmpeg();
        y = ffmpegRow(ui, ffmpeg, x, y, w);
        y = encoderRow(ui, ffmpeg, x, y, w);

        y = label(ui, "rec.container", x, y);
        int container = pills(ui, x, y, w, new String[] {"MKV", "MP4", "MOV"}, indexOf(CONTAINERS, options.container));
        if (!CONTAINERS[container].equals(options.container)) {
            options.container = CONTAINERS[container];
            changed();
        }
        y += rowHeight + 4;
        if ("mkv".equals(options.container)) {
            boolean remux = ui.checkbox(x, y, strings.get("rec.remux"), options.remux);
            if (remux != options.remux) {
                options.remux = remux;
                changed();
            }
            y += 26;
        }
        ui.textFit(ui.regular, 12, x, y, w, Ui.FAINT, strings.get("rec.containerHint"));
        y += 28;

        y = label(ui, "rec.fps", x, y);
        int fps = pills(ui, x, y, w, new String[] {"30", "60"}, options.fps == 60 ? 1 : 0);
        if (FPS[fps] != options.fps) {
            options.fps = FPS[fps];
            changed();
        }
        y += rowHeight + 8;

        y = label(ui, "rec.resolution", x, y);
        int resolution = pills(ui, x, y, w,
                new String[] {strings.get("rec.source"), "1080p", "720p"}, indexOf(RESOLUTIONS, options.resolution));
        if (!RESOLUTIONS[resolution].equals(options.resolution)) {
            options.resolution = RESOLUTIONS[resolution];
            changed();
        }
        y += rowHeight + 8;

        y = label(ui, "rec.quality", x, y);
        int quality = pills(ui, x, y, w, new String[] {strings.get("rec.low"), strings.get("rec.medium"),
                strings.get("rec.high"), strings.get("rec.custom")}, indexOf(QUALITIES, options.quality));
        if (!QUALITIES[quality].equals(options.quality)) {
            options.quality = QUALITIES[quality];
            changed();
        }
        y += rowHeight + 4;
        if ("custom".equals(options.quality)) {
            float sw = Math.min(260, w - 120);
            int bitrate = Math.round(slider(ui, bitrateSlider, x, y - 3, sw, 1, 100, 1, options.bitrate));
            if (bitrate != options.bitrate) {
                options.bitrate = bitrate;
                changed();
            }
            ui.text(ui.bold, 13, x + sw + 12, y + 4, Ui.FG, strings.format("rec.mbit", options.bitrate));
            y += 30;
        }
        y += 8;

        y = label(ui, "rec.folder", x, y);
        float openW = ui.buttonWidth(strings.get("tools.openFolder"));
        if (ui.field(folderEdit, x, y, w - openW - 8, 32, false, options.recordingsFolder().toString())
                && Options.validFolder(folderEdit.value())) {
            options.outputDir = folderEdit.value(); // an invalid path is never saved
            changed();
        }
        if (ui.button(x + w - openW, y + 2, openW, 28, strings.get("tools.openFolder"), false)) {
            openFolder();
        }
        y += 48;
        if (!Options.validFolder(folderEdit.value())) {
            ui.textFit(ui.regular, 12, x, y - 12, w, Ui.DANGER, strings.get("rec.folderInvalid"));
            y += 18;
        }

        y = replayRow(ui, x, y, w);
        y = audioRows(ui, x, y, w);
        y = webpRows(ui, x, y, w);

        if (dirty && !dragged) {
            dirty = false;
            overlay.saveSettings();
        }
        return y;
    }

    private float ffmpegRow(Ui ui, Ffmpeg ffmpeg, float x, float y, float w) {
        y = label(ui, "rec.ffmpeg", x, y);
        int mode = pills(ui, x, y, w, new String[] {strings.get("rec.auto"), strings.get("rec.customPath")},
                "custom".equals(options.ffmpegMode) ? 1 : 0);
        String picked = mode == 1 ? "custom" : "auto";
        if (!picked.equals(options.ffmpegMode)) {
            options.ffmpegMode = picked;
            changed();
            ffmpeg.recheck();
        }
        y += rowHeight + 4;
        if (mode == 1) {
            float checkW = ui.buttonWidth(strings.get("rec.check"));
            if (ui.field(pathEdit, x, y, w - checkW - 8, 32, false, "ffmpeg")) {
                options.ffmpegPath = pathEdit.value();
                changed();
            }
            if (ui.button(x + w - checkW, y + 2, checkW, 28, strings.get("rec.check"), false)) {
                ffmpeg.recheck();
            }
            y += 40;
        }
        String status;
        int color = Ui.MUTED;
        Ffmpeg.State state = ffmpeg.state;
        if (state == Ffmpeg.State.DOWNLOADING) {
            status = strings.format("rec.downloading", Math.round(ffmpeg.progress * 100));
        } else if (ffmpeg.path() != null) {
            status = strings.format("rec.ffmpegReady", ffmpeg.path());
            color = Ui.OK;
        } else if (state == Ffmpeg.State.FAILED) {
            status = strings.format("rec.ffmpegFailed", ffmpeg.error);
            color = Ui.DANGER;
        } else {
            status = strings.get(mode == 1 ? "rec.ffmpegNotFound" : "rec.ffmpegMissing");
            color = Ui.WARN;
        }
        ui.textFit(ui.regular, 13, x, y, w, color, status);
        y += 24;
        boolean canDownload = mode == 0 && ffmpeg.canDownload() && ffmpeg.path() == null
                || mode == 0 && state == Ffmpeg.State.FAILED;
        if (canDownload && state != Ffmpeg.State.DOWNLOADING) {
            String label = strings.format("rec.download", ffmpeg.size());
            if (ui.button(x, y, ui.buttonWidth(label), 28, label, true)) {
                ffmpeg.download();
            }
            y += 36;
        } else if (mode == 0 && !ffmpeg.canDownload() && ffmpeg.path() == null) {
            ui.textFit(ui.regular, 12, x, y, w, Ui.FAINT, strings.get("rec.noBuild"));
            y += 24;
        }
        return y + 8;
    }

    private float encoderRow(Ui ui, Ffmpeg ffmpeg, float x, float y, float w) {
        y = label(ui, "rec.encoder", x, y);
        if (ffmpeg.probing() || ffmpeg.path() == null) {
            ui.text(ui.regular, 13, x, y + 4, Ui.FAINT, strings.get(ffmpeg.probing() ? "rec.probing" : "rec.noEncoders"));
            return y + 36;
        }
        List<String> names = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        names.add("auto");
        labels.add(strings.get("rec.auto"));
        for (String encoder : ffmpeg.encoders()) {
            names.add(encoder);
            labels.add(encoderLabel(encoder));
        }
        int current = Math.max(0, names.indexOf(options.encoder));
        int picked = pills(ui, x, y, w, labels.toArray(new String[0]), current);
        if (!names.get(picked).equals(options.encoder)) {
            options.encoder = names.get(picked);
            changed();
        }
        y += rowHeight + 2;
        String chosen = ffmpeg.chooseEncoder(options.encoder);
        ui.textFit(ui.regular, 12, x, y, w, Ui.FAINT, strings.format("rec.encoderUsed", encoderLabel(chosen)));
        return y + 28;
    }

    static String encoderLabel(String encoder) {
        if ("libx264".equals(encoder)) {
            return "x264 (CPU)";
        }
        String codec = encoder.startsWith("h264") ? "H.264" : encoder.startsWith("hevc") ? "HEVC" : "AV1";
        String api = encoder.endsWith("_nvenc") ? "NVENC" : encoder.endsWith("_amf") ? "AMF"
                : encoder.endsWith("_qsv") ? "QSV" : "VAAPI";
        return api + " " + codec;
    }

    private float replayRow(Ui ui, float x, float y, float w) {
        boolean on = ui.checkbox(x, y, strings.get("rec.replayBuffer"), options.replayBuffer);
        if (on != options.replayBuffer) {
            options.replayBuffer = on;
            changed();
        }
        y += 26;
        if (on) {
            float sw = Math.min(260, w - 120);
            int seconds = Math.round(slider(ui, lengthSlider, x, y - 3, sw, 15, 300, 5, options.replaySeconds));
            if (seconds != options.replaySeconds) {
                options.replaySeconds = seconds;
                changed();
            }
            ui.text(ui.bold, 13, x + sw + 12, y + 4, Ui.FG, strings.format("rec.seconds", options.replaySeconds));
            y += 28;
        }
        ui.textFit(ui.regular, 12, x, y, w, Ui.FAINT, strings.get("rec.replayHint"));
        String problem = overlay.recorder().bufferProblem();
        if (!problem.isEmpty()) {
            ui.textFit(ui.regular, 12, x, y + 18, w, Ui.DANGER, strings.format("rec.bufferStopped", problem));
            y += 18;
        }
        return y + 38;
    }

    private float audioRows(Ui ui, float x, float y, float w) {
        boolean audio = ui.checkbox(x, y, strings.get("rec.audio"), options.audio);
        if (audio != options.audio) {
            options.audio = audio;
            changed();
        }
        y += 28;
        if (!audio) {
            return y + 8;
        }
        boolean[] on = {options.audioGame, options.audioRest, options.audioMic};
        int[] volume = {options.volumeGame, options.volumeRest, options.volumeMic};
        String[] names = {"rec.audioGame", "rec.audioRest", "rec.audioMic"};
        AudioCapture.Kind[] kinds = AudioCapture.Kind.values();
        float labelWidth = 0;
        for (String name : names) {
            labelWidth = Math.max(labelWidth, ui.measure(ui.regular, 13, strings.get(name)));
        }
        for (int i = 0; i < 3; i++) {
            boolean unavailable = AudioCapture.failed(kinds[i]);
            boolean now = ui.checkbox(x + 20, y, strings.get(names[i]), on[i]);
            float sx = x + 20 + 24 + labelWidth + 20;
            float sw = Math.max(80, Math.min(220, x + w - sx - 56));
            int value = Math.round(slider(ui, volumeSliders[i], sx, y - 6, sw, 0, 200, 5, volume[i]));
            ui.text(ui.regular, 13, sx + sw + 10, y + 1, Ui.MUTED, value + "%");
            if (now != on[i] || value != volume[i]) {
                if (i == 0) {
                    options.audioGame = now;
                    options.volumeGame = value;
                } else if (i == 1) {
                    options.audioRest = now;
                    options.volumeRest = value;
                } else {
                    options.audioMic = now;
                    options.volumeMic = value;
                }
                changed();
            }
            y += 26;
            if (unavailable) {
                ui.textFit(ui.regular, 12, x + 20, y - 4, w - 20, Ui.WARN, strings.get("rec.audioUnavailable"));
                y += 18;
            }
        }
        ui.textFit(ui.regular, 12, x + 20, y, w - 20, Ui.FAINT, strings.get("rec.audioHint"));
        return y + 36;
    }

    private float webpRows(Ui ui, float x, float y, float w) {
        y = label(ui, "rec.webp", x, y);
        String[] fpsLabels = new String[WEBP_FPS.length];
        for (int i = 0; i < fpsLabels.length; i++) {
            fpsLabels[i] = WEBP_FPS[i] + " fps";
        }
        int fps = pills(ui, x, y, w, fpsLabels, Math.max(0, indexOf(WEBP_FPS, options.webpFps)));
        if (WEBP_FPS[fps] != options.webpFps) {
            options.webpFps = WEBP_FPS[fps];
            changed();
        }
        y += rowHeight + 4;
        String[] widthLabels = new String[WEBP_WIDTH.length];
        for (int i = 0; i < widthLabels.length; i++) {
            widthLabels[i] = WEBP_WIDTH[i] == 0 ? strings.get("rec.source") : WEBP_WIDTH[i] + " px";
        }
        int width = pills(ui, x, y, w, widthLabels, Math.max(0, indexOf(WEBP_WIDTH, options.webpWidth)));
        if (WEBP_WIDTH[width] != options.webpWidth) {
            options.webpWidth = WEBP_WIDTH[width];
            changed();
        }
        y += rowHeight + 4;
        String[] secondLabels = new String[WEBP_SECONDS.length];
        for (int i = 0; i < secondLabels.length; i++) {
            secondLabels[i] = WEBP_SECONDS[i] + " s";
        }
        int seconds = pills(ui, x, y, w, secondLabels, Math.max(0, indexOf(WEBP_SECONDS, options.webpSeconds)));
        if (WEBP_SECONDS[seconds] != options.webpSeconds) {
            options.webpSeconds = WEBP_SECONDS[seconds];
            changed();
        }
        y += rowHeight + 6;
        boolean lossless = ui.checkbox(x, y, strings.get("rec.webpLossless"), options.webpLossless);
        if (lossless != options.webpLossless) {
            options.webpLossless = lossless;
            changed();
        }
        y += 26;
        if (!lossless) {
            float sw = Math.min(260, w - 120);
            int quality = Math.round(slider(ui, webpSlider, x, y - 3, sw, 50, 100, 1, options.webpQuality));
            if (quality != options.webpQuality) {
                options.webpQuality = quality;
                changed();
            }
            ui.text(ui.bold, 13, x + sw + 12, y + 4, Ui.FG, strings.format("rec.webpQuality", options.webpQuality));
            y += 30;
        }
        ui.textFit(ui.regular, 12, x, y, w, Ui.FAINT, strings.get("rec.webpHint"));
        return y + 28;
    }

    private void openFolder() {
        Path folder = options.recordingsFolder();
        try {
            Files.createDirectories(folder);
            Links.folder(folder);
        } catch (IOException e) {
            overlay.report(e);
            overlay.toast(strings.get("error"), e.getMessage());
        }
    }

    // ---- hotkeys ----

    float hotkeys(Ui ui, float x, float y, float w) {
        if (!overlay.capturingKey()) {
            capturing = -1;
        }
        String[] names = {"hotkeys.open", "hotkeys.record", "hotkeys.save"};
        int[] current = {options.hotkeyOpen, options.hotkeyRecord, options.hotkeySave};
        int[] defaults = {Hotkeys.OPEN, Hotkeys.RECORD, Hotkeys.SAVE};
        for (int i = 0; i < 3; i++) {
            ui.text(ui.regular, 13, x, y + 5, Ui.MUTED, strings.get(names[i]));
            String shown = capturing == i ? strings.get("hotkeys.press") : Hotkeys.name(current[i]);
            float bw = Math.max(150, ui.buttonWidth(shown));
            float bx = x + 220;
            if (ui.button(bx, y, bw, 28, shown, capturing == i)) {
                int row = i;
                capturing = row;
                overlay.captureKey(combo -> {
                    capturing = -1;
                    if (combo != 0) {
                        setHotkey(row, combo);
                    }
                });
            }
            String reset = strings.get("hotkeys.reset");
            float rw = ui.buttonWidth(reset);
            if (current[i] != defaults[i] && ui.button(bx + bw + 8, y, rw, 28, reset, false)) {
                setHotkey(i, defaults[i]);
            }
            y += 38;
        }
        ui.textFit(ui.regular, 12, x, y, w, Ui.FAINT, strings.get("hotkeys.hint"));
        if (!hotkeyProblem.isEmpty()) {
            y += 18;
            ui.textFit(ui.regular, 12, x, y, w, Ui.DANGER, hotkeyProblem);
        }
        return y + 28;
    }

    /** Binds a combination to an action unless another action already has it (one of them would never fire). */
    private void setHotkey(int row, int combo) {
        String[] names = {"hotkeys.open", "hotkeys.record", "hotkeys.save"};
        int[] current = {options.hotkeyOpen, options.hotkeyRecord, options.hotkeySave};
        hotkeyProblem = "";
        for (int other = 0; other < current.length; other++) {
            if (other != row && current[other] == combo) {
                hotkeyProblem = strings.format("hotkeys.taken", strings.get(names[other]));
                return;
            }
        }
        if (row == 0) {
            options.hotkeyOpen = combo;
        } else if (row == 1) {
            options.hotkeyRecord = combo;
        } else {
            options.hotkeySave = combo;
        }
        overlay.saveSettings();
    }

    // ---- widgets ----

    private float label(Ui ui, String key, float x, float y) {
        ui.text(ui.regular, 13, x, y, Ui.MUTED, strings.get(key));
        return y + 22;
    }

    /** A slider that keeps the settings from being saved while it is dragged. */
    private float slider(Ui ui, Object id, float x, float y, float w, float min, float max, float step, float value) {
        float result = ui.slider(id, x, y + 6, w, min, max, step, value);
        dragged |= ui.dragging(id);
        return result;
    }

    /** A row of pills, wrapped to {@code w}; returns the picked index and sets {@link #rowHeight}. */
    private int pills(Ui ui, float x, float y, float w, String[] labels, int current) {
        int picked = current;
        float cx = x;
        float cy = y;
        for (int i = 0; i < labels.length; i++) {
            float pw = ui.measure(ui.bold, 13, labels[i]) + 22;
            if (cx > x && cx + pw > x + w) {
                cx = x;
                cy += 30;
            }
            if (ui.pill(cx, cy, pw, 26, labels[i], i == current)) {
                picked = i;
            }
            cx += pw + 4;
        }
        rowHeight = cy - y + 26;
        return picked;
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return -1;
    }
}
