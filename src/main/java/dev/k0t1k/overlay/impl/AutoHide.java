package dev.k0t1k.overlay.impl;

import java.util.Properties;

/**
 * How one element drawn over the game (the HUD, the toasts, the cat, the recording indicator) fades:
 * its own opacity, and rules that fade it out while the game is in a state where it gets in the way.
 */
final class AutoHide {
    static final int MIN_OPACITY = 10;
    /** Where an element that is only faded (not hidden) goes when the pointer is over it. */
    private static final float HOVER_LEVEL = 0.1f;
    private static final float FADE_MILLIS = 150;

    /** Percent, {@link #MIN_OPACITY} to 100. */
    int opacity = 100;
    boolean hoverHide;
    /** Whether the pointer over the element hides it completely instead of fading it to 10%. */
    boolean hoverFull;
    boolean menus;
    boolean f3;
    boolean playerList;

    private final boolean defaultHover;
    private final boolean defaultF3;
    private float level = 1;
    private long last;

    AutoHide(boolean defaultHover, boolean defaultF3) {
        this.defaultHover = defaultHover;
        this.defaultF3 = defaultF3;
        hoverHide = defaultHover;
        f3 = defaultF3;
    }

    /**
     * The multiplier (0 to 1) for everything the element draws this frame: its opacity times the fade,
     * which moves towards the target within {@link #FADE_MILLIS}.
     */
    float alpha(boolean pointerOver, boolean menu, boolean f3Shown, boolean listHeld, long now) {
        float target = 1;
        if (menus && menu || f3 && f3Shown || playerList && listHeld || hoverHide && hoverFull && pointerOver) {
            target = 0;
        } else if (hoverHide && pointerOver) {
            target = HOVER_LEVEL;
        }
        float step = last == 0 ? 1 : Math.min(1, (now - last) / FADE_MILLIS);
        last = now;
        level = level < target ? Math.min(target, level + step) : Math.max(target, level - step);
        return level * opacity / 100f;
    }

    void load(Properties saved, String prefix) {
        opacity = Options.intSetting(saved, prefix + "Opacity", 100, MIN_OPACITY, 100);
        hoverHide = Boolean.parseBoolean(saved.getProperty(prefix + "HoverHide", Boolean.toString(defaultHover)));
        hoverFull = Boolean.parseBoolean(saved.getProperty(prefix + "HoverFull", "false"));
        menus = Boolean.parseBoolean(saved.getProperty(prefix + "HideMenus", "false"));
        f3 = Boolean.parseBoolean(saved.getProperty(prefix + "HideF3", Boolean.toString(defaultF3)));
        playerList = Boolean.parseBoolean(saved.getProperty(prefix + "HideList", "false"));
    }

    void save(Properties out, String prefix) {
        out.setProperty(prefix + "Opacity", Integer.toString(opacity));
        out.setProperty(prefix + "HoverHide", Boolean.toString(hoverHide));
        out.setProperty(prefix + "HoverFull", Boolean.toString(hoverFull));
        out.setProperty(prefix + "HideMenus", Boolean.toString(menus));
        out.setProperty(prefix + "HideF3", Boolean.toString(f3));
        out.setProperty(prefix + "HideList", Boolean.toString(playerList));
    }
}
