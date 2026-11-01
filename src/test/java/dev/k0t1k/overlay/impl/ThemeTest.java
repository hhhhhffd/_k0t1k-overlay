package dev.k0t1k.overlay.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThemeTest {
    private static final String LIGHT = "{\"version\":1,\"id\":\"light\",\"name\":\"Light\",\"dark\":false,"
            + "\"colors\":{\"base\":\"#f1eff7\",\"surface\":\"#ffffff\",\"fg\":\"#1c1730\",\"accent\":\"#7c3aed\","
            + "\"accent-fg\":\"#ffffff\",\"line\":\"#D9D3E8\"}}";

    @Test
    void parsesTheLaunchersTokens() {
        Theme theme = Theme.parse(LIGHT);
        assertNotNull(theme);
        assertFalse(theme.dark);
        assertEquals(0xfff1eff7, theme.base);
        assertEquals(0xff7c3aed, theme.accent);
        assertEquals(0xffd9d3e8, theme.line);
    }

    @Test
    void missingTokensAreDerivedOrDefaulted() {
        Theme theme = Theme.parse(LIGHT);
        assertNotNull(theme);
        // raised and hover step from the surface towards the text colour
        assertTrue(Theme.luminance(theme.raised) < Theme.luminance(theme.surface));
        assertTrue(Theme.luminance(theme.hover) < Theme.luminance(theme.raised));
        assertEquals(Theme.DEFAULT.danger, theme.danger);
    }

    @Test
    void textOnALightAccentIsDark() {
        Theme theme = Theme.parse("{\"colors\":{\"base\":\"#000000\",\"accent\":\"#34d399\"}}");
        assertNotNull(theme);
        assertEquals(0xff0a0a0a, theme.accentFg);
        assertTrue(theme.dark);
    }

    @Test
    void rejectsWhatIsNotATheme() {
        assertNull(Theme.parse("not json"));
        assertNull(Theme.parse("{\"version\":2,\"colors\":{\"base\":\"#000000\"}}"));
        assertNull(Theme.parse("{\"colors\":{}}"));
        assertNull(Theme.parse("{\"colors\":{\"base\":\"red\"}}"));
    }

    @Test
    void theVeilDarkensTheGameInBothKinds() {
        Ui.apply(Theme.parse(LIGHT));
        try {
            assertTrue(Theme.luminance(Ui.VEIL) < 0.3f);
            assertTrue((Ui.VEIL >>> 24) >= 0xb0);
        } finally {
            Ui.apply(Theme.DEFAULT);
        }
    }
}
