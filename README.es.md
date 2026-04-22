# KonsoleSSH

Terminal SSH multi-pestaña para Android — inspirado en KDE Konsole.

> **Idioma:** Español · [English](README.md) · [Magyar](README.hu.md) · [Deutsch](README.de.md) · [Français](README.fr.md)

## Capturas de pantalla

| Pantalla de bienvenida | Nueva conexión SSH |
| --- | --- |
| ![Bienvenida](play_screenshot/Screenshot_20260422_094020.png) | ![Nueva conexión](play_screenshot/Screenshot_20260422_094447.png) |

| Referencia de tmux | Referencia de Linux |
| --- | --- |
| ![Referencia de tmux](play_screenshot/Screenshot_20260422_094521.png) | ![Referencia de Linux](play_screenshot/Screenshot_20260422_094530.png) |

## Funcionalidades

### Conexiones

- **Interfaz multi-pestaña** — TabLayout + ViewPager2, cada pestaña tiene su propia sesión SSH independiente
- **Conexiones SSH** — librería JSch, autenticación con contraseña **o** clave privada (PEM) con frase de paso opcional
- **Soporte de jump host (`ssh -J`)** — alcanza hosts internos a través de una conexión puerta de enlace guardada (port forwarding local)
- **Solicitud de contraseña interactiva** — aparece un diálogo al conectar si no hay contraseña guardada
- **Conexiones guardadas** — perfiles cifrados en reposo con AES256 (`EncryptedSharedPreferences`, Android Keystore); orden alfabético
- **Selector en árbol** — las conexiones nombradas con guiones bajos (ej. `acme_prod_web`, `acme_prod_db`, `foobar_dev_01`) se agrupan automáticamente por prefijo común. Aquí `acme_` → `prod_` agrupa las dos primeras, mientras que `foobar_dev_01` queda plano porque está solo bajo su prefijo. Los grupos se abren y cierran con una flecha ▶/▼.

### Terminal

- **Emulador de terminal sobre Canvas** — 256 colores + truecolor, negrita, subrayado, vídeo invertido, historial scrollback
- **PTY ANSI/VT100 + xterm-256color** — los prompts de bash/zsh/fish se muestran correctamente
- **Soporte NerdFont** — JetBrainsMono Nerd Font incluida; emojis y caracteres surrogate-pair se muestran bien
- **Copia al seleccionar** (pulsación larga + arrastre) y **pegar desde el portapapeles**
- **Zoom del tamaño de fuente** — botones en la barra de herramientas; el ajuste persiste
- **Botón reconectar** — aparece en el centro de la terminal cuando la conexión cae

### Barra de teclas en pantalla

La barra inferior expone todo lo que el teclado virtual de Android no da:

- **⌨** — abre/cierra el teclado virtual del sistema. _El teclado sólo se abre con este botón o al tocar la terminal; ningún otro botón de la barra lo hace aparecer._
- **Fn** — muestra una fila con las teclas **F1 – F12**
- **CTRL / SHIFT / ALT / ALTGR** — modificadores de un solo uso (se reinician tras la siguiente tecla)
- **CTRL** abre además una fila de combinaciones `Ctrl+A`, `Ctrl+B`, `Ctrl+C`, `Ctrl+D`, `Ctrl+V`, `Ctrl+Z`
  - `Ctrl+C` copia la selección si hay alguna; si no, envía `ETX`
  - `Ctrl+V` pega desde el portapapeles
- **ESC, TAB** — teclas de un toque
- **↑** — muestra una fila de flechas (← ↑ ↓ →)
- **📁** — elige un archivo local y **súbelo por SFTP** al directorio *home* remoto. Un diálogo de progreso muestra MB transferidos / totales. Tras el éxito, un *toast* de confirmación de 3 s ofrece un botón **Deshacer** que elimina el archivo recién subido del servidor.

### Estado / UX

- **Indicadores de estado** — puntos verde/amarillo/rojo en las pestañas y en el selector
- **Página de bienvenida** — deslizable; desliza a la derecha para las referencias
- **Referencia de Linux** — `top`, `df`, `du`, `dd`, `tail`, `head`, `grep`, `egrep`, `awk`, `sed`, `tr`, `ip`, `mc`
- **Referencia de tmux** — sesiones, ventanas, paneles, layouts, prefijo
- **Persistencia en segundo plano** — las sesiones SSH activas siguen vivas al bloquear la pantalla o cerrar la tarea (Foreground Service)
- **Insignia de notificación** — número de conexiones activas
- **Botón cerrar aplicación** — en el menú del selector de pestañas; pide confirmación si hay conexiones activas
- **Mensajes de error amigables** — texto legible en lugar de excepciones Java crudas
- **Disposición edge-to-edge** — gestión correcta de window-insets en Android 15; el contenido queda por encima de la barra de navegación en horizontal y sube al abrirse el teclado

### Localización

- Inglés por defecto, traducciones incluidas: **húngaro**, **español**, **alemán** y **francés**. La aplicación sigue el idioma del sistema. Las hojas de referencia (Linux/tmux) están en inglés para idiomas que no son el húngaro.

## Arquitectura

Las sesiones las posee `SshForegroundService`, no los fragments:

- Las conexiones sobreviven a la recreación de la activity (rotación, botón atrás, cierre de tarea)
- `TerminalFragment` se engancha/desengancha al servicio en `onStart`/`onStop` y reproduce el buffer de salida al reconectar
- `OutputBuffer` mantiene los últimos 256 KB de salida por sesión

## Estructura del proyecto

```
app/src/main/java/hu/billman/konsolessh/
├── model/           ConnectionConfig, SavedConnections (prefs cifradas)
├── ssh/             SshSession, SshForegroundService
├── terminal/        TerminalView, AnsiParser, TerminalClipboard
└── ui/              MainActivity, TerminalFragment, diálogos, sheets
```

## Construcción

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
# Compilación debug + tests unitarios
./gradlew :app:testDebugUnitTest

# App Bundle firmado para release (requiere un keystore configurado)
./gradlew :app:bundleRelease
```

La build **release** se minifica con R8 y se reducen recursos. El código basado en reflexión
(JSch, modelos de Gson) se preserva mediante `app/proguard-rules.pro`. Se produce `mapping.txt`
para que Play Console pueda desofuscar los stack traces.

## Permisos

| Permiso                               | Motivo                                                |
| ------------------------------------- | ----------------------------------------------------- |
| `INTERNET`                            | Conexión SSH                                          |
| `ACCESS_NETWORK_STATE`                | Verificación del estado de la red                     |
| `CHANGE_NETWORK_STATE`                | Foreground service (tipo connectedDevice)             |
| `FOREGROUND_SERVICE`                  | Iniciar el servicio en segundo plano                  |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Foreground service de red (API 34+)                   |
| `POST_NOTIFICATIONS`                  | Notificación persistente de sesión (API 33+)          |

No se recopilan, comparten ni envían datos. Las credenciales permanecen en el dispositivo en el almacenamiento cifrado del Keystore.

## Requisitos

- Android 8.0 (API 26) o superior
- Modos vertical y horizontal soportados

## Historial de versiones

- **1.0.2** — paquete renombrado a `hu.billman.konsolessh`, correcciones de edge-to-edge,
  teclado virtual sólo a petición, subida SFTP con diálogo de progreso y toast de
  Deshacer, minificación R8, Gradle 9.4.1 / AGP 9.2.0
- **1.0.1** — `targetSdk = 35`, primer envío a Play
- **1.0.0** — SSH multi-pestaña, jump host, árbol de conexiones guardadas,
  referencias Linux y tmux, traducción al húngaro
