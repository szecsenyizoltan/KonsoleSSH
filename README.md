# KonsoleSSH

Multi-tab SSH terminal for Android tablets — inspired by KDE Konsole.

## Features

- **Multi-tab UI** — TabLayout + ViewPager2, each tab has its own independent SSH session
- **SSH connections** — JSch library, password + private key (PEM) authentication
- **Jump host support** — reach internal networks via a gateway server (port forwarding)
- **Interactive password prompt** — dialog appears at connect time if no password is stored
- **Saved connections** — profiles stored as JSON in SharedPreferences (alphabetical order)
- **Canvas terminal emulator** — 256-color + truecolor, bold, underline, reverse video, scrollback buffer
- **ANSI/VT100 + xterm-256color PTY** — bash/zsh/fish prompts render correctly
- **NerdFont support** — JetBrainsMono Nerd Font bundled; emoji and surrogate-pair characters display correctly
- **Extra key bar** — ESC, TAB, Ctrl+C, Ctrl+D, Ctrl+V, Ctrl+Z, arrows, Shift, Alt, AltGr, F1–F12
- **Connection status indicators** — green/yellow/red dots on tabs and in the tab picker
- **Font size zoom** — zoom buttons on toolbar, setting persists across restarts
- **Welcome page** — swipeable intro screen without horizontal scroll
- **Linux cheatsheet** — detailed command reference (top, df, du, dd, tail, head, grep, egrep, awk, sed, tr, ip, mc)
- **Background persistence** — active SSH sessions stay alive on screen lock and swipe-away via Foreground Service
- **Reconnect button** — appears in the center of the terminal when a connection drops
- **Notification badge** — shows active connection count on the app icon; tapping opens the app
- **Close app button** — in the tab picker menu; confirms if active connections exist
- **Friendly error messages** — human-readable text instead of raw Java exceptions
- **App icon** — KONSOLE + SSH labels, dark background, terminal `> _` prompt graphic

## Architecture

Sessions are owned by `SshForegroundService`, not by fragments. This means:
- Connections survive activity recreation (rotation, back press, task removal)
- `TerminalFragment` binds/unbinds the service on `onStart`/`onStop` and replays the output buffer on reconnect
- `OutputBuffer` keeps the last 256 KB of output per session for replay

## Project structure

```
app/src/main/java/hu/szecsenyi/konsolessh/
├── model/
│   ├── ConnectionConfig.kt       — SSH connection data (host, port, user, auth, jump)
│   └── SavedConnections.kt       — SharedPreferences-based profile manager
├── ssh/
│   ├── SshSession.kt             — JSch SSH wrapper (connect/read/write/resize/jump)
│   └── SshForegroundService.kt   — Foreground service; owns all sessions and output buffers
├── terminal/
│   └── TerminalView.kt           — Canvas-based terminal emulator (VT100/xterm-256color)
└── ui/
    ├── MainActivity.kt            — Main Activity; TabLayout + ViewPager2 coordination, key bar
    ├── TerminalFragment.kt        — Terminal Fragment (SSH / cheatsheet / welcome)
    ├── TerminalPagerAdapter.kt    — ViewPager2 adapter (fixed pages + SSH tabs)
    ├── TabPickerSheet.kt          — Tab picker bottom sheet
    ├── NewConnectionDialog.kt     — New / edit SSH connection dialog
    └── ConnectionEditActivity.kt  — Saved connections management
```

## Build

```bash
# Android Studio: File → Open → KonsoleSSH → Run
# or command line (JDK 21 required):
JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64 ./gradlew assembleDebug
```

## Usage

1. **New connection**: tap `+` → select a saved connection or create a new one
2. **Connect**: enter host, port (22), username, password / SSH key
3. **Jump host**: fill in the gateway server details to reach internal machines
4. **Close tab**: tap `✕` on the tab label (confirmation required if connection is active)
5. **Rename tab**: long-press the tab
6. **Zoom**: `+` / `−` buttons on the toolbar (setting persists)
7. **Cheatsheet**: swipe left to the last page — Linux command reference
8. **Close app**: tap `+` → *Alkalmazás bezárása* at the bottom of the menu

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| JSch (mwiede fork) | 0.2.16 | SSH protocol |
| kotlinx-coroutines | 1.8.1 | Async SSH I/O |
| ViewPager2 | 1.1.0 | Tab navigation |
| Material Design 3 | 1.12.0 | UI components |
| Gson | 2.10.1 | Connection profiles |
| AndroidX Core | 1.13+ | ServiceCompat (foreground service) |

## Permissions

| Permission | Reason |
|---|---|
| `INTERNET` | SSH connection |
| `ACCESS_NETWORK_STATE` | Network state check |
| `CHANGE_NETWORK_STATE` | Foreground service (connectedDevice type) |
| `FOREGROUND_SERVICE` | Background service startup |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Network foreground service (API 34+) |
| `POST_NOTIFICATIONS` | Notification for active connections (API 33+) |

## Requirements

- Android 8.0+ (API 26+)
- Tablet recommended (landscape mode)

---

# KonsoleSSH — Magyar dokumentáció

Többfüles SSH terminál Android tablethez — KDE Konsole ihlette.

## Funkciók

- **Többfüles UI** — TabLayout + ViewPager2, minden fülön külön SSH session
- **SSH kapcsolatok** — JSch könyvtár, jelszó + privát kulcs (PEM) autentikáció
- **Jump host támogatás** — belső hálózatok elérése átjáró szerveren át (port forwarding)
- **Interaktív jelszókérés** — ha nincs mentett jelszó, dialógus kér be egyet kapcsolódáskor
- **Mentett kapcsolatok** — profilok JSON-ban, SharedPreferences tárolással (ABC sorrend)
- **Canvas terminál emulátor** — 256 szín + truecolor, bold, underline, reverse video, scrollback
- **ANSI/VT100 + xterm-256color PTY** — bash/zsh/fish prompt helyesen jelenik meg
- **NerdFont támogatás** — JetBrainsMono Nerd Font beépítve; emoji és surrogate-pair karakterek helyesen jelennek meg
- **Extra billentyűsor** — ESC, TAB, Ctrl+C, Ctrl+D, Ctrl+V, Ctrl+Z, nyilak, Shift, Alt, AltGr, F1–F12
- **Kapcsolat státusz jelzők** — zöld/sárga/piros jelölők a füleken és a fülválasztóban
- **Betűméret zoom** — nagyítógombok, beállítás megmarad újraindítás után
- **Üdvözlő oldal** — swipe-olható, vízszintes scroll nélkül
- **Linux cheatsheet** — részletes parancs referencia (top, df, du, dd, tail, head, grep, egrep, awk, sed, tr, ip, mc)
- **Háttérben tartás** — aktív SSH kapcsolatok életben maradnak képernyőzároláskor és felfelé húzáskor (Foreground Service)
- **Újracsatlakozás gomb** — a terminál közepén jelenik meg, ha a kapcsolat megszakad
- **Értesítő badge** — aktív kapcsolatok száma az app ikonon; koppintásra az app előjön
- **Alkalmazás bezárása gomb** — a fülválasztó menü alján; aktív kapcsolat esetén megerősítést kér
- **Barátságos hibaüzenetek** — nyers Java exception helyett érthető szöveg
- **App ikon** — KONSOLE + SSH felirat, sötét háttér, terminál `> _` prompt grafika

## Architektúra

A sessionöket a `SshForegroundService` kezeli, nem a fragmentek. Ez azt jelenti:
- A kapcsolatok túlélik az activity újraindítását (forgatás, vissza gomb, task removal)
- A `TerminalFragment` az `onStart`/`onStop`-ban bindet/unbindet, és újracsatlakozáskor visszajátssza az output buffert
- Az `OutputBuffer` sessionönként 256 KB kimenetet tárol visszajátszáshoz

## Projekt struktúra

```
app/src/main/java/hu/szecsenyi/konsolessh/
├── model/
│   ├── ConnectionConfig.kt       — SSH kapcsolat adatok (host, port, user, auth, jump)
│   └── SavedConnections.kt       — SharedPreferences alapú profil kezelés
├── ssh/
│   ├── SshSession.kt             — JSch SSH wrapper (connect/read/write/resize/jump)
│   └── SshForegroundService.kt   — Foreground service; minden sessiont és output buffert kezel
├── terminal/
│   └── TerminalView.kt           — Canvas alapú terminál emulátor (VT100/xterm-256color)
└── ui/
    ├── MainActivity.kt            — Fő Activity, TabLayout + ViewPager2 koordináció, billentyűsor
    ├── TerminalFragment.kt        — Terminal Fragment (SSH + cheatsheet + üdvözlő oldal)
    ├── TerminalPagerAdapter.kt    — ViewPager2 adapter (fix lapok + SSH fülek)
    ├── TabPickerSheet.kt          — Fülválasztó bottom sheet
    ├── NewConnectionDialog.kt     — Új/szerkesztett SSH kapcsolat dialógus
    └── ConnectionEditActivity.kt  — Mentett kapcsolatok kezelése
```

## Build

```bash
# Android Studio: File → Open → KonsoleSSH → Run
# vagy parancssori (JDK 21 szükséges):
JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64 ./gradlew assembleDebug
```

## Használat

1. **Új kapcsolat**: `+` gomb → mentett kapcsolat kiválasztása vagy új létrehozása
2. **Csatlakozás**: host, port (22), felhasználó, jelszó / SSH kulcs megadása
3. **Jump host**: belső gép elérésekor add meg az átjáró szerver adatait
4. **Fül bezárása**: `✕` a fül nevén (aktív kapcsolatnál megerősítés kér)
5. **Átnevezés**: hosszú nyomás a fülön
6. **Zoom**: `+` / `−` gombok a toolbar-on (beállítás megmarad)
7. **Cheatsheet**: húzz balra az utolsó lapra — Linux parancs referencia
8. **Alkalmazás bezárása**: `+` gomb → *Alkalmazás bezárása* a menü alján

## Függőségek

| Library | Verzió | Célra |
|---|---|---|
| JSch (mwiede fork) | 0.2.16 | SSH protokoll |
| kotlinx-coroutines | 1.8.1 | Async SSH I/O |
| ViewPager2 | 1.1.0 | Fül navigáció |
| Material Design 3 | 1.12.0 | UI komponensek |
| Gson | 2.10.1 | Kapcsolat profilok |
| AndroidX Core | 1.13+ | ServiceCompat (foreground service) |

## Engedélyek

| Engedély | Miért szükséges |
|---|---|
| `INTERNET` | SSH kapcsolat |
| `ACCESS_NETWORK_STATE` | Hálózat állapot ellenőrzés |
| `CHANGE_NETWORK_STATE` | Foreground service (connectedDevice típus) |
| `FOREGROUND_SERVICE` | Háttér service indítás |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Hálózati foreground service (API 34+) |
| `POST_NOTIFICATIONS` | Értesítés aktív kapcsolatokról (API 33+) |

## Rendszerkövetelmények

- Android 8.0+ (API 26+)
- Tablet ajánlott (landscape mód)
