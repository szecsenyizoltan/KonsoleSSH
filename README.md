# KonsoleSSH

Többfüles SSH terminál Android tablethez — KDE Konsole ihlette.

## Funkciók

- **Többfüles UI** — TabLayout + ViewPager2, minden fülön külön SSH session
- **SSH kapcsolatok** — JSch könyvtár, jelszó + privát kulcs (PEM) autentikáció
- **Mentett kapcsolatok** — profilok JSON-ban, SharedPreferences tárolással
- **ANSI terminus emuláció** — 256 szín + truecolor (24-bit), bold, reset
- **Extra bill­entyűsor** — ESC, TAB, Ctrl+C, Ctrl+Z, nyilak, gyors elérés
- **xterm-256color** PTY — bash/zsh/fish prompt helyesen jelenik meg

## Projekt struktúra

```
app/src/main/java/hu/szecsenyi/konsolessh/
├── model/
│   ├── ConnectionConfig.kt      — SSH kapcsolat adatok (host, port, user, auth)
│   └── SavedConnections.kt      — SharedPreferences alapú profil kezelés
├── ssh/
│   └── SshSession.kt            — JSch SSH wrapper (connect/read/write/resize)
├── terminal/
│   ├── AnsiParser.kt            — ANSI escape kód → Android Spannable konverzió
│   └── TerminalView.kt          — Egyedi terminal View (ScrollView + TextView)
└── ui/
    ├── MainActivity.kt           — Fő Activity, TabLayout + ViewPager2
    ├── TerminalFragment.kt       — Terminal Fragment (SSH + billentyűzet)
    ├── TerminalPagerAdapter.kt   — ViewPager2 adapter fül kezeléshez
    ├── NewConnectionDialog.kt    — Új SSH kapcsolat dialógus
    └── ConnectionEditActivity.kt — Mentett kapcsolatok kezelése
```

## Build

```bash
# Android Studio: File → Open → KonsoleSSH → Run
# vagy parancssori (JDK 21 szükséges):
JAVA_HOME=/usr/lib/jvm/java-1.21.0-openjdk-amd64 ./gradlew assembleDebug
```

## Használat

1. **Új fül**: `+` gomb vagy menü → "Új fül"
2. **Csatlakozás**: host, port (22), user, jelszó / SSH kulcs megadása
3. **Profil mentése**: "Kapcsolat mentése" checkbox ✓
4. **Fül bezárása**: `✕` a fül nevén
5. **Billentyűzet**: ⌨ gomb a szoft billentyűzethez

## Függőségek

| Library | Verzió | Célra |
|---|---|---|
| JSch (mwiede fork) | 0.2.16 | SSH protokoll |
| kotlinx-coroutines | 1.8.1 | Async SSH I/O |
| ViewPager2 | 1.1.0 | Fül navigáció |
| Material Design 3 | 1.12.0 | UI komponensek |
| Gson | 2.10.1 | Kapcsolat profilok |

## Rendszerkövetelmények

- Android 8.0+ (API 26+)
- Internet hozzáférés engedély
- Tablet ajánlott (landscape mód)
# KonsoleSSH
