package dev.k0t1k.overlay;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.function.Function;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * Entry point of {@code -javaagent:k0t1k-overlay.jar[=key=value,...]}.
 *
 * <p>The agent hooks LWJGL, never Minecraft itself, so it works the same on vanilla, Fabric, Forge
 * and NeoForge: the window library (GLFW, or SDL from 26.3 on, or LWJGL 2's Display, Keyboard and
 * Mouse up to 1.12.2) for frames and input, and Vulkan's swapchain functions for games that render
 * with Vulkan (Minecraft's own backend or VulkanMod).
 *
 * <p>Every hooked method calls one function stored in the system properties with
 * {@code {class, method name, arguments}} (plus the return value on exit). JDK types are visible from
 * every class loader, while the agent's own classes may not be (module layers of Forge/NeoForge). The
 * overlay itself is loaded lazily by {@link ImplLoader} as a child of whichever loader defined the
 * hooked LWJGL class, so it links against the game's own copy of LWJGL.
 */
public final class Agent {
    static final String CALL = "dev.k0t1k.overlay.call";

    private static final String[] WINDOW_TYPES = {
        "org.lwjgl.glfw.GLFW",
        "org.lwjgl.sdl.SDLVideo",
        "org.lwjgl.sdl.SDLEvents",
        "org.lwjgl.sdl.SDLKeyboard",
        "org.lwjgl.sdl.SDLMouse",
        "org.lwjgl.sdl.SDLMisc",
        "org.lwjgl.vulkan.KHRSwapchain",
        "org.lwjgl.vulkan.VK10",
        "org.lwjgl.vulkan.VK11",
        "org.lwjgl.opengl.Display",
        "org.lwjgl.input.Keyboard",
        "org.lwjgl.input.Mouse",
    };

    /**
     * Where the F3 screen takes the CPU, GPU and memory from (see {@code FakeHardware}). These never
     * start the overlay: they only answer once a window library hook has.
     */
    private static final String[] HARDWARE_TYPES = {
        "net.minecraft.client.gui.components.debug.DebugEntrySystemSpecs",
        "net.minecraft.client.gui.components.debug.DebugEntryMemory",
        "com.mojang.blaze3d.platform.GLX",
        "org.lwjgl.opengl.GL11",
        "org.lwjgl.opengl.GL11C",
    };

    /**
     * Where the game opens a web address: the process that starts the system's handler (Minecraft 1.13
     * to 26.2 through Runtime.exec), java.awt.Desktop (1.8 to 1.12; SDL_OpenURL, 26.3+, is in the window
     * types). JDK classes that may be loaded already, so they are retransformed; like the hardware hooks
     * they never start the overlay.
     */
    private static final String[] LAUNCH_TYPES = {"java.lang.ProcessBuilder", "java.awt.Desktop"};

    private static final ElementMatcher<MethodDescription> HARDWARE_ENTER = named("display");

    private static final ElementMatcher<MethodDescription> HARDWARE_EXIT = named("_getCpuInfo")
            .or(named("glGetString").and(takesArguments(int.class)));

    /** Hooks that run before the method and may replace its arguments or skip it. */
    private static final ElementMatcher<MethodDescription> ENTER = namedOneOf(
            // GLFW (Minecraft 1.13 to 26.2)
            "glfwSwapBuffers",
            "glfwSetKeyCallback", "glfwSetCharCallback", "glfwSetCharModsCallback",
            "glfwSetMouseButtonCallback", "glfwSetCursorPosCallback", "glfwSetScrollCallback",
            "glfwSetInputMode",
            // SDL (Minecraft 26.3+)
            "SDL_GL_SwapWindow", "SDL_SetWindowRelativeMouseMode", "SDL_WarpMouseInWindow",
            "SDL_StartTextInput", "SDL_StopTextInput", "SDL_OpenURL",
            // Vulkan
            "vkQueuePresentKHR", "vkCreateSwapchainKHR", "vkDestroySwapchainKHR", "vkDestroyDevice",
            // LWJGL 2 (Minecraft 1.12.2 and older): Mouse.setGrabbed
            "setGrabbed")
            // Display.update(boolean) swaps the buffers; update() only calls it.
            .or(named("update").and(takesArguments(boolean.class)));

    /** Hooks that run after the method and may replace its return value. */
    private static final ElementMatcher<MethodDescription> EXIT = namedOneOf(
            "glfwGetKey", "glfwGetMouseButton",
            "SDL_PollEvent",
            // A held-back call reports success, as SDL would have.
            "SDL_SetWindowRelativeMouseMode", "SDL_StartTextInput", "SDL_StopTextInput", "SDL_OpenURL",
            "vkQueuePresentKHR",
            "vkCreateSwapchainKHR", "vkGetDeviceQueue", "vkGetDeviceQueue2",
            // LWJGL 2: Keyboard and Mouse event queues and state
            "next", "isKeyDown", "isButtonDown", "getDX", "getDY", "getDWheel");

    private Agent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        URL jar = Agent.class.getProtectionDomain().getCodeSource().getLocation();
        System.getProperties().put(CALL, new Dispatcher(jar, args == null ? "" : args));

        new AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .ignore(not(namedOneOf(WINDOW_TYPES).or(namedOneOf(HARDWARE_TYPES)).or(namedOneOf(LAUNCH_TYPES))))
                .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
                .type(namedOneOf(WINDOW_TYPES))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(Enter.class).on(ENTER))
                        .visit(Advice.to(Exit.class).on(EXIT)))
                .type(namedOneOf(HARDWARE_TYPES))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(Enter.class).on(HARDWARE_ENTER))
                        .visit(Advice.to(Exit.class).on(HARDWARE_EXIT)))
                .type(named("java.lang.ProcessBuilder"))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(EnterThis.class).on(named("start").and(takesArguments(0)))))
                .type(named("java.awt.Desktop"))
                .transform((builder, type, loader, module, domain) -> builder
                        .visit(Advice.to(Enter.class).on(named("browse"))))
                .installOn(instrumentation);
    }

    /**
     * Calls the overlay before the method. The overlay answers null to go on unchanged, an array to
     * replace the arguments, or {@code Boolean.TRUE} to skip the method (it then returns 0/null).
     */
    static final class Enter {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
        @SuppressWarnings("unchecked")
        static boolean enter(
                @Advice.Origin Class<?> type,
                @Advice.Origin("#m") String method,
                @Advice.AllArguments(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object[] args) {
            Object hook = System.getProperties().get(CALL);
            if (hook == null) {
                return false;
            }
            Object result = ((Function<Object[], Object>) hook).apply(new Object[] {type, method, args});
            if (result instanceof Object[]) {
                args = (Object[]) result;
                return false;
            }
            return Boolean.TRUE.equals(result);
        }
    }

    /** Calls the overlay before an instance method that takes no arguments, with the instance as the only argument. */
    static final class EnterThis {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        @SuppressWarnings("unchecked")
        static void enter(
                @Advice.Origin Class<?> type,
                @Advice.Origin("#m") String method,
                @Advice.This Object self) {
            Object hook = System.getProperties().get(CALL);
            if (hook != null) {
                ((Function<Object[], Object>) hook).apply(new Object[] {type, method, new Object[] {self}});
            }
        }
    }

    /** Calls the overlay after the method; a non-null answer replaces the return value. */
    static final class Exit {
        @Advice.OnMethodExit(suppress = Throwable.class)
        @SuppressWarnings("unchecked")
        static void exit(
                @Advice.Origin Class<?> type,
                @Advice.Origin("#m") String method,
                @Advice.AllArguments Object[] args,
                @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object value) {
            Object hook = System.getProperties().get(CALL);
            if (hook == null) {
                return;
            }
            Object result = ((Function<Object[], Object>) hook).apply(new Object[] {type, method, args, value});
            if (result != null) {
                value = result;
            }
        }
    }

    /**
     * Creates the overlay on the first hook call, once the class loader of LWJGL is known, and
     * forwards every call to it. Only JDK types cross the class loader boundary.
     */
    static final class Dispatcher implements Function<Object[], Object> {
        private final URL jar;
        private final String args;
        private volatile Function<Object[], Object> impl;
        private boolean failed;

        Dispatcher(URL jar, String args) {
            this.jar = jar;
            this.args = args;
        }

        @Override
        public Object apply(Object[] call) {
            Function<Object[], Object> overlay = impl;
            if (overlay == null) {
                Class<?> type = (Class<?>) call[0];
                if (Arrays.asList(HARDWARE_TYPES).contains(type.getName())
                        || Arrays.asList(LAUNCH_TYPES).contains(type.getName())) {
                    return null;
                }
                overlay = init(type);
                if (overlay == null) {
                    return null;
                }
            }
            return overlay.apply(call);
        }

        @SuppressWarnings("unchecked")
        private synchronized Function<Object[], Object> init(Class<?> lwjgl) {
            if (impl != null || failed) {
                return impl;
            }
            try {
                ClassLoader loader = new ImplLoader(jar, lwjgl.getClassLoader());
                impl = (Function<Object[], Object>) loader.loadClass("dev.k0t1k.overlay.impl.Overlay")
                        .getConstructor(String.class)
                        .newInstance(args);
                return impl;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                failed = true;
                System.err.println("[k0t1k-overlay] disabled, could not start: " + e);
                e.printStackTrace();
                return null;
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
