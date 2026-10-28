package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The mods tab: the instance's mods with a search, and what the selected one does. */
final class ModsView {
    private final Mods mods;
    private final Strings strings;
    private final Overlay overlay;
    private final TextEdit search = new TextEdit();
    private Mods.Mod selected;
    private float listScroll;

    ModsView(Mods mods, Strings strings, Overlay overlay) {
        this.mods = mods;
        this.strings = strings;
        this.overlay = overlay;
    }

    void draw(Ui ui, float x, float y, float w, float h) {
        if (!mods.scanned) {
            mods.scan();
        }
        float listW = Math.min(320, w * 0.38f);
        ui.field(search, x, y, listW, 34, false, strings.get("mods.search"));
        String query = search.value().trim();
        List<Mods.Mod> shown = new ArrayList<>();
        int disabled = 0;
        for (Mods.Mod mod : mods.mods) {
            if (!mod.enabled) {
                disabled++;
            }
            if (Mods.matches(mod, query)) {
                shown.add(mod);
            }
        }
        if (selected == null && !shown.isEmpty()) {
            selected = shown.get(0);
        }

        float listY = y + 46;
        float listH = h - 46;
        ui.panel(x, listY, listW, listH, 10, Ui.BASE, Ui.LINE);
        float itemH = 44;
        listScroll -= ui.takeScroll(x, listY, listW, listH) * itemH * 2;
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, shown.size() * itemH + 8 - listH)));
        ui.clip(x + 1, listY + 1, listW - 2, listH - 2);
        if (shown.isEmpty()) {
            ui.text(ui.regular, 13, x + 14, listY + 14, Ui.FAINT,
                    !mods.scanned ? strings.get("mods.loading") : mods.mods.isEmpty() ? strings.get("mods.none") : strings.get("chat.notFound"));
        }
        boolean inList = ui.hover(x, listY, listW, listH);
        for (int i = 0; i < shown.size(); i++) {
            Mods.Mod mod = shown.get(i);
            float iy = listY + 4 + i * itemH - listScroll;
            if (iy + itemH < listY || iy > listY + listH) {
                continue;
            }
            boolean active = mod == selected;
            boolean hot = inList && ui.hover(x + 4, iy, listW - 8, itemH - 4);
            if (active || hot) {
                ui.round(x + 4, iy, listW - 8, itemH - 4, 7, active ? 0x26a259f7 : Ui.RAISED);
            }
            ui.textFit(ui.bold, 13, x + 14, iy + 5, listW - 28, mod.enabled ? Ui.FG : Ui.FAINT, mod.name);
            String sub = (mod.version.isEmpty() ? mod.file : mod.version) + (mod.enabled ? "" : " · " + strings.get("mods.disabled"));
            ui.textFit(ui.regular, 11, x + 14, iy + 23, listW - 28, mod.enabled ? Ui.FAINT : Ui.WARN, sub);
            if (hot && ui.clickedIn(x + 4, iy, listW - 8, itemH - 4)) {
                selected = mod;
            }
        }
        ui.unclip();

        float dx = x + listW + 14;
        float dw = w - listW - 14;
        String count = strings.format("mods.count", mods.mods.size() - disabled, disabled);
        ui.text(ui.regular, 12, dx + 2, y + (34 - ui.lineHeight(ui.regular, 12)) / 2, Ui.FAINT, count);
        float refreshW = ui.buttonWidth(strings.get("mods.refresh"));
        if (ui.button(dx + dw - refreshW, y, refreshW, 34, strings.get("mods.refresh"), false)) {
            mods.scan();
        }
        ui.panel(dx, listY, dw, listH, 10, Ui.BASE, Ui.LINE);
        if (selected == null || !mods.mods.contains(selected)) {
            selected = null;
            return;
        }
        float px = dx + 18;
        float pw = dw - 36;
        float py = listY + 16;
        ui.textFit(ui.bold, 18, px, py, pw, Ui.FG, selected.name);
        py += 28;
        String meta = (selected.id.isEmpty() ? "" : selected.id + " · ") + (selected.version.isEmpty() ? "" : selected.version + " · ") + selected.file;
        ui.textFit(ui.regular, 12, px, py, pw, Ui.FAINT, meta);
        py += 26;
        ui.text(ui.bold, 12, px, py, Ui.MUTED, strings.get("mods.what"));
        py += 20;
        String description = selected.description.isEmpty() ? strings.get("mods.noDescription") : selected.description;
        float lh = ui.lineHeight(ui.regular, 14);
        float buttonsY = listY + listH - 50;
        List<int[]> lines = ui.wrap(ui.regular, 14, description, pw);
        ui.clip(px, py, pw, buttonsY - py - 8);
        for (int i = 0; i < lines.size(); i++) {
            int[] span = lines.get(i);
            ui.text(ui.regular, 14, px, py + i * lh, selected.description.isEmpty() ? Ui.FAINT : Ui.FG, description, span[0], span[1]);
        }
        ui.unclip();

        float bx = px;
        String modrinth = strings.get("mods.modrinth");
        float mw = ui.buttonWidth(modrinth);
        if (ui.button(bx, buttonsY, mw, 34, modrinth, true)) {
            open(modrinthSearch(selected));
        }
        bx += mw + 8;
        if (!selected.homepage.isEmpty()) {
            String home = strings.get("mods.homepage");
            float hw = ui.buttonWidth(home);
            if (ui.button(bx, buttonsY, hw, 34, home, false)) {
                open(selected.homepage);
            }
        }
    }

    private static String modrinthSearch(Mods.Mod mod) {
        try {
            return "https://modrinth.com/mods?q=" + URLEncoder.encode(mod.name, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void open(String url) {
        try {
            Links.browse(url);
            overlay.toast(strings.get("mods.opened"), url);
        } catch (IOException e) {
            overlay.report(e);
            overlay.toast(strings.get("error"), e.getMessage());
        }
    }
}
