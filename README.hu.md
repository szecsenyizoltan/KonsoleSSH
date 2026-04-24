# KonsoleSSH

Többfüles SSH terminál Androidra — KDE Konsole ihlette.

> **Nyelv:** Magyar · [English](README.md) · [Español](README.es.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · [Slovenčina](README.sk.md) · [Română](README.ro.md)

## Képernyőképek

| Üdvözlő képernyő | Új SSH kapcsolat |
| --- | --- |
| ![Üdvözlő](play_screenshot/Screenshot_20260422_094020.png) | ![Új kapcsolat](play_screenshot/Screenshot_20260422_094447.png) |

| Tmux cheat sheet | Linux cheat sheet |
| --- | --- |
| ![Tmux cheat sheet](play_screenshot/Screenshot_20260422_094521.png) | ![Linux cheat sheet](play_screenshot/Screenshot_20260422_094530.png) |

## Funkciók

### Fülek és navigáció

- **Többfüles UI** — TabLayout + ViewPager2, minden fül független SSH sessionnel, UUID-alapú `TabInfo`-val azonosítva
- **Három fix oldal** — üdvözlő képernyő (0. pozíció), Linux cheat sheet és tmux cheat sheet (utolsó két hely); SSH fülek ezek között
- **Kapcsolat-státusz pötty minden fülön** — zöld (CONNECTED), sárga (CONNECTING), piros (DISCONNECTED), rejtett (NONE)
- **Fül hosszú nyomás** → átnevezési dialógus
- **Fül ✕ gomba** — aktív kapcsolatnál megerősítés; lekapcsoltnál azonnal zár
- **Fülsor-scroll** — ha nem fér ki, finoman pulzáló ◀/▶ hint-gombok jelennek meg (ObjectAnimator ±8dp, 900ms reverse infinite)
- **Tab-indikátor magasság 0dp** egyetlen fül esetén (kevesebb vizuális zaj)

### Fülválasztó / mentett kapcsolatok (`+` menü)

- **BottomSheetDialogFragment** — teljes képernyő magasságú (STATE_EXPANDED, MATCH_PARENT)
- **Aktív fülek + mentett kapcsolatok fa** egymás alatt
- **Fa-struktúra `_`-lel tagolt prefixek alapján** — `acme_prod_web`, `acme_prod_db` → `acme_` → `prod_` csoportba; egyetlen levelet nem csoportosít
- **Dinamikus behúzás** — `16dp + mélység × 20dp`
- **Expanded állapot megőrzés** dialógus-újranyitások között (companion object-ben)
- **Ikonok** — csoport: 📁 / 📂 (zárva/nyitva), levél: ⚡
- **Levélsorok** — `user@host:port` al-sorral, jobbra szerkesztés + törlés gomb
- **Csoportsorok** — al-sor a levél-számmal, ▶/▼ nyíl jobbra
- **Legutóbb használt felhasználónév** pre-fill új kapcsolatnál

### Új / szerkesztő dialógus

- **Edit mód** — `ConnectionConfig` Gson-szerializálva, vissza-betöltve a mezőkbe
- **Auth-választó** RadioGroup: jelszó ↔ privát kulcs, az irreleváns layout automatikusan elrejtőzik
- **Privát kulcs fájlválasztóval** (read-only előnézet), passphrase mező külön
- **`.pub` figyelmeztetés** — ha nyilvános kulcsot próbál betenni, toast figyelmeztet
- **Kulcs-állapot sáv** — PEM betöltve / sikertelen / nincs
- **Jump-host spinner** — saved kapcsolatok közül (saját ID-t kizárva)
- **Automatikus jump-javaslat** — privát IP prefix (`10.`, `172.`, `192.`) felismerésekor a jump szekció magától megnyílik
- **Validáció** — host és username kötelező; port 1–65535, alapérték 22

### Kapcsolat-hitelesítés

- **JSch (mwiede fork)** — password vagy publickey, vagy kettő kombinálva (`buildPreferredAuths`)
- **Interaktív jelszó-prompt** — ha nincs mentve, `KonsoleDialog`-stílusú AlertDialog (fehér szöveg, transparent bg)
- **Keyboard-interactive auth** — szerver-oldali promptok is ugyanezen a dialóguson futnak
- **30 s prompt-timeout** (CountDownLatch) — ha a user nem reagál, a hívó szál tisztán felszabadul
- **Main-thread guard** — a dialógus mindig a UI szálon jelenik meg (Handler post)
- **Jump-host lánc** — a mwiede fork `setPortForwardingL(0, target_host, target_port)` random portra + második session a loopback-re
- **Jump-progress üzenetek** a terminálban — `Jump: host:port → Connecting`, majd `Jump OK → Connecting: target:port`
- **Jump nem található hiba** — ha a referált jump ID már nem létezik

### Terminál-emulátor (Canvas-alapú)

**Renderelés**

- Saját `TerminalView`, `canvas.drawText` cellánként, min 80×24 bázis
- Cellában: karakter (String, surrogate-pair-kezeléssel), fg/bg, bold, underline, reverse
- **NerdFont beépítve** (`assets/fonts/NerdFont.ttf`); fallback `Typeface.MONOSPACE` asset-hibánál
- Font: 6sp…40sp; first-layoutnál automatikus méretezés 80-oszlopos célból
- **Zoom gombok +/−** a toolbarban (`SharedPreferences("settings", "font_size")`), újraindítás után megmarad
- **Per-app** font-méret (nem per-kapcsolat)
- Aláhúzás saját `drawLine` cellH-1-re
- Kurzor-inverzió rajzolási időben (fg↔bg)
- **Kurzor-villogás** 600 ms on / 300 ms off

**ANSI/VT állapotgép**

- NORMAL / ESCAPE / CSI / OSC / DCS / CHARSET állapotok
- SGR: 16 alap + 8 bright + 256-cube + truecolor (`38;2;r;g;b`), bold/underline/reverse + reset-ek
- Kurzor: A/B/C/D, H/f, G, E/F, s/u, ESC 7/8 (DEC legacy)
- Törlés: J, K, X, L/M (insert/delete lines), P/@ (chars)
- **Scroll region** — r, S/T (scroll up/down)
- **Alt screen** — 47 / 1049 toggle (vi, top, less, mc menti/visszaadja a képet)
- **DECCKM app-cursor mode** — `ESC[A/B/C/D` ↔ `ESC O A/B/C/D` toggle
- **Bracketed paste mode** (2004) — beillesztés `ESC[200~ … ESC[201~` keretben

**Szöveg-kijelölés**

- **Long-press 400 ms** → kijelölés indítása, floating `ActionMode` (Copy/Paste)
- Húzás közben élő end-point követés, `invalidateContentRect`
- Forward/backward irány-normalizáció
- `buildSelectedText` — soronként trim-end, `\n`-ekkel fűzve
- Egyszerű tap kijelölés közben → törli a kijelölést
- **ViewPager-swipe blokkolás** (`requestDisallowInterceptTouchEvent`) kijelölés/scroll közben

**Görgetés és érintés**

- Vertikális scroll: `scrollRowOff` 0…`scrollback.size`
- **Scrollback** 3000 sor ring-buffer
- Horizontális scroll engedélyezhető (welcome oldalon kikapcsolva)
- **8 px move-threshold** — tap és scroll diszkrimináció
- Új output érkezésekor automata bottom-gravity (`scrollRowOff = 0`)
- Tap → `focusAndShowKeyboard()`
- **Shift+PageUp/Down** hardver billentyűzeten = scrollback görgetés

**IME-integráció**

- Saját `TerminalInputConnection` (BaseInputConnection)
- Sentinel-trükk: a buffer mindig 1 space-szel kezd, a user-bevitel `removePrefix`
- Swipe/glide IME-kkel is működik
- `deleteSurroundingText` → manuális DEL (0x7F) byte-küldés
- `inputType=TYPE_NULL` — nincs autocomplete, javaslat a terminálon

### Hardver billentyűzet

- Enter→13, Tab→9, Esc→27, DEL→127
- Home/End → `ESC[H` / `ESC[F`
- PageUp/PageDown → `ESC[5~` / `ESC[6~`
- **F1–F12** → `ESC O P/Q/R/S`, `ESC[15~`, `ESC[17~`, …
- Nyílbillentyűk → app-cursor mód szerinti kódok
- Shift+PageUp/Down megkülönböztetve (scroll, nem input)

### Képernyőn megjelenő billentyűsor (keybar)

**Fő sor** (40 dp)

- **⌨** — rendszer IME nyit/zár. Valós IME-láthatóság `WindowInsetsCompat`-en keresztül, visszagesztussal bezárt billentyűzetet is tisztán újra megnyitja.
- **Fn** — F1–F12 sor nyit/zár
- **CTRL / SHIFT / ALT / ALTGR** — sticky módosítók, aktív állapotban `keybar_mod_active` szín; minden billentyű-küldés után **automatikus reset**
- **CTRL** lenyomásakor egy dedikált Ctrl-kombó sor jelenik meg (A/B/C/D/V/Z)
- **ESC, TAB** — közvetlen byte
- **↑** — nyílsor (← ↑ ↓ →) nyit/zár
- **📁** — `ActivityResultContracts.OpenDocument()` → SFTP feltöltés
- Közbülső vizuális `KeyBarDivider` elemek

**Fn-sor** (36 dp) — F1–F12 escape-szekvenciák, minden koppintásnál **flash-effekt** (accent szín 300 ms)

**Ctrl-sor** — dinamikusan generált (`LayoutInflater` + `item_keybar_button`)

- `Ctrl+C`: ha van szelekció → app-belüli vágólapra + toast, különben ETX (0x03)
- `Ctrl+V`: app-belüli vágólapból → `pasteText` + toast
- A/B/D/Z: kód = char - 'A' + 1 (1…26)

**Nyílsor** (36 dp) — ← ↑ ↓ → app-cursor módot is tiszteletben tartva

**Scroll hintek minden sorban** — ◀/▶ animált, csak ha tényleg görgethető; `canScrollHorizontally(±1)` check minden event után; onDestroy-nál animátor cancel + reset

### Speciális billentyű-kombók (`applyModifiers`)

- Ctrl+betű → 1…26 (standard control code)
- **Ctrl+space → 0x00 (NUL)**
- **Ctrl+[ → 27 (ESC)**
- Shift + kisbetű → nagybetű (soft-input fallback)
- Alt / AltGr → `ESC` + eredeti byte (meta prefix)

### Vágólap

**App-belüli vágólap** (`TerminalClipboard`)

- Singleton `var text: String?`
- Ctrl+C + szelekció → **app-belüli** (nem a rendszer-clipboardra) + diszkrét toast
- Ctrl+V → app-belüli → `pasteText`

**Rendszer-vágólap**

- ActionMode **Copy** → `ClipboardManager.setPrimaryClip`
- ActionMode **Paste** → `coerceToText` → `pasteText`
- Paste során `\n` → `\r`, aktív bracketed paste mode-ban keretelve

### SFTP fájlfeltöltés

- 📁 → `OpenDocument` picker
- Fájlnév `OpenableColumns.DISPLAY_NAME`, fallback `uri.lastPathSegment`
- Méret-lekérdezés → progressbar determinate / indeterminate váltás
- **Progress dialógus** — fájlnévvel, `X.X MB / Y.Y MB` vagy ismeretlen total esetén `Z.Z MB`, `setCancelable(false)`
- Siker után `KonsoleToast.showWithAction` — "Feltöltve: ~/filename" + **Vissza** gomb → `deleteRemoteFile`
- Hibánál friendlyError-mapping szerinti magyar szöveg

### Állapot és visszajelzés

- **Státusz-sáv** (20 dp) a terminál felett: `Nincs kapcsolat`, `Csatlakozás: host…`, `Csatlakoztatva: host`, `Lekapcsolódva: host`
- **Reconnect gomb** a terminál közepén, ↺ ikon, csak DISCONNECTED-kor
- **KonsoleToast** — saját dizájnú toast, bottom 100 dp margin, 4000 ms default / 3000 ms akciós, dismiss anim (scale+alpha, 250 ms)

### Hibaüzenetek (friendlyError-mapping)

JSch exception helyett magyar (locale szerint angol) szöveg:

- `connection refused` → "A szerver visszautasította a kapcsolatot"
- `timed out` / `timeout` → "Időtúllépés"
- `no route to host` → "Nincs útvonal a hosthoz"
- `network unreachable` → "Hálózat nem elérhető"
- `unknown host` → "Ismeretlen host"
- `auth fail` / `authentication` → "Sikertelen hitelesítés"
- `connection closed` / `closed by foreign host` → "Kapcsolat bontva"
- `broken pipe` → "Megszakadt pipe"
- `port forwarding`, `channel` → saját üzenetek
- Egyéb: az exception-üzenet regex-tisztítva (stack-osztálynévtől megfosztva)

### Háttér- és életciklus

- **`SshForegroundService`** — `START_STICKY`, `onTaskRemoved`-nál direkt **nem** állítja le a session-öket
- **Output buffer** — 256 KB ring / session, `ByteArrayOutputStream` overflow-trim-mel
- **Replay csatlakozáskor** — fragment bind után a buffer visszafolyik a terminálra, így a korábbi kimenet látható
- **Két NotificationChannel** — `ssh_idle` (inaktív) és `ssh_active` (aktív), eltérő badge-viselkedés
- **Értesítés-badge** az aktív session-ök számával
- Low priority notification (`PRIORITY_LOW`, `setSilent`)
- `onRebind` engedélyezve (`onUnbind → true`)
- `TerminalFragment.onDestroy` **nem** bontja a sessiont (service a tulajdonos)
- `onAttach`/`onDetach` listener-kezelés (memory leak védelem)

### Kapcsolódási indikátor-logika

- `NONE → CONNECTING`: új `connect()` hívás
- `CONNECTING → CONNECTED`: shell-csatorna nyitva (`onConnected`)
- `CONNECTING → DISCONNECTED`: `onError` (auth, timeout, refused)
- `CONNECTED → DISCONNECTED`: olvasóloop vége vagy explicit `disconnectSession`
- `DISCONNECTED → CONNECTING`: Reconnect gomb

### Biztonság

- **EncryptedSharedPreferences** — AES256_GCM érték-séma, AES256_SIV kulcs-séma, Android Keystore-alapú MasterKey
- **Legacy migráció** indításkor — régi plain-text profilok beemelése az encrypted tárba, majd legacy törlés
- **Plain-prefs fallback** — ha a keystore-inicializáció elromlik (warning log), nem veszti el a profilokat
- **JSch `StrictHostKeyChecking=no`** — trust-on-first-use
- A hitelesítő adatok nem hagyják el a készüléket

### Welcome / cheat sheet-ek

- **Üdvözlő banner** — három narancs árnyalatban ANSI-színezett "KonsoleSSH" felirat + 9 sor leírás (`[38;5;244m` dim)
- Fix 16 sp font a welcome-on
- Horizontális scroll kikapcsolva a welcome-on
- **Linux cheat sheet** — `top`, `df`, `du`, `dd`, `tail`, `head`, `grep`, `egrep`, `awk`, `sed`, `tr`, `ip`, `mc` + `⚠` figyelmeztető ikon a destruktív parancsoknál (`dd`, `sed -i`)
- **Tmux cheat sheet** — session/window/pane/layout/prefix/resize/scroll/paste
- **Locale-szelektív tartalom** — HU és EN külön szöveggel, nem csak label-fordítással
- `scrollToTop()` megnyitáskor
- Terminál-resize → cheat-tabon teljes újrarenderelés (`clear()` + új banner)

### Edge-to-edge és orientáció

- `ViewCompat.setOnApplyWindowInsetsListener` minden Activity-ben
- `Type.systemBars() | Type.displayCutout()` + `Type.ime()` együtt — fekvőben a nav-sáv fölött marad a tartalom, IME-nyitásnál automatikusan feljebb csúszik
- `screenOrientation=fullSensor` — álló+fekvő auto-rotate
- `configChanges` beállítása: rotáláskor az Activity nem pusztul el
- `windowSoftInputMode=adjustResize`
- `onSizeChanged` → font-újraszámolás, termRows/termCols rekalibráció

### Lokalizáció

- 7 nyelv: **angol** (alap), **magyar**, **német**, **spanyol**, **francia**, **szlovák**, **román**
- `supportsRtl="true"` — jövőbeli RTL nyelvekhez kész
- A rendszernyelvet követi
- A cheat sheet-ek tartalma is lokalizált (HU/EN)

### Kényelmi apróságok

- ActionMode bezárása után fókusz automatikusan vissza a terminálra
- Modifier-gomb elengedése után is automatikus terminál-fókusz
- Sticky modifier gomb színt vált, miközben aktív (vizuális state)
- Minden keybar-kattintásnál 300 ms flash
- DEL (0x7F) külön kódon — nem Backspace-alternatíva
- Surrogate pair / emoji cellákban egy logikai karakterként
- Terminál resize → `resize(tabId, cols, rows)` a PTY-nak (`vim`, `htop` tudja a méretet)
- Input-connection `resetToSentinel` minden commit után — IME mindig tiszta
- "Connection closed" üzenet a terminálba írva bontáskor
- Ctrl+C / Ctrl+V diszkrét toast: "Másolva" / "Beillesztve"
- Gombok `selectableItemBackgroundBorderless` ripple
- `KonsoleDialog` stílus: fehér szöveg, szürke hint, transparent bg
- Aktív kapcsolatnál bezárás/törlés/kilépés mindig megerősítést kér

### Konstansok

- Connect timeout: 15 s, shell-connect: 10 s, password-prompt: 30 s
- Output buffer: 256 KB / session
- Scrollback: 3000 sor
- Font: 6 sp – 40 sp
- Long-press: 400 ms
- Touch move threshold: 8 px
- Scroll-hint pulzálás: 900 ms periódus, ±8 dp
- Toast: 4000 ms default, 3000 ms akciós, dismiss anim 250 ms
- KonsoleToast bottom margin: 100 dp
- Kurzor villogás: 600 ms on / 300 ms off

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

- **1.0.6** — reconnect után a státusz-indikátor helyesen zöldre vált (nem ragad sárgán);
  billentyűzet-ikon Android 14+ alatt is felhozza az IME-t (deprecated `SHOW_FORCED` helyett
  valós `WindowInsetsCompat`-alapú láthatóság)
- **1.0.5** — belső javítás (kihagyott verzió)
- **1.0.4** — új, karcsúbb `>_` prompt app-ikon (stroke design)
- **1.0.3** — Play-re finomított app-ikon és assets
- **1.0.2** — package átnevezés `hu.billman.konsolessh`-re, edge-to-edge insets
  javítás, szoftveres bill csak kérésre, SFTP-fájlfeltöltés progress-szel és
  Vissza-toasttal, R8 minimalizáció, Gradle 9.4.1 / AGP 9.2.0
- **1.0.1** — `targetSdk = 35`, első Play-feltöltés
- **1.0.0** — többfüles SSH, jump host, mentett kapcsolatok fa-szerkezete,
  Linux és tmux cheat sheetek, magyar fordítás
