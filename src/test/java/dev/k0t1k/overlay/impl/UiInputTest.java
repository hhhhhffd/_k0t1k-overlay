package dev.k0t1k.overlay.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UiInputTest {
    private final Ui ui = new Ui(null, null, null, null);

    @Test
    void repeatedPressDoesNotCreateAnotherClick() {
        ui.onButton(Keys.MOUSE_LEFT, true);
        ui.endFrame();
        ui.onButton(Keys.MOUSE_LEFT, true);
        assertFalse(ui.clicked);
        assertFalse(ui.doubleClicked);
        assertTrue(ui.mouseDown);
        ui.onButton(Keys.MOUSE_LEFT, false);
        assertTrue(ui.released);
        assertFalse(ui.mouseDown);
    }

    @Test
    void nearbySecondClickIsDoubleButDistantClickIsNot() {
        ui.onMove(10, 10);
        ui.onButton(Keys.MOUSE_LEFT, true);
        assertFalse(ui.doubleClicked);
        ui.onButton(Keys.MOUSE_LEFT, false);
        ui.endFrame();
        ui.onMove(12, 11);
        ui.onButton(Keys.MOUSE_LEFT, true);
        assertTrue(ui.doubleClicked);
        ui.onButton(Keys.MOUSE_LEFT, false);
        ui.endFrame();
        ui.onMove(100, 100);
        ui.onButton(Keys.MOUSE_LEFT, true);
        assertFalse(ui.doubleClicked);
    }

    @Test
    void repeatedMiddleAndRightPressesDoNotClickAgain() {
        ui.onButton(Keys.MOUSE_MIDDLE, true);
        ui.onButton(Keys.MOUSE_RIGHT, true);
        ui.endFrame();
        ui.onButton(Keys.MOUSE_MIDDLE, true);
        ui.onButton(Keys.MOUSE_RIGHT, true);
        assertFalse(ui.middleClicked);
        assertFalse(ui.rightClicked);
    }
}
