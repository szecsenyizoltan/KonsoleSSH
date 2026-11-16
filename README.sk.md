# KonsoleSSH

SSH terminál s kartami pre Android, inšpirovaný KDE Konsole.

> **Jazyk:** Slovenčina · [English](README.md) · [Magyar](README.hu.md) · [Español](README.es.md) · [Deutsch](README.de.md) · [Français](README.fr.md) · [Română](README.ro.md)

## Snímky obrazovky

| Uvítacia obrazovka | Nové SSH pripojenie |
| --- | --- |
| ![Uvítanie](play_screenshot/Screenshot_20260422_094020.png) | ![Nové pripojenie](play_screenshot/Screenshot_20260422_094447.png) |

| Ťahák pre tmux | Ťahák pre Linux |
| --- | --- |
| ![Ťahák pre tmux](play_screenshot/Screenshot_20260422_094521.png) | ![Ťahák pre Linux](play_screenshot/Screenshot_20260422_094530.png) |

## Funkcie

**Karty**
- Ľubovoľný počet SSH relácií vedľa seba, každá na vlastnej karte; prepína sa potiahnutím alebo z lišty kariet.
- Stavová bodka na každej karte: pripája sa, pripojené, odpojené; odpojená karta ponúka tlačidlo na opätovné pripojenie.
- Dlhým stlačením sa karta premenuje; pred zatvorením pripojenej karty sa aplikácia opýta.
- Na začiatku je uvítacia stránka; za poslednou kartou nasleduje ťahák pre Linux a pre tmux.

**Pripojenia**
- Uložené pripojenia v strome: názvy so spoločnou predponou po podčiarkovník (`acme_prod_web`, `acme_prod_db`) sa zoskupia.
- Overenie heslom alebo súkromným kľúčom, vrátane otázok keyboard-interactive. Neuložené heslo sa vyžiada pri pripájaní.
- Jump hosty, aj v reťazci: jump host môže mať vlastný jump host, najviac 5 skokov. Pri súkromnej adrese ho formulár navrhne.
- Test pripojenia a vygenerovanie RSA kľúča s nahratím jeho verejnej časti do `authorized_keys` na serveri.
- Overovanie host kľúčov s dôverou pri prvom použití: pri prvom pripojení sa zobrazí odtlačok SHA256 a zmenený kľúč sa prijme len po výslovnej výmene.

**Terminál**
- Emulácia kompatibilná s xterm: 16, 256 farieb a true color, alternatívna obrazovka, oblasť posúvania, bracketed paste, aplikačné kurzorové klávesy, čiarová grafika DEC, široké znaky CJK a emoji. Porovnaná s tmux na nahraných reláciách bash, vim, less, htop a mc.
- 3000 riadkov histórie; výber textu s kopírovaním a vkladaním; priblíženie tlačidlami v lište kariet, zachované aj po reštarte.
- Hardvérové klávesnice fungujú vrátane funkčných kláves, Home/End, PageUp/PageDown a Shift+PageUp/PageDown pre históriu.

**Lišta kláves**
- Prilepovacie Ctrl, Shift, Alt a AltGr: modifikátor platí pre ďalšiu klávesu a potom sa uvoľní.
- Esc, Tab, riadok funkčných kláves (F1–F12), riadok šípok a riadok Ctrl (Ctrl+A…Z; Ctrl+C skopíruje výber, inak preruší).
- Nahrá súbor cez SFTP do vzdialeného domovského priečinka, s priebehom a možnosťou na pár sekúnd vrátiť späť.

**Ďalšie**
- Relácie bežia aj na pozadí, za upozornením, ktoré počíta aktívne pripojenia.
- Sedem jazykov: angličtina, maďarčina, nemčina, španielčina, francúzština, slovenčina, rumunčina; voliteľné aj v aplikácii.
- Voliteľná synchronizácia cez Google Drive: uložené pripojenia (s heslami a kľúčmi) a prijaté host kľúče sa synchronizujú medzi zariadeniami cez skrytý priečinok vo vlastnom Google Drive používateľa, šifrované end-to-end heslom, ktoré pozná len on. Nové zariadenie pri prvom spustení ponúkne ich obnovenie.

## Architektúra

- **Rozhranie:** Jetpack Compose s Material 3 v jedinej aktivite; obrazovky (hlavná obrazovka, uložené pripojenia, synchronizácia) sú ciele Navigation 3.
  Samotný terminál je pohľad kresliaci na Canvas (`TerminalView`), vložený do Compose.
- **Stav:** jediný `SessionManager`, ktorý žije tak dlho ako aplikácia, vlastní karty, ich relácie, výstup a čakajúce
  otázky. Rozhranie sleduje jeho stav cez ViewModely a posiela príkazy; otázka na heslo alebo host kľúč je stav,
  takže prežije otočenie.
- **Pozadie:** `SshForegroundService` len udržiava proces nažive a zobrazuje upozornenie; beží, kým sa niektorá
  karta pripája alebo je pripojená, potom sa sám zastaví.
- **Ukladanie:** pripojenia v súbore DataStore šifrovanom AES-256-GCM (kľúč Android Keystore, vylúčený zo zálohy);
  nastavenia v DataStore Preferences; host kľúče v súbore `known_hosts` v štýle OpenSSH. Nečitateľné úložisko
  sa ohlási a nikdy sa neprepíše.
- **Model terminálu:** `TerminalBuffer` je čistý Kotlin bez závislostí na Androide, preto sa testuje na JVM.
- **Závislosti** sú pospájané ručne v `AppContainer`, bez DI frameworku.
- **Synchronizácia** (voliteľná): `SyncManager` zlučuje pripojenia a host kľúče zariadenia so súborom šifrovaným AES-256-GCM v skrytom priečinku údajov aplikácie v Drive; kľúč pochádza z hesla používateľa (PBKDF2) a nikdy neopustí zariadenia.

## Štruktúra projektu

```
app/src/main/java/hu/billman/konsolessh/
  model/        ConnectionConfig: uložené pripojenie
  data/         šifrované úložisko pripojení, nastavenia, odstránenie starých úložísk
  ssh/          JSch relácie, reťaz skokov, overovanie host kľúčov, nahratie kľúča, typované chyby
  session/      SessionManager, výstupný buffer, otázky na heslo a host kľúč
  sync/         synchronizácia cez Google Drive: šifrovanie, zlučovanie, klient Drive
  terminal/     TerminalView a TerminalBuffer: vykresľovanie, vstup, emulácia
  service/      služba v popredí
  ui/           Compose obrazovky, ViewModely, lišta kláves, téma
  AppContainer.kt  graf závislostí
```

## Zostavenie

```
Kotlin 2.4 · AGP 9.5 · Gradle 9.6 · Java 17
minSdk 26 (Android 8.0) · targetSdk / compileSdk 37 (Android 17)
```

```bash
./gradlew detekt :app:testDebugUnitTest :app:lintDebug   # kontroly a unit testy
./gradlew :app:connectedDebugAndroidTest                 # UI testy na zariadení alebo emulátore
./gradlew :app:bundleRelease                             # podpísaný App Bundle (potrebuje keystore)
```

Unit testy zahŕňajú integračné testy proti skutočným OpenSSH serverom; potrebujú nainštalovaný `sshd`
a bez neho sa preskočia. Release zostavenie je minimalizované pomocou R8.

## Používanie

1. **Nové pripojenie:** `+` → *Nové pripojenie…*, potom host, port, používateľské meno a heslo alebo kľúč.
2. **Jump host:** vo formulári *Nastaviť jump host…* a vybrať uložené pripojenie ako bránu.
3. **Premenovanie alebo zatvorenie karty:** dlhé stlačenie, resp. jej `✕`.
4. **Priblíženie:** tlačidlá s lupou vedľa `+`.
5. **Ťaháky:** potiahnuť za poslednú kartu.
6. **Nahratie súboru:** 📁 v lište kláves; súbor sa uloží do vzdialeného domovského priečinka (`~`).
7. **Ukončenie:** ponuka (⋮) → *Ukončiť*, alebo `+` → *Ukončiť*.

## Závislosti

| Knižnica | Účel |
| --- | --- |
| JSch (fork mwiede) | SSH a SFTP |
| Bouncy Castle | host kľúče Ed25519 a výmena kľúčov curve25519 pre JSch |
| Jetpack Compose, Material 3 | rozhranie |
| AndroidX Lifecycle, Activity | ViewModely, stav s ohľadom na životný cyklus |
| kotlinx.coroutines | asynchrónny SSH vstup/výstup |
| kotlinx.serialization | formát úložiska pripojení |
| AndroidX DataStore | úložisko pripojení a nastavenia |
| AndroidX AppCompat | výber jazyka v aplikácii na Androide 8–12 |
| Google Play services auth | autorizácia pre voliteľnú synchronizáciu cez Google Drive |
| androidx.security | len na migráciu úložísk spred verzie 1.1.12 |

Presné verzie sú v `gradle/libs.versions.toml`.

## Oprávnenia

| Oprávnenie | Dôvod |
| --- | --- |
| `INTERNET` | SSH pripojenia |
| `ACCESS_NETWORK_STATE` | kontrola dostupnosti siete |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | udržanie otvorených relácií na pozadí (API 34+) |
| `POST_NOTIFICATIONS` | upozornenie o reláciách (API 33+) |
| `ACCESS_LOCAL_NETWORK` | hosty v lokálnej sieti (Android 17+) |

Aplikácia nezbiera ani nikam neposiela žiadne údaje. Prihlasovacie údaje zostávajú šifrované v zariadení.

## Licencia

GPL-3.0-or-later; pozri [LICENSE](LICENSE).

## História verzií

- **3.0.0** — hlavná verzia, pretože sa odstraňujú dve veci. Úložiská
  pripojení z 1.x sa už nečítajú: kto aktualizuje z 1.x priamo na 3.0.0, musí
  pripojenia zadať znova a staré súbory sa odstránia. Dialóg hlásenia o chybe
  a jeho položka v ponuke zanikli; pády sa sledujú cez Google Play. Novinky:
  so zapnutou synchronizáciou cez Google Drive sa zmena, ktorú sa nepodarilo
  nahrať (bez siete, zatvorená aplikácia), nahrá neskôr sama a obrazovka
  synchronizácie vysvetľuje, čo synchronizácia robí. Pripojenia SSH používajú
  pri serveroch, ktoré to podporujú, hybridnú postkvantovú výmenu kľúčov
  (mlkem768x25519-sha256) (JSch 2.28.8). Aplikácia sa spúšťa rýchlejšie vďaka
  základnému profilu (baseline profile) a jej obrazovky teraz bežia v jedinej
  aktivite
- **2.3.0** — výber kariet sa zatvára potiahnutím do strany, doľava alebo
  doprava, alebo novým riadkom „Späť", ktorý tam nahradil „Ukončiť";
  aplikácia sa ukončuje z hlavnej ponuky. So zapnutou synchronizáciou cez
  Google Drive aplikácia synchronizuje aj vždy, keď sa dostane do popredia, a
  čakajúce nahrávanie sa pri prechode do pozadia odošle hneď
- **2.2.0** — voliteľná synchronizácia cez Google Drive: uložené pripojenia s
  heslami a kľúčmi a prijaté host kľúče sa synchronizujú medzi zariadeniami cez
  skrytý priečinok vo vlastnom Google Drive používateľa, šifrované end-to-end
  heslom, ktoré pozná len on. Pri prvom spustení bez uložených pripojení
  aplikácia ponúkne ich obnovenie. Zásady ochrany súkromia opisujú
  synchronizáciu
- **2.1.0** — po zatvorení poslednej karty sa opäť zobrazí uvítacia stránka,
  nie ťahák; výber začatý dlhým stlačením zostane aj po zdvihnutí prsta bez
  ťahania; pri veľkom systémovom písme terminál nasleduje nelineárne škálovanie
  Androidu 14; texty s počtom používajú v každom jazyku správny tvar jednotného
  a množného čísla; obrazovka rešpektuje systémový zámok otáčania; písanie do
  predvyplneného poľa pokračuje na jeho konci; ťuknutie na terminál je dostupné
  aj pre služby dostupnosti
- **2.0.0** — nový formát ukladania a prestavané rozhranie. Uložené pripojenia
  sa presúvajú do šifrovaného súboru DataStore a nastavenia do DataStore;
  aktualizácia ich raz zmigruje a odstráni staré súbory, takže verzia 1.x ich
  potom už neprečíta. Všetky obrazovky sú prestavané pomocou Jetpack Compose a
  Material 3. Nahrávanie súboru pokračuje aj počas otočenia; návrh jump hostu
  rozpoznáva súkromné adresy podľa ich skutočných rozsahov; prepínanie kariet
  potiahnutím funguje aj nad terminálom. Služba na pozadí používa typ služby v
  popredí `specialUse`. Ponuka odkazuje na zásady ochrany osobných údajov
- **1.1.30** — zlepšenie kvality vedené testami. Terminál: porovnaný s tmux na
  nahraných reláciách bash, vim, less, htop a mc; opravené sekvencie CSI s
  medzibajtom (tvar kurzora vo vim vypísal zablúdené `q`), mazanie farbou pozadia
  (farebné panely mc), VPA/HPA/REP a tabulátor s počtom (htop bol posunutý o riadok),
  režimy alternatívnej obrazovky xterm, hlavná obrazovka za vim pri otočení, SGR
  stlmené/kurzíva/prečiarknuté a obrovské počty opakovaní, ktoré mohli zablokovať
  parser. SSH: karta zatvorená počas pripájania už nenechá polootvorené spojenie
  (až 2 minúty); poškodený riadok known_hosts sa už nemôže stať dôveryhodným
  kľúčom. Integračné testy so skutočným sshd, testy so vkladaním chýb a UI testy na
  emulátore bežia v CI, s meraním pokrytia
- **1.1.29** — emulácia terminálu: široké znaky (CJK, emoji) zaberajú ako na serveri
  dve bunky, kombinujúce znaky sa pripoja k predchádzajúcej bunke, kreslenie čiar
  DEC (`ESC ( 0`) pre rámy ncurses/dialog/mc, odpovede na dotazy na pozíciu kurzora
  a zariadenie (`CSI 6n`, DA) — pri prehrávaní výstupu po otočení však nie znova;
  text sa kreslí po úsekoch, nie po bunkách. Hlásenia o chybách: po ANR alebo páde
  aplikácia ponúkne zdieľanie hlásenia (verzia, zariadenie, stopa — bez tajomstiev);
  nič sa neodosiela automaticky. Služba v popredí beží iba počas aktívnej relácie.
  Súbory s kľúčom sa čítajú mimo hlavného vlákna. Opravené: ťaháky sa po zmene
  veľkosti zobrazovali dvakrát
- **1.1.28** — výstup terminálu sa spracúva vo vlákne na pozadí po častiach, takže
  veľký výstup (`cat` veľkého logu, `find /`) už neblokuje rozhranie; znaky UTF-8
  rozdelené medzi dve sieťové čítania sa už nepoškodia
- **1.1.27** — vrstvená architektúra po audite kódu: jediný správca relácií ako
  zdroj pravdy, otázky prichádzajú ako stav a prežijú otočenie, typované chyby s
  jedným prekladačom správ; nečitateľné úložisko pripojení sa už neprepíše a nič sa
  neukladá nešifrovane; tajomstvá sa už neodovzdávajú v argumentoch fragmentov; pri
  úprave sa zachová heslová fráza kľúča; bezpečnejší príkaz na nahratie kľúča
- **1.1.26** — overenie kľúča hostiteľa servera (dôvera pri prvom použití, odtlačok
  SHA256, upozornenie pri zmene); viacstupňové jump hosty (jump host môže mať
  vlastný jump host, až 5 skokov), používané aj testom pripojenia a nahrávaním
  kľúča; kľúče hostiteľa Ed25519 a výmena kľúčov curve25519 cez Bouncy Castle;
  šifrované profily vylúčené zo zálohy; text skopírovaný z terminálu označený ako
  citlivý; správy terminálu už neobsahujú nadbytočné „r“
- **1.1.25** — cieľ Android 17 (API 37): povolenie lokálnej siete, prediktívne gesto
  späť, AGP 9.5 / Gradle 9.6
- **1.1.24** — test pripojenia funguje aj so súkromným kľúčom
- **1.1.23** — logo aplikácie a názov „KonsoleSSH“ v paneli nástrojov
- **1.1.22** — položka Ukončiť na konci ponuky s tromi bodkami
- **1.1.21** — statická analýza (detekt, ktlint) a kontrola kvality v CI,
  Dependabot, OSV-Scanner
- **1.1.20** — upratovanie: Node.js 24 v CI, konfigurácia jazykov
- **1.1.19** — aktualizácia závislostí (security-crypto stabilné)
- **1.1.18** — CI, odstránenie mŕtveho kódu, opravy životného cyklu a lintu
- **1.1.17** — SSH keep-alive; systémové správy sa už nezobrazujú pod promptom
- **1.1.16** — oprava: pád pri otočení (súbeh v životnom cykle Fragmentu)
- **1.1.15** — relácie a dialógy prežijú otočenie
- **1.1.14** — vrstva MVVM (ViewModely)
- **1.1.13** — kláves Delete (mazanie dopredu) cez SSH
- **1.1.12** — vlastné šifrovanie AES-256-GCM (Android Keystore) s atomickou
  migráciou starších profilov
- **1.1.11** — riadok s verziou v uvítacom banneri
- **1.1.10** — tok udalostí medzi službou a rozhraním (Kotlin Flow)
- **1.1.4–1.1.9** — interný refaktoring (buffer terminálu, repozitár pripojení,
  generátor kľúčov), aktualizácia závislostí, opravy lintu
- **1.1.3** — chyby pripojenia v toaste; karta sa po 5 s sama zatvorí
- **1.1.2** — samostatné správy pre tri druhy chýb pripojenia
- **1.1.1** — nahrávanie kľúča až po úspešnom teste; verejný kľúč sa nahrá cez
  prihlásenie heslom
- **1.1.0** — vstavaný generátor SSH kľúčov
- **1.0.9** — poradie overovania najprv publickey, vysvetlenie chyby „Auth cancel“
- **1.0.8** — výber jazyka v ponuke s tromi bodkami
- **1.0.7** — pridaná GPL-3.0 licencia, build kompatibilný s F-Droid (podmienená signing konfigurácia)
- **1.0.6** — Indikátor stavu po opätovnom pripojení sa správne mení na zelený
  (už nezostáva žltý); ikona klávesnice spoľahlivo otvára IME na Androide 14+
  (skutočná viditeľnosť cez `WindowInsetsCompat` namiesto zastaraného `SHOW_FORCED`)
- **1.0.5** — interná oprava (vynechaná verzia)
- **1.0.4** — nová štíhlejšia `>_` prompt ikona aplikácie (stroke dizajn)
- **1.0.3** — doladená ikona a zdroje pre Play Store
- **1.0.2** — balík premenovaný na `hu.billman.konsolessh`, oprava edge-to-edge insets,
  softvérová klávesnica len na vyžiadanie, SFTP nahrávanie s dialógom priebehu a
  toastom Späť, R8 minifikácia, Gradle 9.4.1 / AGP 9.2.0
- **1.0.1** — `targetSdk = 35`, prvé odoslanie do Play
- **1.0.0** — viackartové SSH, jump host, strom uložených pripojení,
  šablóny Linux a tmux, maďarský preklad
