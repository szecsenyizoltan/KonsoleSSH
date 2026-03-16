# KonsoleSSH

Többfüles SSH terminál Android tablethez — KDE Konsole ihlette.

## Funkciók

- **Többfüles UI** — TabLayout + ViewPager2, minden fülön külön SSH session
- **SSH kapcsolatok** — JSch könyvtár, jelszó + privát kulcs (PEM) autentikáció
- **Jump host támogatás** — belső hálózatok elérése átjáró szerveren át (port forwarding)
- **Interaktív jelszókérés** — ha nincs mentett jelszó, dialógus kér be egyet kapcsolódáskor
- **Mentett kapcsolatok** — profilok JSON-ban, SharedPreferences tárolással (ABC sorrend)
- **Canvas terminál emulátor** — 256 szín + truecolor, bold, underline, reverse video, scrollback
- **ANSI/VT100 + xterm-256color** PTY — bash/zsh/fish prompt helyesen jelenik meg
- **Extra billentyűsor** — ESC, TAB, Ctrl+C, Ctrl+Z, nyilak, Shift, Alt, AltGr
- **Kapcsolat státusz jelzők** — zöld/sárga/piros jelölők a füleken és a fülválasztóban
- **Betűméret zoom** — nagyítógombok, beállítás megmarad újraindítás után
- **Üdvözlő oldal** — swipe-olható, vízszintes scroll nélkül
- **Linux cheatsheet** — részletes parancs referencia (top, df, du, dd, tail, head, tee, wc, grep, egrep, awk, sed, tr, ip, mc)
- **Háttérben tartás** — aktív SSH kapcsolatok életben maradnak képernyőzároláskor (Foreground Service)
- **Barátságos hibaüzenetek** — nyers Java exception helyett érthető szöveg

## Projekt struktúra

```
app/src/main/java/hu/szecsenyi/konsolessh/
├── model/
│   ├── ConnectionConfig.kt       — SSH kapcsolat adatok (host, port, user, auth, jump)
│   └── SavedConnections.kt       — SharedPreferences alapú profil kezelés
├── ssh/
│   ├── SshSession.kt             — JSch SSH wrapper (connect/read/write/resize/jump)
│   └── SshForegroundService.kt   — Foreground service aktív kapcsolatok életben tartásához
├── terminal/
│   └── TerminalView.kt           — Canvas alapú terminál emulátor (VT100/xterm-256color)
└── ui/
    ├── MainActivity.kt            — Fő Activity, TabLayout + ViewPager2 koordináció
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
2. **Csatlakozás**: host, port (22), user, jelszó / SSH kulcs megadása
3. **Jump host**: belső gép elérésekor add meg az átjáró szerver adatait
4. **Fül bezárása**: `✕` a fül nevén (aktív kapcsolatnál megerősítés kér)
5. **Átnevezés**: hosszú nyomás a fülön
6. **Zoom**: `+` / `-` gombok a toolbar-on (beállítás megmarad)
7. **Cheatsheet**: húzz balra az utolsó lapra — Linux parancs referencia

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
