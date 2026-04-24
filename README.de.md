# KonsoleSSH

SSH-Terminal mit mehreren Tabs für Android — inspiriert von KDE Konsole.

> **Sprache:** Deutsch · [English](README.md) · [Magyar](README.hu.md) · [Español](README.es.md) · [Français](README.fr.md) · [Slovenčina](README.sk.md) · [Română](README.ro.md)

## Screenshots

| Startbildschirm | Neue SSH-Verbindung |
| --- | --- |
| ![Start](play_screenshot/Screenshot_20260422_094020.png) | ![Neue Verbindung](play_screenshot/Screenshot_20260422_094447.png) |

| Tmux-Referenz | Linux-Referenz |
| --- | --- |
| ![Tmux-Referenz](play_screenshot/Screenshot_20260422_094521.png) | ![Linux-Referenz](play_screenshot/Screenshot_20260422_094530.png) |

## Funktionen

### Tabs und Navigation

- **Multi-Tab-Oberfläche** — TabLayout + ViewPager2, jeder Tab besitzt eine eigene SSH-Sitzung, identifiziert über ein UUID-basiertes `TabInfo`
- **Drei feste Seiten** — Startseite (Position 0), Linux-Referenz und Tmux-Referenz (die beiden letzten Plätze); die SSH-Tabs liegen dazwischen
- **Statuspunkt an jedem Tab** — grün (CONNECTED), gelb (CONNECTING), rot (DISCONNECTED), ausgeblendet (NONE)
- **Langes Drücken auf einen Tab** → Umbenennen-Dialog
- **Tab-✕-Schaltfläche** — fordert bei aktiver Sitzung eine Bestätigung an; schließt bei getrennter Verbindung sofort
- **Scrollen der Tab-Leiste** — sobald die Tabs überlaufen, erscheinen dezent pulsierende ◀/▶-Hinweisschaltflächen (ObjectAnimator ±8dp, 900 ms reverse infinite)
- **Tab-Indikator-Höhe auf 0 dp** bei einem einzelnen Tab (weniger optisches Rauschen)

### Tab-Auswahl / gespeicherte Verbindungen (`+`-Menü)

- **BottomSheetDialogFragment** — volle Höhe (STATE_EXPANDED, MATCH_PARENT)
- **Aktive Tabs + Baum der gespeicherten Verbindungen**, übereinander angeordnet
- **Baumgruppierung nach Unterstrich-Präfix** — `acme_prod_web`, `acme_prod_db` → Gruppe `acme_` → `prod_`; ein einzelnes Blatt wird nie gruppiert
- **Tiefenabhängiges Padding** — `16dp + depth × 20dp`
- **Der Aufklapp-Zustand überlebt das erneute Öffnen des Dialogs** (über ein Companion-Object gehalten)
- **Icons** — Gruppe: 📁 / 📂 (geschlossen/offen), Blatt: ⚡
- **Blatt-Zeilen** — `user@host:port` als Unterzeile, Bearbeiten + Löschen rechts
- **Gruppen-Zeilen** — Anzahl der Blätter als Unterzeile, ▶/▼-Pfeil rechts
- **Zuletzt verwendeter Benutzername** wird bei neuen Verbindungen vorausgefüllt

### Neue Verbindung / Bearbeiten-Dialog

- **Bearbeitungsmodus** — vollständiges `ConnectionConfig` wird per Gson serialisiert durch das Bundle gereicht und in die Felder zurückgeschrieben
- **Auth-Umschalter** als RadioGroup: Passwort ↔ privater Schlüssel, das jeweils irrelevante Layout wird automatisch ausgeblendet
- **Privater Schlüssel mit Dateiauswahl** (schreibgeschützte Vorschau), separates Passphrase-Feld
- **`.pub`-Warnung** — Toast, falls versucht wird, den öffentlichen Teil des Schlüssels zu laden
- **Schlüssel-Statuszeile** — PEM geladen / fehlgeschlagen / keiner
- **Jump-Host-Spinner** — listet gespeicherte Verbindungen (ohne die eigene)
- **Automatischer Jump-Vorschlag** — erkennt Private-IP-Präfixe (`10.`, `172.`, `192.`) und klappt den Jump-Abschnitt auf
- **Validierung** — Host und Benutzername erforderlich; Port 1–65535, Standard 22

### Verbindungs-Authentifizierung

- **JSch (mwiede-Fork)** — Passwort, publickey oder beides kombiniert (`buildPreferredAuths`)
- **Interaktive Passwortabfrage** — wenn keines gespeichert ist, erscheint ein `KonsoleDialog`-gestylter AlertDialog (weißer Text, transparenter Hintergrund)
- **Keyboard-interactive Auth** — serverseitige Prompts laufen durch denselben Dialog
- **30 s Prompt-Timeout** (CountDownLatch) — der aufrufende Thread wird sauber freigegeben, falls der Nutzer nicht antwortet
- **Main-Thread-Schutz** — der Dialog erscheint stets auf dem UI-Thread (Handler post)
- **Jump-Host-Kette** — im mwiede-Fork `setPortForwardingL(0, target_host, target_port)` auf einem zufälligen Port + zweite Session auf Loopback
- **Jump-Fortschrittsmeldungen** werden ins Terminal geschrieben — `Jump: host:port → Connecting`, anschließend `Jump OK → Connecting: target:port`
- **„Jump-Host nicht gefunden"-Fehler** — wenn die referenzierte Jump-Verbindungs-ID nicht mehr existiert

### Terminal-Emulator (Canvas-basiert)

**Rendering**

- Eigenes `TerminalView`, pro Zelle `canvas.drawText`, Basis mindestens 80×24
- Zellenfelder: Zeichen (String, Surrogate-Pair-fähig), Vorder-/Hintergrundfarbe, fett, unterstrichen, Reverse
- **NerdFont mitgeliefert** (`assets/fonts/NerdFont.ttf`); fällt bei Asset-Fehler auf `Typeface.MONOSPACE` zurück
- Schriftgrößenbereich 6 sp…40 sp; beim ersten Layout wird auf 80 Spalten automatisch skaliert
- **Zoom +/−-Schaltflächen** in der Toolbar (`SharedPreferences("settings", "font_size")`), über Neustarts hinweg persistent
- **Pro App** gespeicherte Schriftgröße (nicht pro Verbindung)
- Unterstreichung per `drawLine` auf Höhe cellH-1
- Cursor-Invertierung zur Zeichenzeit (fg↔bg)
- **Cursor-Blinken** 600 ms on / 300 ms off

**ANSI/VT-Zustandsautomat**

- Zustände NORMAL / ESCAPE / CSI / OSC / DCS / CHARSET
- SGR: 16 Basisfarben + 8 Bright + 256er-Cube + Truecolor (`38;2;r;g;b`), bold/underline/reverse + Resets
- Cursor: A/B/C/D, H/f, G, E/F, s/u, ESC 7/8 (DEC-Legacy)
- Erase: J, K, X, L/M (Zeilen einfügen/löschen), P/@ (Zeichen)
- **Scroll-Region** — r, S/T (nach oben/unten scrollen)
- **Alt-Screen** — 47 / 1049 Umschalter (vi, top, less, mc sichern und stellen den Bildschirm wieder her)
- **DECCKM App-Cursor-Modus** — `ESC[A/B/C/D` ↔ `ESC O A/B/C/D`
- **Bracketed-Paste-Modus** (2004) — eingefügter Text wird in `ESC[200~ … ESC[201~` eingepackt

**Textauswahl**

- **Langes Drücken 400 ms** → startet die Auswahl, schwebender `ActionMode` (Kopieren/Einfügen)
- Live-Tracking des Endpunkts während des Ziehens, `invalidateContentRect`
- Normalisierung der Richtung (vorwärts/rückwärts)
- `buildSelectedText` — trimmt jede Zeile und verbindet sie mit `\n`
- Tippen während der Auswahl → hebt die Auswahl auf
- **ViewPager-Swipe-Blockade** (`requestDisallowInterceptTouchEvent`) während Auswahl/Scroll

**Scrollen und Berührung**

- Vertikales Scrollen: `scrollRowOff` 0…`scrollback.size`
- **Scrollback**-Ring mit 3000 Zeilen
- Horizontales Scrollen ist optional (auf der Startseite deaktiviert)
- **8-px-Bewegungsschwelle** — unterscheidet Tippen von Scrollen
- Automatische Bottom-Gravity bei neuer Ausgabe (`scrollRowOff = 0`)
- Tippen → `focusAndShowKeyboard()`
- **Shift+PageUp/Down** auf einer Hardware-Tastatur scrollt im Scrollback

**IME-Integration**

- Eigene `TerminalInputConnection` (BaseInputConnection)
- Sentinel-Trick: Der Puffer beginnt stets mit einem Leerzeichen-Sentinel, die Nutzereingabe nutzt `removePrefix`
- Funktioniert mit Swipe-/Glide-IMEs
- `deleteSurroundingText` → manueller DEL-Byte-Strom (0x7F)
- `inputType=TYPE_NULL` — keine Autovervollständigung oder Vorschläge im Terminal

### Hardware-Tastatur

- Enter→13, Tab→9, Esc→27, DEL→127
- Home/End → `ESC[H` / `ESC[F`
- PageUp/PageDown → `ESC[5~` / `ESC[6~`
- **F1–F12** → `ESC O P/Q/R/S`, `ESC[15~`, `ESC[17~`, …
- Pfeiltasten → Codes gemäß App-Cursor-Modus
- Shift+PageUp/Down werden auf den Scrollback umgeleitet (nicht als Eingabe gesendet)

### Bildschirm-Tastenleiste

**Hauptreihe** (40 dp)

- **⌨** — schaltet die System-IME um. Echte IME-Sichtbarkeit wird über `WindowInsetsCompat` ausgelesen, sodass eine per Zurück-Geste geschlossene Tastatur sauber wieder geöffnet wird.
- **Fn** — schaltet die F1–F12-Reihe um
- **CTRL / SHIFT / ALT / ALTGR** — klebrige Modifikatoren, werden aktiv mit `keybar_mod_active` hervorgehoben; **automatisches Zurücksetzen** nach jedem gesendeten Tastendruck
- **CTRL** öffnet zusätzlich eine dedizierte Ctrl-Kombi-Reihe (A/B/C/D/V/Z)
- **ESC, TAB** — direktes Byte
- **↑** — schaltet eine Pfeiltastenreihe um (← ↑ ↓ →)
- **📁** — `ActivityResultContracts.OpenDocument()` → SFTP-Upload
- Dazwischen optische `KeyBarDivider`-Trenner

**Fn-Reihe** (36 dp) — F1–F12-Escape-Sequenzen, jeder Druck **blitzt** für 300 ms in der Akzentfarbe auf

**Ctrl-Reihe** — dynamisch erzeugt (`LayoutInflater` + `item_keybar_button`)

- `Ctrl+C`: kopiert die Auswahl mit Toast in die App-interne Zwischenablage, sonst wird ETX (0x03) gesendet
- `Ctrl+V`: fügt aus der App-internen Zwischenablage ein → `pasteText` + Toast
- A/B/D/Z: Code = char - 'A' + 1 (1…26)

**Pfeiltastenreihe** (36 dp) — ← ↑ ↓ → respektiert den App-Cursor-Modus

**Scroll-Hinweise in jeder Reihe** — ◀/▶ nur dann animiert, wenn tatsächlich gescrollt werden kann; `canScrollHorizontally(±1)` wird nach jedem Event geprüft; Animator-Cancel + Reset in onDestroy

### Spezielle Tastenkombinationen (`applyModifiers`)

- Ctrl+Buchstabe → 1…26 (Standard-Steuercodes)
- **Ctrl+Space → 0x00 (NUL)**
- **Ctrl+[ → 27 (ESC)**
- Shift + Kleinbuchstabe → Großbuchstabe (Fallback für Soft-Input)
- Alt / AltGr → `ESC` + Originalbyte (Meta-Prefix)

### Zwischenablage

**App-interne Zwischenablage** (`TerminalClipboard`)

- Singleton `var text: String?`
- Ctrl+C mit Auswahl → **App-interne** Zwischenablage (nicht die System-Zwischenablage) + dezenter Toast
- Ctrl+V → App-intern → `pasteText`

**System-Zwischenablage**

- ActionMode **Kopieren** → `ClipboardManager.setPrimaryClip`
- ActionMode **Einfügen** → `coerceToText` → `pasteText`
- Beim Einfügen `\n` → `\r`, umschlossen, falls der Bracketed-Paste-Modus aktiv ist

### SFTP-Datei-Upload

- 📁 → `OpenDocument`-Picker
- Dateiname aus `OpenableColumns.DISPLAY_NAME`, Fallback `uri.lastPathSegment`
- Größenabfrage → Umschalten zwischen determinate und indeterminate Fortschrittsbalken
- **Fortschrittsdialog** — Dateiname, `X.X MB / Y.Y MB` oder `Z.Z MB`, wenn die Gesamtgröße unbekannt ist, `setCancelable(false)`
- Bei Erfolg `KonsoleToast.showWithAction` — „Hochgeladen: ~/filename" + **Rückgängig**-Schaltfläche → `deleteRemoteFile`
- Bei Fehler lokalisierte Meldung über das friendlyError-Mapping

### Status und Rückmeldung

- **Statusleiste** (20 dp) oberhalb des Terminals: `Keine Verbindung`, `Verbinde: host…`, `Verbunden: host`, `Getrennt: host`
- **Reconnect-Schaltfläche** in der Mitte des Terminals, ↺-Icon, nur sichtbar bei DISCONNECTED
- **KonsoleToast** — eigener Toast, 100 dp unterer Rand, 4000 ms Standard / 3000 ms mit Aktion, Ausblend-Animation (Scale+Alpha, 250 ms)

### Fehlermeldungen (friendlyError-Mapping)

Rohe JSch-Exceptions werden in lesbaren, lokalisierten Text übersetzt:

- `connection refused` → „Der Server hat die Verbindung abgelehnt"
- `timed out` / `timeout` → „Zeitüberschreitung"
- `no route to host` → „Keine Route zum Host"
- `network unreachable` → „Netzwerk nicht erreichbar"
- `unknown host` → „Unbekannter Host"
- `auth fail` / `authentication` → „Authentifizierung fehlgeschlagen"
- `connection closed` / `closed by foreign host` → „Verbindung geschlossen"
- `broken pipe` → „Verbindung unterbrochen"
- `port forwarding`, `channel` → jeweils eigene Meldungen
- Andernfalls: die Exception-Meldung, per Regex von Stack-Class-Präfixen bereinigt

### Hintergrund und Lebenszyklus

- **`SshForegroundService`** — `START_STICKY`, beendet Sitzungen in `onTaskRemoved` bewusst **nicht**
- **Ausgabepuffer** — 256 KB Ring pro Sitzung, `ByteArrayOutputStream` mit Overflow-Trim
- **Replay bei Reconnect** — nachdem das Fragment gebunden hat, wird der Puffer ins Terminal nachgespielt, sodass frühere Ausgabe sichtbar bleibt
- **Zwei NotificationChannels** — `ssh_idle` (inaktiv) und `ssh_active` (aktiv), unterschiedliches Badge-Verhalten
- **Benachrichtigungs-Badge** zeigt die Anzahl aktiver Sitzungen
- Benachrichtigung mit niedriger Priorität (`PRIORITY_LOW`, `setSilent`)
- `onRebind` aktiviert (`onUnbind → true`)
- `TerminalFragment.onDestroy` trennt die Verbindung **nicht** (der Service besitzt die Sitzung)
- `onAttach`/`onDetach` führen Buch über die Listener (speicherleckfrei)

### Zustandsautomat des Verbindungsindikators

- `NONE → CONNECTING`: neuer `connect()`-Aufruf
- `CONNECTING → CONNECTED`: Shell-Channel geöffnet (`onConnected`)
- `CONNECTING → DISCONNECTED`: `onError` (Auth, Timeout, Refused)
- `CONNECTED → DISCONNECTED`: Read-Loop endet oder explizites `disconnectSession`
- `DISCONNECTED → CONNECTING`: Reconnect-Schaltfläche

### Sicherheit

- **EncryptedSharedPreferences** — Value-Schema AES256_GCM, Key-Schema AES256_SIV, über Android Keystore gestützter MasterKey
- **Legacy-Migration** beim ersten Start — alte Klartext-Profile werden in den verschlüsselten Speicher überführt, anschließend wird der Legacy-Speicher geleert
- **Plain-Prefs-Fallback** — falls die Keystore-Initialisierung scheitert (Warnung im Log), gehen Profile nicht verloren
- **JSch `StrictHostKeyChecking=no`** — Trust-on-first-use
- Zugangsdaten verlassen das Gerät nie

### Startseite / Referenzen

- **Willkommens-Banner** — „KonsoleSSH" in drei Orange-Nuancen mit ANSI-Farben + 9 Zeilen Beschreibung (`[38;5;244m` gedimmt)
- Feste Schriftgröße 16 sp auf der Startseite
- Horizontales Scrollen auf der Startseite deaktiviert
- **Linux-Referenz** — `top`, `df`, `du`, `dd`, `tail`, `head`, `grep`, `egrep`, `awk`, `sed`, `tr`, `ip`, `mc` + `⚠`-Warnsymbol bei destruktiven Befehlen (`dd`, `sed -i`)
- **Tmux-Referenz** — Sessions/Windows/Panes/Layouts/Prefix/Resize/Scroll/Paste
- **Sprachabhängiger Inhalt** — HU und EN sind separat verfasst, nicht nur Label-Übersetzung
- `scrollToTop()` beim Öffnen
- Terminal-Resize auf einem Cheat-Tab rendert vollständig neu (`clear()` + neues Banner)

### Edge-to-Edge und Ausrichtung

- `ViewCompat.setOnApplyWindowInsetsListener` auf jeder Activity
- `Type.systemBars() | Type.displayCutout()` + `Type.ime()` gemeinsam behandelt — im Querformat bleibt der Inhalt über der Navigationsleiste, beim Einblenden der IME wird er hochgezogen
- `screenOrientation=fullSensor` — automatische Drehung Hoch- und Querformat
- `configChanges` so konfiguriert, dass die Activity bei Rotation nicht neu erzeugt wird
- `windowSoftInputMode=adjustResize`
- `onSizeChanged` → Schriftgröße neu berechnen, termRows/termCols neu kalibrieren

### Lokalisierung

- 7 Sprachen: **Englisch** (Standard), **Ungarisch**, **Deutsch**, **Spanisch**, **Französisch**, **Slowakisch**, **Rumänisch**
- `supportsRtl="true"` — vorbereitet für künftige RTL-Sprachen
- Folgt der Systemsprache
- Der Inhalt der Referenzen ist ebenfalls lokalisiert (HU/EN)

### Komfort-Details

- Nach dem Schließen des ActionMode kehrt der Fokus automatisch ins Terminal zurück
- Nach Loslassen einer Modifikator-Schaltfläche kehrt der Fokus ins Terminal zurück
- Klebrige Modifikator-Schaltflächen wechseln während sie aktiv sind die Farbe (klarer visueller Zustand)
- Jeder Tastenleisten-Tipp blitzt 300 ms auf
- DEL (0x7F) wird als eigener Code gesendet — keine Backspace-Alternative
- Surrogate-Pair / Emoji werden pro Zelle als ein einziges logisches Zeichen gespeichert
- Terminal-Resize → `resize(tabId, cols, rows)` ans PTY (`vim`, `htop` übernehmen die neue Größe)
- Input-Connection `resetToSentinel` nach jedem Commit — IME-Zustand bleibt stets sauber
- Beim Trennen erscheint „Verbindung geschlossen" im Terminal
- Ctrl+C / Ctrl+V zeigen einen dezenten Toast: „Kopiert" / „Eingefügt"
- Schaltflächen nutzen den `selectableItemBackgroundBorderless`-Ripple
- `KonsoleDialog`-Stil: weißer Text, grauer Hint, transparenter Hintergrund
- Schließen/Löschen/Beenden einer aktiven Verbindung fordert stets eine Bestätigung an

### Konstanten

- Connect-Timeout: 15 s, Shell-Connect: 10 s, Passwort-Prompt: 30 s
- Ausgabepuffer: 256 KB pro Sitzung
- Scrollback: 3000 Zeilen
- Schrift: 6 sp – 40 sp
- Langes Drücken: 400 ms
- Touch-Bewegungsschwelle: 8 px
- Scroll-Hinweis-Pulsieren: Periode 900 ms, ±8 dp
- Toast: 4000 ms Standard, 3000 ms mit Aktion, Ausblend-Anim. 250 ms
- KonsoleToast unterer Rand: 100 dp
- Cursor-Blinken: 600 ms on / 300 ms off

## Architektur

Sitzungen gehören `SshForegroundService`, nicht den Fragments:

- Verbindungen überleben die Neuanlage der Activity (Rotation, Zurück-Taste, Task-Entfernung)
- `TerminalFragment` bindet/löst sich in `onStart`/`onStop` und spielt den Ausgabepuffer beim Wiederverbinden ab
- `OutputBuffer` bewahrt je Sitzung die letzten 256 KB Ausgabe

## Projektstruktur

```
app/src/main/java/hu/billman/konsolessh/
├── model/           ConnectionConfig, SavedConnections (verschlüsselte Prefs)
├── ssh/             SshSession, SshForegroundService
├── terminal/        TerminalView, AnsiParser, TerminalClipboard
└── ui/              MainActivity, TerminalFragment, Dialoge, Sheets
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
# Debug-Build + Unit-Tests
./gradlew :app:testDebugUnitTest

# Signiertes Release-App-Bundle (Keystore erforderlich)
./gradlew :app:bundleRelease
```

Der **Release**-Build wird mit R8 minifiziert und die Ressourcen verkleinert. Reflection-lastiger Code
(JSch, Gson-Modellklassen) wird über `app/proguard-rules.pro` erhalten. Es wird eine `mapping.txt`
erzeugt, damit Play Console Stack-Traces deobfuszifizieren kann.

## Berechtigungen

| Berechtigung                          | Grund                                                    |
| ------------------------------------- | -------------------------------------------------------- |
| `INTERNET`                            | SSH-Verbindung                                           |
| `ACCESS_NETWORK_STATE`                | Prüfung der Netzwerk-Erreichbarkeit                      |
| `CHANGE_NETWORK_STATE`                | Foreground Service (Typ connectedDevice)                 |
| `FOREGROUND_SERVICE`                  | Start des Hintergrunddienstes                            |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Netzwerk-Foreground-Service (API 34+)                    |
| `POST_NOTIFICATIONS`                  | Persistente Sitzungs-Benachrichtigung (API 33+)          |

Es werden keine Daten erhoben, geteilt oder hochgeladen. Zugangsdaten bleiben auf dem Gerät im Keystore-gestützten verschlüsselten Speicher.

## Voraussetzungen

- Android 8.0 (API 26) oder neuer
- Hoch- und Querformat werden unterstützt

## Versionsgeschichte

- **1.0.6** — Reconnect-Statusanzeige wird nun korrekt grün (bleibt nicht mehr
  auf Gelb hängen); Tastatur-Symbol öffnet die IME zuverlässig auf Android 14+
  (echte `WindowInsetsCompat`-basierte Sichtbarkeit statt des veralteten `SHOW_FORCED`)
- **1.0.5** — interner Fix (übersprungene Version)
- **1.0.4** — neues, schlankeres `>_` Prompt-App-Icon (Stroke-Design)
- **1.0.3** — für Play verfeinertes App-Icon und Assets
- **1.0.2** — Paket umbenannt in `hu.billman.konsolessh`, Edge-to-Edge-Insets-Fix,
  Softtastatur nur auf ausdrückliche Anforderung, SFTP-Upload mit Fortschrittsdialog
  und Rückgängig-Toast, R8-Minifizierung, Gradle 9.4.1 / AGP 9.2.0
- **1.0.1** — `targetSdk = 35`, erste Play-Einreichung
- **1.0.0** — Multi-Tab-SSH, Jump-Host, Baum der gespeicherten Verbindungen,
  Linux- und Tmux-Referenzen, ungarische Übersetzung
