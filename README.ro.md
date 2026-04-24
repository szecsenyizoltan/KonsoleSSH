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

### File și navigare

- **Interfață cu mai multe file** — TabLayout + ViewPager2, fiecare filă deține o sesiune SSH independentă, identificată printr-un `TabInfo` bazat pe UUID
- **Trei pagini fixe** — ecranul de întâmpinare (poziția 0), foaia de referință Linux și foaia de referință tmux (ultimele două sloturi); filele SSH se aranjează între ele
- **Punct de stare a conexiunii pe fiecare filă** — verde (CONNECTED), galben (CONNECTING), roșu (DISCONNECTED), ascuns (NONE)
- **Apăsare lungă pe o filă** → dialog de redenumire
- **Butonul ✕ al filei** — cere confirmare pentru sesiunile active; închide imediat dacă este deconectată
- **Derulare în rândul filelor** — când filele depășesc lățimea, apar butoane ◀/▶ cu pulsație blândă (ObjectAnimator ±8dp, 900 ms reverse infinit)
- **Înălțimea indicatorului de filă setată la 0dp** cu o singură filă (mai puțin zgomot vizual)

### Selector de file / conexiuni salvate (meniul `+`)

- **BottomSheetDialogFragment** — înălțime completă (STATE_EXPANDED, MATCH_PARENT)
- **File active + arbore al conexiunilor salvate**, așezate una sub alta
- **Grupare arborescentă după prefixul cu liniuță jos** — `acme_prod_web`, `acme_prod_db` → grup `acme_` → `prod_`; o frunză singură nu este niciodată grupată
- **Padding pe bază de adâncime** — `16dp + adâncime × 20dp`
- **Starea de expandare supraviețuiește redeschiderilor dialogului** (susținută de companion-object)
- **Pictograme** — grup: 📁 / 📂 (închis/deschis), frunză: ⚡
- **Rânduri de frunză** — sub-linie `user@host:port`, editare + ștergere la dreapta
- **Rânduri de grup** — sub-linie cu numărul de frunze, săgeată ▶/▼ la dreapta
- **Nume de utilizator cel mai recent folosit** pre-completat pentru conexiunile noi

### Dialog Nou / Editare

- **Mod de editare** — `ConnectionConfig` complet serializat Gson prin Bundle și restaurat în câmpuri
- **Comutator de autentificare** RadioGroup: parolă ↔ cheie privată, layout-ul irelevant se ascunde automat
- **Cheie privată cu selector de fișier** (previzualizare read-only), câmp separat pentru parafrază
- **Avertisment `.pub`** — toast dacă utilizatorul încearcă să încarce partea publică a cheii
- **Linie de stare a cheii** — PEM încărcat / eșuat / niciunul
- **Spinner de jump host** — listează conexiunile salvate (excluzându-se pe sine)
- **Sugestie automată de jump** — detectează prefixele de IP privat (`10.`, `172.`, `192.`) și expandează secțiunea jump
- **Validare** — host și nume de utilizator obligatorii; port 1–65535, implicit 22

### Autentificare conexiune

- **JSch (fork mwiede)** — parolă, publickey sau ambele combinate (`buildPreferredAuths`)
- **Cerere interactivă de parolă** — când nu este salvată una, apare un AlertDialog cu stilul `KonsoleDialog` (text alb, fundal transparent)
- **Autentificare keyboard-interactive** — prompturile din partea serverului trec prin același dialog
- **Timeout de 30 s pentru prompt** (CountDownLatch) — firul apelant este eliberat curat dacă utilizatorul nu răspunde
- **Protecție pe firul principal** — dialogul apare întotdeauna pe firul UI (Handler post)
- **Lanț de jump host** — `setPortForwardingL(0, target_host, target_port)` din forkul mwiede pe un port aleator + o a doua sesiune către loopback
- **Mesaje de progres jump** afișate în terminal — `Jump: host:port → Connecting`, apoi `Jump OK → Connecting: target:port`
- **Eroare „Jump host negăsit”** — când un ID de conexiune jump referit nu mai există

### Emulator de terminal (bazat pe Canvas)

**Randare**

- `TerminalView` personalizat, `canvas.drawText` per celulă, bază minimă 80×24
- Câmpuri de celulă: caracter (String, conștient de surrogate-pair), fg/bg, bold, subliniat, invers
- **NerdFont inclus** (`assets/fonts/NerdFont.ttf`); revine la `Typeface.MONOSPACE` în caz de eroare la asset
- Interval font 6sp…40sp; la prima dispunere se auto-dimensionează pentru a încăpea într-o țintă de 80 coloane
- **Butoane zoom +/−** în bara de instrumente (`SharedPreferences("settings", "font_size")`), persistă între reporniri
- Dimensiunea fontului este **per aplicație** (nu per conexiune)
- Subliniere randată cu `drawLine` la cellH-1
- Inversare cursor la desen (fg↔bg)
- **Cursor clipește** 600 ms pornit / 300 ms stins

**Mașină de stări ANSI/VT**

- Stări NORMAL / ESCAPE / CSI / OSC / DCS / CHARSET
- SGR: 16 de bază + 8 luminoase + cub de 256 + truecolor (`38;2;r;g;b`), bold/subliniat/invers + reset-uri
- Cursor: A/B/C/D, H/f, G, E/F, s/u, ESC 7/8 (legacy DEC)
- Ștergere: J, K, X, L/M (insert/delete linii), P/@ (caractere)
- **Regiune de derulare** — r, S/T (scroll sus/jos)
- **Ecran alternativ** — comutare 47 / 1049 (vi, top, less, mc păstrează și restaurează ecranul)
- **Mod app-cursor DECCKM** — `ESC[A/B/C/D` ↔ `ESC O A/B/C/D`
- **Bracketed paste mode** (2004) — textul lipit este încadrat în `ESC[200~ … ESC[201~`

**Selecție de text**

- **Apăsare lungă 400 ms** → pornește selecția, `ActionMode` flotant (Copiere/Lipire)
- Urmărire în timp real a punctului final la tragere, `invalidateContentRect`
- Normalizare a direcției înainte/înapoi
- `buildSelectedText` — trimează fiecare linie, le unește cu `\n`
- Atingere în timpul selecției → anulează selecția
- **Blocare swipe ViewPager** (`requestDisallowInterceptTouchEvent`) în timpul selecției/derulării

**Derulare și atingere**

- Derulare verticală: `scrollRowOff` 0…`scrollback.size`
- **Scrollback** inel de 3000 de linii
- Derularea orizontală este opțională (dezactivată pe pagina de întâmpinare)
- **Prag de mișcare de 8 px** — distinge atingerea de derulare
- Gravitație automată spre partea de jos la ieșire nouă (`scrollRowOff = 0`)
- Atingere → `focusAndShowKeyboard()`
- **Shift+PageUp/Down** pe o tastatură hardware derulează scrollback-ul

**Integrare IME**

- `TerminalInputConnection` personalizat (BaseInputConnection)
- Truc cu sentinelă: bufferul începe întotdeauna cu un spațiu sentinelă, input-ul utilizatorului folosește `removePrefix`
- Funcționează cu IME-urile swipe/glide
- `deleteSurroundingText` → flux manual de octeți DEL (0x7F)
- `inputType=TYPE_NULL` — fără autocomplete sau sugestii în terminal

### Tastatură hardware

- Enter→13, Tab→9, Esc→27, DEL→127
- Home/End → `ESC[H` / `ESC[F`
- PageUp/PageDown → `ESC[5~` / `ESC[6~`
- **F1–F12** → `ESC O P/Q/R/S`, `ESC[15~`, `ESC[17~`, …
- Tastele săgeată → coduri conform modului app-cursor
- Shift+PageUp/Down deviate către scrollback (nu sunt trimise ca input)

### Bara de taste pe ecran

**Rândul principal** (40 dp)

- **⌨** — comută IME-ul sistemului. Vizibilitatea reală a IME-ului este citită prin `WindowInsetsCompat`, astfel încât o tastatură închisă de gestul înapoi este redeschisă curat.
- **Fn** — comută rândul F1–F12
- **CTRL / SHIFT / ALT / ALTGR** — modificatori sticky, evidențiați cu `keybar_mod_active` cât timp sunt activi; **reset automat** după orice trimitere de tastă
- **CTRL** deschide în plus un rând dedicat de combinații Ctrl (A/B/C/D/V/Z)
- **ESC, TAB** — octet direct
- **↑** — comută un rând cu taste săgeată (← ↑ ↓ →)
- **📁** — `ActivityResultContracts.OpenDocument()` → încărcare SFTP
- Separatoare vizuale `KeyBarDivider` între ele

**Rândul Fn** (36 dp) — secvențe escape F1–F12, fiecare apăsare **clipește** 300 ms (culoare de accent)

**Rândul Ctrl** — generat dinamic (`LayoutInflater` + `item_keybar_button`)

- `Ctrl+C`: copiază selecția în clipboard-ul intern al aplicației cu un toast, altfel trimite ETX (0x03)
- `Ctrl+V`: lipește din clipboard-ul intern al aplicației → `pasteText` + toast
- A/B/D/Z: cod = char - 'A' + 1 (1…26)

**Rândul săgeților** (36 dp) — ← ↑ ↓ → respectând modul app-cursor

**Indicatori de derulare pe fiecare rând** — ◀/▶ animate doar când există cu adevărat loc de derulat; `canScrollHorizontally(±1)` verificat după fiecare eveniment; animator cancel + reset în onDestroy

### Combinații de taste speciale (`applyModifiers`)

- Ctrl+literă → 1…26 (coduri de control standard)
- **Ctrl+space → 0x00 (NUL)**
- **Ctrl+[ → 27 (ESC)**
- Shift + minuscule → majuscule (fallback pentru soft-input)
- Alt / AltGr → `ESC` + octetul original (prefix meta)

### Clipboard

**Clipboard intern al aplicației** (`TerminalClipboard`)

- `var text: String?` singleton
- Ctrl+C cu o selecție → clipboard **intern** (nu cel de sistem) + toast discret
- Ctrl+V → intern → `pasteText`

**Clipboard de sistem**

- ActionMode **Copiere** → `ClipboardManager.setPrimaryClip`
- ActionMode **Lipire** → `coerceToText` → `pasteText`
- La lipire `\n` → `\r`, încadrat dacă bracketed paste mode este activ

### Încărcare fișier SFTP

- 📁 → selectorul `OpenDocument`
- Numele fișierului din `OpenableColumns.DISPLAY_NAME`, fallback `uri.lastPathSegment`
- Interogare de dimensiune → comutare între bară de progres determinată / indeterminată
- **Dialog de progres** — nume fișier, `X.X MB / Y.Y MB` sau `Z.Z MB` când totalul este necunoscut, `setCancelable(false)`
- La succes `KonsoleToast.showWithAction` — „Încărcat: ~/filename” + buton **Anulează** → `deleteRemoteFile`
- La eroare, mesaj localizat mapat prin friendlyError

### Stare și feedback

- **Bară de stare** (20 dp) deasupra terminalului: `Nicio conexiune`, `Se conectează: host…`, `Conectat: host`, `Deconectat: host`
- **Buton de reconectare** în centrul terminalului, pictograma ↺, afișat doar când DISCONNECTED
- **KonsoleToast** — toast personalizat, margine inferioară de 100 dp, implicit 4000 ms / 3000 ms cu acțiune, animație de ascundere (scale+alpha, 250 ms)

### Mesaje de eroare (maparea friendlyError)

Excepțiile brute JSch sunt mapate la text lizibil (localizat):

- `connection refused` → „Serverul a refuzat conexiunea”
- `timed out` / `timeout` → „Timeout”
- `no route to host` → „Nicio rută către gazdă”
- `network unreachable` → „Rețea inaccesibilă”
- `unknown host` → „Gazdă necunoscută”
- `auth fail` / `authentication` → „Autentificare eșuată”
- `connection closed` / `closed by foreign host` → „Conexiune închisă”
- `broken pipe` → „Canal întrerupt”
- `port forwarding`, `channel` → mesaje dedicate
- Altfel: mesajul excepției curățat prin regex de prefixe ale claselor din stack

### Fundal și ciclu de viață

- **`SshForegroundService`** — `START_STICKY`, **nu** oprește în mod deliberat sesiunile la `onTaskRemoved`
- **Buffer de ieșire** — inel de 256 KB per sesiune, `ByteArrayOutputStream` cu trimare la overflow
- **Replay la reconectare** — după ce Fragment-ul se leagă, bufferul este rejucat în terminal astfel încât ieșirea anterioară să fie vizibilă
- **Două NotificationChannels** — `ssh_idle` (inactiv) și `ssh_active` (activ), comportament diferit al insignei
- **Insigna de notificare** arată numărul sesiunilor active
- Notificare cu prioritate scăzută (`PRIORITY_LOW`, `setSilent`)
- `onRebind` activat (`onUnbind → true`)
- `TerminalFragment.onDestroy` **nu** deconectează (serviciul deține sesiunea)
- Evidența listener-ilor `onAttach`/`onDetach` (sigură la memory-leak)

### Mașină de stări a indicatorului de conexiune

- `NONE → CONNECTING`: nou apel `connect()`
- `CONNECTING → CONNECTED`: canal shell deschis (`onConnected`)
- `CONNECTING → DISCONNECTED`: `onError` (auth, timeout, refuzat)
- `CONNECTED → DISCONNECTED`: bucla de citire se termină sau `disconnectSession` explicit
- `DISCONNECTED → CONNECTING`: buton de reconectare

### Securitate

- **EncryptedSharedPreferences** — schemă de valori AES256_GCM, schemă de chei AES256_SIV, MasterKey susținută de Android Keystore
- **Migrare legacy** la prima pornire — profilurile vechi în text clar sunt mutate în stocarea criptată, apoi stocarea legacy este golită
- **Fallback plain-prefs** — dacă inițializarea keystore-ului eșuează (warning în log), profilurile nu se pierd
- **JSch `StrictHostKeyChecking=no`** — trust-on-first-use
- Credențialele nu părăsesc niciodată dispozitivul

### Bun venit / foi de referință

- **Banner de întâmpinare** — „KonsoleSSH” colorat ANSI în trei nuanțe de portocaliu + 9 linii de descriere (`[38;5;244m` atenuat)
- Font fix de 16 sp pe pagina de întâmpinare
- Derulare orizontală dezactivată pe pagina de întâmpinare
- **Foaia de referință Linux** — `top`, `df`, `du`, `dd`, `tail`, `head`, `grep`, `egrep`, `awk`, `sed`, `tr`, `ip`, `mc` + pictogramă de avertisment `⚠` pe comenzile distructive (`dd`, `sed -i`)
- **Foaia de referință tmux** — sesiuni/ferestre/panouri/layout-uri/prefix/redimensionare/derulare/lipire
- **Conținut selectiv pe locale** — HU și EN scrise separat, nu doar traducere de etichete
- `scrollToTop()` la deschidere
- Redimensionarea terminalului pe o filă de referință face re-randare completă (`clear()` + banner nou)

### Edge-to-edge și orientare

- `ViewCompat.setOnApplyWindowInsetsListener` pe fiecare Activity
- `Type.systemBars() | Type.displayCutout()` + `Type.ime()` tratate împreună — în peisaj conținutul rămâne deasupra barei de navigare, deschiderea IME-ului îl ridică
- `screenOrientation=fullSensor` — rotire automată portret + peisaj
- `configChanges` configurat astfel încât Activity-ul să nu fie distrus la rotire
- `windowSoftInputMode=adjustResize`
- `onSizeChanged` → recalculare font, recalibrare termRows/termCols

### Localizare

- 7 limbi: **engleză** (implicit), **maghiară**, **germană**, **spaniolă**, **franceză**, **slovacă**, **română**
- `supportsRtl="true"` — pregătit pentru viitoarele limbi RTL
- Urmează limba sistemului
- Conținutul foilor de referință este de asemenea localizat (HU/EN)

### Detalii de confort

- Focusul revine automat la terminal după ce se închide ActionMode
- După eliberarea unui buton de modificator, focusul revine la terminal
- Butonul de modificator sticky își schimbă culoarea cât timp este activ (stare vizuală clară)
- Fiecare atingere din key-bar clipește 300 ms
- DEL (0x7F) trimis ca un cod distinct — nu ca alternativă la Backspace
- Surrogate pair / emoji stocat ca un singur caracter logic per celulă
- Redimensionare terminal → `resize(tabId, cols, rows)` către PTY (`vim`, `htop` preiau noua dimensiune)
- `resetToSentinel` pe input-connection după fiecare commit — starea IME mereu curată
- Mesajul „Conexiune închisă” afișat în terminal la deconectare
- Ctrl+C / Ctrl+V arată un toast discret: „Copiat” / „Lipit”
- Butoanele folosesc ripple `selectableItemBackgroundBorderless`
- Stil `KonsoleDialog`: text alb, hint gri, fundal transparent
- Închiderea/ștergerea/ieșirea cu o conexiune activă cere întotdeauna confirmare

### Constante

- Timeout conectare: 15 s, conectare shell: 10 s, prompt parolă: 30 s
- Buffer de ieșire: 256 KB / sesiune
- Scrollback: 3000 de linii
- Font: 6 sp – 40 sp
- Apăsare lungă: 400 ms
- Prag mișcare atingere: 8 px
- Pulsație indicator derulare: perioadă 900 ms, ±8 dp
- Toast: 4000 ms implicit, 3000 ms cu acțiune, animație de ascundere 250 ms
- Margine inferioară KonsoleToast: 100 dp
- Clipire cursor: 600 ms pornit / 300 ms stins

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

## Licență

Aplicația este distribuită sub licența **GPL-3.0-or-later** — textul complet în fișierul [LICENSE](LICENSE). Este software liber: îl poți folosi, studia, modifica și redistribui liber; lucrările derivate trebuie să rămână sub aceeași licență.

## Istoricul versiunilor

- **1.0.7** — licență GPL-3.0 adăugată, build compatibil F-Droid (configurație de semnare condiționată)
- **1.0.6** — indicatorul de stare la reconectare devine corect verde (nu mai rămâne blocat
  pe galben); pictograma tastaturii deschide fiabil IME-ul pe Android 14+ (vizibilitate
  reală bazată pe `WindowInsetsCompat` în locul `SHOW_FORCED` deprecated)
- **1.0.5** — corecție internă (versiune omisă)
- **1.0.4** — nouă pictogramă `>_` prompt mai subțire (design stroke)
- **1.0.3** — pictogramă și resurse rafinate pentru Play Store
- **1.0.2** — pachet redenumit în `hu.billman.konsolessh`, corectare edge-to-edge insets,
  tastatură virtuală doar la cerere, upload SFTP cu dialog de progres și toast de
  Anulare, minificare R8, Gradle 9.4.1 / AGP 9.2.0
- **1.0.1** — `targetSdk = 35`, prima depunere în Play
- **1.0.0** — SSH cu mai multe file, jump host, arbore al conexiunilor salvate,
  referințe Linux și tmux, traducere în maghiară
