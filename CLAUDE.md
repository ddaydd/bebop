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
- **Bebop 2 vidéo = RTP brut sur chan 4 MUX** (corrigé session 7, 2026-05-25 — la doc officielle §6.2.5 décrit le comportement v1/buf 125 mais ce firmware SC2 utilise le resender ARStream2). Le SC2 ouvre proactivement chan 4/5 et y envoie des paquets RTP bruts (PAS POMP-wrapped). Détection : `byte[0] & 0xC0 == 0x80` → `rtpDepayloader`. SPS/PPS dans STAP-A (NAL type 24). Résolution 864x480.
- **ACK transport OBLIGATOIRE pour les frames WITHACK (buf 126)**, et **le seq acquitté va dans le PAYLOAD, pas dans l'en-tête** : trame `dataType=ACK`, `bufferId|0x80`, `seq` = compteur propre au buffer d'ACK, payload = 1 octet contenant le seq de la trame acquittée. Sans ACK du tout, le SC2 retransmet en boucle et bloque la communication (blocker #1 de la vidéo). Avec un ACK **malformé** (seq dans l'en-tête, payload vide), la panne est bien plus sournoise : ça marche, mais chaque message WITHACK attend le timeout de retransmission — mesuré **903 ms entre deux états** via SC2 contre **3 ms** en Wi-Fi direct — et les rafales (réponse à `AllStates`) sont perdues en quasi-totalité. Symptôme observé : batterie drone jamais affichée en mode manette, alors que le lien, la vidéo et le pilotage fonctionnent (session 11, 2026-08-05).
- **NE PAS déclarer `arstream2_client_stream_port` dans le CONN_REQ pour Bebop 2** : ça bascule le drone en ARStream2 (RTP UDP direct vers client:55004) qui ne traverse pas le SC2 MUX. CONN_REQ Bebop 2 doit être v1-only : `{"controller_type":"Phone","controller_name":"...","d2c_port":43210,"arstream_fragment_size":65000,"arstream_fragment_maximum_number":4,"arstream_max_ack_interval":-1,"proto_v_min":1,"proto_v_max":1}`.
- **ACKs ARStream v1 sur buf 13** : non utilisés en pratique (la vidéo passe par RTP chan 4, pas buf 125). Conservé pour référence.
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
- **Via le SC2, le drone n'est joignable que BIEN APRÈS le `CONN_RESP`** : la manette répond immédiatement, mais elle peut passer plus d'une minute en `drone_manager connection_state: searching` avant d'accrocher le drone (mesuré 70 s le 2026-08-05). Toute ARCommand destinée au drone envoyée dans cet intervalle **part dans le vide, sans erreur**. Ne jamais envoyer `AllStates COMMON` juste après le `CONN_RESP` : attendre la première ARCommand `prj=1 (ardrone3)` reçue, ou `drone_manager` à l'état `connected` (3). Symptôme sinon : batterie SC2 affichée, batterie drone jamais (le drone ne push `(0,5,1)` que quand le pourcentage change).
- **Le SC2 filtre les ARCommands `prj=4 (skyctrl)` et ne les forwarde PAS au drone** (filtre `ARCOMMANDS_Filter` côté SC2). Donc `AllStates SKYCTRL (4,6,0)` ne sert qu'à ré-interroger le SC2 lui-même ; pour forcer le drone à re-push ses states (batterie drone, firmware), utiliser `AllStates COMMON (0,0,0)` qui est forwardée.
- **Classes de `common.xml` (prj=0) — vérifiées sur le wire session 10, 2026-08-04** : `0=Network, 1=NetworkEvent, 2=Settings, 3=SettingsState, 4=Common, 5=CommonState`. Donc :
  - `Common.Common.AllStates` = **(0,4,0)**, `CurrentDate` = **(0,4,1)**, `CurrentTime` = **(0,4,2)**
  - `Common.CommonState.AllStatesChanged` = **(0,5,0)**, `BatteryStateChanged` = **(0,5,1)**, `WifiSignalChanged` = **(0,5,7)** (i16)
  - `Common.SettingsState.ProductVersionChanged` = **(0,3,3)** (2 strings)
  - **Preuve irréfutable** : `(0,3,4)` + `(0,3,5)` (serial high/low) font 10 B chacun = 9 chars + `\0`, et concaténés donnent exactement `PI040384AG7C087996`, le serial du drone (18 car.). Donc cls 3 = SettingsState, cls 5 = CommonState.
  - ⚠️ **La « correction » de la session 8 était une régression** (elle affirmait cls=1 CommonState et cls=0 Common). Conséquence : `AllStates` était envoyé sur `(0,0,0)`, commande inexistante que le drone **ignore en silence** — d'où zéro state renvoyé et batterie jamais reçue, pendant 2 sessions. Symptôme trompeur : le lien reste vivant (PING/PONG, `AllSettings` répond normalement), seul `AllStates` ne produit rien.
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
- **Panne Wi-Fi du SC2 — RÉSOLUE par nettoyage des composants Wi-Fi (2026-08-05)**. Historique : le SC2 restait en LED orange clignotante ("searching"), connaissait le drone (serial PI040384AG7C087996) mais ne captait pas son Wi-Fi même à 1 mètre — oxydation des patchs antenne. Le nettoyage a rétabli la connexion drone↔SC2. Si le symptôme revient, refaire un nettoyage avant de suspecter le firmware ou l'appairage.
- **Pas de port micro-USB** visible sur ce SC2 (carte MPP_MB_07). Pas d'accès ADB au SC2.

## Connexion directe Pixel → Bebop 2 (Wi-Fi, sans SC2)

- Le Bebop 2 crée un AP Wi-Fi `Bebop2-087996` en 2.4 GHz, sans sécurité, IP drone = 192.168.42.1.
- **Android route par défaut via données mobiles** quand le Wi-Fi n'a pas d'internet. Utiliser `ConnectivityManager.requestNetwork(WIFI)` + `Network.bindSocket()` pour forcer le trafic sur le bon réseau.
- Le drone n'accepte qu'**une seule session discovery** à la fois. Port 44444 refuse les connexions tant que la session précédente n'a pas timeout (~30s). Ou redémarrer le drone.
- **Séquence d'init obligatoire** (source : bybop, pyparrot, libARController) : CurrentDate `(0,4,1)` + CurrentTime `(0,4,2)` → AllSettings `(0,2,0)` → attendre AllSettingsChanged `(0,3,0)` → AllStates `(0,4,0)` → attendre AllStatesChanged `(0,5,0)`, puis la batterie arrive sur `(0,5,1)`.
- **Le drone ne libère le port 44444 que sur déconnexion propre** : un `am force-stop` de l'app laisse la session ouverte côté drone et le port refuse toute nouvelle connexion (`ECONNREFUSED`) — observé toujours fermé 10 min après. Toujours cliquer « Déconnecter » avant de réinstaller l'APK ; sinon power-cycle du drone. Après un `disconnect()` propre, la reconnexion passe du 1er coup.
- **Tester la joignabilité du drone depuis `adb shell` donne des faux négatifs** : `ping`/`nc` depuis le shell partent par `rmnet1` (données mobiles) car le Wi-Fi du drone n'a pas d'internet — `ip route get 192.168.42.1` le montre. Toujours forcer l'interface : `ping -I wlan0 192.168.42.1`. L'app, elle, s'en sort avec `Network.bindSocket()`.
- **PCMD à 25Hz obligatoire** même avec sticks à zéro (flag=0). Sans PCMD, le drone considère le client mort après ~200ms et coupe la connexion UDP.
- **Vidéo en direct = ARStream2 / RTP UDP, et il FAUT déclarer les clés `arstream2_*`** (l'inverse exact de la règle SC2, où elles cassent tout parce que le RTP ne traverse pas le MUX). Mettre `"arstream2_client_stream_port":55004,"arstream2_client_control_port":55005` dans le JSON de discovery ; le drone répond `"arstream2_server_stream_port":5004` et envoie le RTP depuis `192.168.42.1:5004` vers le port client. Ensuite `VideoStreamMode(0)` + `VideoEnable(1)` → `RtpDepayloader` → `H264Decoder`, 864x480 ~30 fps.
- **Ouvrir le socket vidéo AVANT d'envoyer `VideoEnable`** : les SPS/PPS sont dans les tout premiers paquets RTP (STAP-A) ; ratés, le décodeur reste bloqué en « attente SPS/PPS » sans erreur.
- **La caméra streame drone posé au sol**, aucun décollage nécessaire — pratique pour tester le pipeline vidéo sans risque.
- **Ce Bebop 2 sort d'usine avec des réglages « débutant » qui donnent l'impression d'un bug** : `MaxRotationSpeed` = **13 °/s** pour un maximum de 200 (tour complet en 28 s !), `MaxTilt` 8° sur 35, `MaxVerticalSpeed` 1 m/s sur 6. Avant de chercher un problème dans le PCMD, lire les réglages : les `*SettingsState` portent **3 floats (courant, min, max)** — `SpeedSettingsState` `(1,12,0)` MaxVerticalSpeed / `(1,12,1)` MaxRotationSpeed, `PilotingSettingsState` `(1,6,1)` MaxTilt. Commandes correspondantes : `(1,11,0)`, `(1,11,1)`, `(1,2,1)`, un seul arg float LE.
- Les settings sont **persistés dans le drone** : une fois poussés ils survivent au redémarrage, pas besoin de les renvoyer à chaque vol.
