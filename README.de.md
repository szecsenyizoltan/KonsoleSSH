# KonsoleSSH

SSH-Terminal mit mehreren Tabs für Android — inspiriert von KDE Konsole.

> **Sprache:** Deutsch · [English](README.md) · [Magyar](README.hu.md) · [Español](README.es.md) · [Français](README.fr.md)

## Screenshots

| Startbildschirm | Neue SSH-Verbindung |
| --- | --- |
| ![Start](play_screenshot/Screenshot_20260422_094020.png) | ![Neue Verbindung](play_screenshot/Screenshot_20260422_094447.png) |

| Tmux-Referenz | Linux-Referenz |
| --- | --- |
| ![Tmux-Referenz](play_screenshot/Screenshot_20260422_094521.png) | ![Linux-Referenz](play_screenshot/Screenshot_20260422_094530.png) |

## Funktionen

### Verbindungen

- **Multi-Tab-Oberfläche** — TabLayout + ViewPager2, jeder Tab besitzt eine eigene SSH-Sitzung
- **SSH-Verbindungen** — JSch-Bibliothek, Authentifizierung per Passwort **oder** privatem Schlüssel (PEM) mit optionaler Passphrase
- **Jump-Host-Unterstützung (`ssh -J`)** — interne Hosts über eine gespeicherte Gateway-Verbindung erreichen (lokales Port-Forwarding)
- **Interaktive Passwortabfrage** — ein Dialog erscheint beim Verbinden, wenn kein Passwort gespeichert ist
- **Gespeicherte Verbindungen** — Profile ruhend mit AES256 verschlüsselt (`EncryptedSharedPreferences`, Android Keystore); alphabetische Sortierung
- **Baumgruppierte Auswahl** — Verbindungen mit Unterstrichen im Namen (z. B. `acme_prod_web`, `acme_prod_db`, `foobar_dev_01`) werden automatisch nach gemeinsamem Präfix gruppiert. Hier fasst `acme_` → `prod_` die ersten beiden zusammen, während `foobar_dev_01` flach bleibt, weil es als einziges unter seinem Präfix steht. Gruppen öffnen/schließen sich mit einem ▶/▼-Pfeil.

### Terminal

- **Canvas-basierter Terminal-Emulator** — 256 Farben + Truecolor, fett, unterstrichen, Reverse Video, Scrollback-Puffer
- **ANSI/VT100 + xterm-256color PTY** — bash/zsh/fish-Prompts werden korrekt dargestellt
- **NerdFont-Unterstützung** — JetBrainsMono Nerd Font integriert; Emojis und Surrogate-Pair-Zeichen werden korrekt angezeigt
- **Kopieren bei Auswahl** (lange drücken + ziehen) und **Einfügen aus der Zwischenablage**
- **Schriftgröße-Zoom** — Zoom-Schaltflächen in der Toolbar, Einstellung bleibt erhalten
- **Wiederverbinden-Schaltfläche** — erscheint in der Mitte des Terminals, wenn die Verbindung abbricht

### On-Screen-Tastenleiste

Die untere Leiste bietet alles, was die Android-Softtastatur nicht kann:

- **⌨** — öffnet/schließt die System-Softtastatur. _Die Softtastatur öffnet sich nur mit dieser Taste oder beim Tippen in das Terminal; keine andere Taste der Leiste lässt sie erscheinen._
- **Fn** — zeigt eine Reihe mit den Funktionstasten **F1 – F12**
- **CTRL / SHIFT / ALT / ALTGR** — klebrige Einmal-Modifikatoren (setzen sich nach dem nächsten Tastendruck zurück)
- **CTRL** öffnet zusätzlich eine Ctrl-Kombi-Reihe mit `Ctrl+A`, `Ctrl+B`, `Ctrl+C`, `Ctrl+D`, `Ctrl+V`, `Ctrl+Z`
  - `Ctrl+C` kopiert die aktuelle Auswahl, sonst sendet es `ETX`
  - `Ctrl+V` fügt aus der Zwischenablage ein
- **ESC, TAB** — Ein-Tipp-Tasten
- **↑** — zeigt eine Pfeiltastenreihe (← ↑ ↓ →)
- **📁** — lokale Datei auswählen und **per SFTP** in das Remote-Home hochladen. Ein Fortschrittsdialog zeigt übertragene / gesamte MB. Nach Erfolg bietet ein 3-Sekunden-Bestätigungs-Toast eine **Rückgängig**-Taste, die die eben hochgeladene Datei vom Server löscht.

### Status / UX

- **Verbindungsstatus-Anzeigen** — grüne/gelbe/rote Punkte auf Tabs und in der Auswahl
- **Startseite** — wischbar; nach rechts wischen, um die Referenzen zu sehen
- **Linux-Referenz** — Beispiele zu `top`, `df`, `du`, `dd`, `tail`, `head`, `grep`, `egrep`, `awk`, `sed`, `tr`, `ip`, `mc`
- **Tmux-Referenz** — Sessions, Windows, Panes, Layouts, Prefix-Bindings
- **Hintergrund-Persistenz** — aktive SSH-Sitzungen bleiben beim Sperren des Bildschirms und beim Wegwischen via Foreground Service erhalten
- **Benachrichtigungs-Badge** — zeigt die Anzahl aktiver Verbindungen
- **App-schließen-Schaltfläche** — im Tab-Auswahl-Menü; fragt bei aktiven Verbindungen nach
- **Freundliche Fehlermeldungen** — lesbarer Text statt roher Java-Exceptions
- **Edge-to-Edge-Layout** — korrekte Window-Insets-Behandlung auf Android 15; der Inhalt bleibt im Querformat über der Navigationsleiste und rutscht beim Öffnen der Softtastatur hoch

### Lokalisierung

- Englisch als Standard, Übersetzungen enthalten: **Ungarisch**, **Spanisch**, **Deutsch** und **Französisch**. Die App folgt der Systemsprache. Die eingebauten Referenzen (Linux/tmux) sind für alle Sprachen außer Ungarisch auf Englisch.

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

- **1.0.2** — Paket umbenannt in `hu.billman.konsolessh`, Edge-to-Edge-Insets-Fix,
  Softtastatur nur auf ausdrückliche Anforderung, SFTP-Upload mit Fortschrittsdialog
  und Rückgängig-Toast, R8-Minifizierung, Gradle 9.4.1 / AGP 9.2.0
- **1.0.1** — `targetSdk = 35`, erste Play-Einreichung
- **1.0.0** — Multi-Tab-SSH, Jump-Host, Baum der gespeicherten Verbindungen,
  Linux- und Tmux-Referenzen, ungarische Übersetzung
