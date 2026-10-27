# Integrating the overlay into the launcher

What the launcher side needs to know to ship and start the overlay agent. For the agent's internals,
see [README.md](README.md).

## TL;DR

1. Get the jar onto the user's disk under a **versioned file name**. The section on distribution
   below explains why the version must be in the name.
2. When launching an instance with the overlay enabled, add two JVM arguments through
   `LaunchOptions::java_agent_args` (`packages/core/src/launch/mod.rs:49`):

   ```text
   -javaagent:<absolute path to k0t1k-overlay-<version>.jar>=lang=<en|ru>
   -Dk0t1k.overlay.dataDir=<absolute path to the overlay data folder>
   ```

3. Nothing else is needed. There is no IPC yet: the overlay reads the game's own `logs/latest.log`
   and keeps its state in `dataDir`.

## The artifact

- **Build:** run `./gradlew shadowJar` in `packages/overlay-agent`. It needs JDK 21 or newer to
  build; the Gradle wrapper and toolchain handle the rest. It does not need a shader compiler,
  because the SPIR-V is committed.
- **Output:** `packages/overlay-agent/build/libs/k0t1k-overlay-<version>.jar`, about 5.4 MB. The
  version comes from `build.gradle.kts` (`version = "0.1.0"`) and also appears in the manifest as
  `Implementation-Version`.
- **Contents:** one jar for every OS, CPU, loader and Minecraft version. There are no natives
  inside; it uses the LWJGL the game ships. Byte Buddy is shaded under
  `dev.k0t1k.overlay.shadow.bytebuddy`.
- **Runtime:** Java 8 to 25.

## Launch arguments

`java_agent_args` already carries authlib-injector for ely.by accounts. Append the overlay's two
arguments to the same list; the order between the agents does not matter. `build_command` puts the
list before the profile's JVM arguments, which is what a `-javaagent` needs.

| Argument | Notes |
|---|---|
| `-javaagent:<jar>=lang=ru` | Use the absolute jar path. Put only simple `key=value,key=value` options after the `=`; the agent splits that text on commas. |
| `-Dk0t1k.overlay.dataDir=<dir>` | Pass paths as system properties. A system property wins over the agent argument and can contain commas and spaces. `std::process::Command` passes each argument verbatim, so no quoting is needed. |
| `-Dk0t1k.overlay.lang=ru` | Equivalent to `lang=` in the agent argument. |
| `-Dk0t1k.overlay.gameDir=<dir>` | Only needed if the working directory is not the game folder. The launcher already starts the game in `game_dir`, so leave it out. |

**Language:** the launcher forces `-Duser.language=en`, so the agent cannot detect the language by
itself. Pass the launcher's UI language: `ru` or `en`. Anything else falls back to English.

**Username:** the agent reads `--username` from the game arguments to detect mentions in chat. That
already works, so nothing has to be passed.

## The data folder

Recommendation: one shared folder in the launcher's data directory, for example
`<launcher data>/overlay/`. That fits the "everything next to the exe" rule and gives one chat
history and one set of notes across all instances, which is the point of the feature. The default,
when `dataDir` is not passed, is `<gameDir>/k0t1k-overlay`, a separate folder per instance.

The agent creates these files on demand:

| File | Contents |
|---|---|
| `chat-history.tsv` | Every chat line ever received, one per line: `epoch ms TAB server TAB text`. Backslash escapes tab, newline and itself. Append-only, so it grows forever, by roughly 100 bytes per message. |
| `notes/<id>.txt` | One UTF-8 file per note. |
| `overlay.properties` | HUD on/off and its corner. |

- Several games can run at the same time with the same `dataDir`. Each appends to the history in
  one write per batch.
- Two games started from the **same game folder** both read the same `latest.log` and store every
  line twice. That only matters for "run a second copy of this instance" if it reuses the folder.
- **Privacy:** the history keeps private messages and server chat in plain text with no limit. The
  launcher should say so where the overlay is switched on, and should offer a way to clear it:
  deleting `chat-history.tsv` while no game is running is enough.

## Distribution and updates

The JVM keeps the agent jar open for the whole session, and **on Windows the file is locked**.
Therefore:

- Never overwrite the jar in place. Store it as `k0t1k-overlay-<version>.jar` and point new
  launches at the new file.
- Delete old versions only when no game is running. The launcher already detects running games
  through java processes.

There are two reasonable ways to deliver the jar. The user has not decided between them yet.

- **A signed GitHub release asset**, like the RatScan and packconv plugins. The launcher downloads
  it on first use and verifies the signature, so release builds do not need a JDK. This fits the
  existing plugin infrastructure.
- **Embedded in the launcher binary** with `include_bytes!` and written to the data folder on
  first launch. This works offline with no download, but every launcher build environment then
  needs JDK 21. The Windows build machine has Temurin 21; the Ubuntu chroot for the AppImage would
  need one too. It also adds 5.4 MB to the exe.

## What to expect at runtime

- **Hotkey:** Shift+Tab opens and closes the overlay, Esc closes it. The hotkey is not
  configurable yet. While a game text field is focused, Shift+Tab still goes to the overlay.
- **Supported setups:**
  - Minecraft 1.13–26.1 on GLFW with OpenGL
  - 26.2 on GLFW with OpenGL or Vulkan
  - 26.3+ on SDL3 with OpenGL or Vulkan
  - VulkanMod
  - vanilla, Fabric, Forge and NeoForge

  The README has the exact test matrix.
- **Unsupported setups are harmless.** On Minecraft before 1.13 (LWJGL 2), the hooks never match and
  the agent does nothing. The same happens with a renderer the agent cannot draw into, such as a
  queue without graphics support or macOS before 1.17. So the launcher can add the agent
  unconditionally when the user enables the feature. Hiding the option for versions before 1.13 is
  still cleaner.
- **Separate clients:** Lunar and Feather are started by their own launchers, so the overlay does
  not apply to them. LabyMod 4 in the client slot is untested.
- **Cost:** while the overlay is closed and the HUD is off, the game's frame is untouched; the hooks
  only time frames and read the log every 250 ms. Memory is about 10 MB of direct buffers, plus a
  few MB of GPU memory under Vulkan.
- **Startup:** Byte Buddy installs its hooks before the game's `main`, which adds a fraction of a
  second.

## Log lines

Everything the agent prints is prefixed `[k0t1k-overlay]` and goes to the game's stdout/stderr, so
it shows up in the launcher's game log:

| Line | Meaning |
|---|---|
| `[k0t1k-overlay] started, data in <dir>` | The agent is running. It appears when the first hooked LWJGL call happens. |
| `[k0t1k-overlay] disabled, could not start: ...` | The agent could not load. The game runs as if the agent were not there. |
| `[k0t1k-overlay] disabled after an error:` plus a stack trace | The overlay switched itself off mid-game; the game keeps running. Worth a bug report. |
| `[k0t1k-overlay] <exception>` | A recoverable error, for example a failed disk write. The overlay keeps running. |

The launcher could watch for `disabled` and show a small notice.

**Java 24 and newer** print a JDK warning, `sun.misc.Unsafe::objectFieldOffset has been called by
dev.k0t1k.overlay.shadow.bytebuddy...`. It is harmless and comes from Byte Buddy. On Minecraft 26.x
the JDK prints the same kind of warning for LWJGL's own `System::load`.

## Testing an integration

1. Launch an instance with the overlay and look for `[k0t1k-overlay] started` in the game log.
2. In game, press Shift+Tab: the overlay should open. Join a server or open a world and check that
   chat messages appear in the Chat tab and in `<dataDir>/chat-history.tsv`.
3. Restart the game: the history and the notes should still be there.

Run these tests only on a virtual display (Xvfb) in WSL, never on the user's desktop, as the rest
of the project does. The agent was tested this way. Two environment problems can get in the way:

- **Mesa 26.2.5** makes Minecraft hang in its own texture upload, with or without the agent. The
  machine was rolled back to 26.2.2.
- **26.3 with OpenGL** cannot create its window on Xvfb, because Xvfb offers no sRGB-capable GLX
  visual. Use `--graphicsBackend vulkan` there.

## Not there yet

- **No channel to the launcher.** A local WebSocket was discussed as the transport for launcher
  features inside the game, such as chat with friends, support tickets or the resource pack
  converter. It is not designed or implemented. The user will specify it.
- **No settings from the launcher** beyond `lang` and `dataDir`. The HUD on/off setting lives in
  `overlay.properties`. Neither the hotkey nor the enable switch can be set in the agent; the
  launcher decides whether to add the agent at all.
