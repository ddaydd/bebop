# Bebop — pièges et règles du projet

## Architecture

App Android (Kotlin/Compose) → Skycontroller 2 (USB AOA) → Bebop 2 (Wi-Fi).
**Ne PAS chercher d'interface RNDIS / IP côté Pixel quand le SC2 est branché** : le SC2 fait du USB Accessory, le Pixel reste en mode device. Toute la com passe par `UsbManager.openAccessory()`.

Protocole sur le canal AOA (confirmé en lisant les bytes reçus) :
1. **libmux** — header 12 B : magic `MUX!` (4d 55 58 21) + `chanid` u32 LE + `size` u32 LE (taille totale)
2. **libpomp** — header 12 B : magic `POMP` (50 4f 4d 50) + `msgid` u32 LE + `size` u32 LE
3. ARSDK proxy (list drones, ARCommands, ARStream)

Sources des couches 1 et 2 : repos `Parrot-Developers/libmux`, `Parrot-Developers/libpomp`, `Parrot-Developers/arsdk-ng`, `Parrot-Developers/libARStream` sur GitHub.

## Pièges protocole Parrot (confirmés en session)

- **Le SC2 fait du pass-through vers le drone, pas une véritable discovery** : `DISCOVER` (chan 2 POMP msgid=1) renvoie UNIQUEMENT le SC2 lui-même (PID 0x090F). Pour parler au Bebop 2, faire `CONN_REQ` (chan 3 msgid=1) avec le device_id du **SC2** — le SC2 route en transparence. Le JSON du `CONN_RESP` annonce les ports du drone (5004/5005 ARStream v2 ou v1 selon fw).
- **Bebop 2 vidéo via SC2 USB AOA = ARStream v1 sur buffer 125 chan 1 MUX** (vérifié session 6, 2026-05-23 — corrige 2 fausses pistes successives : IP_PROXY et chan 4/5/6 v2). Source officielle : `developer.parrot.com/docs/SDK3/SkyControllerDev.pdf` §6.2.5 — "Routing an ARStream1 stream is done in the same way as routing the commands: All stream packets received from the drone are sent to the tablet **at the ARNetwork level**". Buffer IDs confirmés dans `libARDiscovery/Sources/Usb/ARDISCOVERY_DEVICE_Usb.c` (préfixe `MPP_*` = Mass-market Phone Proxy) : VIDEO_DATA=125 (D2C), VIDEO_ACK=13 (C2D), NAVDATA=127, EVENT=126, NONACK/ACK/EMERGENCY=10/11/12, port=43210. **Chan 4/5/6 (STREAM_DATA/CONTROL/RTP) sont pour Anafi/Disco (ARStream2 resender), PAS Bebop 2**. Format frame v1 reçue : `frameNum u16 LE + frameFlags u8 + fragNum u8 + fragsPerFrame u8 + chunk H.264 ~1KB`.
- **NE PAS déclarer `arstream2_client_stream_port` dans le CONN_REQ pour Bebop 2** : ça bascule le drone en ARStream2 (RTP UDP direct vers client:55004) qui ne traverse pas le SC2 MUX. CONN_REQ Bebop 2 doit être v1-only : `{"controller_type":"Phone","controller_name":"...","d2c_port":43210,"arstream_fragment_size":65000,"arstream_fragment_maximum_number":4,"arstream_max_ack_interval":-1,"proto_v_min":1,"proto_v_max":1}`.
- **ACKs ARStream v1 à envoyer périodiquement sur buf 13** : pour qu'le drone continue à streamer. Format à confirmer (probablement `frameNum u16 + ackBitfield`). Sans ACK, le drone risque de stopper le stream après quelques secondes.
- **POMP varint ZigZag pour I32/I64 et simple pour U32/U64** : ne PAS confondre avec encodage fixed LE. STR = tag 0x09 + varint(len incluant `\0`) + bytes + `\0`.
- **MUX_PROTOCOL_VERSION = 2** dans le HANDSHAKE (ctrl_msg id=127 sur chan 0, struct 32 B = id + chanid + 6 args u32). Sans HANDSHAKE, le SC2 n'ouvre pas les canaux ARSDK.
- **ARCommand sur canal 1 = pas POMP** : wrap dans header arsdk_transport v1 7 B (type/id/seq/size LE) puis ARCommand brute `prj_u8 + cls_u8 + cmd_u16 LE + args`. Pour `VideoEnable(1)` Bebop 2 : prj=1, cls=21, cmd=0, arg u8=1. Buffer `C2D_CMD_WITHACK=11`, data_type `WITHACK=4`.
- **Wire format ARSDK transport — V1 vs V2** (sources : `arsdk-ng/libarsdk/include/arsdk/arsdk.h`, `arsdk_transport_mux.c`, `arsdk_cmd_itf2.c`) :
  - V1 (`cmd_itf1`) : header 7B fixe (type u8 + id u8 + seq u8 + f_len u32 LE), **1 ARCommand par trame**, pas de packing
  - V2 (`cmd_itf2`) : header varint version + type u8 + id u8 + seq u16 LE + varint p_len ; payload = ARCommands chacune préfixée par **u16 LE size** (`unpack_cmds()` itère le payload)
  - On négocie V1 dans le `CONN_REQ` JSON (`{"proto_v_min":1,"proto_v_max":1}`) — donc le SC2 répond en V1
  - `ARSDK_TRANSPORT_DATA_TYPE_MAX = 10` : si premier byte d'une trame < 10, c'est V1 (type direct). Si = `varuint(12)` = `0x0c`, c'est V2.
- **Project IDs ARSDK** confirmés via `arsdk-xml/xml/skyctrl.xml` : `skyctrl` = **prj 4** (pas 8 contrairement à des suppositions courantes). `common=0, ardrone3=1, minidrone=2, jpsumo=3`.
- **Tuples SC2 utiles** (`(prj,cls,cmd)`) :
  - `(4,8,4)` `AttitudeChanged` — 4 floats quaternion, **NOACK haute fréquence** (~16 Hz), poussé dès qu'on est connecté en chan 1, même sans drone. Bon signal "SC2 vivant".
  - `(4,8,0)` `BatteryChanged` — 1 u8 percent, **WITHACK** (donc bufferId=126, pas 127), poussé **uniquement quand le % change**. Invisible si batterie stable (ex: 100% en charge).
  - `(4,6,0)` `skyctrl.Common.AllStates` — requête pour forcer le re-push de tous les states SC2.
- **Forme exacte d'une frame ARCommand chan 1 V1** vérifiée : `02 7f <seq> <f_len_u32_LE> <prj> <cls> <cmd_u16_LE> <args>`. Exemple `02 7f 71 9e 00 00 00 04 08 04 00 73 cf 71 …` = NOACK buf 127, seq 0x71, f_len 158, ARCommand AttitudeChanged avec 4 floats.
- **Sequence ARSDK par bufferId, pas globale** : `arsdk_cmd_itf1` maintient un compteur seq distinct par bufferId. Un PCMD à 20 Hz (buf 10 NOACK) ne doit PAS faire avancer la seq des WITHACK (buf 11). Utiliser `Map<bufferId, AtomicInteger>` côté implémentation.
- **PCMD doit être poussé à 20 Hz minimum** : si silence > ~200 ms le drone considère qu'il n'y a plus de pilote et bloque les inputs. Tuple `(1,0,2)`, args `flag u8 + roll/pitch/yaw/gaz i8×4 + timestamp u32 LE`, NOACK buf 10. Flag=1 si l'utilisateur bouge un stick, 0 sinon.
- **Emergency = buffer HIGHPRIO (12), pas WITHACK (11)** : pour shortcut la queue WITHACK qui peut être saturée. Tuple `(1,0,4)` no args.
- **Classes ardrone3 pour la vidéo** (source `arsdk-xml/xml/ardrone3.xml`) : `cls=21` MediaStreaming (commands : `cmd=0` VideoEnable u8, `cmd=1` VideoStreamMode enum), `cls=22` MediaStreamingState (events : `cmd=0` VideoEnableChanged enum 0=enabled/1=disabled/2=error, `cmd=1` VideoStreamModeChanged enum). **`cls=24` et `cls=25` n'existent PAS** en ardrone3 — si on les voit dans des stats c'est une mauvaise identification.
- **Le SC2 filtre les ARCommands `prj=4 (skyctrl)` et ne les forwarde PAS au drone** (filtre `ARCOMMANDS_Filter` côté SC2). Donc `AllStates SKYCTRL (4,6,0)` ne sert qu'à ré-interroger le SC2 lui-même ; pour forcer le drone à re-push ses states (batterie drone, firmware), utiliser `AllStates COMMON (0,4,0)` qui est forwardée.
- **Détecter le handshake MUX abouti** : le SC2 N'envoie PAS un `HANDSHAKE id=127` en retour. Il ouvre directement les canaux via `CHANNEL_OPEN id=0`. Donc `ctrlHistory.any{ it.id==127 }` est faux — utiliser `ctrlHistory.isNotEmpty()`.
- **Tuple firmware drone** : `(prj=0, cls=5, cmd=0)` ProductVersionChanged = 2 strings null-terminated (software, hardware). Taille variable donc ne PAS mettre dans la table `argsSize` fixe — capturer dans le path "tuple inconnu" et parser à part.
- **Pour Bebop 2, la vidéo ne sortira PAS avec un simple CONN_REQ v1 + VideoEnable(1)** (vérifié session 6) — il manque probablement : (a) `VideoStreamMode(0=low_latency)` avant VideoEnable, ou (b) le firmware bascule v2 par défaut, ou (c) mppd (daemon SC2) modifie le CONN_REQ. À investiguer en lisant les indicateurs UI ajoutés en v9 : `VideoEnable répondu ?`, `VideoStreamMode`, `Firmware drone`.

## Build & déploiement

- Aucun Android Studio installé. Build uniquement en CLI : `./gradlew assembleDebug` dans `android/`.
- Install : `~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Java 21 OK avec AGP 8.7.x / Kotlin 2.0.21.

## Pièges Android 14+ confirmés

- **`/proc/net/route` interdit par SELinux** aux apps untrusted → `EACCES`. Wrap dans try/catch et utiliser `ConnectivityManager.getLinkProperties().routes` à la place.
- **`BroadcastReceiver` dynamique** doit utiliser `Context.RECEIVER_NOT_EXPORTED` sur SDK 33+.
- **USB Accessory** : déclarer un intent-filter `USB_ACCESSORY_ATTACHED` + `res/xml/accessory_filter.xml` (`<usb-accessory manufacturer="Parrot" model="Skycontroller 2"/>`) pour que l'app se lance toute seule au branchement.
- **Android 15 (targetSdk=35) edge-to-edge forcé par défaut** : le contenu passe sous status bar/nav bar sauf si on gère explicitement les insets. Options : `Modifier.systemBarsPadding()` pour pousser le contenu sous, OU mode immersif via `WindowCompat.setDecorFitsSystemWindows(window, false)` + `WindowInsetsControllerCompat(window, window.decorView).hide(systemBars())`.
- **Après un changement dans `MainActivity.onCreate()`**, faire `adb shell am force-stop io.dayd.bebop` avant de relancer, sinon Android peut restaurer l'ancienne instance sans réexécuter onCreate.

## Workflow itérations

Le port USB du Pixel sert au SC2 pendant les tests : pour installer une nouvelle build, débrancher SC2 → brancher câble PC → `adb install` → débrancher → rebrancher SC2.
**ADB sans fil testé et abandonné** sur ce Pixel : le port de connexion timeout malgré pairing réussi (Android ferme le port à la veille). Préférer un screenshot via `~/Téléchargements/` quand on veut voir l'écran de l'app.

## Câblage

Câble USB-A → USB-C standard "data" suffit (le SC2 est USB host, le Pixel est USB device). Pas besoin de câble OTG.

## Bebop 2 + Skycontroller 2 — usage matériel

- Ordre d'allumage : **drone d'abord** (attendre LED fixe), **SC2 ensuite** (attendre LED verte fixe = connecté au drone).
- Si LED SC2 reste orange clignotante : pas connecté. Reset usine SC2 = débrancher batterie 1-2 min.
- L'appairage initial drone↔SC2 nécessitait FreeFlight Pro à l'origine (qui ne marche plus). Si le SC2 a déjà été apparié au drone, il se reconnecte tout seul.
