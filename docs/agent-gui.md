# Agent-driven GUI testing on Linux / WSL

> [!WARNING]
> **Dev-only and unsupported on Linux.** Production `safe-db` supports macOS and Windows only; [`Main.kt`](../src/main/kotlin/com/safedb/Main.kt) and [`DataDirectory.kt`](../shared/src/main/kotlin/com/safedb/platform/DataDirectory.kt) continue to reject Linux. The `runAgentGui` entry point lives in the `render` source set, which is never packaged into shipped artifacts, and uses an in-memory credential store (`SecretsManager.initMemoryStore()`) rather than `SAFEDB_KEYCHAIN_BACKEND=disabled` so credentials stay in process memory. Font rendering, Swing file choosers, and window chrome may differ slightly from macOS and Windows.

This workflow lets any CLI agent manually test the real Compose Desktop GUI on a Linux or WSL dev box using a headless virtual X display (`Xvfb`), a lightweight window manager (`openbox`), `xdotool` for input, and ImageMagick (`import`) for screenshots.

## One-time system setup

Install the Xvfb, input, screenshot, and window-manager tools on Ubuntu/WSL:

```bash
sudo apt install xvfb xdotool imagemagick openbox x11vnc
```

`x11vnc` is optional and only needed if you want to watch the headless display live from a VNC client.

## Lifecycle

1. **Start database fixtures** (if testing live connections):
   ```bash
   scripts/docker_test_databases.sh up
   ```
   To run only a single TLS-enabled Compose service such as `postgres`, generate the test certificates once first with `scripts/docker_test_databases.sh certs`, then start `docker compose up -d postgres`. Plain `mysql-connectivity` starts directly with `docker compose up -d mysql-connectivity`.

2. **Start the headless GUI**:
   ```bash
   scripts/agent_gui.sh up
   # with live VNC viewing on localhost:5900:
   scripts/agent_gui.sh up --vnc
   # or keep the command in the foreground (for runners that reap background children on exit):
   scripts/agent_gui.sh up --foreground
   ```
   This starts `Xvfb :99` (`1600x1000x24`), `openbox`, optional `x11vnc`, and `./gradlew --no-daemon runAgentGui`, then waits for the `Safe-DB` window, moves it to `(0, 0)`, and focuses it. Because `-Dsun.java2d.uiScale=1` is set on `runAgentGui`, screenshot pixel coordinates match `click X Y` coordinates 1:1. A failed `up` and a foreground exit run `down` through the `EXIT` trap, including `HUP`, not only `SIGINT`/`SIGTERM`. A successful background `up` disarms that trap before it returns.

   App state persists in `build/agent-gui/data` across runs so saved connections survive restarts (override with `SAFEDB_AGENT_GUI_DATA_DIR`).

   **Sandboxed agents:** If your tool runner uses a restrictive sandbox, run `up --foreground` and the input/screenshot commands outside that sandbox. Skiko extracts its native library to `~/.skiko`, `Xvfb` needs `/tmp/.X11-unix`, and a daemon/long-lived background invocation of `up --foreground` keeps the display and app alive across tool calls.

3. **Drive the GUI** in a screenshot-and-act loop (see below).

4. **Stop (or wipe state)**:
   ```bash
   scripts/agent_gui.sh down
   # or stop and wipe build/agent-gui/data and build/agent-gui/shots:
   scripts/agent_gui.sh reset
   ```

## Agent loop protocol

When driving the UI, follow this loop:

1. **Capture and inspect**: run `scripts/agent_gui.sh screenshot [name]` and inspect the PNG path it prints.
2. **Perform one action**: send a single `click`, `drag`, `scroll`, `type`, `key`, or `focus` command.
3. **Verify**: take another screenshot before deciding the next action.
4. **Prefer keyboard shortcuts for dense controls**: use `key Tab`, `key Shift+Tab`, `key Return`, `key Escape`, or `key ctrl+a` when click targets are small or obscured.
5. **Track secondary windows**: run `scripts/agent_gui.sh windows` whenever an action may have opened a dialog or the Explore window, and `scripts/agent_gui.sh focus "<title>"` to bring it to the front.
6. **Check logs on failure**: run `scripts/agent_gui.sh logs` (or `logs 100`) if the UI stalls or a connection behaves unexpectedly.

Tips:
- Coordinates are absolute screen pixels (including the `openbox` title bar at the top of the window).
- Click the center of elements read from the latest screenshot. Clicking on a text field's border or label can leave focus in the previously focused field.
- Pasting a connection string and clicking **Apply** is the fastest way to fill the New Connection form.
- The New Connection screen says "OS keychain", but in this dev-only mode `AgentGui.kt` uses an in-memory credential store (`SecretsManager.initMemoryStore()`) rather than `SAFEDB_KEYCHAIN_BACKEND=disabled`. Saved connections persist across restarts while passwords do not — after a restart, edit the connection (from the card gear or Command Palette `Edit connection: ...`) to re-enter its password.

## Script commands

| Command | Purpose |
| --- | --- |
| `scripts/agent_gui.sh up [--vnc] [--foreground]` | Start `Xvfb`, `openbox`, optional `x11vnc`, and `runAgentGui`. A failed `up` and a foreground exit run `down` through the `EXIT` trap, including `HUP`. A successful background `up` disarms that trap before returning. |
| `scripts/agent_gui.sh down` | Stop the app, VNC, `openbox`, and `Xvfb` (idempotent). |
| `scripts/agent_gui.sh status` | Show process status and visible windows. |
| `scripts/agent_gui.sh screenshot [name] [--window]` | Save root or active-window PNG under `build/agent-gui/shots/` and print its path. |
| `scripts/agent_gui.sh windows` | List visible window IDs, `WIDTHxHEIGHT+X+Y` geometry, and titles. |
| `scripts/agent_gui.sh focus <title-substring>` | Activate and raise a window matching `<title-substring>`. |
| `scripts/agent_gui.sh click X Y [left\|right\|double]` | Move pointer to `(X, Y)` and click. |
| `scripts/agent_gui.sh drag X1 Y1 X2 Y2` | Drag with the left mouse button from `(X1, Y1)` to `(X2, Y2)`. |
| `scripts/agent_gui.sh scroll up\|down [N [X Y]]` | Scroll `N` wheel ticks (default `3`), optionally moving to `(X, Y)` first (`N` is required before `X Y`, e.g. `scroll down 5 400 500`). |
| `scripts/agent_gui.sh type "<text>"` | Type text into the focused window. |
| `scripts/agent_gui.sh key <combo>...` | Press key(s), e.g. `ctrl+a`, `Return`, `Tab`, `Escape`. |
| `scripts/agent_gui.sh logs [N]` | Tail the last `N` lines of `build/agent-gui/app.log` (default `50`). |
| `scripts/agent_gui.sh reset` | Stop everything and delete `build/agent-gui/data` and `build/agent-gui/shots` (an overridden `SAFEDB_AGENT_GUI_DATA_DIR` outside those paths is left in place). |

Environment overrides: `AGENT_GUI_DISPLAY` (default `:99`), `AGENT_GUI_SCREEN` (default `1600x1000`), and `SAFEDB_AGENT_GUI_DATA_DIR` (default `build/agent-gui/data`).

## Docker fixture connections

All test-only fixture connection parameters and passwords live in [testing.md](testing.md#integration-tests) and [compose.yaml](../compose.yaml). For TLS testing, MySQL TLS listens on `127.0.0.1:3307` (`safedb_ssl`, user `safedb`) and generated CAs live under `.docker/safedb-ssl/`.

## Watching live from Windows

Start the display with VNC enabled:

```bash
scripts/agent_gui.sh up --vnc
```

`x11vnc` binds to `localhost:5900` (`-localhost -forever -nopw`), which WSL2 forwards to Windows automatically. Connect any Windows VNC viewer (such as TigerVNC or RealVNC Viewer) to `localhost:5900`.

## Troubleshooting

- **Window never appears / `up` fails**: run `scripts/agent_gui.sh logs 100` to inspect Gradle compilation or runtime stack traces.
- **Black or blank screenshots**: check `app.log` for OpenGL/Skiko warnings. If Mesa/Xvfb GL fails on your machine, add `"-Dskiko.renderApi=SOFTWARE_FAST"` to `jvmArgs` in [`gradle/dev-tasks.gradle.kts`](../gradle/dev-tasks.gradle.kts) and restart with `down` then `up`.
- **Clicks don't register or typing goes nowhere**: run `scripts/agent_gui.sh windows` to verify which window is open, then `scripts/agent_gui.sh focus "Safe-DB"` (or the Explore window title) before sending keys or clicks.
