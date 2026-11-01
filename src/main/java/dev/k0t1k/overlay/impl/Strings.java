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
        add("tab.settings", "Settings", "Настройки");
        add("settings.interface", "Interface", "Интерфейс");
        add("settings.scale", "Interface scale", "Масштаб интерфейса");
        add("settings.scaleAuto", "Auto (%d%%)", "Авто (%d%%)");
        add("settings.scaleHint", "Ctrl + mouse wheel or Ctrl + plus / minus changes it, Ctrl+0 returns to Auto",
                "Ctrl + колесо мыши или Ctrl + плюс / минус меняют его, Ctrl+0 — вернуть Авто");
        add("settings.hud", "HUD", "HUD");
        add("settings.notify", "Notifications", "Уведомления");
        add("settings.cat", "Cat", "Кот");
        add("hud.size", "Size", "Размер");
        add("hud.opacity", "Background opacity", "Прозрачность фона");
        add("hud.metrics", "Show", "Показывать");
        add("hud.edit", "Edit HUD position", "Положение HUD");
        add("hud.presets", "Corner", "Угол");
        add("perf.editHint", "Drag the HUD, Esc/Enter to finish", "Перетащи HUD, Esc/Enter — закончить");
        add("perf.editDone", "Done", "Готово");
        add("toast.position", "Where notifications appear", "Где появляются уведомления");
        add("toast.center", "Under the crosshair", "Под прицелом");
        add("toast.topLeft", "Top left", "Слева сверху");
        add("toast.topRight", "Top right", "Справа сверху");
        add("toast.bottomLeft", "Bottom left", "Слева снизу");
        add("toast.bottomRight", "Bottom right", "Справа снизу");
        add("toast.test", "Test notification", "Проверить уведомление");
        add("toast.testBody", "This is how notifications look here", "Так выглядят уведомления здесь");
        add("settings.replay", "Replay", "Повтор");
        add("settings.hardware", "Fake hardware", "Подмена железа");
        add("hw.toggle", "Show fake hardware on the F3 screen", "Показывать на экране F3 другое железо");
        add("hw.cpu", "CPU name", "Процессор");
        add("hw.threads", "CPU threads", "Потоков процессора");
        add("hw.gpu", "GPU name", "Видеокарта");
        add("hw.vendor", "GPU vendor", "Производитель GPU");
        add("hw.memory", "Max memory, MiB", "Макс. память, МиБ");
        add("hw.hint", "Only the F3 screen changes; the game and crash reports keep the real hardware. Memory applies on Minecraft 26.x only.",
                "Меняется только экран F3; игра и отчёты о сбоях видят настоящее железо. Память работает только на Minecraft 26.x.");
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
        add("perf.graphShort", "Graph", "График");
        add("perf.hud", "Show HUD while playing", "Показывать HUD в игре");
        add("perf.gcBody", "%d runs, %d ms", "%d сборок, %d мс");
        add("perf.threads", "%d threads", "%d потоков");
        add("perf.system", "System", "Система");
        add("error", "Overlay error, see the game log", "Ошибка оверлея, подробности в логе игры");
        add("tab.tools", "Tools", "Инструменты");
        add("tab.mods", "Mods", "Моды");
        add("tab.profile", "Profile", "Профиль");
        add("chat.player", "Player…", "Игрок…");
        add("chat.all", "All", "Все");
        add("chat.mentions", "Mentions", "Упоминания");
        add("chat.private", "Private", "Личка");
        add("tools.coords", "Overworld ↔ Nether", "Верхний мир ↔ Незер");
        add("tools.overworld", "Overworld", "Верхний мир");
        add("tools.nether", "Nether", "Незер");
        add("tools.end", "The End", "Энд");
        add("tools.here", "My position", "Где я");
        add("tools.position", "%s: %s · click to copy", "%s: %s · клик — скопировать");
        add("tools.notInWorld", "Not in a world", "Не в мире");
        add("tools.noPosition", "Position needs Minecraft 26.1+ or NeoForge", "Позиция видна на Minecraft 26.1+ и NeoForge");
        add("tools.stacks", "Stacks and shulkers", "Стаки и шалкеры");
        add("tools.stackSize", "Stack", "Стак");
        add("tools.shulkers", "Shulker boxes", "Шалкеры");
        add("tools.stacksField", "Stacks", "Стаки");
        add("tools.items", "Items", "Штуки");
        add("tools.total", "%,d items", "%,d шт.");
        add("tools.breakdown", "= %d shulkers + %d stacks + %d", "= %d шалк. + %d стак. + %d шт.");
        add("tools.storage", "Fills %d shulker boxes or %d double chests", "Займёт шалкеров: %d, двойных сундуков: %d");
        add("tools.timers", "Timers and alarms", "Таймеры и будильник");
        add("tools.timerLabel", "What to check…", "Что проверить…");
        add("tools.timerTime", "min or 14:30", "мин или 14:30");
        add("tools.timerDefault", "Timer", "Таймер");
        add("tools.timerDone", "Time is up", "Время вышло");
        add("tools.start", "Start", "Запустить");
        add("tools.minutes", "%d min", "%d мин");
        add("tools.badTime", "Type minutes (20) or a time (14:30)", "Введи минуты (20) или время (14:30)");
        add("tools.noTimers", "No timers. Timers keep running after the game closes.",
                "Таймеров нет. Они идут, даже если закрыть игру.");
        add("tools.screenshots", "Screenshots", "Скриншоты");
        add("tools.openFolder", "Open folder", "Открыть папку");
        add("tools.noShots", "Press F2 in game: the overlay shows each screenshot with the instance and position.",
                "Нажми F2 в игре: оверлей покажет скриншот со сборкой и координатами.");
        add("tools.copyPath", "Click to copy the file path", "Клик — скопировать путь к файлу");
        add("shot.saved", "Screenshot saved", "Скриншот сохранён");
        add("mods.search", "Search mods…", "Поиск модов…");
        add("mods.loading", "Reading mods…", "Читаю моды…");
        add("mods.none", "No mods in this instance", "В этой сборке нет модов");
        add("mods.disabled", "disabled", "выключен");
        add("mods.count", "%d on, %d off", "Включено: %d, выключено: %d");
        add("mods.refresh", "Refresh", "Обновить");
        add("mods.what", "What it does", "Что делает");
        add("mods.noDescription", "The mod has no description.", "У мода нет описания.");
        add("mods.modrinth", "Find on Modrinth", "Найти на Modrinth");
        add("mods.homepage", "Homepage", "Сайт мода");
        add("mods.opened", "Opened in the browser", "Открыто в браузере");
        add("stat.playMillis", "Played", "Наиграно");
        add("stat.sessions", "Sessions", "Запусков");
        add("stat.deaths", "Deaths", "Смертей");
        add("stat.advancements", "Advancements", "Достижений");
        add("stat.screenshots", "Screenshots", "Скриншотов");
        add("stat.crashes", "Crashes survived", "Пережито крашей");
        add("stat.hours", "%d h %02d m", "%d ч %02d м");
        add("ach.title", "Achievements %d / %d", "Ачивки %d / %d");
        add("ach.unlocked", "Achievement unlocked", "Новая ачивка");
        add("ach.hour1", "Getting comfy", "Освоился");
        add("ach.hour1.d", "Play for an hour", "Наиграть час");
        add("ach.hour10", "Regular", "Завсегдатай");
        add("ach.hour10.d", "Play for 10 hours", "Наиграть 10 часов");
        add("ach.hour100", "No-lifer", "Ноулайфер");
        add("ach.hour100.d", "Play for 100 hours", "Наиграть 100 часов");
        add("ach.sessions50", "Back again", "Снова здесь");
        add("ach.sessions50.d", "Start the game 50 times", "Запустить игру 50 раз");
        add("ach.death1", "Oops", "Упс");
        add("ach.death1.d", "Die once", "Умереть один раз");
        add("ach.death9", "Nine lives", "Девять жизней");
        add("ach.death9.d", "Die 9 times, like a cat", "Умереть 9 раз, как кошка");
        add("ach.adv10", "Achiever", "Ачивер");
        add("ach.adv10.d", "Earn 10 advancements", "Получить 10 достижений");
        add("ach.shot10", "Photographer", "Фотограф");
        add("ach.shot10.d", "Take 10 screenshots", "Сделать 10 скриншотов");
        add("ach.crash1", "It happens", "Бывает");
        add("ach.crash1.d", "Survive a crash", "Пережить краш");
        add("ach.crash10", "Battle-hardened", "Закалённый");
        add("ach.crash10.d", "Survive 10 crashes", "Пережить 10 крашей");
        add("ach.timer1", "Punctual", "Пунктуальный");
        add("ach.timer1.d", "Let a timer run out", "Дождаться таймера");
        add("ach.nightOwl", "Night owl", "Сова");
        add("ach.nightOwl.d", "Play between 3 and 5 am", "Играть с 3 до 5 утра");
        add("cat.toggle", "Cat reacts to events", "Кот реагирует на события");
        add("cat.death1", "Meow! One life down.", "Мяу! Минус жизнь.");
        add("cat.death2", "I saw nothing. Go get your stuff.", "Я ничего не видел. Беги за вещами.");
        add("cat.death3", "Cats have nine lives. You have one.", "У котов девять жизней. У тебя одна.");
        add("cat.advancement1", "Purr! New advancement.", "Мур! Новое достижение.");
        add("cat.advancement2", "Look at you go!", "Ого, вот это ты!");
        add("cat.advancement3", "Proud of you. Treat?", "Горжусь. Вкусняшку?");
        add("cat.afk1", "Still there? I'll guard your stuff.", "Ты тут? Я посторожу вещи.");
        add("cat.afk2", "Zzz… wake me when you're back.", "Хррр… разбуди, когда вернёшься.");
        add("cat.back", "Oh, you're back!", "О, вернулся!");
        add("cat.shot1", "Nice shot!", "Красивый кадр!");
        add("cat.shot2", "Did you get my good side?", "А меня с хорошей стороны сняли?");
        add("cat.achievement", "Mrrr, an achievement!", "Мрр, ачивка!");
        add("cat.replay", "That one's a keeper!", "Это надо сохранить!");
        add("media.now", "Now playing", "Сейчас играет");
        add("tab.browser", "Browser", "Браузер");
        add("browser.address", "Search or type an address", "Поиск или адрес сайта");
        add("browser.install", "The browser is Chromium; it downloads once (about %s MB) and stays for every game.",
                "Браузер — это Chromium, он скачается один раз (около %s МБ) и останется для всех игр.");
        add("browser.download", "Download the browser", "Скачать браузер");
        add("browser.downloading", "Downloading Chromium… %d%%", "Скачиваю Chromium… %d%%");
        add("browser.starting", "Starting the browser…", "Запускаю браузер…");
        add("browser.loading", "Loading…", "Загрузка…");
        add("browser.failed", "The browser failed: %s", "Браузер не запустился: %s");
        add("browser.retry", "Try again", "Попробовать снова");
        add("browser.unsupported", "The browser needs Windows or Linux on x64", "Браузеру нужен Windows или Linux на x64");
        add("browser.unsupportedRenderer", "The browser cannot draw with this game's renderer",
                "Браузер не умеет рисоваться рендерером этой игры");
        add("browser.newTab", "New tab", "Новая вкладка");
        add("browser.open", "Open", "Открыть");
        add("browser.openTab", "Open in new tab", "Открыть в новой вкладке");
        add("browser.rename", "Rename", "Переименовать");
        add("browser.delete", "Delete", "Удалить");
        add("browser.linkTab", "Open link in new tab", "Открыть ссылку в новой вкладке");
        add("browser.linkCopy", "Copy link address", "Копировать адрес ссылки");
        add("browser.imageTab", "Open image in new tab", "Открыть картинку в новой вкладке");
        add("browser.imageCopy", "Copy image address", "Копировать адрес картинки");
        add("browser.cut", "Cut", "Вырезать");
        add("browser.copy", "Copy", "Копировать");
        add("browser.paste", "Paste", "Вставить");
        add("browser.selectAll", "Select all", "Выделить всё");
        add("browser.search", "Search for “%s”", "Искать «%s»");
        add("browser.back", "Back", "Назад");
        add("browser.forward", "Forward", "Вперёд");
        add("browser.reload", "Reload", "Обновить");
        add("settings.browser", "Browser", "Браузер");
        add("settings.browserHome", "Home page", "Домашняя страница");
        add("settings.browserSearch", "Search engine", "Поисковик");
        add("settings.browserBar", "Show the bookmarks bar", "Показывать панель закладок");
        add("settings.browserClear", "Clear cookies and logins", "Удалить куки и входы");
        add("settings.browserCleared", "Cleared", "Удалено");
        add("settings.browserStopped", "Open the browser tab first", "Сначала открой вкладку браузера");
        add("settings.browserImport", "Import sign-ins", "Перенести входы");
        add("settings.browserImportHint",
                "Copies cookies from another browser's allowlisted sites, so you don't have to log in here again. Only cookies from these domains (or their subdomains) are copied.",
                "Копирует куки сайтов из списка ниже из другого браузера, чтобы не входить здесь заново. Переносятся только куки этих доменов (и их поддоменов).");
        add("settings.browserImportAllowlist", "Allowed domains", "Разрешённые домены");
        add("settings.browserImportScanning", "Looking for browsers…", "Ищу браузеры…");
        add("settings.browserImportNone", "No other browser found on this computer", "На этом компьютере не нашлось других браузеров");
        add("settings.browserImportButton", "Import", "Перенести");
        add("settings.browserImportRunning", "Importing…", "Переношу…");
        add("settings.browserImportNoTab", "Open the browser tab first", "Сначала открой вкладку браузера");
        add("settings.browserImportLocked", "Close %s and try again", "Закрой %s и попробуй снова");
        add("settings.browserImportErrorGeneric", "Import failed: %s", "Перенос не удался: %s");
        add("settings.browserImportDone", "Imported %d cookies from %s (%s)", "Перенесено куки: %d, из %s (%s)");
        add("settings.browserImportSkipped", "%d could not be decrypted", "%d не удалось расшифровать");
        add("replay.title", "Replay", "Повтор");
        add("replay.toggle", "Keep the last 30 s", "Записывать последние 30 с");
        add("replay.video", "Video", "Видео");
        add("replay.gif", "GIF", "GIF");
        add("replay.save", "Save 30 s", "Сохранить 30 с");
        add("replay.hint", "Shift+F2 in game · video is MP4 with ffmpeg installed, AVI otherwise",
                "Shift+F2 в игре · видео в MP4, если стоит ffmpeg, иначе AVI");
        add("replay.unsupported", "Recording does not work with this game's renderer",
                "Запись не работает с рендерером этой игры");
        add("replay.off", "Recording is off", "Запись выключена");
        add("replay.saving", "Saving the last 30 seconds…", "Сохраняю последние 30 секунд…");
        add("replay.saved", "Replay saved", "Повтор сохранён");
        add("replay.failed", "Nothing recorded yet", "Пока нечего сохранять");
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
