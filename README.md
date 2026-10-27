# overlay-agent

An in-game overlay for Minecraft Java, in the spirit of the Steam overlay. It is a Java agent, not a
mod, so one jar works on vanilla, Fabric, Forge and NeoForge.

Press **Shift+Tab** in game to open it, and **Shift+Tab** or **Esc** to close it.

- **Chat**: the whole chat history across sessions, servers and instances, with a full-text search.
  Click a message to copy it. Chat messages that mention the player's name show a toast.
- **Notes**: notes that persist on disk and save automatically.
- **Performance**: FPS, frame time, 1% low, a frame time graph, heap, GC and the game's CPU load,
  plus an optional HUD in a corner of the screen.

## How it works

The agent hooks LWJGL's `org.lwjgl.glfw.GLFW` with Byte Buddy. It never touches Minecraft classes.

| Hook | Purpose |
|---|---|
| `glfwSwapBuffers` | Draws the overlay over the finished frame |
| `glfwSet{Key,Char,CharMods,MouseButton,CursorPos,Scroll}Callback` | Wraps the game's callbacks so the overlay sees input first and swallows it while open. A release still reaches the game if the game saw the press, so held keys do not stick |
| `glfwGetKey`, `glfwGetMouseButton` | Report "released" while the overlay is open |
| `glfwSetInputMode(GLFW_CURSOR)` | Defers the game's cursor changes until the overlay closes |

The inlined advice only uses JDK types stored in the system properties, which every class loader can
see. The overlay itself (`dev.k0t1k.overlay.impl`) loads in a child-first class loader whose parent
is the loader of `GLFW`, so it links against the game's own LWJGL, including inside Forge/NeoForge
module layers.

Drawing uses a small OpenGL 3.2 core renderer with signed-distance rounded rectangles and glyphs
rasterised on demand by stb_truetype. Every Minecraft version ships stb, so no natives are bundled.
All GL state the renderer touches is saved and restored, because Minecraft caches GL state. The font
is Inter (SIL OFL 1.1, `src/main/resources/dev/k0t1k/overlay/impl/Inter-LICENSE.txt`).

The chat history comes from `logs/latest.log`: the game writes every chat line there as `[CHAT] ...`,
and `Connecting to host, port` gives the server. New lines go to an append-only store, which is read
back a page at a time.

## Supported versions

- Minecraft 1.13 to 26.2 (GLFW). Tested in game on vanilla 1.21.4 and NeoForge 21.4 (1.21.4).
- The agent is compiled against LWJGL 3.1.6, the oldest version the game ships. Every LWJGL member
  it references was checked to resolve in 3.1.6, 3.2.2, 3.3.3 and 3.4.0. Compiling against a newer
  LWJGL would break 1.13, because from 3.2 on javac binds inherited GL methods to `GL30`.
- Before 1.17 the game runs in a compatibility context. macOS only offers OpenGL 2.1 there, so the
  overlay's GLSL 1.50 shaders do not compile and the overlay disables itself.
- **26.3+ is not supported yet**: the game moved from GLFW to SDL3, so none of the hooks fire there,
  and the overlay stays silent.
- With the experimental Vulkan renderer (26.2) there is no OpenGL context, so the overlay draws nothing.
- Runs on Java 8 to 25 (compiled with `--release 8`).

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
- `overlay.properties`: the HUD settings

The agent writes nothing else. On exit, a shutdown hook stores the last chat lines and any unsaved
notes. If anything fails, it prints `[k0t1k-overlay] ...` with a stack trace to the game's output
and disables itself, and the game keeps running.

## Known limitations

- Shift+Tab always goes to the overlay, even while a game text field has focus, so the game's
  reverse tab completion does not get it.
- Two games started from the same game folder both read its `latest.log`, so they store every
  message twice.
- Any message containing the player's name counts as a mention, including death messages.
