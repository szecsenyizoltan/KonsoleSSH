# KonsoleSSH

Többfüles SSH terminál Androidra — KDE Konsole ihlette.

> **Nyelv:** Magyar · [English](README.md)

## Képernyőképek

| Üdvözlő képernyő | Új SSH kapcsolat |
| --- | --- |
| ![Üdvözlő](play_screenshot/Screenshot_20260422_094020.png) | ![Új kapcsolat](play_screenshot/Screenshot_20260422_094447.png) |

| Tmux cheat sheet | Linux cheat sheet |
| --- | --- |
| ![Tmux cheat sheet](play_screenshot/Screenshot_20260422_094521.png) | ![Linux cheat sheet](play_screenshot/Screenshot_20260422_094530.png) |

## Funkciók

### Kapcsolatok

- **Többfüles UI** — TabLayout + ViewPager2, minden fül önálló SSH session
- **SSH kapcsolatok** — JSch, jelszó **vagy** privát kulcs (PEM) autentikáció, opcionális jelmondattal
- **Jump host támogatás (`ssh -J`)** — belső hálózatok elérése mentett átjáró-kapcsolaton át (lokális port-forward)
- **Interaktív jelszókérés** — ha nincs mentett jelszó, dialógus kér be egyet csatlakozáskor
- **Mentett kapcsolatok** — profilok AES256-tal titkosítva (`EncryptedSharedPreferences`, Android Keystore); ABC sorrend
- **Fa-struktúrájú lista** — az `_`-lel tagolt nevek (pl. `acme_prod_web`, `acme_prod_db`, `foobar_dev_01`) automatikusan közös prefix szerint csoportosulnak. Itt `acme_` → `prod_` alá kerül az első kettő, a `foobar_dev_01` pedig önálló marad, mert egyedül van a saját prefixén. ▶/▼ nyíllal nyílnak/csukódnak.

### Terminál

- **Canvas terminál-emulátor** — 256 szín + truecolor, félkövér, aláhúzott, inverz, scrollback
- **ANSI/VT100 + xterm-256color PTY** — bash/zsh/fish promptok jól jelennek meg
- **NerdFont támogatás** — JetBrainsMono Nerd Font beépítve; emojik, surrogate-pair karakterek helyesen jelennek meg
- **Másolás szelekcióra** (hosszú nyomás + húzás) és **beillesztés vágólapról**
- **Betűméret nagyítás** — zoom gombok a toolbar-on, megmarad újraindítás után
- **Újracsatlakozás gomb** — a terminál közepén, ha a kapcsolat megszakad

### Képernyőn megjelenő billentyűsor

A billentyűsor lefed mindent, ami a rendszer-billentyűzetből hiányzik:

- **⌨** — a rendszer szoftveres billentyűzetét nyitja/zárja. _Szoftveres billentyűzet csak erre vagy a terminálra koppintva jön elő; semmilyen más billentyűsor-gomb nem dobja fel._
- **Fn** — nyitja/csukja az **F1 – F12** sort
- **CTRL / SHIFT / ALT / ALTGR** — sticky egyszer-használatos módosítók (a következő billentyű után automatikusan visszaáll)
- **CTRL** ezen kívül nyit egy Ctrl-kombó sort: `Ctrl+A`, `Ctrl+B`, `Ctrl+C`, `Ctrl+D`, `Ctrl+V`, `Ctrl+Z`
  - `Ctrl+C` ha van szelekció, másol; különben `ETX`-et küld
  - `Ctrl+V` vágólapról illeszt be
- **ESC, TAB** — egy kattintás
- **↑** — nyílbillentyű sor nyit/csuk (← ↑ ↓ →)
- **📁** — helyi fájl választása és **SFTP feltöltés** a távoli home-ba. Progress-dialóg mutatja a folyamatot (MB / MB). Sikeres feltöltés után 3 mp-ig egy megerősítő toast jelenik meg egy **Vissza** gombbal, amivel az épp feltöltött fájl törölhető a szerverről.

### Állapot / UX

- **Kapcsolat-státusz jelzők** — zöld/sárga/piros pontok a füleken és a kiválasztóban
- **Üdvözlő oldal** — swipe-olható; jobbra húzva érhetők el a cheat sheet oldalak
- **Linux cheat sheet** — referencia: `top`, `df`, `du`, `dd`, `tail`, `head`, `grep`, `egrep`, `awk`, `sed`, `tr`, `ip`, `mc`
- **Tmux cheat sheet** — session, window, pane, layout, prefix billentyűk
- **Háttérben tartás** — aktív SSH kapcsolatok Foreground Service-szel maradnak élve képernyőzárásnál és az appból való kilépésnél
- **Értesítő badge** — az aktív kapcsolatok száma
- **Alkalmazás bezárása gomb** — a fülválasztó menü alján; aktív kapcsolatnál megerősítés kér
- **Barátságos hibaüzenetek** — nyers Java exception helyett magyar nyelvű szöveg
- **Edge-to-edge elrendezés** — Android 15-en is helyes window-insets kezelés; fekvő nézetben a tartalom a navigációs sáv fölött marad, szoftveres billentyű megnyitásakor feljebb csúszik

### Lokalizáció

- Alapértelmezésben angol, **magyar fordítás** benne (`values-hu/`). Az app követi a rendszernyelvet; magyar rendszer esetén a cheat sheet tartalma is magyarra vált.

## Architektúra

A sessionöket a `SshForegroundService` kezeli, nem a fragmentek:

- A kapcsolatok túlélik az activity újraindítását (forgatás, vissza gomb, task removal)
- A `TerminalFragment` az `onStart`/`onStop`-ban bindel/unbindel, és újracsatlakozáskor visszajátssza az output buffert
- Az `OutputBuffer` sessionönként 256 KB-ot tart fenn visszajátszáshoz

## Projekt-struktúra

```
app/src/main/java/hu/billman/konsolessh/
├── model/
│   ├── ConnectionConfig.kt       — SSH kapcsolat adatok (host, port, user, auth, jump)
│   └── SavedConnections.kt       — EncryptedSharedPreferences alapú profil-kezelés
├── ssh/
│   ├── SshSession.kt             — JSch wrapper (connect/read/write/resize/jump, SFTP upload, remote rm)
│   └── SshForegroundService.kt   — Foreground service; sessionök, output bufferek, feltöltési progress
├── terminal/
│   ├── TerminalView.kt           — Canvas terminál-emulátor (VT100/xterm-256color)
│   ├── AnsiParser.kt             — ANSI/VT állapot-automata
│   └── TerminalClipboard.kt      — App-on belüli közös vágólap
└── ui/
    ├── MainActivity.kt           — Fül-koordináció, billentyűsor, feltöltés-folyamat, window insets
    ├── TerminalFragment.kt       — Terminál / cheat sheet / üdvözlő
    ├── TerminalPagerAdapter.kt   — ViewPager2 adapter (fix lapok + SSH fülek)
    ├── TabPickerSheet.kt         — Fül- és mentett kapcsolat-választó fa-struktúrás csoportosítással
    ├── NewConnectionDialog.kt    — Új / szerkesztett kapcsolat dialógus
    ├── ConnectionEditActivity.kt — Mentett kapcsolatok kezelése
    └── KonsoleToast.kt           — App-on belüli toast opcionális akciógombbal
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
# Debug build + unit tesztek
./gradlew :app:testDebugUnitTest

# Aláírt release App Bundle (konfigurált keystore szükséges)
./gradlew :app:bundleRelease
```

A **release** build R8-cal minimalizál és resource-shrink-el. A reflection-ön
alapuló kódot (JSch, Gson modellosztályok) az `app/proguard-rules.pro` keep-szabályai
védik. A `mapping.txt` automatikusan készül, hogy a Play Console deobfuszkálni
tudja a crash-stack-eket.

## Használat

1. **Új kapcsolat**: `+` → *Új kapcsolat…* → host, port (22), felhasználónév, jelszó vagy SSH kulcs
2. **Jump host**: ha belső címtartományba esik a cél (10./172./192.), a jump szekció automatikusan megjelenik; válassz egy mentett átjárót
3. **Fül bezárása**: `✕` a fülön (aktív kapcsolatnál megerősítést kér)
4. **Átnevezés**: hosszú nyomás a fülön
5. **Zoom**: `+` / `−` a toolbar-on (megmarad)
6. **Cheat sheet**: az üdvözlő oldalról jobbra húzva — először Linux, aztán tmux
7. **SFTP feltöltés**: 📁 a billentyűsorban → fájlválasztás → a távoli home-ba (`~`) kerül, Vissza gomb a toastban
8. **Alkalmazás bezárása**: `+` → *Kilépés* a menü alján

## Függőségek

| Library            | Verzió            | Célra                                       |
| ------------------ | ----------------- | ------------------------------------------- |
| JSch (mwiede fork) | 0.2.16            | SSH + SFTP                                  |
| kotlinx-coroutines | 1.8.1             | Async SSH I/O                               |
| ViewPager2         | 1.1.0             | Fül-navigáció                               |
| Material Design    | 1.12.0            | UI komponensek                              |
| Gson               | 2.10.1            | Kapcsolat-profil (de)szerializáció          |
| androidx.security  | 1.1.0-alpha06     | EncryptedSharedPreferences                  |
| AndroidX Core      | 1.13+             | ServiceCompat, WindowInsetsCompat           |

## Engedélyek

| Engedély                              | Miért kell                                                 |
| ------------------------------------- | ---------------------------------------------------------- |
| `INTERNET`                            | SSH kapcsolat                                              |
| `ACCESS_NETWORK_STATE`                | Hálózat állapot-ellenőrzés                                 |
| `CHANGE_NETWORK_STATE`                | Foreground service (connectedDevice típus)                 |
| `FOREGROUND_SERVICE`                  | Háttér-service indítás                                     |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Hálózati foreground service (API 34+)                      |
| `POST_NOTIFICATIONS`                  | Tartós session-kezelő értesítés (API 33+)                  |

Adatot nem gyűjtünk, nem továbbítunk. A hitelesítő adatok titkosítva maradnak a készüléken.

## Rendszerkövetelmények

- Android 8.0+ (API 26+)
- Álló és fekvő mód egyaránt támogatott

## Verziótörténet

- **1.0.2** — package átnevezés `hu.billman.konsolessh`-re, edge-to-edge insets
  javítás, szoftveres bill csak kérésre, SFTP-fájlfeltöltés progress-szel és
  Vissza-toasttal, R8 minimalizáció, Gradle 9.4.1 / AGP 9.2.0
- **1.0.1** — `targetSdk = 35`, első Play-feltöltés
- **1.0.0** — többfüles SSH, jump host, mentett kapcsolatok fa-szerkezete,
  Linux és tmux cheat sheetek, magyar fordítás
