package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.util.List;

/**
 * The tools tab, things players otherwise work out on paper: Overworld/Nether coordinates, stacks and
 * shulker boxes, timers and alarms, and the latest screenshots.
 */
final class ToolsView {
    private static final int[] QUICK_MINUTES = {5, 10, 20, 30, 60};

    private final Strings strings;
    private final Overlay overlay;
    private final GameState game;
    private final Timers timers;
    private final Screenshots screenshots;

    private final TextEdit overX = new TextEdit();
    private final TextEdit overZ = new TextEdit();
    private final TextEdit netherX = new TextEdit();
    private final TextEdit netherZ = new TextEdit();

    private final TextEdit shulkers = new TextEdit();
    private final TextEdit stacks = new TextEdit();
    private final TextEdit items = new TextEdit();
    private int stackSize = 64;

    private final TextEdit timerLabel = new TextEdit();
    private final TextEdit timerTime = new TextEdit();

    ToolsView(Strings strings, Overlay overlay, GameState game, Timers timers, Screenshots screenshots) {
        this.strings = strings;
        this.overlay = overlay;
        this.game = game;
        this.timers = timers;
        this.screenshots = screenshots;
    }

    void draw(Ui ui, float x, float y, float w, float h) {
        float gap = 14;
        float cw = (w - gap) / 2;
        float ch = (h - gap) / 2;
        coordinates(ui, x, y, cw, ch);
        stacks(ui, x + cw + gap, y, cw, ch);
        timers(ui, x, y + ch + gap, cw, ch);
        screenshots(ui, x + cw + gap, y + ch + gap, cw, ch);
    }

    private float card(Ui ui, float x, float y, float w, float h, String title) {
        ui.panel(x, y, w, h, 10, Ui.BASE, Ui.LINE);
        ui.text(ui.bold, 14, x + 16, y + 14, Ui.FG, title);
        return y + 42;
    }

    // ---- Overworld <-> Nether ----

    private void coordinates(Ui ui, float x, float y, float w, float h) {
        float cy = card(ui, x, y, w, h, strings.get("tools.coords"));
        float px = x + 16;
        float pw = w - 32;
        String where = !game.available ? strings.get("tools.noPosition")
                : !game.inWorld ? strings.get("tools.notInWorld")
                : strings.format("tools.position", dimensionLabel(game.dimension), game.blockPosition());
        float labelW = 96;
        float fw = (pw - labelW - 8) / 2;
        ui.text(ui.regular, 12, px + labelW, cy, Ui.FAINT, "X");
        ui.text(ui.regular, 12, px + labelW + fw + 8, cy, Ui.FAINT, "Z");
        cy += 16;
        ui.text(ui.bold, 13, px, cy + 7, Ui.OK, strings.get("tools.overworld"));
        boolean overChanged = ui.field(overX, px + labelW, cy, fw, 30, false, "0");
        overChanged |= ui.field(overZ, px + labelW + fw + 8, cy, fw, 30, false, "0");
        cy += 36;
        ui.text(ui.bold, 13, px, cy + 7, Ui.DANGER, strings.get("tools.nether"));
        boolean netherChanged = ui.field(netherX, px + labelW, cy, fw, 30, false, "0");
        netherChanged |= ui.field(netherZ, px + labelW + fw + 8, cy, fw, 30, false, "0");
        cy += 38;
        if (overChanged) {
            netherX.set(scaled(overX.value(), 1 / 8.0));
            netherZ.set(scaled(overZ.value(), 1 / 8.0));
        } else if (netherChanged) {
            overX.set(scaled(netherX.value(), 8));
            overZ.set(scaled(netherZ.value(), 8));
        }
        boolean canFill = game.available && game.inWorld;
        String fill = strings.get("tools.here");
        float bw = ui.buttonWidth(fill);
        if (canFill && ui.button(px, cy, bw, 30, fill, true)) {
            long bx = (long) Math.floor(game.x);
            long bz = (long) Math.floor(game.z);
            if ("the_nether".equals(game.dimension)) {
                netherX.set(Long.toString(bx));
                netherZ.set(Long.toString(bz));
                overX.set(Long.toString(bx * 8));
                overZ.set(Long.toString(bz * 8));
            } else {
                overX.set(Long.toString(bx));
                overZ.set(Long.toString(bz));
                netherX.set(scaled(overX.value(), 1 / 8.0));
                netherZ.set(scaled(overZ.value(), 1 / 8.0));
            }
        }
        float tx = canFill ? px + bw + 12 : px;
        ui.textFit(ui.regular, 12, tx, cy + (30 - ui.lineHeight(ui.regular, 12)) / 2, px + pw - tx, Ui.MUTED, where);
        if (canFill && ui.hover(tx, cy, px + pw - tx, 30) && ui.clickedIn(tx, cy, px + pw - tx, 30)) {
            ui.platform.setClipboard(game.blockPosition());
            overlay.toast(strings.get("chat.copied"), game.blockPosition());
        }
    }

    /** Multiplies a typed block coordinate, rounding down like the game; empty for anything else. */
    private static String scaled(String typed, double factor) {
        try {
            String value = typed.trim();
            return value.isEmpty() || "-".equals(value) ? "" : Long.toString((long) Math.floor(Long.parseLong(value) * factor));
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private String dimensionLabel(String dimension) {
        switch (dimension) {
            case "overworld":
                return strings.get("tools.overworld");
            case "the_nether":
                return strings.get("tools.nether");
            case "the_end":
                return strings.get("tools.end");
            default:
                return dimension;
        }
    }

    // ---- stacks and shulker boxes ----

    private void stacks(Ui ui, float x, float y, float w, float h) {
        float cy = card(ui, x, y, w, h, strings.get("tools.stacks"));
        float px = x + 16;
        float pw = w - 32;
        float sizeX = x + w - 16;
        for (int size : new int[] {1, 16, 64}) {
            String label = Integer.toString(size);
            float pwid = ui.measure(ui.bold, 13, label) + 22;
            sizeX -= pwid;
            if (ui.pill(sizeX, y + 9, pwid, 26, label, stackSize == size)) {
                stackSize = size;
            }
            sizeX -= 4;
        }
        ui.text(ui.regular, 12, sizeX - ui.measure(ui.regular, 12, strings.get("tools.stackSize")) - 6,
                y + 9 + (26 - ui.lineHeight(ui.regular, 12)) / 2, Ui.FAINT, strings.get("tools.stackSize"));

        float fw = (pw - 16) / 3;
        String[] labels = {strings.get("tools.shulkers"), strings.get("tools.stacksField"), strings.get("tools.items")};
        TextEdit[] fields = {shulkers, stacks, items};
        for (int i = 0; i < 3; i++) {
            float fx = px + i * (fw + 8);
            ui.text(ui.regular, 12, fx, cy, Ui.FAINT, labels[i]);
            ui.field(fields[i], fx, cy + 16, fw, 30, false, "0");
        }
        cy += 56;

        long total = count(shulkers) * 27L * stackSize + count(stacks) * stackSize + count(items);
        long perShulker = 27L * stackSize;
        String breakdown = strings.format("tools.breakdown", total / perShulker, total % perShulker / stackSize, total % stackSize);
        ui.text(ui.bold, 18, px, cy, Ui.FG, strings.format("tools.total", total));
        cy += 26;
        ui.textFit(ui.regular, 13, px, cy, pw, Ui.MUTED, breakdown);
        cy += 19;
        long chests = (total + 54L * stackSize - 1) / (54L * stackSize);
        long singles = (total + perShulker - 1) / perShulker;
        ui.textFit(ui.regular, 13, px, cy, pw, Ui.FAINT, strings.format("tools.storage", singles, chests));
    }

    private static long count(TextEdit edit) {
        try {
            String value = edit.value().trim();
            return value.isEmpty() ? 0 : Math.max(0, Math.min(1_000_000_000L, Long.parseLong(value)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- timers and alarms ----

    private void timers(Ui ui, float x, float y, float w, float h) {
        float cy = card(ui, x, y, w, h, strings.get("tools.timers"));
        float px = x + 16;
        float pw = w - 32;
        long now = System.currentTimeMillis();
        String start = strings.get("tools.start");
        float sw = ui.buttonWidth(start);
        float timeW = 110;
        float labelW = pw - timeW - sw - 16;
        ui.field(timerLabel, px, cy, labelW, 32, false, strings.get("tools.timerLabel"));
        ui.field(timerTime, px + labelW + 8, cy, timeW, 32, false, strings.get("tools.timerTime"));
        long due = Timers.parseDue(timerTime.value(), now);
        boolean submit = ui.button(px + pw - sw, cy, sw, 32, start, true);
        if ((ui.focus == timerLabel || ui.focus == timerTime) && pressed(ui, Keys.ENTER, Keys.KP_ENTER)) {
            submit = true;
        }
        if (submit) {
            if (due < 0) {
                overlay.toast(strings.get("tools.timers"), strings.get("tools.badTime"));
            } else {
                String label = timerLabel.value().trim();
                timers.add(label.isEmpty() ? strings.get("tools.timerDefault") : label, due, now);
                timerLabel.set("");
                timerTime.set("");
                ui.focus = null;
            }
        }
        cy += 40;
        float qx = px;
        for (int minutes : QUICK_MINUTES) {
            String label = strings.format("tools.minutes", minutes);
            float qw = ui.measure(ui.bold, 13, label) + 20;
            if (ui.pill(qx, cy, qw, 26, label, false)) {
                timerTime.set(Integer.toString(minutes));
            }
            qx += qw + 4;
        }
        cy += 34;

        if (timers.timers.isEmpty()) {
            ui.text(ui.regular, 13, px, cy + 4, Ui.FAINT, strings.get("tools.noTimers"));
            return;
        }
        float rowH = 30;
        float bottom = y + h - 10;
        for (Timers.Timer timer : new java.util.ArrayList<>(timers.timers)) {
            if (cy + rowH > bottom) {
                break;
            }
            long left = timer.due - now;
            float progress = timer.length <= 0 ? 1 : 1 - Math.max(0, left) / (float) timer.length;
            ui.round(px, cy, pw, rowH - 4, 6, Ui.RAISED);
            ui.round(px, cy + rowH - 6, pw * Math.max(0, Math.min(1, progress)), 2, 1, Ui.ACCENT);
            String time = Timers.remaining(left);
            float tw = ui.measure(ui.bold, 13, time);
            ui.textFit(ui.regular, 13, px + 10, cy + 5, pw - tw - 56, Ui.FG, timer.label);
            ui.text(ui.bold, 13, px + pw - tw - 36, cy + 5, Ui.ACCENT, time);
            boolean hot = ui.hover(px + pw - 28, cy, 28, rowH - 4);
            ui.text(ui.bold, 13, px + pw - 20, cy + 5, hot ? Ui.DANGER : Ui.FAINT, "×");
            if (hot && ui.clickedIn(px + pw - 28, cy, 28, rowH - 4)) {
                timers.remove(timer);
            }
            cy += rowH;
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

    // ---- screenshots ----

    /** The replay buffer row: on/off, format, save. Returns the y below it. */
    private float replay(Ui ui, float x, float y, float w) {
        Options options = overlay.options();
        boolean on = ui.checkbox(x, y + 6, strings.get("replay.toggle"), options.replay);
        if (on != options.replay) {
            options.replay = on;
            overlay.saveSettings();
        }
        float fx = x + ui.measure(ui.regular, 13, strings.get("replay.toggle")) + 40;
        String[] formats = {"video", "gif"};
        for (String format : formats) {
            String label = strings.get("replay." + format);
            float pw = ui.measure(ui.bold, 13, label) + 20;
            if (ui.pill(fx, y + 1, pw, 26, label, format.equals(options.replayFormat))) {
                options.replayFormat = format;
                overlay.saveSettings();
            }
            fx += pw + 4;
        }
        String save = strings.get("replay.save");
        float sw = ui.buttonWidth(save);
        if (ui.button(x + w - sw, y, sw, 28, save, overlay.replayAvailable())) {
            overlay.saveReplay();
        }
        String hint = overlay.replayAvailable() ? strings.get("replay.hint")
                : options.replay ? strings.get("replay.glOnly") : strings.get("replay.off");
        ui.textFit(ui.regular, 12, x, y + 34, w, Ui.FAINT, hint);
        return y + 56;
    }

    private void screenshots(Ui ui, float x, float y, float w, float h) {
        card(ui, x, y, w, h, strings.get("tools.screenshots"));
        float px = x + 16;
        float pw = w - 32;
        String open = strings.get("tools.openFolder");
        float ow = ui.buttonWidth(open);
        if (ui.button(x + w - 16 - ow, y + 8, ow, 28, open, false)) {
            try {
                java.nio.file.Files.createDirectories(screenshots.folder);
                Links.folder(screenshots.folder);
            } catch (IOException e) {
                overlay.report(e);
                overlay.toast(strings.get("error"), e.getMessage());
            }
        }
        float cy = replay(ui, px, y + 44, pw);
        List<String> recent = screenshots.recent;
        if (recent.isEmpty()) {
            List<int[]> lines = ui.wrap(ui.regular, 13, strings.get("tools.noShots"), pw);
            String text = strings.get("tools.noShots");
            for (int i = 0; i < lines.size(); i++) {
                ui.text(ui.regular, 13, px, cy + i * ui.lineHeight(ui.regular, 13), Ui.FAINT, text, lines.get(i)[0], lines.get(i)[1]);
            }
            return;
        }
        ui.text(ui.regular, 12, px, cy, Ui.FAINT, strings.get("tools.copyPath"));
        cy += 20;
        float rowH = 28;
        for (String name : recent) {
            if (cy + rowH > y + h - 8) {
                break;
            }
            boolean hot = ui.hover(px - 6, cy, pw + 12, rowH - 2);
            if (hot) {
                ui.round(px - 6, cy, pw + 12, rowH - 2, 5, Ui.RAISED);
            }
            ui.textFit(ui.regular, 13, px, cy + 5, pw, Ui.FG, name);
            if (hot && ui.clickedIn(px - 6, cy, pw + 12, rowH - 2)) {
                String path = screenshots.folder.resolve(name).toString();
                ui.platform.setClipboard(path);
                overlay.toast(strings.get("chat.copied"), path);
            }
            cy += rowH;
        }
    }
}
