package dev.k0t1k.overlay.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Which chat lines raise a mention toast. */
class MentionTest {
    @Test
    void ownMessagesInCommonServerFormatsAreNotMentions() {
        assertFalse(Overlay.mentions("<Steve> hello", "Steve"));
        assertFalse(Overlay.mentions("Steve: hello", "Steve"));
        assertFalse(Overlay.mentions("[VIP] Steve: hello", "Steve"));
        assertFalse(Overlay.mentions("[G] [Admin] steve » hello", "Steve"));
        assertFalse(Overlay.mentions("Steve > hello Steve", "Steve"));
        assertFalse(Overlay.mentions("[12:30] Steve: hi", "Steve"));
    }

    @Test
    void otherPlayersNamingThePlayerAreMentions() {
        assertTrue(Overlay.mentions("<Alex> hi Steve", "Steve"));
        assertTrue(Overlay.mentions("[VIP] Alex: steve, come here", "Steve"));
        assertTrue(Overlay.mentions("Alex » @Steve look", "Steve"));
    }

    @Test
    void partialNamesAndLinesAboutThePlayerAreNotMentions() {
        assertFalse(Overlay.mentions("<Alex> hi Steve_2", "Steve"));
        assertFalse(Overlay.mentions("Steve joined the game", "Steve"));
    }
}
