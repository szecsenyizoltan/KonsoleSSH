# KonsoleSSH

Terminal SSH multi-onglets pour Android — inspiré par KDE Konsole.

> **Langue :** Français · [English](README.md) · [Magyar](README.hu.md) · [Español](README.es.md) · [Deutsch](README.de.md)

## Captures d'écran

| Écran d'accueil | Nouvelle connexion SSH |
| --- | --- |
| ![Accueil](play_screenshot/Screenshot_20260422_094020.png) | ![Nouvelle connexion](play_screenshot/Screenshot_20260422_094447.png) |

| Aide-mémoire tmux | Aide-mémoire Linux |
| --- | --- |
| ![Aide-mémoire tmux](play_screenshot/Screenshot_20260422_094521.png) | ![Aide-mémoire Linux](play_screenshot/Screenshot_20260422_094530.png) |

## Fonctionnalités

### Connexions

- **Interface multi-onglets** — TabLayout + ViewPager2, chaque onglet porte sa propre session SSH indépendante
- **Connexions SSH** — bibliothèque JSch, authentification par mot de passe **ou** clé privée (PEM) avec phrase de passe facultative
- **Jump host (`ssh -J`)** — atteindre des hôtes internes via une connexion-passerelle enregistrée (port forwarding local)
- **Demande de mot de passe interactive** — un dialogue s'ouvre à la connexion si aucun mot de passe n'est enregistré
- **Connexions enregistrées** — profils chiffrés au repos en AES256 (`EncryptedSharedPreferences`, Android Keystore) ; ordre alphabétique
- **Sélecteur en arbre** — les connexions nommées avec des tirets bas (ex. `acme_prod_web`, `acme_prod_db`, `foobar_dev_01`) sont automatiquement regroupées par préfixe commun. Ici `acme_` → `prod_` regroupe les deux premières, tandis que `foobar_dev_01` reste à plat parce qu'il est seul sous son préfixe. Les groupes s'ouvrent et se ferment avec une flèche ▶/▼.

### Terminal

- **Émulateur de terminal basé Canvas** — 256 couleurs + truecolor, gras, souligné, vidéo inverse, mémoire tampon de défilement
- **PTY ANSI/VT100 + xterm-256color** — les prompts bash/zsh/fish s'affichent correctement
- **Support NerdFont** — JetBrainsMono Nerd Font embarquée ; emojis et caractères surrogate-pair s'affichent bien
- **Copie sur sélection** (appui long + glissement) et **collage depuis le presse-papiers**
- **Zoom de la taille de police** — boutons dans la barre d'outils ; le réglage persiste
- **Bouton de reconnexion** — apparaît au centre du terminal si la connexion tombe

### Barre de touches à l'écran

La barre inférieure propose tout ce que le clavier virtuel Android ne fait pas :

- **⌨** — ouvre/ferme le clavier virtuel du système. _Le clavier ne s'ouvre qu'avec ce bouton ou en touchant le terminal ; aucune autre touche de la barre ne le fait apparaître._
- **Fn** — affiche une ligne des touches de fonction **F1 – F12**
- **CTRL / SHIFT / ALT / ALTGR** — modificateurs à usage unique (réinitialisés après la touche suivante)
- **CTRL** ouvre en plus une ligne de combinaisons `Ctrl+A`, `Ctrl+B`, `Ctrl+C`, `Ctrl+D`, `Ctrl+V`, `Ctrl+Z`
  - `Ctrl+C` copie la sélection si elle existe, sinon envoie `ETX`
  - `Ctrl+V` colle depuis le presse-papiers
- **ESC, TAB** — touches à un appui
- **↑** — affiche une ligne de flèches (← ↑ ↓ →)
- **📁** — choisir un fichier local et **l'envoyer via SFTP** dans le répertoire *home* distant. Un dialogue de progression affiche Mo transférés / totaux. En cas de succès, un *toast* de confirmation de 3 s propose un bouton **Annuler** qui supprime le fichier fraîchement envoyé du serveur.

### État / UX

- **Indicateurs d'état de connexion** — points verts/jaunes/rouges sur les onglets et dans le sélecteur
- **Page d'accueil** — glissable ; balaie à droite pour les aide-mémoires
- **Aide-mémoire Linux** — `top`, `df`, `du`, `dd`, `tail`, `head`, `grep`, `egrep`, `awk`, `sed`, `tr`, `ip`, `mc`
- **Aide-mémoire tmux** — sessions, fenêtres, panneaux, layouts, bindings de préfixe
- **Persistance en arrière-plan** — les sessions SSH actives restent vivantes au verrouillage d'écran et au retrait de la tâche grâce à un Foreground Service
- **Badge de notification** — nombre de connexions actives
- **Bouton Quitter** — dans le menu du sélecteur d'onglets ; demande confirmation si des connexions sont actives
- **Messages d'erreur lisibles** — texte humain à la place des exceptions Java brutes
- **Disposition edge-to-edge** — gestion correcte des window-insets sur Android 15 ; le contenu reste au-dessus de la barre de navigation en paysage et remonte à l'ouverture du clavier

### Localisation

- Anglais par défaut, traductions incluses : **hongrois**, **espagnol**, **allemand** et **français**. L'application suit la langue du système. Les aide-mémoires intégrés (Linux/tmux) sont en anglais pour les langues autres que le hongrois.

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

## Historique des versions

- **1.0.2** — paquet renommé en `hu.billman.konsolessh`, corrections d'insets edge-to-edge,
  clavier virtuel uniquement à la demande, envoi SFTP avec dialogue de progression et
  toast d'annulation, minification R8, Gradle 9.4.1 / AGP 9.2.0
- **1.0.1** — `targetSdk = 35`, première soumission Play
- **1.0.0** — SSH multi-onglets, jump host, arbre des connexions enregistrées,
  aide-mémoires Linux et tmux, traduction hongroise
