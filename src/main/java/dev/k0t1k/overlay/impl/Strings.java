package dev.k0t1k.overlay.impl;

import java.util.HashMap;
import java.util.Map;

/** UI texts in English and Russian, like the launcher. */
final class Strings {
    private final Map<String, String> table = new HashMap<>();
    final boolean russian;

    Strings(String lang) {
        russian = lang.toLowerCase(java.util.Locale.ROOT).startsWith("ru");
        add("tab.chat", "Chat", "Чат");
        add("tab.notes", "Notes", "Заметки");
        add("tab.perf", "Performance", "Производительность");
        add("hint.close", "Shift+Tab to close", "Shift+Tab — закрыть");
        add("session", "Session %s", "Сессия %s");
        add("welcome", "Overlay is ready", "Оверлей готов");
        add("welcome.body", "Press Shift+Tab", "Нажми Shift+Tab");
        add("chat.search", "Search all history…", "Поиск по всей истории…");
        add("chat.empty", "No chat messages yet. They appear here as they arrive in the game.",
                "Сообщений пока нет. Они появятся здесь, как только придут в игре.");
        add("chat.notFound", "Nothing found", "Ничего не найдено");
        add("chat.searching", "Searching…", "Ищу…");
        add("chat.found", "%d found", "Найдено: %d");
        add("chat.count", "%d loaded", "Загружено: %d");
        add("chat.older", "Loading older messages…", "Загружаю старые сообщения…");
        add("chat.start", "Beginning of history", "Начало истории");
        add("chat.copied", "Copied", "Скопировано");
        add("chat.copyHint", "Click a message to copy it", "Клик по сообщению — скопировать");
        add("chat.unknownServer", "Unknown server", "Неизвестный сервер");
        add("chat.singleplayer", "Singleplayer", "Одиночная игра");
        add("chat.mention", "Mention", "Упоминание");
        add("chat.toBottom", "Newest ↓", "К новым ↓");
        add("notes.new", "+ New note", "+ Новая заметка");
        add("notes.untitled", "Untitled", "Без названия");
        add("notes.empty", "No notes yet", "Заметок пока нет");
        add("notes.placeholder", "Write anything: coordinates, plans, recipes…",
                "Пиши что угодно: координаты, планы, рецепты…");
        add("notes.delete", "Delete", "Удалить");
        add("notes.confirm", "Delete for good?", "Точно удалить?");
        add("notes.pick", "Pick a note or create one", "Выбери заметку или создай новую");
        add("notes.saved", "Saved automatically", "Сохраняется автоматически");
        add("perf.fps", "FPS", "FPS");
        add("perf.frame", "Frame time", "Время кадра");
        add("perf.low", "1% low", "1% low");
        add("perf.heap", "Heap", "Куча");
        add("perf.gc", "GC", "GC");
        add("perf.cpu", "CPU (game)", "CPU (игра)");
        add("perf.graph", "Frame times, last %d frames", "Время кадров, последние %d");
        add("perf.hud", "Show HUD while playing", "Показывать HUD в игре");
        add("perf.corner", "Corner", "Угол");
        add("perf.gcBody", "%d runs, %d ms", "%d сборок, %d мс");
        add("perf.threads", "%d threads", "%d потоков");
        add("perf.system", "System", "Система");
        add("error", "Overlay error, see the game log", "Ошибка оверлея, подробности в логе игры");
    }

    private void add(String key, String en, String ru) {
        table.put(key, russian ? ru : en);
    }

    String get(String key) {
        String value = table.get(key);
        return value == null ? key : value;
    }

    String format(String key, Object... args) {
        return String.format(get(key), args);
    }
}
