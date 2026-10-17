# ScreenDimmer

A lightweight, always-dark screen dimmer for Windows and Linux. It dims your
screen with a pure-black translucent overlay instead of touching the system's
brightness APIs — so it works on desktop and external monitors, can go darker
than the hardware minimum, and is completely independent of the system
brightness driver.

## Download

Grab what fits from the [latest release](https://github.com/leekayden/dimmer/releases/latest):

- `ScreenDimmer-1.1.0-windows-standalone.zip` — **no Java needed**: unzip and
  run `ScreenDimmer\ScreenDimmer.exe` (private JRE bundled)
- `ScreenDimmer-1.1.0-linux-standalone.tar.gz` — **no Java needed**: extract
  and run `sh ScreenDimmer/dimmer.sh` (bundled Linux runtime, experimental)
- `ScreenDimmer-1.1.0.jar` — universal fat jar, needs Java 17+
- `ScreenDimmer-1.1.0-windows.zip` / `ScreenDimmer-1.1.0-linux.zip` — jar +
  launcher script

Run it and look for the **blue crescent-moon icon in the system tray**
(bottom-right corner; if you don't see it, click the `^` chevron to show hidden
tray icons).

## Controls

- **Left-click the tray icon** — opens the dark control popup. The slider spans
  a **sun** (left, 0% — no dimming) to a **moon** (right, 90% — maximum
  dimming): drag it, or click either icon to jump straight to that end. The
  sun/moon icon lights up when you reach its end. **Disable/Enable** toggles
  the overlay. Clicking anywhere outside the popup minimizes it back to the
  tray (Esc works too). The **×** button in the popup's top-right corner quits
  the app completely (the tray right-click menu has Quit as well).
  While dimming is **disabled**, the slider row (sun, slider, moon) grays out
  and is hatched over with diagonal stripes (like a blocked-off parking spot)
  so the state is obvious at a glance — the Enable button stays clickable.
- **Right-click the tray icon** — small dark menu with **Disable/Enable
  dimming** and **Quit Screen Dimmer** (quit lives here to keep the popup
  minimal).
- Settings are remembered in `~\.screendimmer.properties` (`dim`, `enabled`).

That's the whole UI — no window, no taskbar entry, no focus stealing.

## How it works

One borderless, always-on-top black window per monitor at a configurable
opacity (Java's uniform window translucency). JNA makes the overlay
click-through: on Windows via the `WS_EX_TRANSPARENT` window style, on
Linux/X11 via an empty XShape input region — so every mouse click and keystroke
passes straight through to whatever is underneath; the window also never takes
focus and has no taskbar/Alt-Tab presence. A background check every 4 seconds
keeps the overlays in sync if a monitor is added, removed or rearranged.

Notes:
- Fullscreen **exclusive** games and the secure desktop (UAC prompts, login
  screen) draw over the overlay — they will not appear dimmed.
- The dim level is capped at 90% so you can't black the screen out.

## Running it

Double-click **`Start ScreenDimmer.bat`** (or the jar directly). Memory usage
is roughly **60 MB** working set (JVM baseline; heap capped at 48 MB).

Start with Windows: press `Win+R`, type `shell:startup`, press Enter, and put a
shortcut to `Start ScreenDimmer.bat` in the folder that opens.

### Linux (experimental)

Requires Java 17+ and an X11 session (or a Wayland session with XWayland); a
running compositor is needed for window translucency. Run with:

```sh
sh dimmer.sh
```

Click-through is implemented via the XShape extension (an empty input region)
and is marked **experimental** — it has not been hand-tested on real Linux
desktops yet. Safety first: if click-through or translucency cannot be
established, the app runs in tray-only mode with dimming disabled, so the
overlay can never trap your mouse. The tray icon needs a StatusNotifier host
(KDE/XFCE/MATE work; on GNOME enable an AppIndicator extension, otherwise a
fallback control window opens instead).

## Files

- `ScreenDimmer.java` — the whole app, one file (~480 lines)
- `ScreenDimmer.jar` — runnable fat jar (app + JNA + FlatLaf bundled, ~2.9 MB)
- `jna-5.17.0.jar` — JNA, only needed for rebuilding
- `flatlaf-3.5.4.jar` — FlatLaf (Swing look-and-feel), only needed for rebuilding
- `Start ScreenDimmer.bat` — launch (headless, no console)
- `Build.bat` — rebuild the jar from source (needs a JDK on PATH)

## UI

The control popup and tray menu are built with Java Swing styled by
**FlatLaf** (`FlatDarkLaf`, always dark, accent `#7aa2f7`): the dim slider is a
real `JSlider`, the toggle and menu rows are real `JButton`s. The tray icon and
the sun/moon anchors are still drawn in code, so there are no image assets.
The popup/menu live in undecorated `JDialog` shells that close on focus loss,
which is what makes tray popups behave reliably on Windows.

## Rebuilding

```
Build.bat
```

Requires a JDK (built and tested with Temurin 17). JNA and FlatLaf are the only
dependencies and are merged into the jar, so the app stays a single
double-clickable file.
