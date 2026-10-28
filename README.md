# overlay-agent

An in-game overlay for Minecraft Java, in the spirit of the Steam overlay. It is a Java agent, not a
mod, so one jar works on vanilla, Fabric, Forge and NeoForge, with OpenGL or Vulkan (Minecraft's own
Vulkan backend or VulkanMod), on LWJGL 2 (Minecraft 1.8 to 1.12.2), GLFW or SDL3 (Minecraft 26.3+).

Press **Shift+Tab** in game to open it, and **Shift+Tab** or **Esc** to close it.

- **Chat**: the whole chat history across sessions, servers and instances, with a full-text search
  and filters: mentions, private messages, one player's messages. Click a message to copy it. Chat
  messages that mention the player's name show a toast.
- **Notes**: notes that persist on disk and save automatically.
- **Tools**: Overworld ↔ Nether coordinates (with "my position"), stacks and shulker boxes, timers and
  alarms that survive a restart, and the latest screenshots. Each F2 screenshot shows a toast with the
  instance and position.
- **Mods**: the instance's mods with a search and what each one does (from the mod's metadata), with
  links to Modrinth and the mod's homepage.
- **Profile**: lifetime stats (play time, sessions, deaths, advancements, screenshots, crashes) and the
  launcher's achievements on top of them.
- **Performance**: FPS, frame time, 1% low, a frame time graph, heap, GC and the game's CPU load,
  plus an optional HUD in a corner of the screen.

The launcher's cat sits in a corner and reacts to deaths, advancements, a five minute AFK, timers,
screenshots and achievements with a speech bubble (it can be turned off in Profile).

The player's position, dimension and health are read by reflection on `net.minecraft` classes, so
they only work where the game runs with Mojang names: Minecraft 26.1+ and NeoForge. Elsewhere the
position button is hidden and deaths are not counted. Advancements are read from the chat.

## How it works

The agent hooks LWJGL with Byte Buddy. It never touches Minecraft classes. Every hooked method
calls one dispatcher with `{class, method, arguments}`. Before the call, the dispatcher can replace
the arguments or skip the call. After the call, it can replace the return value.

| Hook | Purpose |
|---|---|
| `glfwSwapBuffers`, `SDL_GL_SwapWindow` | OpenGL: draws the overlay over the finished frame |
| `vkQueuePresentKHR` | Vulkan: draws into the swapchain image right before it is presented |
| `vkCreateSwapchainKHR`, `vkDestroySwapchainKHR`, `vkDestroyDevice`, `vkGetDeviceQueue(2)` | Vulkan: tracks swapchains and queue families, and adds colour attachment usage to swapchains (Minecraft only blits into them) |
| `glfwSet{Key,Char,CharMods,MouseButton,CursorPos,Scroll}Callback` | GLFW: wraps the game's callbacks, so the overlay sees input first and swallows it while open |
| `SDL_PollEvent` | SDL: the overlay inspects each event and neutralises the ones it takes by rewriting their type to `SDL_EVENT_USER`, which the game ignores |
| `glfwGetKey`, `glfwGetMouseButton` | GLFW: keys and buttons read as released while the overlay is open |
| `Display.update(boolean)` | LWJGL 2: draws before the buffers are swapped (`update()` only calls it, and `processMessages` runs after the swap) |
| `Keyboard.next`, `Mouse.next` | LWJGL 2: after the game reads an event, the overlay looks at it and, if it takes it, reads the next one in its place |
| `Keyboard.isKeyDown`, `Mouse.isButtonDown`, `Mouse.getDX/getDY/getDWheel` | LWJGL 2: keys and buttons read as released and the mouse as still while the overlay is open |
| `Mouse.setGrabbed` | LWJGL 2: holds back the game's grab changes until the overlay closes |
| `glfwSetInputMode(GLFW_CURSOR)`, `SDL_SetWindowRelativeMouseMode`, `SDL_WarpMouseInWindow`, `SDL_Start/StopTextInput` | Holds back the game's cursor and text input changes until the overlay closes |

A key or button release still reaches the game if the game saw the press, so held keys do not stick.

Only calls made on the render thread are filtered. Ixeris polls GLFW or SDL on a thread of its own and
repeats the render thread's window calls there, the overlay's own cursor calls included; those
repeats pass untouched, or the overlay would hold back its own cursor release (tested with Ixeris
4.6.10 on Fabric 26.2).

The advice only uses JDK types stored in the system properties, which every class loader can see.
The overlay itself (`dev.k0t1k.overlay.impl`) loads in a child-first class loader whose parent is
the loader of the hooked LWJGL class. That way it links against the game's own LWJGL, including
inside Forge/NeoForge module layers.

Each frame is built once on the CPU as a `Batch`: rounded rectangles as signed distances, glyphs
rasterised on demand, and the glyph atlas. On LWJGL 3 the glyphs come from stb_truetype, which every
Minecraft since 1.13 ships; LWJGL 2 has none, so there `AwtFont` rasterises them with java.awt. No
natives are bundled. A renderer for the game's graphics API then draws the batch:

- **`GlRenderer`** (OpenGL 3.2 core) saves and restores all GL state it touches, because Minecraft
  caches GL state.
- **`LegacyGlRenderer`** is the same renderer written against LWJGL 2's GL classes. It needs OpenGL 3.0
  (GLSL 1.30, or 1.50 from OpenGL 3.2) in the game's compatibility context.
- **`VkRenderer`** records one render pass over the swapchain image (load, draw, back to
  `PRESENT_SRC_KHR`). It submits on the presenting queue, waits on the semaphores the game passed
  to the present, and makes the present wait on its own semaphore instead. Its SPIR-V is compiled
  from `src/main/shaders` and committed. `./gradlew compileShaders` rebuilds it with `glslc`.

The font is Inter (SIL OFL 1.1, `src/main/resources/dev/k0t1k/overlay/impl/Inter-LICENSE.txt`).

The Vulkan, SDL and LWJGL 2 code lives in its own source sets (`src/vulkan`, `src/sdl`, `src/lwjgl2`).
Each compiles against the LWJGL it needs (3.3.1 for Vulkan, 3.4.3 for SDL, 2.9.3 for LWJGL 2). They are
loaded by name, so the main code still links on LWJGL 3.1.6 (Minecraft 1.13).

The chat history comes from `logs/latest.log`: the game writes every chat line there as `[CHAT] ...`,
and `Connecting to host, port` gives the server. Minecraft 1.8.9 and 1.12.2 write the same lines
(`[12:00:00] [Client thread/INFO]: [CHAT] ...`). New lines go to an append-only store, which is read
back a page at a time.

## Supported versions

| Setup | Window | Graphics | Tested in game |
|---|---|---|---|
| Minecraft 1.8 to 1.12.2 | LWJGL 2 | OpenGL (compatibility context) | vanilla 1.8.9 and 1.12.2 |
| Minecraft 1.13 to 26.1 | GLFW | OpenGL | vanilla 1.21.4, NeoForge 21.4 (1.21.4) |
| Minecraft 26.2 | GLFW | OpenGL or Vulkan | Vulkan |
| Minecraft 26.3+ | SDL3 | OpenGL or Vulkan | Vulkan |
| VulkanMod (Fabric) | GLFW | Vulkan | 0.6.8 on 1.21.11 |

The Vulkan runs were on lavapipe (Mesa's software Vulkan) under Xvfb. They were checked with the
Khronos validation layer, synchronization validation included, while opening the overlay, typing
(atlas uploads) and resizing the window (swapchain recreation). The layer reported nothing that came
from the overlay. VulkanMod's own errors were reported both with and without it. If a game presents
on a queue family without graphics support, the overlay logs that once and draws nothing. On SDL, OpenGL is not tested in game: Xvfb has
no sRGB-capable GLX visual, so 26.3 cannot create its OpenGL window there. The parts are tested
separately: SDL input on 26.3 with Vulkan, and the GL renderer on GLFW.

- LWJGL 2 games (1.8 to 1.12.2) were tested on Linux with Mesa llvmpipe (OpenGL 4.6 compatibility context)
  under Xvfb, on Java 8: opening and closing with Shift+Tab and Esc, mouse cursor release and re-grab
  (checked with `XGrabPointer`), no camera jump after closing, a key held through the overlay does not
  stick, typing with key repeat, Cyrillic text, the clipboard, and the chat history from `latest.log`.
  Not tested: Windows, Forge 1.12.2 (its loading screen draws on a second GL context, so the
  renderer is recreated when `GLContext.getCapabilities()` changes; that path never ran), other 1.8 to 1.12 releases, and any
  OpenGL below 3.0, where the overlay logs an error and disables itself. Versions before 1.8 are
  not supported. Two things differ from LWJGL 3: the HiDPI scale is always 1, and glyphs are not
  kerned. The glyph rasteriser uses java.awt (no window is opened).
- The agent is compiled against LWJGL 3.1.6, the oldest version the game ships. Every LWJGL member
  it references was checked to resolve in 3.1.6, 3.2.2, 3.3.3 and 3.4.0. Compiling against a newer
  LWJGL would break 1.13, because from 3.2 on javac binds inherited GL methods to `GL30`.
- Before 1.17 the game runs in a compatibility context. macOS only offers OpenGL 2.1 there, so the
  overlay's GLSL 1.50 shaders do not compile and the overlay disables itself.
- Runs on Java 8 to 25 (compiled with `--release 8`). Minecraft 1.8 to 1.16 uses Java 8.

## Build

```sh
./gradlew shadowJar   # build/libs/k0t1k-overlay-<version>.jar
```

Byte Buddy is shaded and relocated to `dev.k0t1k.overlay.shadow.bytebuddy`, so it cannot clash with
mods.

## Launching

Add the agent to the JVM arguments:

```text
-javaagent:/path/to/k0t1k-overlay.jar=lang=ru
```

Options are `key=value` pairs separated by commas after `=`. Each can also be set with a system
property `-Dk0t1k.overlay.<key>=...`, which wins and suits paths that contain commas.

| Option | Default | Meaning |
|---|---|---|
| `dataDir` | `<gameDir>/k0t1k-overlay` | Where the chat history, notes and settings live. Point every instance at one folder to share them |
| `gameDir` | working directory | The game folder holding `logs/latest.log` |
| `lang` | `en` | `en` or `ru` |

The data folder contains:

- `chat-history.tsv`: one message per line, `epoch ms TAB server TAB text`, where backslash escapes
  tab, newline and itself
- `notes/<id>.txt`: one note per file
- `overlay.properties`: the HUD and cat settings
- `stats.properties`: lifetime counters and unlocked achievements; each game adds its own changes, so
  games running at once do not overwrite each other
- `session-<hash>.lock`: present while a game folder is running; one left behind counts as a crash
- `timers.tsv`: `due epoch ms TAB length ms TAB label`
- `screenshots.tsv`: a caption per screenshot, `file TAB epoch ms TAB instance TAB dimension TAB x y z`

The agent writes nothing else. On exit, a shutdown hook stores the last chat lines, any unsaved
notes and the stats. If anything fails, it prints `[k0t1k-overlay] ...` with a stack trace to the game's output
and disables itself, and the game keeps running.

## Known limitations

- Shift+Tab always goes to the overlay, even while a game text field has focus, so the game's
  reverse tab completion does not get it.
- Two games started from the same game folder both read its `latest.log`, so they store every
  message twice.
- Any message containing the player's name counts as a mention, including death messages.
