package dev.k0t1k.overlay.impl;

/** What a notification is about; each kind can be switched off or given its own lifetime. */
enum ToastKind {
    WELCOME("welcome", 0),
    MENTION("mention", 0),
    MUSIC("music", 0),
    SCREENSHOT("screenshot", 0),
    RECORDING("recording", 0),
    TIMER("timer", 12),
    ACHIEVEMENT("achievement", 0),
    ERROR("error", 0),
    OTHER("other", 0),
    /** The settings test button: always shown, always for the global time, not listed in the settings. */
    TEST("test", 0);

    /** The settings key and string suffix. */
    final String id;
    /** The seconds this kind stays by default: 0 for the global time. */
    final int defaultSeconds;

    ToastKind(String id, int defaultSeconds) {
        this.id = id;
        this.defaultSeconds = defaultSeconds;
    }
}
