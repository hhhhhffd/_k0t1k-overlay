package dev.k0t1k.overlay.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The colours of the interface: the launcher's resolved theme from {@code <dataDir>/theme.json}
 * (see INTEGRATION.md), or the launcher's default dark palette when the file is missing or invalid.
 * Only the launcher's tokens are stored; the overlay's other shades (hover, tints, the veil over the
 * game) are derived from them, so every theme, dark or light, gets a matching set.
 */
final class Theme {
    static final String FILE = "theme.json";

    /** The launcher's default dark theme (apps/app/src/style.css). */
    static final Theme DEFAULT = new Theme(true, 0xff0b0910, 0xff13101c, 0xff1b1728, 0xff251f37, 0xff2b2542,
            0xffece8f6, 0xffa199ba, 0xff6d6588, 0xffa259f7, 0xffffffff, 0xff4ade80, 0xfffbbf24, 0xfff87171);

    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*(\\d+)");
    private static final Pattern DARK = Pattern.compile("\"dark\"\\s*:\\s*(true|false)");
    private static final Pattern COLORS = Pattern.compile("\"colors\"\\s*:\\s*\\{([^}]*)\\}");
    private static final Pattern COLOR = Pattern.compile("\"([a-z-]+)\"\\s*:\\s*\"#([0-9a-fA-F]{6})\"");

    final boolean dark;
    final int base;
    final int surface;
    final int raised;
    final int hover;
    final int line;
    final int fg;
    final int muted;
    final int faint;
    final int accent;
    final int accentFg;
    final int ok;
    final int warn;
    final int danger;

    Theme(boolean dark, int base, int surface, int raised, int hover, int line, int fg, int muted, int faint,
            int accent, int accentFg, int ok, int warn, int danger) {
        this.dark = dark;
        this.base = base;
        this.surface = surface;
        this.raised = raised;
        this.hover = hover;
        this.line = line;
        this.fg = fg;
        this.muted = muted;
        this.faint = faint;
        this.accent = accent;
        this.accentFg = accentFg;
        this.ok = ok;
        this.warn = warn;
        this.danger = danger;
    }

    /**
     * Parses the launcher's theme.json. Returns null when it is not one (unknown version, no colours);
     * tokens that are missing fall back to the shades derived from the others, then to the defaults.
     */
    static Theme parse(String json) {
        Matcher version = VERSION.matcher(json);
        if (version.find() && !version.group(1).equals("1")) {
            return null;
        }
        Matcher block = COLORS.matcher(json);
        if (!block.find()) {
            return null;
        }
        java.util.Map<String, Integer> colors = new java.util.HashMap<>();
        Matcher color = COLOR.matcher(block.group(1));
        while (color.find()) {
            colors.put(color.group(1), 0xff000000 | Integer.parseInt(color.group(2), 16));
        }
        if (!colors.containsKey("base") && !colors.containsKey("surface") && !colors.containsKey("accent")) {
            return null;
        }
        Theme d = DEFAULT;
        int base = colors.getOrDefault("base", d.base);
        int surface = colors.getOrDefault("surface", base);
        int fg = colors.getOrDefault("fg", d.fg);
        int muted = colors.getOrDefault("muted", mix(fg, base, 0.35f));
        int accent = colors.getOrDefault("accent", d.accent);
        Matcher dark = DARK.matcher(json);
        boolean isDark = dark.find() ? dark.group(1).equals("true") : luminance(base) < 0.5f;
        return new Theme(isDark, base, surface,
                colors.getOrDefault("raised", mix(surface, fg, 0.06f)),
                colors.getOrDefault("hover", mix(surface, fg, 0.12f)),
                colors.getOrDefault("line", mix(surface, fg, 0.16f)),
                fg, muted,
                colors.getOrDefault("faint", mix(muted, base, 0.45f)),
                accent,
                colors.getOrDefault("accent-fg", luminance(accent) > 0.55f ? 0xff0a0a0a : 0xffffffff),
                colors.getOrDefault("ok", d.ok),
                colors.getOrDefault("warn", d.warn),
                colors.getOrDefault("danger", d.danger));
    }

    /** Reads and parses the theme file; null when it is missing or not a valid theme. */
    static Theme load(Path file) {
        try {
            return parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    // ---- colour maths on 0xAARRGGBB ----

    /** {@code a} replaced by {@code b} by the fraction {@code t}; opaque. */
    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xff) * (1 - t) + ((b >> 16) & 0xff) * t);
        int g = Math.round(((a >> 8) & 0xff) * (1 - t) + ((b >> 8) & 0xff) * t);
        int bl = Math.round((a & 0xff) * (1 - t) + (b & 0xff) * t);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }

    /** The colour with its alpha set to {@code alpha} (0 to 255). */
    static int alpha(int color, int alpha) {
        return alpha << 24 | (color & 0xffffff);
    }

    /** Relative luminance of the colour's RGB, 0 to 1. */
    static float luminance(int color) {
        return (0.2126f * ((color >> 16) & 0xff) + 0.7152f * ((color >> 8) & 0xff) + 0.0722f * (color & 0xff)) / 255f;
    }
}
