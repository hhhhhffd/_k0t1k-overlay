package dev.k0t1k.overlay.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatViewTest {
    @Test
    void searchNavigatesBetweenIdenticalHitsWithoutHidingOtherRows(@TempDir Path dir) throws Exception {
        ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
        try {
            ChatHistory history = new ChatHistory(dir, dir, io, io, Runnable::run, error -> {
                throw new AssertionError(error);
            });
            for (int i = 0; i < 180; i++) {
                ChatHistory.Line line = new ChatHistory.Line(1, "server", i == 20 || i == 150 ? "needle" : "context " + i);
                line.start = i * 100;
                history.lines.add(line);
            }
            ChatView view = new ChatView(history, new Strings("en"), null);
            List<ChatHistory.Line> matches = Arrays.asList(history.lines.get(20), history.lines.get(150));
            field(view, "results").set(view, matches);
            Batch batch = new Batch();
            Font font = new AwtFont("Inter-Regular.ttf", new Font.Atlas(batch));
            Ui ui = new Ui(batch, font, font, null);

            draw(view, ui, batch);
            assertEquals(15000L, field(view, "activeMatchStart").getLong(view));
            float firstScroll = field(view, "scroll").getFloat(view);
            assertTrue(firstScroll > 0);
            int messages = 0;
            for (Object row : (List<?>) field(view, "rows").get(view)) {
                if (field(row, "header").get(row) == null) {
                    messages++;
                }
            }
            assertEquals(180, messages);

            Method navigate = ChatView.class.getDeclaredMethod("navigateMatch", int.class, List.class);
            navigate.setAccessible(true);
            navigate.invoke(view, -1, matches);
            draw(view, ui, batch);
            assertEquals(2000L, field(view, "activeMatchStart").getLong(view));
            assertTrue(field(view, "scroll").getFloat(view) > firstScroll);

            navigate.invoke(view, -1, matches);
            draw(view, ui, batch);
            assertEquals(15000L, field(view, "activeMatchStart").getLong(view));
            assertEquals(firstScroll, field(view, "scroll").getFloat(view), 0.01f);
        } finally {
            io.shutdownNow();
        }
    }

    private static void draw(ChatView view, Ui ui, Batch batch) {
        batch.begin(1000, 360);
        view.draw(ui, 0, 0, 1000, 360);
        batch.end();
        ui.endFrame();
    }

    private static Field field(Object object, String name) throws NoSuchFieldException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
