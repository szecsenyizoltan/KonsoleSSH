# KonsoleSSH

SSH terminál s viacerými kartami pre Android — inšpirovaný KDE Konsole.

> **Jazyk:** Slovenčina · [English](README.md) · [Magyar](README.hu.md) · [Español](README.es.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · [Română](README.ro.md)

## Snímky obrazovky

| Úvodná obrazovka | Nové SSH pripojenie |
| --- | --- |
| ![Úvod](play_screenshot/Screenshot_20260422_094020.png) | ![Nové pripojenie](play_screenshot/Screenshot_20260422_094447.png) |

| Tmux šablóna | Linux šablóna |
| --- | --- |
| ![Tmux šablóna](play_screenshot/Screenshot_20260422_094521.png) | ![Linux šablóna](play_screenshot/Screenshot_20260422_094530.png) |

## Funkcie

### Pripojenia

- **Rozhranie s viacerými kartami** — TabLayout + ViewPager2, každá karta má vlastnú SSH reláciu
- **SSH pripojenia** — knižnica JSch, overovanie heslom **alebo** súkromným kľúčom (PEM) s voliteľnou prístupovou frázou
- **Podpora jump hostu (`ssh -J`)** — dosiahnutie interných hostiteľov cez uloženú bránu (lokálne port forwarding)
- **Interaktívne zadanie hesla** — ak nie je uložené heslo, pri pripájaní sa zobrazí dialóg
- **Uložené pripojenia** — profily sú v pokoji šifrované AES256 (`EncryptedSharedPreferences`, Android Keystore); abecedné poradie
- **Stromové triedenie** — pripojenia s podčiarkovníkmi v názve (napr. `acme_prod_web`, `acme_prod_db`, `foobar_dev_01`) sa automaticky zoskupujú podľa spoločného prefixu. Tu `acme_` → `prod_` spojí prvé dva záznamy, kým `foobar_dev_01` zostáva samostatne, lebo je sám pod svojím prefixom. Skupiny sa otvárajú/zatvárajú šípkou ▶/▼.

### Terminál

- **Terminálový emulátor na báze Canvas** — 256 farieb + truecolor, tučné, podčiarknuté, inverzné video, scrollback buffer
- **ANSI/VT100 + xterm-256color PTY** — prompty bash/zsh/fish sa zobrazujú správne
- **Podpora NerdFont** — JetBrainsMono Nerd Font je priložené; emotikony a surrogate-pair znaky sa zobrazujú správne
- **Kopírovanie pri výbere** (dlhé stlačenie + ťahanie) a **vloženie zo schránky**
- **Priblíženie písma** — tlačidlá na paneli nástrojov, nastavenie sa uchová
- **Tlačidlo opätovného pripojenia** — objaví sa v strede terminálu, keď pripojenie spadne

### Pás s klávesmi na obrazovke

Spodný pás ponúka všetko, čo softvérová klávesnica Androidu nemá:

- **⌨** — otvára/zatvára systémovú softvérovú klávesnicu. _Klávesnica sa otvára len týmto tlačidlom alebo klepnutím do terminálu; žiadne iné tlačidlo pásu ju nevyvolá._
- **Fn** — zobrazí rad funkčných klávesov **F1 – F12**
- **CTRL / SHIFT / ALT / ALTGR** — jednorazové modifikátory (po ďalšom klávese sa automaticky zrušia)
- **CTRL** navyše otvára rad kombinácií `Ctrl+A`, `Ctrl+B`, `Ctrl+C`, `Ctrl+D`, `Ctrl+V`, `Ctrl+Z`
  - `Ctrl+C` skopíruje výber, ak je, inak pošle `ETX`
  - `Ctrl+V` vloží zo schránky
- **ESC, TAB** — klávesy na jedno klepnutie
- **↑** — zobrazí rad šípok (← ↑ ↓ →)
- **📁** — vyber miestny súbor a **nahraj ho cez SFTP** do vzdialeného domovského adresára. Dialóg priebehu ukazuje prenesené / celkové MB. Po úspechu 3-sekundový potvrdzujúci toast ponúka tlačidlo **Späť**, ktoré práve nahraný súbor zo servera odstráni.

### Stav / UX

- **Indikátory stavu pripojenia** — zelené/žlté/červené bodky na kartách a v prehliadači
- **Uvítacia stránka** — potiahnutím doprava sa dostaneš k šablónam
- **Linux šablóna** — `top`, `df`, `du`, `dd`, `tail`, `head`, `grep`, `egrep`, `awk`, `sed`, `tr`, `ip`, `mc`
- **Tmux šablóna** — relácie, okná, panely, rozloženia, prefix bindings
- **Beh na pozadí** — aktívne SSH relácie zostávajú živé pri uzamknutí obrazovky a odstránení úlohy (Foreground Service)
- **Odznak oznámení** — počet aktívnych pripojení
- **Tlačidlo Ukončiť aplikáciu** — v menu prehliadača kariet; pýta potvrdenie, ak sú aktívne pripojenia
- **Priateľské chybové hlásenia** — čitateľný text namiesto surových Java výnimiek
- **Edge-to-edge rozloženie** — správna obsluha window-insets na Androide 15; obsah zostáva nad navigačným pásom v režime na šírku a pri otvorení klávesnice sa posunie nahor

### Lokalizácia

- V predvolenom nastavení angličtina, zahrnuté preklady: **maďarčina**, **španielčina**, **nemčina**, **francúzština**, **slovenčina** a **rumunčina**. Aplikácia sleduje systémový jazyk. Vstavané šablóny (Linux/tmux) sú v jazykoch iných než maďarčina uvádzané v angličtine.

## Architektúra

Relácie vlastní `SshForegroundService`, nie fragmenty:

- Pripojenia prežijú opätovné vytvorenie activity (rotácia, tlačidlo Späť, odstránenie úlohy)
- `TerminalFragment` sa naviaže/odviaže v `onStart`/`onStop` a pri opätovnom pripojení prehráva výstupný buffer
- `OutputBuffer` uchováva posledných 256 KB výstupu na reláciu

## Štruktúra projektu

```
app/src/main/java/hu/billman/konsolessh/
├── model/           ConnectionConfig, SavedConnections (šifrované prefs)
├── ssh/             SshSession, SshForegroundService
├── terminal/        TerminalView, AnsiParser, TerminalClipboard
└── ui/              MainActivity, TerminalFragment, dialógy, sheets
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
# Debug build + jednotkové testy
./gradlew :app:testDebugUnitTest

# Podpísaný release App Bundle (vyžaduje nakonfigurovaný keystore)
./gradlew :app:bundleRelease
```

**Release** build je minifikovaný R8 a zdroje sú zmenšené. Kód pracujúci cez reflexiu
(JSch, Gson modelové triedy) je zachovaný cez `app/proguard-rules.pro`. Vytvára sa
`mapping.txt`, aby Play Console dokázala deobfuskovať stack trace.

## Povolenia

| Povolenie                             | Dôvod                                                     |
| ------------------------------------- | --------------------------------------------------------- |
| `INTERNET`                            | SSH pripojenie                                            |
| `ACCESS_NETWORK_STATE`                | Kontrola stavu siete                                      |
| `CHANGE_NETWORK_STATE`                | Foreground service (typ connectedDevice)                  |
| `FOREGROUND_SERVICE`                  | Spustenie služby na pozadí                                |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Sieťový foreground service (API 34+)                      |
| `POST_NOTIFICATIONS`                  | Trvalé oznámenie o relácii (API 33+)                      |

Nezbierame, nezdieľame ani neposielame žiadne údaje. Prihlasovacie údaje zostávajú na zariadení v šifrovanom úložisku podopretom Keystore.

## Požiadavky

- Android 8.0 (API 26) alebo novší
- Podporovaný režim na výšku aj na šírku

## História verzií

- **1.0.2** — balík premenovaný na `hu.billman.konsolessh`, oprava edge-to-edge insets,
  softvérová klávesnica len na vyžiadanie, SFTP nahrávanie s dialógom priebehu a
  toastom Späť, R8 minifikácia, Gradle 9.4.1 / AGP 9.2.0
- **1.0.1** — `targetSdk = 35`, prvé odoslanie do Play
- **1.0.0** — viackartové SSH, jump host, strom uložených pripojení,
  šablóny Linux a tmux, maďarský preklad
