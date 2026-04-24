# KonsoleSSH

Multi-tab SSH terminal for Android — inspired by KDE Konsole.

> **Language:** English · [Magyar](README.hu.md) · [Español](README.es.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · [Slovenčina](README.sk.md) · [Română](README.ro.md)

## Screenshots

| Welcome screen | New SSH connection |
| --- | --- |
| ![Welcome](play_screenshot/Screenshot_20260422_094020.png) | ![New connection](play_screenshot/Screenshot_20260422_094447.png) |

| Tmux cheat sheet | Linux cheat sheet |
| --- | --- |
| ![Tmux cheat sheet](play_screenshot/Screenshot_20260422_094521.png) | ![Linux cheat sheet](play_screenshot/Screenshot_20260422_094530.png) |

## Features

### Tabs and navigation

- **Multi-tab UI** — TabLayout + ViewPager2, each tab owns an independent SSH session, identified by a UUID-based `TabInfo`
- **Three fixed pages** — welcome screen (position 0), Linux cheat sheet and tmux cheat sheet (last two slots); SSH tabs live in between
- **Connection-status dot on every tab** — green (CONNECTED), yellow (CONNECTING), red (DISCONNECTED), hidden (NONE)
- **Long-press a tab** → rename dialog
- **Tab ✕ button** — asks for confirmation on active sessions; closes instantly when disconnected
- **Tab-row scroll** — when tabs overflow, gently pulsing ◀/▶ hint buttons appear (ObjectAnimator ±8dp, 900ms reverse infinite)
- **Tab-indicator height set to 0dp** with a single tab (less visual noise)

### Tab picker / saved connections (`+` menu)

- **BottomSheetDialogFragment** — full-height (STATE_EXPANDED, MATCH_PARENT)
- **Active tabs + saved connection tree**, stacked
- **Tree grouping by underscore prefix** — `acme_prod_web`, `acme_prod_db` → `acme_` → `prod_` group; a single leaf is never grouped
- **Depth-based padding** — `16dp + depth × 20dp`
- **Expanded state survives dialog reopens** (companion-object backed)
- **Icons** — group: 📁 / 📂 (closed/open), leaf: ⚡
- **Leaf rows** — `user@host:port` sub-line, edit + delete on the right
- **Group rows** — leaf-count sub-line, ▶/▼ arrow on the right
- **Most-recently used username** pre-fill for new connections

### New / edit dialog

- **Edit mode** — full `ConnectionConfig` Gson-serialised through the Bundle and restored into fields
- **Auth switch** RadioGroup: password ↔ private key, the irrelevant layout hides automatically
- **Private key with file picker** (read-only preview), separate passphrase field
- **`.pub` warning** — toast if the user tries to load the public half of the key
- **Key-status line** — PEM loaded / failed / none
- **Jump-host spinner** — lists saved connections (excluding self)
- **Automatic jump suggestion** — detects private-IP prefixes (`10.`, `172.`, `192.`) and expands the jump section
- **Validation** — host and username required; port 1–65535, default 22

### Connection authentication

- **JSch (mwiede fork)** — password, publickey, or both combined (`buildPreferredAuths`)
- **Interactive password prompt** — when none is saved, a `KonsoleDialog`-styled AlertDialog (white text, transparent bg)
- **Keyboard-interactive auth** — server-side prompts go through the same dialog
- **30 s prompt timeout** (CountDownLatch) — the caller thread is cleanly released if the user doesn't answer
- **Main-thread guard** — the dialog always appears on the UI thread (Handler post)
- **Jump-host chain** — mwiede fork's `setPortForwardingL(0, target_host, target_port)` on a random port + second session to loopback
- **Jump progress messages** printed to the terminal — `Jump: host:port → Connecting`, then `Jump OK → Connecting: target:port`
- **"Jump not found" error** — when a referenced jump-connection ID no longer exists

### Terminal emulator (Canvas-based)

**Rendering**

- Custom `TerminalView`, per-cell `canvas.drawText`, 80×24 minimum base
- Cell fields: character (String, surrogate-pair aware), fg/bg, bold, underline, reverse
- **NerdFont bundled** (`assets/fonts/NerdFont.ttf`); falls back to `Typeface.MONOSPACE` on asset error
- Font range 6sp…40sp; first-layout auto-sizes to fit an 80-column target
- **Zoom +/− buttons** in the toolbar (`SharedPreferences("settings", "font_size")`), persists across restarts
- **Per-app** font size (not per-connection)
- Underline rendered via `drawLine` at cellH-1
- Cursor inversion at draw time (fg↔bg)
- **Cursor blink** 600 ms on / 300 ms off

**ANSI/VT state machine**

- NORMAL / ESCAPE / CSI / OSC / DCS / CHARSET states
- SGR: 16 base + 8 bright + 256-cube + truecolor (`38;2;r;g;b`), bold/underline/reverse + resets
- Cursor: A/B/C/D, H/f, G, E/F, s/u, ESC 7/8 (DEC legacy)
- Erase: J, K, X, L/M (insert/delete lines), P/@ (chars)
- **Scroll region** — r, S/T (scroll up/down)
- **Alt screen** — 47 / 1049 toggle (vi, top, less, mc preserve and restore the screen)
- **DECCKM app-cursor mode** — `ESC[A/B/C/D` ↔ `ESC O A/B/C/D`
- **Bracketed paste mode** (2004) — pasted text wrapped in `ESC[200~ … ESC[201~`

**Text selection**

- **Long-press 400 ms** → starts selection, floating `ActionMode` (Copy/Paste)
- Live end-point tracking during drag, `invalidateContentRect`
- Forward/backward direction normalisation
- `buildSelectedText` — trims each line, joins with `\n`
- Tap during selection → clears the selection
- **ViewPager swipe block** (`requestDisallowInterceptTouchEvent`) during selection/scroll

**Scrolling and touch**

- Vertical scroll: `scrollRowOff` 0…`scrollback.size`
- **Scrollback** ring of 3000 lines
- Horizontal scroll is optional (disabled on the welcome page)
- **8 px move threshold** — tap vs. scroll discrimination
- Auto bottom-gravity on new output (`scrollRowOff = 0`)
- Tap → `focusAndShowKeyboard()`
- **Shift+PageUp/Down** on a hardware keyboard scrolls the scrollback

**IME integration**

- Custom `TerminalInputConnection` (BaseInputConnection)
- Sentinel trick: the buffer always starts with one space sentinel, user input uses `removePrefix`
- Works with swipe/glide IMEs
- `deleteSurroundingText` → manual DEL (0x7F) byte stream
- `inputType=TYPE_NULL` — no autocomplete or suggestions in the terminal

### Hardware keyboard

- Enter→13, Tab→9, Esc→27, DEL→127
- Home/End → `ESC[H` / `ESC[F`
- PageUp/PageDown → `ESC[5~` / `ESC[6~`
- **F1–F12** → `ESC O P/Q/R/S`, `ESC[15~`, `ESC[17~`, …
- Arrow keys → codes according to app-cursor mode
- Shift+PageUp/Down diverted to scrollback (not sent as input)

### On-screen key bar

**Main row** (40 dp)

- **⌨** — toggles the system IME. Real IME-visibility read via `WindowInsetsCompat`, so a keyboard dismissed by the back gesture is cleanly reopened.
- **Fn** — toggles the F1–F12 row
- **CTRL / SHIFT / ALT / ALTGR** — sticky modifiers, highlighted with `keybar_mod_active` while active; **auto-reset** after any key send
- **CTRL** also opens a dedicated Ctrl-combo row (A/B/C/D/V/Z)
- **ESC, TAB** — direct byte
- **↑** — toggles an arrow-key row (← ↑ ↓ →)
- **📁** — `ActivityResultContracts.OpenDocument()` → SFTP upload
- Visual `KeyBarDivider` separators in between

**Fn row** (36 dp) — F1–F12 escape sequences, every press **flashes** for 300 ms (accent colour)

**Ctrl row** — dynamically generated (`LayoutInflater` + `item_keybar_button`)

- `Ctrl+C`: copies the selection to the in-app clipboard with a toast, otherwise sends ETX (0x03)
- `Ctrl+V`: pastes from the in-app clipboard → `pasteText` + toast
- A/B/D/Z: code = char - 'A' + 1 (1…26)

**Arrow row** (36 dp) — ← ↑ ↓ → respecting app-cursor mode

**Scroll hints on every row** — ◀/▶ animated only when actually scrollable; `canScrollHorizontally(±1)` checked after every event; animator cancel + reset in onDestroy

### Special key combinations (`applyModifiers`)

- Ctrl+letter → 1…26 (standard control codes)
- **Ctrl+space → 0x00 (NUL)**
- **Ctrl+[ → 27 (ESC)**
- Shift + lowercase → uppercase (soft-input fallback)
- Alt / AltGr → `ESC` + original byte (meta prefix)

### Clipboard

**In-app clipboard** (`TerminalClipboard`)

- Singleton `var text: String?`
- Ctrl+C with a selection → **in-app** clipboard (not the system one) + discrete toast
- Ctrl+V → in-app → `pasteText`

**System clipboard**

- ActionMode **Copy** → `ClipboardManager.setPrimaryClip`
- ActionMode **Paste** → `coerceToText` → `pasteText`
- On paste `\n` → `\r`, wrapped if bracketed paste mode is active

### SFTP file upload

- 📁 → `OpenDocument` picker
- Filename from `OpenableColumns.DISPLAY_NAME`, fallback `uri.lastPathSegment`
- Size query → progress bar determinate / indeterminate switch
- **Progress dialog** — filename, `X.X MB / Y.Y MB` or `Z.Z MB` when total is unknown, `setCancelable(false)`
- On success `KonsoleToast.showWithAction` — "Uploaded: ~/filename" + **Undo** button → `deleteRemoteFile`
- On error, friendlyError-mapped localized message

### Status and feedback

- **Status bar** (20 dp) above the terminal: `No connection`, `Connecting: host…`, `Connected: host`, `Disconnected: host`
- **Reconnect button** at the centre of the terminal, ↺ icon, only shown when DISCONNECTED
- **KonsoleToast** — custom toast, bottom 100 dp margin, 4000 ms default / 3000 ms actionable, dismiss animation (scale+alpha, 250 ms)

### Error messages (friendlyError mapping)

Raw JSch exceptions are mapped to readable text (localized):

- `connection refused` → "The server refused the connection"
- `timed out` / `timeout` → "Timeout"
- `no route to host` → "No route to host"
- `network unreachable` → "Network unreachable"
- `unknown host` → "Unknown host"
- `auth fail` / `authentication` → "Authentication failed"
- `connection closed` / `closed by foreign host` → "Connection closed"
- `broken pipe` → "Broken pipe"
- `port forwarding`, `channel` → dedicated messages
- Otherwise: the exception message regex-stripped of stack class prefixes

### Background and lifecycle

- **`SshForegroundService`** — `START_STICKY`, deliberately does **not** stop sessions on `onTaskRemoved`
- **Output buffer** — 256 KB ring per session, `ByteArrayOutputStream` with overflow trim
- **Replay on reconnect** — after the fragment binds, the buffer is replayed into the terminal so prior output is visible
- **Two NotificationChannels** — `ssh_idle` (inactive) and `ssh_active` (active), different badge behaviour
- **Notification badge** shows the active session count
- Low-priority notification (`PRIORITY_LOW`, `setSilent`)
- `onRebind` enabled (`onUnbind → true`)
- `TerminalFragment.onDestroy` does **not** disconnect (service owns the session)
- `onAttach`/`onDetach` listener bookkeeping (memory-leak safe)

### Connection indicator state machine

- `NONE → CONNECTING`: new `connect()` call
- `CONNECTING → CONNECTED`: shell channel open (`onConnected`)
- `CONNECTING → DISCONNECTED`: `onError` (auth, timeout, refused)
- `CONNECTED → DISCONNECTED`: read loop terminates or explicit `disconnectSession`
- `DISCONNECTED → CONNECTING`: Reconnect button

### Security

- **EncryptedSharedPreferences** — AES256_GCM value scheme, AES256_SIV key scheme, Android Keystore-backed MasterKey
- **Legacy migration** on first run — old plain-text profiles moved into the encrypted store, then the legacy store is cleared
- **Plain-prefs fallback** — if keystore init fails (warning logged), profiles are not lost
- **JSch `StrictHostKeyChecking=no`** — trust-on-first-use
- Credentials never leave the device

### Welcome / cheat sheets

- **Welcome banner** — three shades of orange ANSI-coloured "KonsoleSSH" + 9 lines of description (`[38;5;244m` dim)
- Fixed 16 sp font on the welcome page
- Horizontal scroll disabled on the welcome page
- **Linux cheat sheet** — `top`, `df`, `du`, `dd`, `tail`, `head`, `grep`, `egrep`, `awk`, `sed`, `tr`, `ip`, `mc` + `⚠` warning icon on destructive commands (`dd`, `sed -i`)
- **Tmux cheat sheet** — sessions/windows/panes/layouts/prefix/resize/scroll/paste
- **Locale-selective content** — HU and EN written separately, not just label translation
- `scrollToTop()` on open
- Terminal resize on a cheat tab fully re-renders (`clear()` + new banner)

### Edge-to-edge and orientation

- `ViewCompat.setOnApplyWindowInsetsListener` on every Activity
- `Type.systemBars() | Type.displayCutout()` + `Type.ime()` handled together — in landscape content stays above the nav bar, IME push pulls it up
- `screenOrientation=fullSensor` — auto-rotate portrait + landscape
- `configChanges` configured so the Activity is not destroyed on rotation
- `windowSoftInputMode=adjustResize`
- `onSizeChanged` → font recalculation, termRows/termCols recalibration

### Localisation

- 7 languages: **English** (default), **Hungarian**, **German**, **Spanish**, **French**, **Slovak**, **Romanian**
- `supportsRtl="true"` — ready for future RTL languages
- Follows the system language
- Cheat-sheet content is also localised (HU/EN)

### Comfort details

- Focus returns to the terminal automatically after the ActionMode closes
- After releasing a modifier button, focus returns to the terminal
- Sticky modifier button changes colour while active (clear visual state)
- Every key-bar tap flashes for 300 ms
- DEL (0x7F) sent as a distinct code — not a Backspace alternative
- Surrogate pair / emoji stored as a single logical character per cell
- Terminal resize → `resize(tabId, cols, rows)` to the PTY (`vim`, `htop` pick up the new size)
- Input-connection `resetToSentinel` after every commit — IME state always clean
- "Connection closed" message printed to the terminal on disconnect
- Ctrl+C / Ctrl+V show a discrete toast: "Copied" / "Pasted"
- Buttons use `selectableItemBackgroundBorderless` ripple
- `KonsoleDialog` style: white text, grey hint, transparent bg
- Active connection close/delete/exit always asks for confirmation

### Constants

- Connect timeout: 15 s, shell-connect: 10 s, password prompt: 30 s
- Output buffer: 256 KB / session
- Scrollback: 3000 lines
- Font: 6 sp – 40 sp
- Long press: 400 ms
- Touch move threshold: 8 px
- Scroll-hint pulse: 900 ms period, ±8 dp
- Toast: 4000 ms default, 3000 ms actionable, dismiss anim 250 ms
- KonsoleToast bottom margin: 100 dp
- Cursor blink: 600 ms on / 300 ms off

## Architecture

Sessions are owned by `SshForegroundService`, not by fragments. This means:

- Connections survive activity recreation (rotation, back press, task removal)
- `TerminalFragment` binds/unbinds the service on `onStart`/`onStop` and replays the output buffer on reconnect
- `OutputBuffer` keeps the last 256 KB of output per session for replay

## Project structure

```
app/src/main/java/hu/billman/konsolessh/
├── model/
│   ├── ConnectionConfig.kt       — SSH connection data (host, port, user, auth, jump)
│   └── SavedConnections.kt       — EncryptedSharedPreferences-backed profile manager
├── ssh/
│   ├── SshSession.kt             — JSch wrapper (connect/read/write/resize/jump, SFTP upload, remote rm)
│   └── SshForegroundService.kt   — Foreground service; owns sessions, output buffers and upload progress
├── terminal/
│   ├── TerminalView.kt           — Canvas-based terminal emulator (VT100/xterm-256color)
│   ├── AnsiParser.kt             — ANSI/VT state machine
│   └── TerminalClipboard.kt      — Shared in-app clipboard
└── ui/
    ├── MainActivity.kt           — Tab coordination, key bar, upload flow, window insets
    ├── TerminalFragment.kt       — Terminal / cheat-sheet / welcome
    ├── TerminalPagerAdapter.kt   — ViewPager2 adapter (fixed pages + SSH tabs)
    ├── TabPickerSheet.kt         — Tab + saved-connection picker with tree grouping
    ├── NewConnectionDialog.kt    — Create / edit connection dialog
    ├── ConnectionEditActivity.kt — Saved connections management
    └── KonsoleToast.kt           — In-app toast with optional action button
```

## Build

```
Kotlin       2.2.10
AGP          9.2.0
Gradle       9.4.1
Java target  17
namespace    hu.billman.konsolessh
minSdk       26   (Android 8.0)
targetSdk    35   (Android 15)
```

```bash
# Debug build + unit tests
./gradlew :app:testDebugUnitTest

# Signed release App Bundle (requires a configured keystore)
./gradlew :app:bundleRelease
```

The **release** build is R8-minified and resource-shrunk. Reflection-heavy
code (JSch, Gson model classes) is preserved via `app/proguard-rules.pro`.
A `mapping.txt` is produced so the Play Console can deobfuscate stack traces.

## Usage

1. **New connection**: tap `+` → *New connection…* → enter host, port (22), username, password or SSH key.
2. **Jump host**: if the target is on an internal range (10./172./192.) the jump section appears automatically; pick a saved gateway connection.
3. **Close tab**: tap `✕` on the tab label (confirmation if a connection is active).
4. **Rename tab**: long-press the tab.
5. **Zoom**: `+` / `−` on the toolbar (setting persists).
6. **Cheat sheets**: swipe right past the welcome page — Linux, then tmux.
7. **SFTP upload**: 📁 in the key bar → pick a file → uploads to the remote home (`~`), confirmation toast with an Undo.
8. **Close app**: `+` → *Exit* at the bottom of the menu.

## Dependencies

| Library            | Version           | Purpose                                   |
| ------------------ | ----------------- | ----------------------------------------- |
| JSch (mwiede fork) | 0.2.16            | SSH + SFTP                                |
| kotlinx-coroutines | 1.8.1             | Async SSH I/O                             |
| ViewPager2         | 1.1.0             | Tab navigation                            |
| Material Design    | 1.12.0            | UI components                             |
| Gson               | 2.10.1            | Connection profile (de)serialisation      |
| androidx.security  | 1.1.0-alpha06     | EncryptedSharedPreferences                |
| AndroidX Core      | 1.13+             | ServiceCompat, WindowInsetsCompat         |

## Permissions

| Permission                            | Reason                                                  |
| ------------------------------------- | ------------------------------------------------------- |
| `INTERNET`                            | SSH connection                                          |
| `ACCESS_NETWORK_STATE`                | Network reachability checks                             |
| `CHANGE_NETWORK_STATE`                | Foreground service (connectedDevice type)               |
| `FOREGROUND_SERVICE`                  | Background service startup                              |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Network foreground service (API 34+)                    |
| `POST_NOTIFICATIONS`                  | Persistent session-keeper notification (API 33+)        |

No data is collected, shared or uploaded. Credentials stay on the device in the encrypted keystore-backed store.

## Requirements

- Android 8.0 (API 26) or newer
- Portrait and landscape both supported

## License

This application is released under the **GPL-3.0-or-later** license — full text in the [LICENSE](LICENSE) file. It is free software: you may freely use, study, modify and redistribute it, provided that any derived works remain under the same license.

## Release history

- **1.0.7** — GPL-3.0 license added, F-Droid-compatible build (conditional signing config)
- **1.0.6** — reconnect status indicator correctly turns green (no longer sticks on yellow);
  keyboard icon reliably opens the IME on Android 14+ (real `WindowInsetsCompat`-based
  visibility replacing the deprecated `SHOW_FORCED`)
- **1.0.5** — internal fix (skipped version)
- **1.0.4** — new, slimmer `>_` prompt app icon (stroke design)
- **1.0.3** — Play-store refined app icon and assets
- **1.0.2** — package renamed to `hu.billman.konsolessh`, edge-to-edge insets
  fix, soft keyboard only on explicit request, SFTP upload with progress dialog
  and Undo toast, R8 minification, Gradle 9.4.1 / AGP 9.2.0
- **1.0.1** — `targetSdk = 35`, initial Play submission
- **1.0.0** — multi-tab SSH, jump host, saved-connection tree, Linux and tmux
  cheat sheets, Hungarian translation
