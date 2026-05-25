# Release Notes

## 2026-05-25 (session 8)

### Auto-connect au démarrage
- `DroneViewModel.autoConnect()` : séquence automatique AOA → Handshake → Discover → Connect → AllStates → OpenStreamChannels → VideoStreamMode → VideoEnable
- `PilotScreen` : overlay central avec spinner + texte de progression (ou message d'erreur en rouge) quand pas connecté
- Plus besoin de toucher les boutons manuels dans la page debug

### Mode immersif Android 17
- `enableEdgeToEdge()` + `window.insetsController.hide(systemBars())` dans `onWindowFocusChanged` (API plateforme au lieu de la lib compat)
- Note : un appel téléphonique en cours force Android à garder la barre visible (`forciblyShownTypes=statusBars`)

### Logging tag "Bebop"
- `Log.i/d/w/e("Bebop", ...)` ajouté dans `DroneViewModel` (auto-connect), `AoaController` (AOA, chan 4, RTP, ARCommands, batteries), `H264Decoder` (SPS/PPS, codec config, output, erreurs)
- Chaque nouveau tuple ARCommand logué à la première occurrence
- Stats périodiques toutes les 300 frames (RTP + décodeur)

### Bugfix — tuples ARSDK incorrects
- `COMMON_BATTERY` corrigé : `Triple(0, 5, 1)` → `Triple(0, 1, 1)` (cls=1 CommonState, pas cls=5 SettingsState)
- `COMMON_ALL_STATES` corrigé : `Triple(0, 4, 0)` → `Triple(0, 0, 0)` (cls=0 Common, pas cls=4 Controller)
- Ces bugs empêchaient la réception de la batterie drone et l'envoi correct de la requête AllStates

## 2026-05-25 (session 7)

### VIDÉO LIVE — enfin débloquée (864x480 H.264)
- **Bug critique #1 — ACK transport manquant** : les frames WITHACK reçues (buf 126) n'étaient jamais ACKées, le SC2 retransmettait en boucle et considérait le client non-réactif. Fix : envoi automatique d'ACK (dataType=1, bufferId|0x80, même seq) dans `handleChan1`. Résultat : 11 KB → 56 MB de données reçues.
- **Bug critique #2 — format vidéo chan 4 = RTP brut** : le fallback `ArStreamReader` (header v1 5B) assemblait du garbage. Diagnostic hex ajouté (`chan4RawHead`, `assembledFrameHead`) → identifié `80 e0` = RTP version 2, PT=96. Fix : détection `byte[0] & 0xC0 == 0x80` → `rtpDepayloader.feed()`. SPS/PPS extraits des STAP-A (NAL type 24), décodeur H.264 initialisé.
- `ArCommand.videoStreamMode(mode)` ajouté — envoyé avant `VideoEnable(1)` dans le flow "Start video"

### Écran pilotage avec joysticks flottants
- `ui/PilotScreen.kt` (nouveau) : vidéo plein écran, HUD (status, batteries, indicateur REC), boutons ARMER/DÉCOLLER/ATTERRIR/STOP/URGENCE/REC
- `ui/FloatingJoystick.kt` (nouveau) : joystick apparaît où le pouce se pose, disparaît au relâcher, gauche=gaz/yaw droite=pitch/roll, multi-touch via zones séparées
- `HorizontalPager` (2 pages) : swipe gauche=pilotage, droite=debug
- Dépendance `androidx.compose.foundation:foundation` ajoutée

### Refactoring parsing transport
- `handleChan1` réécrit pour utiliser `ArsdkTransport.decodeAll()` (respecte le champ `size` du header) au lieu de deviner 7/11 bytes — corrige potentiellement la batterie drone non reçue
- `parseArCommand()` unifié : plus de split `tryIterate`/`iterateArCommands`, toutes les ARCommands (connues ou inconnues) passent par le même path
- `iterateArCommands()` et `tryIterate()` supprimés

### Enregistrement vidéo drone
- `ArCommand.videoRecord(start)` : ardrone3.MediaRecord.VideoV2 (prj=1 cls=3 cmd=1)
- `ArsdkIds.ARDRONE3_VIDEO_RECORD_STATE` (1,7,3) : parsing VideoStateChangedV2 (stopped/started/failed/autostopped)
- Bouton REC/STOP REC dans PilotScreen + indicateur "● REC" rouge dans le HUD

### Divers
- `FLAG_KEEP_SCREEN_ON` — plus de mise en veille pendant le pilotage
- Compteur C2D envoyées (`c2dSent` StateFlow) visible dans la page debug
- `startPilotingLoop()` / `stopPilotingLoop()` exposés publiquement dans le ViewModel
- Repo Git initialisé + publié sur https://github.com/ddaydd/bebop
- README.md avec architecture, build, usage, découvertes clés

## 2026-05-23

### Recherche vidéo Bebop 2 — théorie verrouillée via doc Parrot officielle
- Confirmé par PDF officiel `developer.parrot.com/docs/SDK3/SkyControllerDev.pdf` §6.2.5 : la vidéo Bebop 2 via SC2 USB AOA passe par **ARStream v1 → ARNetwork → buffer 125 sur chan 1 MUX**, PAS par les canaux statiques chan 4/5/6 (qui sont pour Anafi/Disco en ARStream2)
- Buffer IDs `MPP_*` extraits de `libARDiscovery/Sources/Usb/ARDISCOVERY_DEVICE_Usb.c` : VIDEO_DATA=125, VIDEO_ACK=13, NAVDATA=127, EVENT=126, NONACK/ACK/EMERGENCY=10/11/12, port=43210
- `aoa/AoaController.kt::sendConnect` réécrit pour envoyer un JSON CONN_REQ ARStream v1-only (retiré les clés `arstream2_*` qui faisaient basculer le drone en RTP UDP direct qui ne traverse pas le SC2)
- Pièges identifiés et documentés dans `CLAUDE.md` + nouvelle mémoire `feedback_bebop_video_research_pitfalls.md` (12 entrées)

### Observabilité — décodage tuples critiques + UI
- `arsdk/ArsdkTransport.kt` : ajout dans `ArsdkIds` de `COMMON_PRODUCT_VERSION (0,5,0)`, `ARDRONE3_VIDEO_ENABLE_CHANGED (1,22,0)`, `ARDRONE3_VIDEO_STREAM_MODE_CHANGED (1,22,1)` + entrées correspondantes dans `ArCommandSizes`
- `aoa/AoaController.kt` : nouveaux StateFlow `droneFirmware`, `videoEnableState`, `videoStreamMode`, `videoV1Frames`, `videoV1LastSize`, `videoV1LastHead` ; helpers `readU32Le()` et `readCString()` ; capture frames buf 125 sur chan 1 ; capture spéciale `ProductVersionChanged` (2 strings) dans le path "tuple inconnu"
- `ui/DroneViewModel.kt` : expose les nouveaux StateFlow
- `ui/MainScreen.kt` : 3 nouvelles lignes dans "État du lien" (Firmware drone / VideoEnable répondu / VideoStreamMode) + indicateur ARStream v1 dans VideoCard (frames buf 125 + 24 premiers bytes)
- Correction du détecteur de handshake : le SC2 ne renvoie pas de HANDSHAKE id=127 — utiliser `ctrlHistory.isNotEmpty()` (au moins un ctrl_msg reçu)
- Label "Handshake SC2" reformulé : "OK — SC2 ouvre les canaux" / "en attente (SC2 muet)"

### Plein écran (Android 15 edge-to-edge)
- `MainActivity.kt` : `WindowCompat.setDecorFitsSystemWindows(window, false)` + `WindowInsetsControllerCompat.hide(systemBars)` avec swipe pour faire revenir les barres temporairement
- `ui/MainScreen.kt` : `Modifier.systemBarsPadding()` ajouté au container racine pour gérer les insets quand les barres reviennent

## 2026-05-22

### Phase 6 — Commandes de pilotage (Takeoff/Land/Emergency/FlatTrim + PCMD 20 Hz)
- `arsdk/ArsdkTransport.kt` : encoders `ArCommand.takeoff/landing/emergency/flatTrim` (tous prj=1 cls=0, no args) et `pcmd(flag, roll, pitch, yaw, gaz, ts)` — args u8 + 4×i8 + u32 LE timestamp
- `aoa/AoaController.kt` :
  - Séquences ARSDK désormais tenues **par bufferId** (`ConcurrentHashMap<Int, AtomicInteger>`) au lieu d'un compteur global — conforme à `arsdk_cmd_itf1` qui maintient une seq distincte par buffer
  - `sendTakeoff/Landing/FlatTrim` en WITHACK buf 11, `sendEmergency` en HIGHPRIO buf 12 pour shortcut la queue
  - `sendPcmd` en NOACK buf 10 (flag auto = 1 si roll/pitch ≠ 0)
  - `PilotingInput(roll, pitch, yaw, gaz)` exposé via `pilotingInput` StateFlow, mutateur `setPilotingInput()` qui coerce dans [-100, 100]
  - `startPilotingLoop()` / `stopPilotingLoop()` : boucle coroutine 20 Hz (50 ms) qui pousse `sendPcmd` avec les inputs courants ; arrêt automatique sur `disconnect()`
- `ui/DroneViewModel.kt` : expose `pilotingInput`, `pilotingActive`, `setPilotingInput()`, `togglePilotingLoop()`, `aoaTakeoff/Landing/Emergency/FlatTrim`
- `ui/MainScreen.kt` : nouvelle carte **Pilotage** (entre LinkStateCard et VideoCard) avec boutons Takeoff/Land/Emergency/Flat trim, Switch toggle PCMD, 4 sliders roll/pitch/yaw/gaz (−100..100), bouton "Centrer sticks"
- Test terrain (post-install) : lien Phone↔SC2↔Bebop **confirmé** (29 tuples ARCommand uniques reçus, dont `prj=1` Ardrone3 → preuve de trafic d'origine drone)

### Décodage vidéo robuste (SPS/PPS extraction)
- `video/H264Nal.kt` (nouveau) : parser Annex-B (scan start codes, extraction NAL units, détection SPS/PPS/IDR)
- `video/H264Decoder.kt` refactoré : configuration paresseuse de `MediaCodec` avec `csd-0`/`csd-1` extraits du premier IDR au lieu d'un format hardcodé. Détection keyframe via NAL type 5 (pas le flag FLUSH_FRAME d'ARStream qui n'a aucun rapport).
- Stats exposées : `configured`, `framesQueued`, `outputWidth/Height` (récupéré via `INFO_OUTPUT_FORMAT_CHANGED`), `lastError`

### Carte "État du lien"
- Nouvelle `LinkStateCard` dans `ui/MainScreen.kt` qui résume USB AOA / Handshake / Devices / CONN_RESP / Sticks / Vidéo / Batteries + verdict
- Verdict strict : "lien SC2 ↔ drone CONFIRMÉ" uniquement avec preuve de trafic d'origine drone (frame vidéo OU batt drone OU `prj=1` Ardrone3 reçu). CONN_RESP=ok seul ne prouve que la liaison phone↔SC2.

### Décodeur ARCommand entrant sur chan 1
- `arsdk/ArsdkTransport.kt` étendu : `decodeAll()` itère les transport frames packées, types `ArsdkFrame` et `ArCommandHeader`, table `ArCommandSizes` (tailles d'args connues par tuple), constantes `ArsdkIds`
- `aoa/AoaController.kt` : décode les ARCommands sur chan 1, accumule stats par tuple `(prj,cls,cmd)` et par `(dataType,bufferId)`, parse batterie drone `(0,5,1)` et batterie SC2 `(4,8,0)`
- Filtrage des trames ACK (`dataType=1`) et PING/PONG (`bufferId=0/1`) avant tentative de décodage ARCommand
- Itération multi-pack des ARCommands à l'intérieur d'une trame transport via la table de tailles
- Boutons "AllStates SC2" et "AllStates drone" qui envoient les requêtes ARSDK pour forcer le re-push des états

### Découvertes terrain confirmées (sources Parrot)
- Project ID `skyctrl` = **4** (pas 8). Confirmé empiriquement et via `arsdk-xml/xml/skyctrl.xml`.
- Tuple `(4,8,4)` = `SkyController.SkyControllerState.AttitudeChanged` (4 floats quaternion, NOACK haute fréquence). Notre app capte ça même sans drone connecté → preuve que le SC2 répond.
- Tuple `(4,8,0)` = `BatteryChanged` (u8 percent). N'est poussé **que quand le pourcentage change** + buffer WITHACK=126 (pas 127). Donc invisible si batterie stable (100% en charge).
- Wire format ARSDK transport :
  - V1 (`cmd_itf1`) : header 7B fixe (type+id+seq u8 + f_len u32 LE), 1 ARCommand par trame
  - V2 (`cmd_itf2`) : header varint version + type/id + seq u16 + varint p_len ; payload = ARCommands préfixées chacune par u16 LE size (`unpack_cmds()` dans `arsdk_cmd_itf2.c:909`)
- On négocie V1 dans le `CONN_REQ` JSON (`proto_v_min=1, proto_v_max=1`) — ce qu'on observe sur le wire.

## 2026-05-21

### Scaffold projet Android
- Création du projet `android/` (Gradle KTS, AGP 8.7.2, Kotlin 2.0.21, Compose BOM 2024.10.01, minSdk 26, targetSdk 35)
- Build CLI via `./gradlew assembleDebug` (sans Android Studio)
- Gradle wrapper 8.10.2 téléchargé, `local.properties` pointant vers `~/Android/Sdk`

### Phase 1 — Découverte du protocole et ouverture du canal SC2
- `ArDiscovery.kt` : handshake TCP `:44444` (Wi-Fi direct drone, conservé pour usage futur sans SC2)
- `NetworkInspector.kt` + `NetworkInspectorAndroid.kt` : énumération interfaces réseau + gateways (via `ConnectivityManager`, car `/proc/net/route` bloqué par SELinux sur Android 14+)
- `UsbInspector.kt` : énumération `UsbManager.deviceList` + `accessoryList` pour repérer le Skycontroller 2
- `AoaTransport.kt` + `AoaController.kt` : ouverture du canal USB Accessory (AOA), permission via `BroadcastReceiver` + `RECEIVER_NOT_EXPORTED`, boucle de lecture, dump hex
- Manifest : intent-filter `USB_ACCESSORY_ATTACHED` + `res/xml/accessory_filter.xml` (auto-lancement au branchement du SC2)
- UI Compose : panneaux Diagnostic réseau + USB + Canal AOA, boutons Connecter/Rafraîchir/Probe

### Découverte clé
- Le Skycontroller 2 ne s'expose **pas** en RNDIS. Il utilise le mode **AOA** (USB Accessory).
- Sur ce canal, le SC2 envoie spontanément des trames avec magic `MUX!` (libmux) encapsulant des messages `POMP` (libpomp).
- L'architecture confirmée : `App ↔ AOA ↔ libmux ↔ libpomp ↔ ARSDK proxy ↔ Wi-Fi ↔ Bebop 2`.
- Les sources des deux couches sont open source chez `Parrot-Developers` sur GitHub.

### À suivre
- Implémenter framing `libmux` (parser/encoder trames `MUX!`)
- Implémenter framing `libpomp` (messages avec event id + args typés)
- Handshake ARSDK over USB : list drones + ouverture des tunnels UDP virtuels
- Décodeur vidéo H.264 via `MediaCodec` → `SurfaceView`
- Commandes ARSDK : `Common.MediaRecord.PictureV2` et `VideoV2`

### Phase 2 — Framing libmux + libpomp
- `mux/MuxFrame.kt` : data class + `encode()` (header 12 B : magic + chanid LE + size LE)
- `mux/MuxDecoder.kt` : décodeur streaming avec resync sur magic, payload borné, compteur de resyncs
- `mux/MuxCtrlMessage.kt` : struct propriétaire 32 B sur canal 0 (id + chanid + 6 args u32), constantes `ID_CHANNEL_OPEN/CHANNEL_CLOSE/RESET/HANDSHAKE`
- `mux/PompMessage.kt` : décodeur POMP (varint ZigZag pour I32/I64, varint simple pour U32/U64, STR avec varint(len) + null terminator)
- `mux/PompEncoder.kt` : encodeur POMP minimal (str/i32/u32/u16/i8/u8)
- Capture binaire dump `aoa-dump-*.bin` sur `getExternalFilesDir` pour analyse offline
- UI : compteurs de frames MUX par canal avec nom symbolique (control/arsdk-discovery/blackbox…)

### Phase 3 — Handshake, Discovery, Connect
- `AoaController.sendHandshake(isAck=false)` : ctrl msg id=127 sur canal 0 avec `protocol_version=2`
- `AoaController.sendDiscover()` : POMP msgid=1 sans args sur canal 2 (arsdk-discovery)
- `AoaController.sendConnect(deviceId, ctrlName="BebopApp")` : POMP msgid=1 sur canal 3 avec 4 strings (name, type, id, json `{"proto_v_min":1,"proto_v_max":1}`)
- Parsing DEVICE_ADDED/REMOVED (chan 2 msgid=2/3, format `%s%u%s`) → liste `ArsdkDevice`
- Parsing CONN_RESP (chan 3 msgid=2, format `%d%s`) → status + JSON ports stream
- Décodage spécifique du PILOTING_INFO blackbox (chan 20) : `(source, roll, pitch, yaw, gaz)` en `int8`

### Découverte clé — pass-through SC2
- Le SC2 expose UNIQUEMENT lui-même via DISCOVER (PID 0x090F, serial `Pi…`)
- Connect au SC2 connecte en réalité au Bebop 2 via pass-through transparent
- Le `CONN_RESP` JSON révèle les ports stream du **Bebop 2** (5004/5005) confirmant le routage
- Le SC2 envoie spontanément les `CHANNEL_OPEN` pour les canaux 2/3/4/5/10/20 dès le handshake

### Phase 4 — ARSDK transport + ARCommand
- `arsdk/ArsdkTransport.kt` : encodage header v1 7 B (type/id/seq/size LE) — canal 1 NON-POMP
- Constantes `DATA_TYPE_*` (ACK=1, NOACK=2, LOWLATENCY=3, WITHACK=4) et `BUFFER_ID_*` (C2D_CMD_NOACK=10, C2D_CMD_WITHACK=11, etc.)
- `arsdk/ArCommand.videoEnable(enable)` : project=1 (ardrone3), class=21 (MediaStreaming), cmd=0, arg u8
- `AoaController.sendArCommand` : compteur seq atomique par buffer_id

### Phase 5 — ARStream v1 + MediaCodec
- Découverte : le Bebop 2 utilise **ARStream v1** sur canal 4 (PAS RTP v2 sur canal 6 comme attendu pour les drones récents)
- `arsdk/ArStreamReader.kt` : header 5 B (frameNumber u16 LE + frameFlags u8 + fragmentNumber u8 + fragmentsPerFrame u8) + assemblage à offset fixe `fragNum × 65000`, bitfield 128 bits, détection frame complète
- `arsdk/ArStreamAck.kt` : encodage 18 B `[u16 frameNum][u64 high][u64 low]` LE, envoyé sur canal 5 à chaque packet reçu
- `video/H264Decoder.kt` : MediaCodec H.264 1280x720 configuré sur Surface, feed Annex-B direct
- UI : carte "Vidéo Bebop 2" avec SurfaceView 16:9 et compteur de frames assemblées + taille de la dernière

### Validation terrain
- 3 frames H.264 complètes assemblées (~196 KB/frame, 4 fragments)
- ACKs partent correctement (out: 16868 B)
- Affichage MediaCodec ajouté en fin de session — comportement visuel pas encore validé
