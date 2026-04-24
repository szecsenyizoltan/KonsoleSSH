# KonsoleSSH

Terminal SSH multi-onglets pour Android — inspiré par KDE Konsole.

> **Langue :** Français · [English](README.md) · [Magyar](README.hu.md) · [Español](README.es.md) · [Deutsch](README.de.md) · [Slovenčina](README.sk.md) · [Română](README.ro.md)

## Captures d'écran

| Écran d'accueil | Nouvelle connexion SSH |
| --- | --- |
| ![Accueil](play_screenshot/Screenshot_20260422_094020.png) | ![Nouvelle connexion](play_screenshot/Screenshot_20260422_094447.png) |

| Aide-mémoire tmux | Aide-mémoire Linux |
| --- | --- |
| ![Aide-mémoire tmux](play_screenshot/Screenshot_20260422_094521.png) | ![Aide-mémoire Linux](play_screenshot/Screenshot_20260422_094530.png) |

## Fonctionnalités

### Onglets et navigation

- **Interface multi-onglets** — TabLayout + ViewPager2, chaque onglet possède une session SSH indépendante, identifiée par un `TabInfo` basé sur un UUID
- **Trois pages fixes** — écran d'accueil (position 0), aide-mémoire Linux et aide-mémoire tmux (deux dernières positions) ; les onglets SSH se placent entre les deux
- **Point d'état de connexion sur chaque onglet** — vert (CONNECTED), jaune (CONNECTING), rouge (DISCONNECTED), masqué (NONE)
- **Appui long sur un onglet** → dialogue de renommage
- **Bouton ✕ de l'onglet** — demande confirmation sur une session active ; fermeture immédiate si déconnectée
- **Défilement de la barre d'onglets** — lorsque les onglets débordent, des boutons indicateurs ◀/▶ pulsent doucement (ObjectAnimator ±8 dp, 900 ms reverse infinite)
- **Hauteur de l'indicateur d'onglet fixée à 0 dp** avec un seul onglet (moins de bruit visuel)

### Sélecteur d'onglets / connexions enregistrées (menu `+`)

- **BottomSheetDialogFragment** — pleine hauteur (STATE_EXPANDED, MATCH_PARENT)
- **Onglets actifs + arbre des connexions enregistrées**, empilés
- **Regroupement en arbre par préfixe avec tiret bas** — `acme_prod_web`, `acme_prod_db` → groupe `acme_` → `prod_` ; une feuille isolée n'est jamais regroupée
- **Marge selon la profondeur** — `16 dp + profondeur × 20 dp`
- **L'état déplié survit à la réouverture du dialogue** (stocké dans un companion-object)
- **Icônes** — groupe : 📁 / 📂 (fermé/ouvert), feuille : ⚡
- **Lignes de feuille** — sous-ligne `user@host:port`, éditer + supprimer à droite
- **Lignes de groupe** — sous-ligne du nombre de feuilles, flèche ▶/▼ à droite
- **Préremplissage du nom d'utilisateur le plus récemment utilisé** pour les nouvelles connexions

### Dialogue Nouveau / Édition

- **Mode édition** — `ConnectionConfig` complet sérialisé via Gson à travers le Bundle et restauré dans les champs
- **Bascule d'authentification** RadioGroup : mot de passe ↔ clé privée, la mise en page non pertinente se masque automatiquement
- **Clé privée avec sélecteur de fichier** (aperçu en lecture seule), champ distinct pour la phrase de passe
- **Avertissement `.pub`** — toast si l'utilisateur tente de charger la moitié publique de la clé
- **Ligne d'état de la clé** — PEM chargée / échouée / aucune
- **Spinner de jump host** — liste les connexions enregistrées (à l'exclusion de soi-même)
- **Suggestion automatique de jump** — détecte les préfixes d'IP privée (`10.`, `172.`, `192.`) et déploie la section jump
- **Validation** — host et nom d'utilisateur requis ; port 1–65535, valeur par défaut 22

### Authentification de connexion

- **JSch (fork mwiede)** — mot de passe, publickey, ou les deux combinés (`buildPreferredAuths`)
- **Invite interactive de mot de passe** — lorsqu'aucun n'est enregistré, un AlertDialog stylé `KonsoleDialog` (texte blanc, fond transparent)
- **Authentification keyboard-interactive** — les invites côté serveur passent par le même dialogue
- **Délai d'invite de 30 s** (CountDownLatch) — le thread appelant est libéré proprement si l'utilisateur ne répond pas
- **Garde du main-thread** — le dialogue apparaît toujours sur l'UI thread (Handler post)
- **Chaîne de jump host** — `setPortForwardingL(0, target_host, target_port)` du fork mwiede sur un port aléatoire + seconde session vers loopback
- **Messages de progression du jump** imprimés dans le terminal — `Jump: host:port → Connecting`, puis `Jump OK → Connecting: target:port`
- **Erreur « Jump host introuvable »** — lorsqu'un ID de connexion jump référencé n'existe plus

### Émulateur de terminal (basé sur Canvas)

**Rendu**

- `TerminalView` personnalisée, `canvas.drawText` par cellule, base minimale 80×24
- Champs de cellule : caractère (String, conscient des surrogate-pairs), fg/bg, gras, souligné, inverse
- **NerdFont embarquée** (`assets/fonts/NerdFont.ttf`) ; bascule sur `Typeface.MONOSPACE` en cas d'erreur d'asset
- Plage de police 6 sp…40 sp ; la première mise en page s'ajuste automatiquement à une cible de 80 colonnes
- **Boutons de zoom +/−** dans la barre d'outils (`SharedPreferences("settings", "font_size")`), persiste au redémarrage
- Taille de police **par application** (pas par connexion)
- Soulignement rendu via `drawLine` à cellH-1
- Inversion du curseur au moment du dessin (fg↔bg)
- **Clignotement du curseur** 600 ms allumé / 300 ms éteint

**Machine à états ANSI/VT**

- États NORMAL / ESCAPE / CSI / OSC / DCS / CHARSET
- SGR : 16 de base + 8 lumineux + cube 256 + truecolor (`38;2;r;g;b`), gras/souligné/inverse + réinitialisations
- Curseur : A/B/C/D, H/f, G, E/F, s/u, ESC 7/8 (legacy DEC)
- Effacement : J, K, X, L/M (insertion/suppression de lignes), P/@ (caractères)
- **Région de défilement** — r, S/T (scroll up/down)
- **Écran alternatif** — bascule 47 / 1049 (vi, top, less, mc préservent et restaurent l'écran)
- **Mode DECCKM app-cursor** — `ESC[A/B/C/D` ↔ `ESC O A/B/C/D`
- **Bracketed paste mode** (2004) — texte collé encadré par `ESC[200~ … ESC[201~`

**Sélection de texte**

- **Appui long 400 ms** → démarre la sélection, `ActionMode` flottant (Copier/Coller)
- Suivi en direct du point final pendant le glissement, `invalidateContentRect`
- Normalisation du sens avant/arrière
- `buildSelectedText` — coupe chaque ligne, joint avec `\n`
- Tap pendant la sélection → efface la sélection
- **Blocage du swipe ViewPager** (`requestDisallowInterceptTouchEvent`) pendant la sélection/le défilement

**Défilement et toucher**

- Défilement vertical : `scrollRowOff` 0…`scrollback.size`
- **Scrollback** circulaire de 3000 lignes
- Défilement horizontal optionnel (désactivé sur la page d'accueil)
- **Seuil de mouvement de 8 px** — distinction tap vs. défilement
- Gravité automatique vers le bas sur nouvelle sortie (`scrollRowOff = 0`)
- Tap → `focusAndShowKeyboard()`
- **Shift+PageUp/Down** sur un clavier matériel fait défiler le scrollback

**Intégration IME**

- `TerminalInputConnection` personnalisée (BaseInputConnection)
- Astuce sentinelle : le buffer commence toujours par un espace sentinelle, la saisie utilisateur utilise `removePrefix`
- Fonctionne avec les IME swipe/glide
- `deleteSurroundingText` → flux manuel d'octets DEL (0x7F)
- `inputType=TYPE_NULL` — pas d'autocomplétion ni de suggestions dans le terminal

### Clavier matériel

- Enter→13, Tab→9, Esc→27, DEL→127
- Home/End → `ESC[H` / `ESC[F`
- PageUp/PageDown → `ESC[5~` / `ESC[6~`
- **F1–F12** → `ESC O P/Q/R/S`, `ESC[15~`, `ESC[17~`, …
- Flèches → codes selon le mode app-cursor
- Shift+PageUp/Down déroutés vers le scrollback (non envoyés comme saisie)

### Barre de touches à l'écran

**Rangée principale** (40 dp)

- **⌨** — bascule l'IME système. Visibilité réelle de l'IME lue via `WindowInsetsCompat`, de sorte qu'un clavier fermé par le geste retour se rouvre proprement.
- **Fn** — bascule la rangée F1–F12
- **CTRL / SHIFT / ALT / ALTGR** — modificateurs collants, surlignés avec `keybar_mod_active` pendant l'activation ; **réinitialisation automatique** après tout envoi de touche
- **CTRL** ouvre en plus une rangée dédiée de combinaisons Ctrl (A/B/C/D/V/Z)
- **ESC, TAB** — octet direct
- **↑** — bascule une rangée de flèches (← ↑ ↓ →)
- **📁** — `ActivityResultContracts.OpenDocument()` → transfert SFTP
- Séparateurs visuels `KeyBarDivider` entre les éléments

**Rangée Fn** (36 dp) — séquences d'échappement F1–F12, chaque appui **clignote** pendant 300 ms (couleur d'accent)

**Rangée Ctrl** — générée dynamiquement (`LayoutInflater` + `item_keybar_button`)

- `Ctrl+C` : copie la sélection vers le presse-papiers interne avec un toast, sinon envoie ETX (0x03)
- `Ctrl+V` : colle depuis le presse-papiers interne → `pasteText` + toast
- A/B/D/Z : code = char - 'A' + 1 (1…26)

**Rangée des flèches** (36 dp) — ← ↑ ↓ → respectant le mode app-cursor

**Indicateurs de défilement sur chaque rangée** — ◀/▶ animés uniquement lorsqu'un défilement est réellement possible ; `canScrollHorizontally(±1)` vérifié après chaque événement ; annulation et réinitialisation de l'animateur dans onDestroy

### Combinaisons de touches spéciales (`applyModifiers`)

- Ctrl+lettre → 1…26 (codes de contrôle standards)
- **Ctrl+space → 0x00 (NUL)**
- **Ctrl+[ → 27 (ESC)**
- Shift + minuscule → majuscule (repli soft-input)
- Alt / AltGr → `ESC` + octet original (préfixe meta)

### Presse-papiers

**Presse-papiers interne** (`TerminalClipboard`)

- Singleton `var text: String?`
- Ctrl+C avec une sélection → presse-papiers **interne** (pas celui du système) + toast discret
- Ctrl+V → interne → `pasteText`

**Presse-papiers système**

- ActionMode **Copier** → `ClipboardManager.setPrimaryClip`
- ActionMode **Coller** → `coerceToText` → `pasteText`
- Au collage `\n` → `\r`, encadré si le bracketed paste mode est actif

### Transfert de fichier SFTP

- 📁 → sélecteur `OpenDocument`
- Nom de fichier depuis `OpenableColumns.DISPLAY_NAME`, repli `uri.lastPathSegment`
- Requête de taille → bascule barre de progression déterminée / indéterminée
- **Dialogue de progression** — nom de fichier, `X.X MB / Y.Y MB` ou `Z.Z MB` lorsque le total est inconnu, `setCancelable(false)`
- En cas de succès `KonsoleToast.showWithAction` — « Transféré : ~/filename » + bouton **Annuler** → `deleteRemoteFile`
- En cas d'erreur, message localisé mappé par friendlyError

### État et retour

- **Barre d'état** (20 dp) au-dessus du terminal : `Aucune connexion`, `Connexion : host…`, `Connecté : host`, `Déconnecté : host`
- **Bouton de reconnexion** au centre du terminal, icône ↺, affiché uniquement en état DISCONNECTED
- **KonsoleToast** — toast personnalisé, marge basse 100 dp, 4000 ms par défaut / 3000 ms actionnable, animation de disparition (scale+alpha, 250 ms)

### Messages d'erreur (mappage friendlyError)

Les exceptions JSch brutes sont converties en texte lisible (localisé) :

- `connection refused` → « Le serveur a refusé la connexion »
- `timed out` / `timeout` → « Délai dépassé »
- `no route to host` → « Aucune route vers l'hôte »
- `network unreachable` → « Réseau inaccessible »
- `unknown host` → « Hôte inconnu »
- `auth fail` / `authentication` → « Échec de l'authentification »
- `connection closed` / `closed by foreign host` → « Connexion fermée »
- `broken pipe` → « Tube rompu »
- `port forwarding`, `channel` → messages dédiés
- Sinon : le message d'exception dont les préfixes de classe de pile sont retirés par regex

### Arrière-plan et cycle de vie

- **`SshForegroundService`** — `START_STICKY`, ne stoppe **pas** délibérément les sessions sur `onTaskRemoved`
- **Buffer de sortie** — anneau de 256 KB par session, `ByteArrayOutputStream` avec recadrage en cas de dépassement
- **Rejeu à la reconnexion** — après l'attachement du fragment, le buffer est rejoué dans le terminal pour que la sortie précédente soit visible
- **Deux NotificationChannels** — `ssh_idle` (inactif) et `ssh_active` (actif), comportements de badge différents
- **Le badge de notification** affiche le nombre de sessions actives
- Notification en priorité basse (`PRIORITY_LOW`, `setSilent`)
- `onRebind` activé (`onUnbind → true`)
- `TerminalFragment.onDestroy` ne déconnecte **pas** (le service possède la session)
- Tenue des listeners dans `onAttach`/`onDetach` (sûr contre les fuites mémoire)

### Machine à états de l'indicateur de connexion

- `NONE → CONNECTING` : nouvel appel `connect()`
- `CONNECTING → CONNECTED` : canal shell ouvert (`onConnected`)
- `CONNECTING → DISCONNECTED` : `onError` (auth, timeout, refused)
- `CONNECTED → DISCONNECTED` : la boucle de lecture se termine ou `disconnectSession` explicite
- `DISCONNECTED → CONNECTING` : bouton de reconnexion

### Sécurité

- **EncryptedSharedPreferences** — schéma de valeur AES256_GCM, schéma de clé AES256_SIV, MasterKey adossé à l'Android Keystore
- **Migration legacy** au premier lancement — les anciens profils en texte clair sont déplacés vers le stockage chiffré, puis l'ancien stockage est vidé
- **Repli prefs en clair** — si l'initialisation du keystore échoue (avertissement journalisé), les profils ne sont pas perdus
- **JSch `StrictHostKeyChecking=no`** — confiance au premier usage
- Les identifiants ne quittent jamais l'appareil

### Accueil / aide-mémoires

- **Bannière d'accueil** — « KonsoleSSH » en ANSI trois nuances d'orange + 9 lignes de description (`[38;5;244m` atténué)
- Police fixe 16 sp sur la page d'accueil
- Défilement horizontal désactivé sur la page d'accueil
- **Aide-mémoire Linux** — `top`, `df`, `du`, `dd`, `tail`, `head`, `grep`, `egrep`, `awk`, `sed`, `tr`, `ip`, `mc` + icône d'avertissement `⚠` sur les commandes destructrices (`dd`, `sed -i`)
- **Aide-mémoire tmux** — sessions/fenêtres/panneaux/layouts/préfixe/redimensionnement/défilement/collage
- **Contenu localisé** — HU et EN rédigés séparément, pas uniquement traduction des libellés
- `scrollToTop()` à l'ouverture
- Le redimensionnement du terminal sur un onglet d'aide-mémoire redessine intégralement (`clear()` + nouvelle bannière)

### Edge-to-edge et orientation

- `ViewCompat.setOnApplyWindowInsetsListener` sur chaque Activity
- `Type.systemBars() | Type.displayCutout()` + `Type.ime()` traités conjointement — en paysage le contenu reste au-dessus de la barre de navigation, l'apparition de l'IME le fait remonter
- `screenOrientation=fullSensor` — rotation automatique portrait + paysage
- `configChanges` configuré pour que l'Activity ne soit pas détruite à la rotation
- `windowSoftInputMode=adjustResize`
- `onSizeChanged` → recalcul de la police, recalibrage de termRows/termCols

### Localisation

- 7 langues : **anglais** (par défaut), **hongrois**, **allemand**, **espagnol**, **français**, **slovaque**, **roumain**
- `supportsRtl="true"` — prêt pour de futures langues RTL
- Suit la langue du système
- Le contenu des aide-mémoires est aussi localisé (HU/EN)

### Détails de confort

- Le focus revient automatiquement au terminal après la fermeture de l'ActionMode
- Après le relâchement d'un bouton modificateur, le focus revient au terminal
- Le bouton modificateur collant change de couleur pendant l'activation (état visuel clair)
- Chaque tap sur la barre de touches clignote pendant 300 ms
- DEL (0x7F) envoyé comme code distinct — pas une alternative à Backspace
- Surrogate pair / emoji stocké comme un seul caractère logique par cellule
- Redimensionnement du terminal → `resize(tabId, cols, rows)` vers le PTY (`vim`, `htop` prennent en compte la nouvelle taille)
- `resetToSentinel` sur l'input-connection après chaque commit — l'état IME reste toujours propre
- Message « Connexion fermée » imprimé dans le terminal à la déconnexion
- Ctrl+C / Ctrl+V affichent un toast discret : « Copié » / « Collé »
- Les boutons utilisent le ripple `selectableItemBackgroundBorderless`
- Style `KonsoleDialog` : texte blanc, hint gris, fond transparent
- La fermeture/suppression/quitte d'une connexion active demande toujours confirmation

### Constantes

- Timeout de connexion : 15 s, shell-connect : 10 s, invite de mot de passe : 30 s
- Buffer de sortie : 256 KB / session
- Scrollback : 3000 lignes
- Police : 6 sp – 40 sp
- Appui long : 400 ms
- Seuil de mouvement tactile : 8 px
- Pulsation des indicateurs de défilement : période 900 ms, ±8 dp
- Toast : 4000 ms par défaut, 3000 ms actionnable, anim de disparition 250 ms
- Marge basse KonsoleToast : 100 dp
- Clignotement du curseur : 600 ms allumé / 300 ms éteint

## Architecture

Les sessions appartiennent à `SshForegroundService`, pas aux fragments :

- Les connexions survivent à la recréation de l'activity (rotation, retour, suppression de la tâche)
- `TerminalFragment` s'attache/détache du service dans `onStart`/`onStop` et rejoue le buffer de sortie à la reconnexion
- `OutputBuffer` conserve les 256 derniers kio de sortie par session

## Structure du projet

```
app/src/main/java/hu/billman/konsolessh/
├── model/           ConnectionConfig, SavedConnections (prefs chiffrées)
├── ssh/             SshSession, SshForegroundService
├── terminal/        TerminalView, AnsiParser, TerminalClipboard
└── ui/              MainActivity, TerminalFragment, dialogues, sheets
```

## Construction

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
# Build debug + tests unitaires
./gradlew :app:testDebugUnitTest

# App Bundle release signé (keystore requis)
./gradlew :app:bundleRelease
```

Le build **release** est minifié via R8 et ses ressources réduites. Le code à base
de réflexion (JSch, modèles Gson) est préservé via `app/proguard-rules.pro`. Un
`mapping.txt` est produit afin que Play Console puisse désobfusquer les stack traces.

## Permissions

| Permission                            | Raison                                                  |
| ------------------------------------- | ------------------------------------------------------- |
| `INTERNET`                            | Connexion SSH                                           |
| `ACCESS_NETWORK_STATE`                | Vérification de l'état du réseau                        |
| `CHANGE_NETWORK_STATE`                | Foreground service (type connectedDevice)               |
| `FOREGROUND_SERVICE`                  | Démarrage du service en arrière-plan                    |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Foreground service réseau (API 34+)                     |
| `POST_NOTIFICATIONS`                  | Notification persistante de session (API 33+)           |

Aucune donnée n'est collectée, partagée ni envoyée. Les identifiants restent sur l'appareil dans le stockage chiffré adossé au Keystore.

## Configuration requise

- Android 8.0 (API 26) ou plus récent
- Modes portrait et paysage pris en charge

## Licence

L'application est publiée sous licence **GPL-3.0-or-later** — le texte complet se trouve dans le fichier [LICENSE](LICENSE). C'est un logiciel libre : vous pouvez l'utiliser, l'étudier, le modifier et le redistribuer librement ; les travaux dérivés doivent rester sous la même licence.

## Historique des versions

- **1.0.7** — licence GPL-3.0 ajoutée, build compatible F-Droid (configuration de signature conditionnelle)
- **1.0.6** — L'indicateur d'état passe correctement au vert après une reconnexion
  (ne reste plus bloqué en jaune) ; l'icône du clavier ouvre correctement l'IME sur
  Android 14+ (visibilité réelle via `WindowInsetsCompat` à la place de `SHOW_FORCED` obsolète)
- **1.0.5** — correctif interne (version omise)
- **1.0.4** — nouvelle icône `>_` plus fine (design stroke)
- **1.0.3** — icône et ressources affinées pour le Play Store
- **1.0.2** — paquet renommé en `hu.billman.konsolessh`, corrections d'insets edge-to-edge,
  clavier virtuel uniquement à la demande, envoi SFTP avec dialogue de progression et
  toast d'annulation, minification R8, Gradle 9.4.1 / AGP 9.2.0
- **1.0.1** — `targetSdk = 35`, première soumission Play
- **1.0.0** — SSH multi-onglets, jump host, arbre des connexions enregistrées,
  aide-mémoires Linux et tmux, traduction hongroise
