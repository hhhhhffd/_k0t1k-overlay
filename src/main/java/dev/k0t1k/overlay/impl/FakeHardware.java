package dev.k0t1k.overlay.impl;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static java.util.Collections.unmodifiableList;

/**
 * A joke: the F3 debug screen shows the CPU, GPU and memory the player typed in the settings. The
 * game, the performance tab and crash reports keep the real hardware.
 *
 * <p>Two mechanisms, because Minecraft builds the F3 text differently:
 * <ul>
 *   <li>26.x builds it from {@code DebugEntry*#display(DebugScreenDisplayer, ...)}. The displayer is
 *       replaced by a proxy ({@link F3Lines}) that rewrites the lines on their way to the screen. That
 *       proxy is the one place where a future custom F3 can change or add lines.
 *   <li>1.8 to 1.21.11 read the CPU from {@code GLX._getCpuInfo} and the GPU from
 *       {@code glGetString} every frame while F3 is shown. Those return values are replaced, but only
 *       on the render thread while F3 is on screen, which is tracked from the key events.
 * </ul>
 * Nothing here touches {@code Runtime}: the game's own memory logic must see the real values.
 */
final class FakeHardware {
    private static final int F3 = 292;
    private static final int GL_VENDOR = 7936;
    private static final int GL_RENDERER = 7937;

    private final Options options;
    private final Overlay overlay;

    /** Set by the overlay around its own OpenGL queries, which must read the real renderer. */
    volatile boolean bypass;
    private boolean f3Down;
    private boolean f3Combo;
    private boolean f3Shown;

    FakeHardware(Overlay overlay, Options options) {
        this.overlay = overlay;
        this.options = options;
    }

    /**
     * A key event the game sees. F3 toggles the debug screen on release, unless another key was
     * used in a recognized debug combination while it was held (F3+D and the like).
     */
    void key(int key, int action) {
        if (key == F3) {
            if (action == Keys.PRESS) {
                f3Down = true;
                f3Combo = false;
            } else if (action == Keys.RELEASE) {
                if (f3Down && !f3Combo) {
                    f3Shown = !f3Shown;
                }
                f3Down = false;
            }
        } else if (action != Keys.RELEASE && f3Down && debugCombo(key)) {
            f3Combo = true;
        }
    }

    private static boolean debugCombo(int key) {
        // Approximation across Minecraft 1.8–1.21; other keys must still allow the F3 toggle.
        return key >= '1' && key <= '9' || key >= 'A' && key <= 'Z'
                && "ABCDFGHILNPQSTVW".indexOf(key) >= 0 || key == 293 || key == Keys.ESCAPE;
    }

    /** Before {@code DebugEntry*#display}: swaps the displayer for the rewriting proxy, or null to change nothing. */
    Object[] display(Object[] args) {
        if (!options.fakeHardware || args.length == 0 || args[0] == null) {
            return null;
        }
        Object displayer = args[0];
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        for (Class<?> type = displayer.getClass(); type != null; type = type.getSuperclass()) {
            interfaces.addAll(Arrays.asList(type.getInterfaces()));
        }
        Object[] replaced = args.clone();
        replaced[0] = Proxy.newProxyInstance(displayer.getClass().getClassLoader(),
                interfaces.toArray(new Class<?>[0]), new F3Lines(displayer));
        return replaced;
    }

    /** After {@code GLX._getCpuInfo()} or {@code glGetString(int)}: the fake text, or null to keep the real one. */
    Object exit(String method, Object[] args) {
        if (!options.fakeHardware || !f3Shown || bypass || !overlay.onRenderThread()) {
            return null;
        }
        if ("_getCpuInfo".equals(method)) {
            return cpu();
        }
        int name = (Integer) args[0];
        if (name == GL_VENDOR) {
            return options.fakeVendor;
        }
        return name == GL_RENDERER ? options.fakeGpu : null;
    }

    private String cpu() {
        return options.fakeThreads + "x " + options.fakeCpu;
    }

    /** Wraps the game's {@code DebugScreenDisplayer}, passing every call through with its lines rewritten. */
    private final class F3Lines implements InvocationHandler {
        private final Object target;

        F3Lines(Object target) {
            this.target = target;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object[] passed = args;
            if (args != null) {
                passed = args.clone();
                for (int i = 0; i < passed.length; i++) {
                    if (passed[i] instanceof String) {
                        passed[i] = rewrite((String) passed[i]);
                    } else if (passed[i] instanceof Collection) {
                        passed[i] = rewrite((Collection<?>) passed[i]);
                    }
                }
            }
            try {
                return method.invoke(target, passed);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        /** A group of lines added together; only the system group (it starts with the Java line) has the GPU name. */
        private Object rewrite(Collection<?> group) {
            List<Object> lines = new ArrayList<>(group.size());
            for (Object line : group) {
                lines.add(line instanceof String ? rewrite((String) line) : line);
            }
            int gpu = lines.size() - 2;
            if (gpu >= 1 && lines.get(0) instanceof String && ((String) lines.get(0)).startsWith("Java: ")
                    && lines.get(gpu) instanceof String) {
                // Java, CPU, Display, [more], GPU name with its (dGPU) kind, backend and driver.
                lines.set(gpu, options.fakeGpu + kind((String) lines.get(gpu)));
            }
            return unmodifiableList(lines);
        }

        private String rewrite(String line) {
            try {
                return rewriteLine(line);
            } catch (RuntimeException e) {
                // Malformed or changed debug text must never prevent the game's F3 from drawing.
                return line;
            }
        }

        private String rewriteLine(String line) {
            if (line.startsWith("CPU: ")) {
                return "CPU: " + cpu();
            }
            if (line.startsWith("Display: ")) {
                int vendor = line.lastIndexOf(" (");
                return vendor < 0 ? line : line.substring(0, vendor) + " (" + options.fakeVendor + ")";
            }
            if (line.startsWith("Mem: ")) {
                long[] n = numbers(line);
                if (n.length == 3) {
                    long max = Math.max(1, options.fakeMemory);
                    return String.format(Locale.ROOT, "Mem: %2d%% %03d/%03dMiB", n[1] * 100 / max, n[1], max);
                }
            }
            if (line.startsWith("Allocated: ")) {
                long[] n = numbers(line);
                if (n.length == 2) {
                    return String.format(Locale.ROOT, "Allocated: %2d%% %03dMiB", n[1] * 100 / Math.max(1, options.fakeMemory), n[1]);
                }
            }
            return line;
        }

        /**
         * The " (dGPU)" style kind the game appends to the GPU name, as a discrete card whatever the real
         * one is: a fake RTX marked "(software)" or "(iGPU)" would give the joke away. Nothing when the
         * game shows no kind.
         */
        private String kind(String gpu) {
            for (String kind : new String[] {" (iGPU)", " (dGPU)", " (vGPU)", " (software)"}) {
                if (gpu.endsWith(kind)) {
                    return " (dGPU)";
                }
            }
            return "";
        }

        /** Every run of digits in the text, in order. */
        private long[] numbers(String text) {
            List<Long> found = new ArrayList<>();
            int i = 0;
            while (i < text.length()) {
                if (!Character.isDigit(text.charAt(i))) {
                    i++;
                    continue;
                }
                int end = i;
                while (end < text.length() && Character.isDigit(text.charAt(end))) {
                    end++;
                }
                found.add(Long.parseLong(text.substring(i, end)));
                i = end;
            }
            long[] result = new long[found.size()];
            for (int k = 0; k < result.length; k++) {
                result[k] = found.get(k);
            }
            return result;
        }
    }
}
