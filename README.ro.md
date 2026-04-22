# KonsoleSSH

Terminal SSH cu mai multe file pentru Android — inspirat din KDE Konsole.

> **Limbă:** Română · [English](README.md) · [Magyar](README.hu.md) · [Español](README.es.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · [Slovenčina](README.sk.md)

## Capturi de ecran

| Ecran de întâmpinare | Conexiune SSH nouă |
| --- | --- |
| ![Întâmpinare](play_screenshot/Screenshot_20260422_094020.png) | ![Conexiune nouă](play_screenshot/Screenshot_20260422_094447.png) |

| Referință tmux | Referință Linux |
| --- | --- |
| ![Referință tmux](play_screenshot/Screenshot_20260422_094521.png) | ![Referință Linux](play_screenshot/Screenshot_20260422_094530.png) |

## Funcționalități

### Conexiuni

- **Interfață cu mai multe file** — TabLayout + ViewPager2, fiecare filă are propria sesiune SSH
- **Conexiuni SSH** — bibliotecă JSch, autentificare cu parolă **sau** cu cheie privată (PEM) cu parafrază opțională
- **Suport jump host (`ssh -J`)** — accesezi gazdele interne printr-o conexiune-poartă salvată (port forwarding local)
- **Cerere interactivă de parolă** — dacă nu există parolă salvată, apare un dialog la conectare
- **Conexiuni salvate** — profiluri criptate în repaus cu AES256 (`EncryptedSharedPreferences`, Android Keystore); ordine alfabetică
- **Selector arborescent** — conexiunile numite cu liniuță jos (ex. `acme_prod_web`, `acme_prod_db`, `foobar_dev_01`) sunt grupate automat după prefixul comun. Aici `acme_` → `prod_` grupează primele două, iar `foobar_dev_01` rămâne neîncadrat pentru că este singur sub prefixul său. Grupurile se deschid/închid cu săgeata ▶/▼.

### Terminal

- **Emulator de terminal pe Canvas** — 256 culori + truecolor, bold, subliniat, video invers, memorie de defilare
- **PTY ANSI/VT100 + xterm-256color** — prompturile bash/zsh/fish se afișează corect
- **Suport NerdFont** — JetBrainsMono Nerd Font inclus; emoji și caracterele surrogate-pair se afișează corect
- **Copiere la selectare** (apăsare lungă + tragere) și **lipire din clipboard**
- **Zoom la dimensiunea fontului** — butoane în bara de instrumente, setarea este păstrată
- **Buton de reconectare** — apare în centrul terminalului când conexiunea cade

### Bară de taste pe ecran

Bara de jos oferă tot ce nu are tastatura virtuală Android:

- **⌨** — deschide/închide tastatura virtuală a sistemului. _Tastatura se deschide doar de la acest buton sau când atingi terminalul; niciun alt buton al barei nu o activează._
- **Fn** — afișează un rând cu tastele funcționale **F1 – F12**
- **CTRL / SHIFT / ALT / ALTGR** — modificatori cu o singură utilizare (resetați după următoarea tastă)
- **CTRL** deschide în plus un rând de combinații `Ctrl+A`, `Ctrl+B`, `Ctrl+C`, `Ctrl+D`, `Ctrl+V`, `Ctrl+Z`
  - `Ctrl+C` copiază selecția dacă există, altfel trimite `ETX`
  - `Ctrl+V` lipește din clipboard
- **ESC, TAB** — taste cu o atingere
- **↑** — afișează un rând cu săgeți (← ↑ ↓ →)
- **📁** — alege un fișier local și **urcă-l prin SFTP** în directorul *home* de pe server. Un dialog de progres afișează MB transferați / totali. După succes, un toast de confirmare de 3 s oferă un buton **Anulează** care șterge fișierul tocmai urcat de pe server.

### Stare / UX

- **Indicatori de stare a conexiunii** — puncte verzi/galbene/roșii pe file și în selector
- **Pagina de întâmpinare** — glisabilă; glisează la dreapta pentru referințe
- **Referință Linux** — `top`, `df`, `du`, `dd`, `tail`, `head`, `grep`, `egrep`, `awk`, `sed`, `tr`, `ip`, `mc`
- **Referință tmux** — sesiuni, ferestre, panouri, layout-uri, legăturile de prefix
- **Persistență în fundal** — sesiunile SSH active rămân vii la blocarea ecranului și la îndepărtarea aplicației via Foreground Service
- **Insignă de notificare** — numărul conexiunilor active
- **Buton Închide aplicația** — în meniul selectorului de file; cere confirmare dacă există conexiuni active
- **Mesaje de eroare prietenoase** — text lizibil în loc de excepții Java brute
- **Aranjament edge-to-edge** — tratare corectă a window-insets pe Android 15; conținutul rămâne deasupra barei de navigare în peisaj și urcă la deschiderea tastaturii

### Localizare

- Implicit engleză, traduceri incluse: **maghiară**, **spaniolă**, **germană**, **franceză**, **slovacă** și **română**. Aplicația urmează limba sistemului. Referințele încorporate (Linux/tmux) sunt în engleză pentru limbile diferite de maghiară.

## Arhitectură

Sesiunile aparțin lui `SshForegroundService`, nu fragmentelor:

- Conexiunile supraviețuiesc recreării activity-ului (rotire, buton Înapoi, eliminarea sarcinii)
- `TerminalFragment` se leagă/dezleagă de serviciu în `onStart`/`onStop` și rejoacă bufferul de ieșire la reconectare
- `OutputBuffer` păstrează ultimii 256 KB de ieșire per sesiune

## Structura proiectului

```
app/src/main/java/hu/billman/konsolessh/
├── model/           ConnectionConfig, SavedConnections (prefs criptate)
├── ssh/             SshSession, SshForegroundService
├── terminal/        TerminalView, AnsiParser, TerminalClipboard
└── ui/              MainActivity, TerminalFragment, dialoguri, sheets
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
# Build de debug + teste unitare
./gradlew :app:testDebugUnitTest

# App Bundle de release semnat (necesită keystore configurat)
./gradlew :app:bundleRelease
```

Build-ul **release** este minificat cu R8 și are resursele reduse. Codul care folosește reflexia
(JSch, clasele de model Gson) este păstrat prin `app/proguard-rules.pro`. Se generează un
`mapping.txt` pentru ca Play Console să poată deofusca stack trace-urile.

## Permisiuni

| Permisiune                            | Motiv                                                  |
| ------------------------------------- | ------------------------------------------------------ |
| `INTERNET`                            | Conexiune SSH                                          |
| `ACCESS_NETWORK_STATE`                | Verificarea stării rețelei                             |
| `CHANGE_NETWORK_STATE`                | Foreground service (tip connectedDevice)               |
| `FOREGROUND_SERVICE`                  | Pornirea serviciului de fundal                         |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Foreground service de rețea (API 34+)                  |
| `POST_NOTIFICATIONS`                  | Notificare persistentă pentru sesiune (API 33+)        |

Nu se colectează, nu se distribuie și nu se încarcă nicio dată. Credențialele rămân pe dispozitiv în stocarea criptată ancorată în Keystore.

## Cerințe de sistem

- Android 8.0 (API 26) sau mai nou
- Modurile portret și peisaj sunt ambele acceptate

## Istoric versiuni

- **1.0.2** — pachet redenumit în `hu.billman.konsolessh`, corectare edge-to-edge insets,
  tastatură virtuală doar la cerere, upload SFTP cu dialog de progres și toast de
  Anulare, minificare R8, Gradle 9.4.1 / AGP 9.2.0
- **1.0.1** — `targetSdk = 35`, prima depunere în Play
- **1.0.0** — SSH cu mai multe file, jump host, arbore al conexiunilor salvate,
  referințe Linux și tmux, traducere în maghiară
