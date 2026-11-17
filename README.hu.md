# KonsoleSSH

Többfüles SSH-terminál Androidra, a KDE Konsole mintájára.

> **Nyelv:** Magyar · [English](README.md) · [Español](README.es.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · [Slovenčina](README.sk.md) · [Română](README.ro.md)

## Képernyőképek

| Üdvözlő képernyő | Új SSH-kapcsolat |
| --- | --- |
| ![Üdvözlő](play_screenshot/Screenshot_20260422_094020.png) | ![Új kapcsolat](play_screenshot/Screenshot_20260422_094447.png) |

| Tmux-puska | Linux-puska |
| --- | --- |
| ![Tmux-puska](play_screenshot/Screenshot_20260422_094521.png) | ![Linux-puska](play_screenshot/Screenshot_20260422_094530.png) |

## Funkciók

**Fülek**
- Tetszőleges számú SSH-munkamenet egymás mellett, mindegyik saját fülön; váltás húzással vagy a fülsávból.
- Állapotjelző pötty minden fülön: kapcsolódik, kapcsolódva, megszakadt; a megszakadt fülön újrakapcsolódás gomb.
- Hosszan nyomva a fül átnevezhető; kapcsolódott fül bezárása előtt az app rákérdez.
- Elöl az üdvözlő oldal; az utolsó fül után Linux- és tmux-puska.

**Kapcsolatok**
- Mentett kapcsolatok fában: az aláhúzásig közös előtagú nevek (`acme_prod_web`, `acme_prod_db`) csoportba kerülnek.
- Jelszavas vagy kulcsos hitelesítés, keyboard-interactive kérdésekkel együtt. A nem mentett jelszót kapcsolódáskor kéri.
- Jump hostok, láncolva is: egy jump hostnak is lehet jump hostja, legfeljebb 5 lépésig. Privát címnél az űrlap javasolja.
- Kapcsolatteszt, valamint kulcs generálása (Ed25519, ha a szerver elfogadja, egyébként RSA) és a nyilvános fél feltöltése a szerver `authorized_keys` fájljába.
- Host key ellenőrzés első használatkor bizalommal (TOFU): az első kapcsolódáskor megjelenik a SHA256-ujjlenyomat, a megváltozott kulcsot pedig csak kifejezett csere után fogadja el.

**Terminál**
- xterm-kompatibilis emuláció: 16, 256 és true color, alternatív képernyő, görgetési tartomány, bracketed paste, alkalmazás-kurzorbillentyűk, DEC vonalrajzolás, széles CJK- és emoji-karakterek. Rögzített bash-, vim-, less-, htop- és mc-munkameneteken tmuxszal összevetve.
- 3000 soros visszagörgetés; szövegkijelölés másolással és beillesztéssel; nagyítás a fülsáv gombjaival, újraindítás után is megmarad.
- Hardveres billentyűzet: funkcióbillentyűk, Home/End, PageUp/PageDown, Shift+PageUp/PageDown a visszagörgetéshez.

**Billentyűsor**
- Ragadós Ctrl, Shift, Alt és AltGr: a módosító a következő billentyűre hat, utána felenged.
- Esc, Tab, funkcióbillentyű-sor (F1–F12), nyílbillentyű-sor és Ctrl-sor (Ctrl+A…Z; a Ctrl+C kijelölésnél másol, egyébként megszakít).
- Fájlfeltöltés SFTP-n a távoli home könyvtárba, folyamatjelzővel és néhány másodpercig visszavonhatóan.

**Egyéb**
- A munkamenetek a háttérben is futnak, egy értesítés mögött, amely az aktív kapcsolatokat számolja.
- Hét nyelv: angol, magyar, német, spanyol, francia, szlovák, román; az appon belül is választható.
- Bekapcsolható Google Drive szinkron: a mentett kapcsolatok (jelszavakkal, kulcsokkal) és az elfogadott host keyek a felhasználó saját Google Drive-jának rejtett mappáján át szinkronizálódnak az eszközök között, végponttól végpontig titkosítva egy csak általa ismert jelszóval. Új eszközön az első indításkor felajánlja a visszaállítást.

## Architektúra

- **Felület:** Jetpack Compose, Material 3, egyetlen activityben; a képernyők (főképernyő, mentett kapcsolatok, szinkron) Navigation 3 célpontok.
  Maga a terminál Canvasra rajzoló nézet (`TerminalView`), Compose-ba ágyazva.
- **Állapot:** egyetlen, az alkalmazással együtt élő `SessionManager` birtokolja a füleket, a munkameneteket,
  a kimenetet és a függő kérdéseket. A felület ViewModeleken át figyeli az állapotát és parancsokat küld;
  a jelszó- vagy host key kérdés állapot, ezért túléli a forgatást.
- **Háttér:** az `SshForegroundService` csak életben tartja a folyamatot és mutatja az értesítést;
  addig fut, amíg van kapcsolódó vagy kapcsolódott fül, utána leáll.
- **Tárolás:** a kapcsolatok AES-256-GCM-mel titkosított DataStore-fájlban (Android Keystore-kulcs,
  mentésből kizárva); a beállítások DataStore Preferencesben; a host keyek OpenSSH-szerű `known_hosts`
  fájlban. Az olvashatatlan tárat az app jelzi, és sosem írja felül.
- **Terminálmodell:** a `TerminalBuffer` Android-függőség nélküli Kotlin, ezért JVM-en unit-tesztelt.
- **Függőségek:** kézzel összekötve az `AppContainer`-ben, DI-keretrendszer nélkül.
- **Szinkron** (bekapcsolható): a `SyncManager` az eszköz kapcsolatait és host keyeit egy AES-256-GCM-mel titkosított fájllal fésüli össze a Drive rejtett alkalmazásmappájában; a kulcs a felhasználó jelszavából származik (PBKDF2), és nem hagyja el az eszközöket.

## Projektszerkezet

```
app/src/main/java/hu/billman/konsolessh/
  model/        ConnectionConfig: a mentett kapcsolat
  data/         titkosított kapcsolattár, beállítások, régi tárak törlése
  ssh/          JSch-munkamenetek, jump-lánc, host key ellenőrzés, kulcsfeltöltés, típusos hibák
  session/      SessionManager, kimeneti puffer, jelszó- és host key kérdések
  sync/         Google Drive szinkron: titkosítás, összefésülés, Drive-kliens
  terminal/     TerminalView és TerminalBuffer: rajzolás, bevitel, emuláció
  service/      az előtérszolgáltatás
  ui/           Compose-képernyők, ViewModelek, billentyűsor, téma
  AppContainer.kt  a függőségi gráf
```

## Fordítás

```
Kotlin 2.4 · AGP 9.5 · Gradle 9.6 · Java 17
minSdk 26 (Android 8.0) · targetSdk / compileSdk 37 (Android 17)
```

```bash
./gradlew detekt :app:testDebugUnitTest :app:lintDebug   # ellenőrzések és unit tesztek
./gradlew :app:connectedDebugAndroidTest                 # UI-tesztek eszközön vagy emulátoron
./gradlew :app:bundleRelease                             # aláírt App Bundle (keystore kell hozzá)
```

A unit tesztek között valódi OpenSSH-szerverek elleni integrációs tesztek is vannak; ezekhez telepített
`sshd` kell, nélküle kimaradnak. A release build R8-cal minimalizált.

## Használat

1. **Új kapcsolat:** `+` → *Új kapcsolat…*, majd host, port, felhasználónév és jelszó vagy kulcs.
2. **Jump host:** az űrlapon *Jump host beállítása…*, és egy mentett kapcsolat kiválasztása átjárónak.
3. **Fül átnevezése vagy bezárása:** hosszan nyomva, illetve a `✕`-szel.
4. **Nagyítás:** a `+` melletti nagyító gombok.
5. **Puskák:** lapozás az utolsó fülön túl.
6. **Fájlfeltöltés:** 📁 a billentyűsorban; a fájl a távoli home könyvtárba (`~`) kerül.
7. **Kilépés:** menü (⋮) → *Kilépés*, vagy `+` → *Kilépés*.

## Függőségek

| Könyvtár | Szerepe |
| --- | --- |
| JSch (mwiede fork) | SSH és SFTP |
| Bouncy Castle | Ed25519 host keyek és curve25519 kulcscsere a JSch-hez |
| Jetpack Compose, Material 3 | felület |
| AndroidX Lifecycle, Activity | ViewModelek, életciklus-tudatos állapot |
| kotlinx.coroutines | aszinkron SSH-I/O |
| kotlinx.serialization | a kapcsolattár formátuma |
| AndroidX DataStore | kapcsolattár és beállítások |
| AndroidX AppCompat | appon belüli nyelvválasztás Android 8–12-n |
| Google Play services auth | engedélykérés a bekapcsolható Google Drive szinkronhoz |
| androidx.security | csak az 1.1.12 előtti tárak migrációjához |

A pontos verziók a `gradle/libs.versions.toml` fájlban vannak.

## Engedélyek

| Engedély | Indok |
| --- | --- |
| `INTERNET` | SSH-kapcsolatok |
| `ACCESS_NETWORK_STATE` | elérhetőség ellenőrzése |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | nyitott munkamenetek életben tartása a háttérben (API 34+) |
| `POST_NOTIFICATIONS` | a munkamenet-értesítés (API 33+) |
| `ACCESS_LOCAL_NETWORK` | helyi hálózati hostok (Android 17+) |

Az app semmilyen adatot nem gyűjt és nem küld sehova. A hitelesítő adatok titkosítva a készüléken maradnak.

## Licenc

GPL-3.0-or-later; lásd [LICENSE](LICENSE).

## Verziótörténet

- **3.1.0** — a kulcsfeltöltés Ed25519 kulcsot generál, ha a szerver elfogadja
  (OpenSSH 6.5 vagy újabb, Dropbear 2020.79 vagy újabb), egyébként RSA-3072-t,
  és a kulcs generálása közben már nem fagy meg a képernyő. A kapcsolat már
  csak a hozzá választott hitelesítési móddal lép be, és az űrlap csak annak a
  módnak a titkát menti; a meglévő kapcsolatok változatlanul működnek. A
  sikertelen feltöltést és visszavonást, valamint az olvashatatlan vagy túl
  nagy kulcsfájlt az alkalmazás nyelvén jelzi. A host keyek és a szinkronkulcs
  írása áramszünet esetén sem hagy üres fájlt, és a felhőtárhelyről választott
  kulcsfájl nem vész el, ha betöltés közben elforgatják a készüléket
- **3.0.0** — főverzió, mert két dolog kikerült. Az 1.x verziók
  kapcsolattárait az alkalmazás már nem olvassa: aki 1.x-ről közvetlenül a
  3.0.0-ra frissít, annak újra fel kell vennie a kapcsolatait, a régi fájlokat
  pedig az alkalmazás törli. Megszűnt a hibajelentés-ablak és a menüpontja; az
  összeomlásokat a Google Play követi. Új: bekapcsolt Google Drive szinkronnál
  a fel nem töltött módosítás (nincs hálózat, bezárt alkalmazás) később
  magától felkerül, és a szinkronképernyő elmagyarázza, mit csinál a szinkron.
  Az SSH-kapcsolatok a támogató szervereknél poszt-kvantum hibrid kulcscserét
  (mlkem768x25519-sha256) használnak (JSch 2.28.8). Az alkalmazás gyorsabban
  indul (baseline profile), a képernyői pedig egyetlen activityben futnak
- **2.3.0** — a fülválasztó oldalra (jobbra vagy balra) húzással, illetve az
  új „Vissza” sorral záródik be, amely ott a „Kilépés” helyére került; az
  alkalmazásból a főmenüből lehet kilépni. Bekapcsolt Google Drive szinkronnál
  az alkalmazás akkor is szinkronizál, amikor előtérbe kerül, és háttérbe
  tételkor a még várakozó feltöltés azonnal lefut
- **2.2.0** — bekapcsolható Google Drive szinkron: a mentett kapcsolatok a
  jelszavaikkal és kulcsaikkal, valamint az elfogadott host keyek a felhasználó
  saját Google Drive-jának rejtett mappáján át szinkronizálódnak az eszközök
  között, végponttól végpontig titkosítva egy csak általa ismert jelszóval.
  Üres alkalmazás első indításakor felajánlja a visszaállítást. Az adatvédelmi
  irányelvek leírják a szinkront
- **2.1.0** — az utolsó fül bezárása után ismét az üdvözlő oldal jelenik meg,
  nem egy puska; a hosszú nyomással indított kijelölés megmarad, ha az ujj
  húzás nélkül engedi fel; nagy rendszerbetűméretnél a terminál az Android 14
  nemlineáris skálázását követi; a számot tartalmazó szövegek minden nyelven
  helyes egyes és többes számot használnak; a képernyő követi a rendszer
  forgatászárát; az előtöltött mezőkbe a szöveg végére lehet gépelni; a
  terminálra koppintás akadálymentesítési szolgáltatásokból is elérhető
- **2.0.0** — új tárolási formátum és újraépített felület. A mentett kapcsolatok
  titkosított DataStore-fájlba, a beállítások DataStore-ba kerülnek; a frissítés
  egyszer átköltözteti őket és törli a régi fájlokat, ezért utána az 1.x már nem
  tudja olvasni őket. Minden képernyő Jetpack Compose-zal és Material 3-mal
  épült újra. A fájlfeltöltés forgatás közben is folytatódik; a jump host
  javaslat a privát címeket a valódi tartományaik alapján ismeri fel; a fülek
  közti lapozás a terminál fölött is működik. A háttérszolgáltatás a
  `specialUse` előtérszolgáltatás-típust használja. A menüből elérhetők az
  adatvédelmi irányelvek
- **1.1.30** — tesztekkel vezérelt minőségjavítás. Terminál: tmux-szal
  összevetve rögzített bash, vim, less, htop és mc munkameneteken; javítva a köztes
  bájtos CSI-szekvenciák (a vim kurzoralakja egy kósza `q`-t írt ki), a háttérszínes
  törlés (az mc színes paneljei), a VPA/HPA/REP és a darabszámos tabulátor (a htop
  egy sorral elcsúszott), az xterm-féle alt-screen módok, a vim mögötti fő képernyő
  forgatáskor, az SGR halvány/dőlt/áthúzott, és a parsert percekre lefoglaló
  óriási ismétlésszámok. SSH: a kapcsolódás közben bezárt fül nem hagy maga után
  félig nyitott kapcsolatot (eddig akár 2 percig); sérült known_hosts sorból nem
  lehet megbízható kulcs. Valódi sshd-s, hibainjektáló és emulátoros UI-tesztek
  futnak a CI-ban, lefedettség-méréssel
- **1.1.29** — terminál-emuláció: a széles karakterek (CJK, emoji) a szerverrel
  egyezően két cellát foglalnak, a kombináló jel az előző cellához tapad, DEC
  vonalrajzolás (`ESC ( 0`) az ncurses / dialog / mc keretekhez, válasz a
  kurzorpozíció- és eszközlekérdezésekre (`CSI 6n`, DA) — forgatás utáni
  visszajátszáskor viszont nem még egyszer; a szöveg futamonként rajzolódik, nem
  cellánként. Hibajelentés: ANR vagy összeomlás után az app felajánlja a jelentés
  megosztását (verzió, készülék, nyom — titok nélkül); automatikusan semmi nem megy
  el. A foreground service csak aktív munkamenet alatt fut. A kulcsfájl beolvasása
  háttérszálon. Javítva: átméretezés után kétszer látszottak a puskák
- **1.1.28** — a terminálkimenet feldolgozása háttérszálon, szeletekben fut, így
  nagy kimenet (`cat` egy nagy logra, `find /`) nem akasztja meg a felületet; a
  hálózati olvasások határán kettévágott ékezetes / többbájtos karakter nem romlik el
- **1.1.27** — rétegzett architektúra kódaudit alapján: egyetlen munkamenet-kezelő
  az igazságforrás, a kérdések állapotként érkeznek és túlélik a forgatást, típusos
  hibák egyetlen szövegleképezővel; olvashatatlan kapcsolattárat nem ír felül, és
  semmit nem ment titkosítatlanul; titkok nem kerülnek Fragment-argumentumba;
  szerkesztéskor megmarad a kulcs passphrase-e; biztonságosabb kulcsfeltöltő parancs
- **1.1.26** — szerver host key ellenőrzés (első használatkori megbízás,
  SHA256-ujjlenyomat, figyelmeztetés változáskor); többlépcsős jump host (a jump
  hostnak is lehet jump hostja, legfeljebb 5 ugrás), a kapcsolatteszt és a
  kulcsfeltöltés is ezen megy; Ed25519 host key és curve25519 kulcscsere Bouncy
  Castle-lel; a titkosított profilok kimaradnak a mentésből; a terminálból másolt
  szöveg érzékenynek jelölve; a terminálüzenetekben nincs többé fölösleges „r”
- **1.1.25** — Android 17 (API 37) cél: helyi hálózati engedély, prediktív vissza,
  AGP 9.5 / Gradle 9.6
- **1.1.24** — a kapcsolatteszt privát kulccsal is működik
- **1.1.23** — app-logó és „KonsoleSSH” felirat az eszköztáron
- **1.1.22** — Kilépés menüpont a 3-pont menü végén
- **1.1.21** — statikus elemzés (detekt, ktlint) és CI minőségkapu, Dependabot,
  OSV-Scanner
- **1.1.20** — takarítás: Node.js 24 a CI-ban, nyelvi konfiguráció
- **1.1.19** — függőségfrissítés (security-crypto stabil)
- **1.1.18** — CI, holt kód eltávolítása, életciklus- és lint-javítások
- **1.1.17** — SSH keep-alive; a rendszerüzenetek nem kerülnek a prompt alá
- **1.1.16** — javítás: összeomlás forgatáskor (Fragment-életciklus versenyhelyzet)
- **1.1.15** — a munkamenetek és párbeszédablakok túlélik a forgatást
- **1.1.14** — MVVM-réteg (ViewModel-ek)
- **1.1.13** — Delete (előre törlés) billentyű SSH-n át
- **1.1.12** — saját AES-256-GCM titkosítás (Android Keystore), a régi profilok
  atomikus migrációjával
- **1.1.11** — verziósor az üdvözlő bannerben
- **1.1.10** — eseményfolyam a service és a felület között (Kotlin Flow)
- **1.1.4–1.1.9** — belső refaktorálás (terminálpuffer, kapcsolat-repository,
  kulcsgenerátor), függőségfrissítés, lint-javítások
- **1.1.3** — kapcsolódási hiba toastban; a fül 5 mp után magától bezárul
- **1.1.2** — három különböző kapcsolódási hibára külön üzenet
- **1.1.1** — kulcsfeltöltés csak sikeres teszt után; a publikus kulcs jelszavas
  belépéssel kerül fel
- **1.1.0** — beépített SSH-kulcsgenerátor
- **1.0.9** — publickey-first hitelesítési sorrend, magyarázat az „Auth cancel”
  hibára
- **1.0.8** — nyelvválasztó a 3-pont menüben
- **1.0.7** — GPL-3.0 licenc hozzáadása, F-Droid-kompatibilis build (feltételes aláírási konfiguráció)
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
