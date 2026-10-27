package dev.k0t1k.overlay.impl;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** The notes tab: a list on the left, the selected note's editor on the right. */
final class NotesView {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    private final Notes notes;
    private final Strings strings;
    private final ZoneId zone = ZoneId.systemDefault();
    private Notes.Note selected;
    private Notes.Note confirmDelete;
    private float listScroll;

    NotesView(Notes notes, Strings strings) {
        this.notes = notes;
        this.strings = strings;
    }

    void draw(Ui ui, float x, float y, float w, float h) {
        if (selected == null && !notes.notes.isEmpty()) {
            selected = notes.notes.get(0);
        }
        float listW = Math.min(240, w * 0.32f);
        if (ui.button(x, y, listW, 34, strings.get("notes.new"), true)) {
            selected = notes.create();
            ui.focus = selected.edit;
        }

        float listY = y + 46;
        float listH = h - 46;
        ui.panel(x, listY, listW, listH, 10, Ui.BASE, Ui.LINE);
        float itemH = 50;
        listScroll -= ui.takeScroll(x, listY, listW, listH) * itemH;
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, notes.notes.size() * itemH + 8 - listH)));
        ui.clip(x + 1, listY + 1, listW - 2, listH - 2);
        if (notes.notes.isEmpty() && notes.loaded) {
            ui.text(ui.regular, 13, x + 14, listY + 14, Ui.FAINT, strings.get("notes.empty"));
        }
        boolean inList = ui.hover(x, listY, listW, listH);
        for (int i = 0; i < notes.notes.size(); i++) {
            Notes.Note note = notes.notes.get(i);
            float iy = listY + 4 + i * itemH - listScroll;
            if (iy + itemH < listY || iy > listY + listH) {
                continue;
            }
            boolean active = note == selected;
            boolean hot = inList && ui.hover(x + 4, iy, listW - 8, itemH - 4);
            if (active || hot) {
                ui.round(x + 4, iy, listW - 8, itemH - 4, 7, active ? 0x26a259f7 : Ui.RAISED);
            }
            if (active) {
                ui.round(x + 4, iy + 10, 3, itemH - 24, 1.5f, Ui.ACCENT);
            }
            String title = note.title();
            ui.textFit(ui.bold, 13, x + 16, iy + 7, listW - 30, title.isEmpty() ? Ui.FAINT : Ui.FG,
                    title.isEmpty() ? strings.get("notes.untitled") : title);
            ui.text(ui.regular, 11, x + 16, iy + 26, Ui.FAINT, WHEN.format(Instant.ofEpochMilli(note.modified).atZone(zone)));
            if (hot && ui.clickedIn(x + 4, iy, listW - 8, itemH - 4)) {
                selected = note;
                confirmDelete = null;
            }
        }
        ui.unclip();

        float ex = x + listW + 14;
        float ew = w - listW - 14;
        if (selected == null) {
            String pick = strings.get("notes.pick");
            ui.text(ui.regular, 14, ex + (ew - ui.measure(ui.regular, 14, pick)) / 2, y + h / 2 - 10, Ui.FAINT, pick);
            return;
        }
        ui.text(ui.regular, 12, ex + 2, y + 10, Ui.FAINT, strings.get("notes.saved"));
        String label = confirmDelete == selected ? strings.get("notes.confirm") : strings.get("notes.delete");
        float bw = ui.buttonWidth(label);
        boolean hotDelete = ui.hover(ex + ew - bw, y, bw, 34);
        ui.panel(ex + ew - bw, y, bw, 34, 7, hotDelete ? 0x40f87171 : Ui.RAISED, confirmDelete == selected ? Ui.DANGER : Ui.LINE);
        ui.text(ui.bold, 13, ex + ew - bw + 12, y + (34 - ui.lineHeight(ui.bold, 13)) / 2, Ui.DANGER, label);
        if (ui.clickedIn(ex + ew - bw, y, bw, 34)) {
            if (confirmDelete == selected) {
                notes.delete(selected);
                selected = notes.notes.isEmpty() ? null : notes.notes.get(0);
                confirmDelete = null;
                ui.focus = null;
                return;
            }
            confirmDelete = selected;
        }

        if (ui.field(selected.edit, ex, y + 46, ew, h - 46, true, strings.get("notes.placeholder"))) {
            notes.changed(selected);
            confirmDelete = null;
            // Keep the list newest first.
            if (notes.notes.indexOf(selected) > 0) {
                notes.notes.remove(selected);
                notes.notes.add(0, selected);
            }
        }
        notes.flush(false);
    }
}
