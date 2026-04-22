# KonsoleSSH

Multi-tab SSH terminal for Android — inspired by KDE Konsole.

> **Language:** English · [Magyar](README.hu.md) · [Español](README.es.md) · [Deutsch](README.de.md) · [Français](README.fr.md)

## Screenshots

| Welcome screen | New SSH connection |
| --- | --- |
| ![Welcome](play_screenshot/Screenshot_20260422_094020.png) | ![New connection](play_screenshot/Screenshot_20260422_094447.png) |

| Tmux cheat sheet | Linux cheat sheet |
| --- | --- |
| ![Tmux cheat sheet](play_screenshot/Screenshot_20260422_094521.png) | ![Linux cheat sheet](play_screenshot/Screenshot_20260422_094530.png) |

## Features

### Connections

- **Multi-tab UI** — TabLayout + ViewPager2, each tab owns an independent SSH session
- **SSH connections** — JSch library, password **or** private-key (PEM) authentication with optional passphrase
- **Jump host support (`ssh -J`)** — reach internal hosts through a saved gateway connection (local port forwarding)
- **Interactive password prompt** — dialog appears at connect time if no password is stored
- **Saved connections** — profiles encrypted at rest with AES256 (`EncryptedSharedPreferences`, Android Keystore); alphabetical order
- **Tree-grouped picker** — connections named with underscores (e.g. `acme_prod_web`, `acme_prod_db`, `foobar_dev_01`) are automatically collapsed by shared prefix. Here `acme_` → `prod_` groups the first two, while `foobar_dev_01` stays flat because it is alone under its prefix. Groups expand and collapse with a ▶/▼ arrow.

### Terminal

- **Canvas terminal emulator** — 256 colour + truecolor, bold, underline, reverse video, scrollback buffer
- **ANSI/VT100 + xterm-256color PTY** — bash/zsh/fish prompts render correctly
- **NerdFont support** — JetBrainsMono Nerd Font bundled; emoji and surrogate-pair characters display correctly
- **Copy on selection** (long-press + drag) and **paste from the clipboard**
- **Font size zoom** — zoom buttons on the toolbar, setting persists
- **Reconnect button** — appears in the center of the terminal when a connection drops

### On-screen key bar

The bottom bar exposes everything the Android soft keyboard can't:

- **⌨** — toggles the system soft keyboard. _The soft keyboard only opens for this button or when you tap the terminal itself; no other key-bar button pops it up._
- **Fn** — toggles a row of **F1 – F12** function keys
- **CTRL / SHIFT / ALT / ALTGR** — sticky one-shot modifiers (auto-reset after the next key press)
- **CTRL** also opens a Ctrl-combo row with `Ctrl+A`, `Ctrl+B`, `Ctrl+C`, `Ctrl+D`, `Ctrl+V`, `Ctrl+Z`
  - `Ctrl+C` copies the current selection if any, otherwise sends `ETX`
  - `Ctrl+V` pastes from the clipboard
- **ESC, TAB** — one-tap keys
- **↑** — toggles an arrow-key row (← ↑ ↓ →)
- **📁** — pick a local file and **upload it over SFTP** to the remote home directory. A progress dialog shows transferred / total MB. After success, a 3-second confirmation toast offers an **Undo** button that deletes the just-uploaded file from the server.

### Status / UX

- **Connection status indicators** — green/yellow/red dots on tabs and in the tab picker
- **Welcome page** — swipeable intro; swipe right for the cheat sheets
- **Linux cheat sheet** — inline reference for `top`, `df`, `du`, `dd`, `tail`, `head`, `grep`, `egrep`, `awk`, `sed`, `tr`, `ip`, `mc`
- **Tmux cheat sheet** — sessions, windows, panes, layouts, prefix bindings
- **Background persistence** — active SSH sessions stay alive on screen lock and task removal via Foreground Service
- **Notification badge** — shows the active-connection count
- **Close-app button** — in the tab picker menu; confirms if active connections exist
- **Friendly error messages** — human-readable text instead of raw Java exceptions
- **Edge-to-edge layout** — correct window-insets handling on Android 15; the content stays above the navigation bar in landscape and is pushed up when the soft keyboard opens

### Localisation

- English by default, **Hungarian translation** included (`values-hu/`). The app follows the system language; `Locale.getDefault().language == "hu"` switches the cheat-sheet body to Hungarian too.

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

## Release history

- **1.0.2** — package renamed to `hu.billman.konsolessh`, edge-to-edge insets
  fix, soft keyboard only on explicit request, SFTP upload with progress dialog
  and Undo toast, R8 minification, Gradle 9.4.1 / AGP 9.2.0
- **1.0.1** — `targetSdk = 35`, initial Play submission
- **1.0.0** — multi-tab SSH, jump host, saved-connection tree, Linux and tmux
  cheat sheets, Hungarian translation
