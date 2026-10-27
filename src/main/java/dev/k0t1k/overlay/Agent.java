package dev.k0t1k.overlay;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.function.BooleanSupplier;
import java.util.function.IntBinaryOperator;
import java.util.function.LongConsumer;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Entry point of {@code -javaagent:k0t1k-overlay.jar[=key=value,...]}.
 *
 * <p>The agent hooks LWJGL's {@code GLFW} class, never Minecraft itself, so it works the same on
 * vanilla, Fabric, Forge and NeoForge. The advice inlined into {@code GLFW} only talks to JDK types
 * stored in the system properties: those are visible from every class loader, while the agent's own
 * classes may not be (module layers of Forge/NeoForge). The overlay itself is loaded lazily by
 * {@link ImplLoader} as a child of whichever loader defined {@code GLFW}, so it links against the
 * game's own copy of LWJGL.
 */
public final class Agent {
    static final String SWAP = "dev.k0t1k.overlay.swap";
    static final String WRAP = "dev.k0t1k.overlay.wrap";
    static final String OPEN = "dev.k0t1k.overlay.open";
    static final String INPUT_MODE = "dev.k0t1k.overlay.inputMode";

    private Agent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        URL jar = Agent.class.getProtectionDomain().getCodeSource().getLocation();
        Dispatcher dispatcher = new Dispatcher(jar, args == null ? "" : args);
        System.getProperties().put(SWAP, dispatcher.swap);
        System.getProperties().put(WRAP, dispatcher.wrap);
        System.getProperties().put(OPEN, dispatcher.open);
        System.getProperties().put(INPUT_MODE, dispatcher.inputMode);

        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .ignore(not(named("org.lwjgl.glfw.GLFW")))
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                .type(named("org.lwjgl.glfw.GLFW"))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(SwapAdvice.class)
                                .on(named("glfwSwapBuffers").and(takesArguments(long.class))))
                        .visit(Advice.to(CallbackAdvice.class).on(named("glfwSetKeyCallback")
                                        .or(named("glfwSetCharCallback"))
                                        .or(named("glfwSetCharModsCallback"))
                                        .or(named("glfwSetMouseButtonCallback"))
                                        .or(named("glfwSetCursorPosCallback"))
                                        .or(named("glfwSetScrollCallback"))
                                .and(takesArguments(2))
                                .and(takesArgument(0, long.class))))
                        .visit(Advice.to(PollAdvice.class)
                                .on(named("glfwGetKey").or(named("glfwGetMouseButton"))
                                        .and(takesArguments(long.class, int.class))))
                        .visit(Advice.to(InputModeAdvice.class)
                                .on(named("glfwSetInputMode").and(takesArguments(long.class, int.class, int.class)))))
                .installOn(instrumentation);
    }

    /** Draws the overlay right before the frame is presented. */
    static final class SwapAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        @SuppressWarnings("unchecked")
        static void enter(@Advice.Argument(0) long window, @Advice.Origin Class<?> glfw) {
            Object hook = System.getProperties().get(SWAP);
            if (hook != null) {
                ((java.util.function.ObjLongConsumer<Class<?>>) hook).accept(glfw, window);
            }
        }
    }

    /** Replaces every input callback the game installs with one that the overlay can intercept. */
    static final class CallbackAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        @SuppressWarnings("unchecked")
        static void enter(
                @Advice.Argument(value = 1, readOnly = false, typing = Assigner.Typing.DYNAMIC) Object callback,
                @Advice.Origin Class<?> glfw,
                @Advice.Origin("#m") String method) {
            Object hook = System.getProperties().get(WRAP);
            if (hook != null && callback != null) {
                callback = ((java.util.function.Function<Object[], Object>) hook)
                        .apply(new Object[] {glfw, method, callback});
            }
        }
    }

    /** Keys and buttons read as released while the overlay is open, so the game does not act on them. */
    static final class PollAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        static void exit(@Advice.Return(readOnly = false) int state) {
            Object hook = System.getProperties().get(OPEN);
            if (hook != null && ((BooleanSupplier) hook).getAsBoolean()) {
                state = 0;
            }
        }
    }

    /** While the overlay is open the cursor belongs to it; the game's cursor changes apply on close. */
    static final class InputModeAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
        static boolean enter(@Advice.Argument(1) int mode, @Advice.Argument(2) int value) {
            Object hook = System.getProperties().get(INPUT_MODE);
            return hook != null && ((IntBinaryOperator) hook).applyAsInt(mode, value) != 0;
        }
    }

    /**
     * Creates the overlay on the first hook call, once the class loader of {@code GLFW} is known, and
     * forwards every hook to it. Only JDK functional types cross the class loader boundary.
     */
    static final class Dispatcher {
        private final URL jar;
        private final String args;
        private volatile boolean failed;
        private LongConsumer implSwap;
        private java.util.function.BiFunction<String, Object, Object> implWrap;
        private BooleanSupplier implOpen;
        private IntBinaryOperator implInputMode;

        final java.util.function.ObjLongConsumer<Class<?>> swap = (glfw, window) -> {
            if (init(glfw)) {
                implSwap.accept(window);
            }
        };
        /** Takes {GLFW class, setter name, callback}. */
        final java.util.function.Function<Object[], Object> wrap =
                call -> init((Class<?>) call[0]) ? implWrap.apply((String) call[1], call[2]) : call[2];
        final BooleanSupplier open = () -> implOpen != null && implOpen.getAsBoolean();
        final IntBinaryOperator inputMode =
                (mode, value) -> implInputMode == null ? 0 : implInputMode.applyAsInt(mode, value);

        Dispatcher(URL jar, String args) {
            this.jar = jar;
            this.args = args;
        }

        @SuppressWarnings("unchecked")
        private synchronized boolean init(Class<?> glfw) {
            if (implSwap != null) {
                return true;
            }
            if (failed) {
                return false;
            }
            try {
                ClassLoader loader = new ImplLoader(jar, glfw.getClassLoader());
                Object overlay = loader.loadClass("dev.k0t1k.overlay.impl.Overlay")
                        .getConstructor(String.class)
                        .newInstance(args);
                Object[] hooks = (Object[]) overlay.getClass().getMethod("hooks").invoke(overlay);
                implWrap = (java.util.function.BiFunction<String, Object, Object>) hooks[1];
                implOpen = (BooleanSupplier) hooks[2];
                implInputMode = (IntBinaryOperator) hooks[3];
                implSwap = (LongConsumer) hooks[0];
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                failed = true;
                System.err.println("[k0t1k-overlay] disabled, could not start: " + e);
                e.printStackTrace();
                return false;
            }
        }
    }

    /** Child-first for the overlay's own classes, parent (the game's LWJGL loader) for the rest. */
    static final class ImplLoader extends URLClassLoader {
        private static final String IMPL = "dev.k0t1k.overlay.impl.";

        static {
            registerAsParallelCapable();
        }

        ImplLoader(URL jar, ClassLoader parent) {
            super(new URL[] {jar}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(IMPL)) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
