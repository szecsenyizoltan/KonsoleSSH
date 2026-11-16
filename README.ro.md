# KonsoleSSH

Terminal SSH cu file pentru Android, inspirat de KDE Konsole.

> **Limbă:** Română · [English](README.md) · [Magyar](README.hu.md) · [Español](README.es.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · [Slovenčina](README.sk.md)

## Capturi de ecran

| Ecran de bun venit | Conexiune SSH nouă |
| --- | --- |
| ![Bun venit](play_screenshot/Screenshot_20260422_094020.png) | ![Conexiune nouă](play_screenshot/Screenshot_20260422_094447.png) |

| Fițuică tmux | Fițuică Linux |
| --- | --- |
| ![Fițuică tmux](play_screenshot/Screenshot_20260422_094521.png) | ![Fițuică Linux](play_screenshot/Screenshot_20260422_094530.png) |

## Funcții

**File**
- Oricâte sesiuni SSH una lângă alta, fiecare în fila ei; comutare prin glisare sau din bara de file.
- Un punct de stare pe fiecare filă: se conectează, conectată sau deconectată; o filă deconectată oferă un buton de reconectare.
- O apăsare lungă redenumește fila; înainte de a închide o filă conectată, aplicația cere confirmare.
- Prima este pagina de bun venit; după ultima filă urmează o fițuică Linux și una tmux.

**Conexiuni**
- Conexiuni salvate într-un arbore: numele cu prefix comun până la liniuța de subliniere (`acme_prod_web`, `acme_prod_db`) sunt grupate.
- Autentificare cu parolă sau cheie privată, inclusiv întrebări keyboard-interactive. O parolă nesalvată este cerută la conectare.
- Jump hosturi, și înlănțuite: un jump host poate avea propriul jump host, până la 5 salturi. Pentru o adresă privată, formularul propune unul.
- Test de conexiune, precum și generarea unei chei RSA și încărcarea părții publice în `authorized_keys` de pe server.
- Verificarea cheilor de host prin încredere la prima utilizare: la prima conectare se afișează amprenta SHA256, iar o cheie schimbată este acceptată doar după înlocuire explicită.

**Terminal**
- Emulare compatibilă xterm: 16, 256 de culori și true color, ecran alternativ, regiune de derulare, bracketed paste, taste cursor în mod aplicație, desen de linii DEC, caractere late CJK și emoji. Comparată cu tmux pe sesiuni înregistrate de bash, vim, less, htop și mc.
- 3000 de linii de istoric; selecție de text cu copiere și lipire; zoom cu butoanele din bara de file, păstrat și după repornire.
- Tastaturile fizice funcționează, inclusiv tastele funcționale, Home/End, PageUp/PageDown și Shift+PageUp/PageDown pentru istoric.

**Bara de taste**
- Ctrl, Shift, Alt și AltGr fixabile: un modificator se aplică tastei următoare, apoi se eliberează.
- Esc, Tab, un rând de taste funcționale (F1–F12), un rând de săgeți și un rând Ctrl (Ctrl+A…Z; Ctrl+C copiază o selecție, altfel întrerupe).
- Încarcă un fișier prin SFTP în directorul personal de la distanță, cu progres și posibilitatea de anulare câteva secunde.

**Altele**
- Sesiunile continuă în fundal, în spatele unei notificări care numără conexiunile active.
- După o cădere sau un ANR, aplicația oferă un raport de partajat; nimic nu se trimite automat.
- Șapte limbi: engleză, maghiară, germană, spaniolă, franceză, slovacă, română; selectabile și în aplicație.
- Sincronizare opțională cu Google Drive: conexiunile salvate (cu parole și chei) și cheile de host acceptate se sincronizează între dispozitive printr-un dosar ascuns din propriul Google Drive al utilizatorului, criptate end-to-end cu o frază pe care doar el o știe. Un dispozitiv nou oferă restaurarea lor la prima pornire.

## Arhitectură

- **Interfață:** Jetpack Compose cu Material 3, o activitate pe ecran (ecranul principal, conexiuni salvate).
  Terminalul însuși este o vizualizare care desenează pe Canvas (`TerminalView`), integrată în Compose.
- **Stare:** un singur `SessionManager`, care trăiește cât aplicația, deține filele, sesiunile lor, ieșirea și
  întrebările în așteptare. Interfața îi urmărește starea prin ViewModel-uri și trimite comenzi; o întrebare
  de parolă sau de cheie de host este stare, deci supraviețuiește unei rotiri.
- **Fundal:** `SshForegroundService` doar ține procesul în viață și afișează notificarea; rulează cât timp o filă
  se conectează sau este conectată, apoi se oprește singur.
- **Stocare:** conexiunile într-un fișier DataStore criptat cu AES-256-GCM (cheie din Android Keystore, exclus din
  backup); setările în DataStore Preferences; cheile de host într-un fișier `known_hosts` în stil OpenSSH.
  Un depozit ilizibil este semnalat, niciodată suprascris.
- **Modelul terminalului:** `TerminalBuffer` este Kotlin fără dependențe Android, deci este testat pe JVM.
- **Dependențele** sunt legate manual în `AppContainer`, fără framework de injecție.
- **Sincronizare** (opțională): `SyncManager` îmbină conexiunile și cheile de host ale dispozitivului cu un fișier criptat AES-256-GCM din dosarul ascuns de date al aplicației din Drive; cheia provine din fraza utilizatorului (PBKDF2) și nu părăsește niciodată dispozitivele.

## Structura proiectului

```
app/src/main/java/hu/billman/konsolessh/
  model/        ConnectionConfig: conexiunea salvată
  data/         depozitul criptat de conexiuni, setări, migrarea formatelor vechi
  ssh/          sesiuni JSch, lanț de salturi, verificarea cheilor de host, încărcarea cheii, erori tipizate
  session/      SessionManager, buffer de ieșire, întrebări de parolă și cheie de host
  sync/         sincronizare Google Drive: criptare, îmbinare, client Drive
  terminal/     TerminalView și TerminalBuffer: desenare, intrare, emulare
  service/      serviciul în prim-plan
  diagnostics/  rapoarte de cădere și ANR
  ui/           ecrane Compose, ViewModel-uri, bara de taste, temă
  AppContainer.kt  graful de dependențe
```

## Compilare

```
Kotlin 2.4 · AGP 9.5 · Gradle 9.6 · Java 17
minSdk 26 (Android 8.0) · targetSdk / compileSdk 37 (Android 17)
```

```bash
./gradlew detekt :app:testDebugUnitTest :app:lintDebug   # verificări și teste unitare
./gradlew :app:connectedDebugAndroidTest                 # teste de interfață pe dispozitiv sau emulator
./gradlew :app:bundleRelease                             # App Bundle semnat (necesită un keystore)
```

Testele unitare includ teste de integrare cu servere OpenSSH reale; au nevoie de `sshd` instalat și sunt
omise fără el. Build-ul release este minimizat cu R8.

## Utilizare

1. **Conexiune nouă:** `+` → *Conexiune nouă…*, apoi host, port, nume de utilizator și parolă sau cheie.
2. **Jump host:** în formular, *Configurare jump host…* și alegerea unei conexiuni salvate ca gateway.
3. **Redenumirea sau închiderea unei file:** apăsare lungă, respectiv `✕`.
4. **Zoom:** butoanele cu lupă de lângă `+`.
5. **Fițuici:** glisare dincolo de ultima filă.
6. **Încărcarea unui fișier:** 📁 în bara de taste; fișierul ajunge în directorul personal de la distanță (`~`).
7. **Ieșire:** meniu (⋮) → *Ieșire*, sau `+` → *Ieșire*.

## Dependențe

| Bibliotecă | Rol |
| --- | --- |
| JSch (fork mwiede) | SSH și SFTP |
| Bouncy Castle | chei de host Ed25519 și schimb de chei curve25519 pentru JSch |
| Jetpack Compose, Material 3 | interfață |
| AndroidX Lifecycle, Activity | ViewModel-uri, stare conștientă de ciclul de viață |
| kotlinx.coroutines | I/O SSH asincron |
| kotlinx.serialization | formatul depozitului de conexiuni |
| AndroidX DataStore | depozitul de conexiuni și setările |
| AndroidX AppCompat | alegerea limbii în aplicație pe Android 8–12 |
| Google Play services auth | autorizare pentru sincronizarea opțională cu Google Drive |
| androidx.security | doar pentru migrarea depozitelor dinainte de 1.1.12 |

Versiunile exacte sunt în `gradle/libs.versions.toml`.

## Permisiuni

| Permisiune | Motiv |
| --- | --- |
| `INTERNET` | conexiuni SSH |
| `ACCESS_NETWORK_STATE` | verificarea accesibilității rețelei |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | menținerea sesiunilor deschise în fundal (API 34+) |
| `POST_NOTIFICATIONS` | notificarea de sesiune (API 33+) |
| `ACCESS_LOCAL_NETWORK` | hosturi din rețeaua locală (Android 17+) |

Nu se colectează și nu se trimit date nicăieri. Datele de autentificare rămân criptate pe dispozitiv.

## Licență

GPL-3.0-or-later; vezi [LICENSE](LICENSE).

## Istoricul versiunilor

- **2.2.0** — sincronizare opțională cu Google Drive: conexiunile salvate, cu
  parolele și cheile lor, și cheile de host acceptate se sincronizează între
  dispozitive printr-un dosar ascuns din propriul Google Drive al
  utilizatorului, criptate end-to-end cu o frază pe care doar el o știe. La prima
  pornire fără nimic salvat, aplicația oferă restaurarea lor. Politica de
  confidențialitate descrie sincronizarea
- **2.1.0** — după închiderea ultimei file apare din nou pagina de bun venit,
  nu o fițuică; o selecție începută cu o apăsare lungă rămâne când degetul este
  ridicat fără glisare; cu un font de sistem mare, terminalul urmează scalarea
  neliniară din Android 14; textele cu numere folosesc forma corectă de singular
  și plural în fiecare limbă; ecranul respectă blocarea rotirii din sistem;
  scrierea într-un câmp precompletat continuă de la sfârșitul lui; atingerea
  terminalului este disponibilă pentru serviciile de accesibilitate
- **2.0.0** — un nou format de stocare și o interfață reconstruită.
  Conexiunile salvate trec într-un fișier DataStore criptat, iar setările în
  DataStore; actualizarea le migrează o dată și șterge fișierele vechi, astfel
  că 1.x nu le mai poate citi după aceea. Toate ecranele sunt reconstruite cu
  Jetpack Compose și Material 3. O încărcare de fișier continuă și în timpul
  unei rotiri; sugestia de jump host recunoaște adresele private după
  intervalele lor reale; glisarea între file funcționează și deasupra
  terminalului. Serviciul de fundal folosește tipul de serviciu în prim-plan
  `specialUse`. Meniul are un link către politica de confidențialitate
- **1.1.30** — îmbunătățiri de calitate conduse de teste. Terminal: comparat cu tmux
  pe sesiuni înregistrate de bash, vim, less, htop și mc; corectate secvențele CSI
  cu octet intermediar (forma cursorului din vim afișa un `q` rătăcit), ștergerea cu
  culoarea de fundal (panourile colorate din mc), VPA/HPA/REP și tabularea cu număr
  (htop era decalat cu un rând), modurile de ecran alternativ xterm, ecranul
  principal din spatele vim la rotire, SGR estompat/cursiv/tăiat și numerele uriașe
  de repetare care puteau bloca analizorul. SSH: o filă închisă în timpul conectării
  nu mai lasă în urmă o conexiune pe jumătate deschisă (până la 2 minute); o linie
  known_hosts deteriorată nu mai poate deveni o cheie de încredere. Teste de
  integrare cu sshd real, de injectare de erori și de UI pe emulator rulează în CI,
  cu măsurarea acoperirii
- **1.1.29** — emularea terminalului: caracterele late (CJK, emoji) ocupă două
  celule ca pe server, semnele combinante se lipesc de celula anterioară, desen de
  linii DEC (`ESC ( 0`) pentru cadrele ncurses/dialog/mc, se răspunde la
  interogările de poziție a cursorului și de dispozitiv (`CSI 6n`, DA) — dar nu din
  nou la redarea ieșirii după rotire; textul se desenează pe segmente, nu celulă cu
  celulă. Rapoarte de eroare: după un ANR sau o închidere neașteptată, aplicația
  oferă partajarea unui raport (versiune, dispozitiv, urmă — fără secrete); nimic nu
  se trimite automat. Serviciul în prim-plan rulează doar cât există o sesiune
  activă. Fișierele de cheie se citesc în afara firului principal. Remediat:
  fișele de comenzi apăreau de două ori după redimensionare
- **1.1.28** — ieșirea terminalului este procesată pe un fir de fundal, pe bucăți,
  astfel că o ieșire mare (`cat` pe un log mare, `find /`) nu mai blochează interfața;
  caracterele UTF-8 tăiate între două citiri din rețea nu se mai strică
- **1.1.27** — arhitectură pe straturi după un audit de cod: un singur manager de
  sesiuni ca sursă de adevăr, întrebările vin ca stare și supraviețuiesc rotirii,
  erori tipizate cu un singur traducător de mesaje; un depozit de conexiuni ilizibil
  nu mai este suprascris și nimic nu se mai salvează necriptat; secretele nu mai
  sunt transmise în argumentele fragmentelor; la editare se păstrează fraza de acces
  a cheii; comandă mai sigură de încărcare a cheii
- **1.1.26** — verificarea cheii de gazdă a serverului (încredere la prima
  utilizare, amprentă SHA256, avertisment la schimbare); jump host în mai mulți pași
  (un jump host poate avea propriul jump host, până la 5 salturi), folosit și de
  testul de conexiune și de încărcarea cheii; chei de gazdă Ed25519 și schimb de
  chei curve25519 prin Bouncy Castle; profilurile criptate excluse din backup;
  textul copiat din terminal marcat ca sensibil; mesajele terminalului nu mai
  afișează un „r” în plus
- **1.1.25** — țintă Android 17 (API 37): permisiune pentru rețeaua locală, gest
  înapoi predictiv, AGP 9.5 / Gradle 9.6
- **1.1.24** — testul de conexiune funcționează și cu cheie privată
- **1.1.23** — logo-ul aplicației și titlul „KonsoleSSH” în bara de instrumente
- **1.1.22** — opțiunea Ieșire la finalul meniului cu trei puncte
- **1.1.21** — analiză statică (detekt, ktlint) și control de calitate în CI,
  Dependabot, OSV-Scanner
- **1.1.20** — curățenie: Node.js 24 în CI, configurarea limbilor
- **1.1.19** — actualizări de dependențe (security-crypto stabil)
- **1.1.18** — CI, eliminarea codului mort, corecturi de ciclu de viață și lint
- **1.1.17** — keep-alive SSH; mesajele de sistem nu mai apar sub prompt
- **1.1.16** — corectură: blocare la rotire (condiție de cursă în ciclul de viață al
  Fragmentului)
- **1.1.15** — sesiunile și dialogurile supraviețuiesc rotirii
- **1.1.14** — strat MVVM (ViewModel-uri)
- **1.1.13** — tasta Delete (ștergere înainte) prin SSH
- **1.1.12** — criptare proprie AES-256-GCM (Android Keystore) cu migrare atomică a
  profilurilor vechi
- **1.1.11** — linie de versiune în bannerul de bun venit
- **1.1.10** — flux de evenimente între serviciu și interfață (Kotlin Flow)
- **1.1.4–1.1.9** — refactorizare internă (buffer terminal, depozit de conexiuni,
  generator de chei), actualizări de dependențe, corecturi lint
- **1.1.3** — erorile de conexiune într-un toast; fila se închide singură după 5 s
- **1.1.2** — mesaje separate pentru trei tipuri de erori de conexiune
- **1.1.1** — încărcarea cheii doar după un test reușit; cheia publică se încarcă
  prin autentificare cu parolă
- **1.1.0** — generator de chei SSH integrat
- **1.0.9** — ordinea de autentificare publickey întâi, explicație pentru eroarea
  „Auth cancel”
- **1.0.8** — selector de limbă în meniul cu trei puncte
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
