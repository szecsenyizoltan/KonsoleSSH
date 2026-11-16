# KonsoleSSH

Terminal SSH à onglets pour Android, inspiré de KDE Konsole.

> **Langue :** Français · [English](README.md) · [Magyar](README.hu.md) · [Español](README.es.md) · [Deutsch](README.de.md) · [Slovenčina](README.sk.md) · [Română](README.ro.md)

## Captures d'écran

| Écran d'accueil | Nouvelle connexion SSH |
| --- | --- |
| ![Accueil](play_screenshot/Screenshot_20260422_094020.png) | ![Nouvelle connexion](play_screenshot/Screenshot_20260422_094447.png) |

| Aide-mémoire tmux | Aide-mémoire Linux |
| --- | --- |
| ![Aide-mémoire tmux](play_screenshot/Screenshot_20260422_094521.png) | ![Aide-mémoire Linux](play_screenshot/Screenshot_20260422_094530.png) |

## Fonctionnalités

**Onglets**
- Autant de sessions SSH que voulu, chacune dans son onglet ; on passe de l'une à l'autre en balayant ou depuis la barre d'onglets.
- Un point d'état sur chaque onglet : connexion en cours, connecté ou déconnecté ; un onglet déconnecté propose un bouton de reconnexion.
- Un appui long renomme l'onglet ; avant de fermer un onglet connecté, l'app demande confirmation.
- La page d'accueil vient en premier ; après le dernier onglet suivent un aide-mémoire Linux et un aide-mémoire tmux.

**Connexions**
- Connexions enregistrées en arbre : les noms partageant un préfixe jusqu'au tiret bas (`acme_prod_web`, `acme_prod_db`) sont regroupés.
- Authentification par mot de passe ou clé privée, questions keyboard-interactive comprises. Un mot de passe non enregistré est demandé à la connexion.
- Jump hosts, y compris en chaîne : un jump host peut avoir son propre jump host, jusqu'à 5 sauts. Pour une adresse privée, le formulaire en propose un.
- Test de connexion, et génération d'une clé (Ed25519 si le serveur l'accepte, sinon RSA) avec envoi de sa partie publique dans le `authorized_keys` du serveur.
- Vérification des host keys par confiance à la première utilisation : l'empreinte SHA256 s'affiche à la première connexion, et une clé modifiée n'est acceptée qu'après un remplacement explicite.

**Terminal**
- Émulation compatible xterm : 16, 256 couleurs et couleurs vraies, écran alternatif, zone de défilement, bracketed paste, touches de curseur en mode application, tracé de lignes DEC, caractères larges CJK et emoji. Comparée à tmux sur des sessions enregistrées de bash, vim, less, htop et mc.
- 3000 lignes d'historique ; sélection de texte avec copier-coller ; zoom avec les boutons de la barre d'onglets, conservé après un redémarrage.
- Les claviers physiques fonctionnent, y compris touches de fonction, Début/Fin, Page préc./suiv. et Maj+Page préc./suiv. pour l'historique.

**Barre de touches**
- Ctrl, Maj, Alt et AltGr verrouillables : un modificateur s'applique à la touche suivante, puis se relâche.
- Échap, Tab, une rangée de touches de fonction (F1–F12), une rangée de flèches et une rangée Ctrl (Ctrl+A…Z ; Ctrl+C copie une sélection, sinon interrompt).
- Envoie un fichier par SFTP dans le répertoire personnel distant, avec progression et possibilité d'annuler pendant quelques secondes.

**Divers**
- Les sessions continuent en arrière-plan, derrière une notification qui compte les connexions actives.
- Sept langues : anglais, hongrois, allemand, espagnol, français, slovaque, roumain ; à choisir aussi dans l'app.
- Synchronisation Google Drive facultative : les connexions enregistrées (avec mots de passe et clés) et les host keys acceptées se synchronisent entre appareils via un dossier caché du propre Google Drive de l'utilisateur, chiffrées de bout en bout avec une phrase secrète qu'il est seul à connaître. Un nouvel appareil propose de les restaurer au premier démarrage.

## Architecture

- **Interface :** Jetpack Compose avec Material 3 dans une seule activité ; les écrans (écran principal, connexions enregistrées, synchronisation) sont des destinations Navigation 3.
  Le terminal lui-même est une vue dessinant sur Canvas (`TerminalView`), intégrée à Compose.
- **État :** un `SessionManager` unique, qui vit aussi longtemps que l'application, possède les onglets, leurs sessions,
  la sortie et les questions en attente. L'interface observe son état via des ViewModels et envoie des commandes ;
  une question de mot de passe ou de host key est un état, elle survit donc à une rotation.
- **Arrière-plan :** `SshForegroundService` ne fait que garder le processus en vie et afficher la notification ;
  il tourne tant qu'un onglet se connecte ou est connecté, puis s'arrête de lui-même.
- **Stockage :** connexions dans un fichier DataStore chiffré en AES-256-GCM (clé Android Keystore, exclu des
  sauvegardes) ; réglages dans DataStore Preferences ; host keys dans un fichier `known_hosts` à la manière
  d'OpenSSH. Un stockage illisible est signalé, jamais écrasé.
- **Modèle du terminal :** `TerminalBuffer` est du Kotlin sans dépendance Android, testé donc sur la JVM.
- **Les dépendances** sont reliées à la main dans `AppContainer`, sans framework d'injection.
- **Synchronisation** (facultative) : `SyncManager` fusionne les connexions et host keys de l'appareil avec un fichier chiffré en AES-256-GCM dans le dossier caché de données d'application de Drive ; la clé provient de la phrase secrète de l'utilisateur (PBKDF2) et ne quitte jamais les appareils.

## Structure du projet

```
app/src/main/java/hu/billman/konsolessh/
  model/        ConnectionConfig : la connexion enregistrée
  data/         stockage chiffré des connexions, réglages, suppression des anciens stockages
  ssh/          sessions JSch, chaîne de sauts, vérification des host keys, envoi de clé, erreurs typées
  session/      SessionManager, tampon de sortie, questions de mot de passe et de host key
  sync/         synchronisation Google Drive : chiffrement, fusion, client Drive
  terminal/     TerminalView et TerminalBuffer : rendu, saisie, émulation
  service/      le service de premier plan
  ui/           écrans Compose, ViewModels, barre de touches, thème
  AppContainer.kt  le graphe de dépendances
```

## Compilation

```
Kotlin 2.4 · AGP 9.5 · Gradle 9.6 · Java 17
minSdk 26 (Android 8.0) · targetSdk / compileSdk 37 (Android 17)
```

```bash
./gradlew detekt :app:testDebugUnitTest :app:lintDebug   # vérifications et tests unitaires
./gradlew :app:connectedDebugAndroidTest                 # tests d'interface sur appareil ou émulateur
./gradlew :app:bundleRelease                             # App Bundle signé (nécessite un keystore)
```

Les tests unitaires comprennent des tests d'intégration contre de vrais serveurs OpenSSH ; ils demandent
un `sshd` installé et sont ignorés sans lui. Le build release est minifié avec R8.

## Utilisation

1. **Nouvelle connexion :** `+` → *Nouvelle connexion…*, puis hôte, port, nom d'utilisateur et mot de passe ou clé.
2. **Jump host :** dans le formulaire, *Configurer le jump host…*, puis choisir une connexion enregistrée comme passerelle.
3. **Renommer ou fermer un onglet :** appui long, ou son `✕`.
4. **Zoom :** les boutons loupe à côté de `+`.
5. **Aide-mémoire :** balayer au-delà du dernier onglet.
6. **Envoyer un fichier :** 📁 dans la barre de touches ; le fichier va dans le répertoire personnel distant (`~`).
7. **Quitter :** menu (⋮) → *Quitter*, ou `+` → *Quitter*.

## Dépendances

| Bibliothèque | Rôle |
| --- | --- |
| JSch (fork de mwiede) | SSH et SFTP |
| Bouncy Castle | host keys Ed25519 et échange de clés curve25519 pour JSch |
| Jetpack Compose, Material 3 | interface |
| AndroidX Lifecycle, Activity | ViewModels, état tenant compte du cycle de vie |
| kotlinx.coroutines | E/S SSH asynchrones |
| kotlinx.serialization | format du stockage des connexions |
| AndroidX DataStore | stockage des connexions et réglages |
| AndroidX AppCompat | choix de la langue dans l'app sous Android 8–12 |
| Google Play services auth | autorisation pour la synchronisation Google Drive facultative |
| androidx.security | uniquement pour migrer les stockages antérieurs à 1.1.12 |

Les versions exactes se trouvent dans `gradle/libs.versions.toml`.

## Autorisations

| Autorisation | Raison |
| --- | --- |
| `INTERNET` | connexions SSH |
| `ACCESS_NETWORK_STATE` | vérifier la connectivité |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | garder les sessions ouvertes en arrière-plan (API 34+) |
| `POST_NOTIFICATIONS` | la notification de session (API 33+) |
| `ACCESS_LOCAL_NETWORK` | hôtes du réseau local (Android 17+) |

Aucune donnée n'est collectée ni envoyée. Les identifiants restent chiffrés sur l'appareil.

## Licence

GPL-3.0-or-later ; voir [LICENSE](LICENSE).

## Historique des versions

- **3.0.0** — une version majeure, car deux choses sont retirées. Les
  stockages de connexions de la 1.x ne sont plus lus : en passant directement
  de la 1.x à la 3.0.0, il faut saisir de nouveau ses connexions, et les
  anciens fichiers sont supprimés. La boîte de dialogue de rapport d'erreur et
  son entrée de menu disparaissent ; les plantages sont suivis via Google
  Play. Nouveau : avec la synchronisation Google Drive activée, une
  modification qui n'a pas pu être envoyée (pas de réseau, app fermée) part
  plus tard d'elle-même, et l'écran de synchronisation explique ce que fait la
  synchronisation. Les connexions SSH utilisent l'échange de clés hybride
  post-quantique (mlkem768x25519-sha256) avec les serveurs qui le prennent en
  charge (JSch 2.28.8). L'app démarre plus vite grâce à un profil de référence
  (baseline profile), et ses écrans tournent désormais dans une seule activité
- **2.3.0** — le sélecteur d'onglets se ferme d'un balayage latéral, vers la
  gauche ou la droite, ou avec sa nouvelle ligne « Retour », qui y remplace
  « Quitter » ; on quitte l'app depuis le menu principal. Avec la
  synchronisation Google Drive activée, l'app synchronise aussi dès qu'elle
  revient au premier plan, et un envoi encore en attente part aussitôt quand
  elle passe en arrière-plan
- **2.2.0** — synchronisation Google Drive facultative : les connexions
  enregistrées, avec leurs mots de passe et clés, et les host keys acceptées se
  synchronisent entre appareils via un dossier caché du propre Google Drive de
  l'utilisateur, chiffrées de bout en bout avec une phrase secrète qu'il est
  seul à connaître. Au premier démarrage sans rien d'enregistré, l'app propose
  de les restaurer. La politique de confidentialité décrit la synchronisation
- **2.1.0** — après la fermeture du dernier onglet, la page d'accueil réapparaît
  au lieu d'un aide-mémoire ; une sélection commencée par un appui long reste
  en place quand le doigt est levé sans glisser ; avec une grande police
  système, le terminal suit la mise à l'échelle non linéaire d'Android 14 ; les
  textes avec un nombre utilisent le singulier et le pluriel corrects dans
  chaque langue ; l'écran respecte le verrouillage de la rotation ; la saisie
  dans un champ prérempli continue à la fin ; le toucher du terminal est
  accessible aux services d'accessibilité
- **2.0.0** — un nouveau format de stockage et une interface reconstruite.
  Les connexions enregistrées passent dans un fichier DataStore chiffré et les
  réglages dans DataStore ; la mise à jour les migre une fois et supprime les
  anciens fichiers, si bien que 1.x ne peut plus les lire ensuite. Tous les
  écrans sont reconstruits avec Jetpack Compose et Material 3. Un envoi de
  fichier continue pendant une rotation ; la suggestion de jump host reconnaît
  les adresses privées d'après leurs plages réelles ; le balayage entre onglets
  fonctionne aussi au-dessus du terminal. Le service d'arrière-plan utilise le
  type de service de premier plan `specialUse`. Le menu renvoie à la politique
  de confidentialité
- **1.1.30** — qualité pilotée par les tests. Terminal : comparé à tmux sur des
  sessions bash, vim, less, htop et mc enregistrées ; corrigés les séquences CSI à
  octet intermédiaire (la forme du curseur de vim affichait un `q` parasite),
  l'effacement avec couleur de fond (panneaux colorés de mc), VPA/HPA/REP et la
  tabulation avec compteur (htop était décalé d'une ligne), les modes d'écran
  alternatif de xterm, l'écran principal derrière vim lors d'une rotation, SGR
  atténué/italique/barré et les compteurs énormes qui pouvaient bloquer
  l'analyseur. SSH : un onglet fermé pendant la connexion ne laisse plus de
  connexion à moitié ouverte (jusqu'à 2 minutes) ; une ligne known_hosts
  endommagée ne peut plus devenir une clé de confiance. Tests d'intégration avec
  un vrai sshd, d'injection de pannes et d'interface sur émulateur dans la CI, avec
  mesure de couverture
- **1.1.29** — émulation du terminal : les caractères larges (CJK, emoji) occupent
  deux cellules comme sur le serveur, les marques combinantes s'attachent à la
  cellule précédente, dessin de lignes DEC (`ESC ( 0`) pour les cadres
  ncurses/dialog/mc, réponse aux requêtes de position du curseur et d'appareil
  (`CSI 6n`, DA) — mais pas une seconde fois lors de la relecture après une
  rotation ; le texte est dessiné par segments et non cellule par cellule. Rapports
  d'erreur : après un ANR ou un plantage, l'application propose de partager un
  rapport (version, appareil, trace — sans secret) ; rien n'est envoyé
  automatiquement. Le service de premier plan ne tourne que pendant une session
  active. Les fichiers de clé sont lus hors du thread principal. Corrigé : les
  aide-mémoire s'affichaient deux fois après un redimensionnement
- **1.1.28** — la sortie du terminal est traitée sur un thread d'arrière-plan, par
  tranches : une sortie volumineuse (`cat` d'un gros journal, `find /`) ne bloque plus
  l'interface ; les caractères UTF-8 coupés entre deux lectures réseau restent intacts
- **1.1.27** — architecture en couches après un audit du code : un gestionnaire de
  sessions unique comme source de vérité, les questions arrivent sous forme d'état
  et survivent à la rotation, erreurs typées avec un seul traducteur de messages ;
  un stockage de connexions illisible n'est plus écrasé et rien n'est jamais
  enregistré en clair ; plus de secrets dans les arguments des fragments ; la phrase
  de passe de la clé est conservée à l'édition ; commande d'envoi de clé plus sûre
- **1.1.26** — vérification de la clé d'hôte du serveur (confiance au premier usage,
  empreinte SHA256, alerte en cas de changement) ; jump hosts à plusieurs sauts (un
  jump host peut avoir son propre jump host, jusqu'à 5 sauts), utilisés aussi par le
  test de connexion et l'envoi de clé ; clés d'hôte Ed25519 et échange curve25519
  via Bouncy Castle ; profils chiffrés exclus de la sauvegarde ; texte copié depuis
  le terminal marqué comme sensible ; plus de « r » parasite dans les messages du
  terminal
- **1.1.25** — cible Android 17 (API 37) : autorisation réseau local, retour
  prédictif, AGP 9.5 / Gradle 9.6
- **1.1.24** — le test de connexion fonctionne avec une clé privée
- **1.1.23** — logo de l'app et titre « KonsoleSSH » dans la barre d'outils
- **1.1.22** — entrée Quitter à la fin du menu à trois points
- **1.1.21** — analyse statique (detekt, ktlint) et contrôle qualité en CI,
  Dependabot, OSV-Scanner
- **1.1.20** — nettoyage : Node.js 24 en CI, configuration des langues
- **1.1.19** — mise à jour des dépendances (security-crypto stable)
- **1.1.18** — CI, suppression de code mort, corrections de cycle de vie et de lint
- **1.1.17** — keep-alive SSH ; les messages système ne s'affichent plus sous
  l'invite
- **1.1.16** — correctif : plantage à la rotation (concurrence dans le cycle de vie
  du Fragment)
- **1.1.15** — les sessions et dialogues survivent à la rotation
- **1.1.14** — couche MVVM (ViewModels)
- **1.1.13** — touche Suppr (suppression avant) via SSH
- **1.1.12** — chiffrement AES-256-GCM maison (Android Keystore) avec migration
  atomique des anciens profils
- **1.1.11** — ligne de version dans la bannière d'accueil
- **1.1.10** — flux d'événements entre le service et l'interface (Kotlin Flow)
- **1.1.4–1.1.9** — refactorisation interne (tampon du terminal, dépôt des
  connexions, générateur de clés), mise à jour des dépendances, corrections lint
- **1.1.3** — erreurs de connexion dans un toast ; l'onglet se ferme seul après 5 s
- **1.1.2** — messages distincts pour trois types d'erreur de connexion
- **1.1.1** — envoi de clé seulement après un test réussi ; la clé publique est
  envoyée via authentification par mot de passe
- **1.1.0** — générateur de clés SSH intégré
- **1.0.9** — ordre d'authentification publickey en premier, explication de l'erreur
  « Auth cancel »
- **1.0.8** — sélecteur de langue dans le menu à trois points
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
