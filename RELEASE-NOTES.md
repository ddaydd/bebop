# Release Notes

## 2026-08-15 (session 12)

### Les réglages de performance marchent aussi en mode manette
- Les préréglages **Modéré** / **Max** n'existaient que sur la voie Wi-Fi directe : en mode SC2, le drone restait à ses réglages « débutant » d'usine (13 °/s de rotation pour un maximum de 200 — un tour sur soi-même en 28 s), sans aucun moyen de les changer depuis l'app.
- Rien de spécifique au SC2 n'était en cause : les commandes de réglage sont des ARCommands `ardrone3` ordinaires, que la manette **relaie** au drone (contrairement aux `prj=4 skyctrl`, qu'elle garde pour elle). Il manquait seulement le parsing des `*SettingsState` et les envois côté `AoaController`.
- Le type `PerfSetting` (courant, min, max + `throttled` + `at(fraction)`) et les tuples `(1,12,0)`, `(1,12,1)`, `(1,6,1)` remontent dans `arsdk/ArsdkTransport.kt` : les deux voies décodent les mêmes 3 floats avec le même code, et les bornes restent celles annoncées par le drone — jamais une constante en dur.
- `common.Settings.AllSettings (0,2,0)`, qui fait repousser ces bornes, était envoyé en dur aux deux endroits : extrait en `sendAllSettings()` de chaque côté. Sur la voie SC2 il fait déjà partie de la séquence d'init jouée quand le drone s'accroche.
- **Card « Performances du drone » à part, visible dans les deux modes** (elle était enfermée dans la carte « connexion directe »). `DroneViewModel` combine les deux voies comme pour la batterie — le direct prime quand il est actif — et `perfModerate()` / `perfMax()` dispatchent vers la voie en service.
- Boutons **désactivés tant que le drone n'a pas annoncé ses bornes**, avec le libellé « Limites inconnues ». Sans bornes il n'y a rien à interpoler, et la commande partirait dans le vide — sur cette voie, une ARCommand envoyée à un pair injoignable ne produit aucune erreur. Bouton **Relire** pour rejouer `AllSettings` si le drone s'est accroché après la séquence d'init.
- **Pas encore testé sur le matériel** : l'app compile, la voie SC2 n'a pas été exercée depuis la réparation Wi-Fi de la manette. À vérifier au prochain vol : les trois valeurs doivent s'afficher en mode manette, puis passer à 105 °/s / 20° / 3,25 m/s après un tap sur « Modéré ».

## 2026-08-05 (session 11)

### Le mode de vol est choisi au lancement, plus deviné
- Nouvel écran `ModeScreen` en premier plan : **Avec la manette (SC2)** ou **Téléphone seul (Wi-Fi direct)**. Rien n'est tenté avant la réponse.
- Avant, `retryAutoConnect()` décidait seul : SC2 branché → AOA, sinon Wi-Fi. Un SC2 mal détecté basculait en Wi-Fi et affichait « connecté » alors que la manette était censée piloter — l'échec désignait le mauvais coupable.
- Une seule voie est essayée par mode. `FlightMode` (`Sc2` / `Phone`) dans `DroneViewModel`, `chooseMode()` / `leaveMode()`.
- L'écran reflète le matériel en temps réel (sondage 1,5 s) : manette branchée ou non, Wi-Fi du téléphone allumé ou non, avec pastille verte/ambre.
- Retour au choix par **CHANGER DE MODE** (overlay d'échec du pilotage) et **Changer de mode** (écran debug). `leaveMode()` coupe les deux voies avant de rendre la main : garder la session ouverte côté drone le ferait refuser toute reconnexion pendant ~30 s.

### L'app rejoint elle-même le Wi-Fi du drone
- Nouveau `network/DroneWifi.kt`. En mode téléphone seul : si le téléphone est déjà sur l'AP du drone on le réutilise, sinon `WifiNetworkSpecifier` avec motif de SSID `Bebop2-` → Android affiche sa popup, ne propose que le drone, et le réseau obtenu est réservé à l'app.
- `DirectController.findWifiNetwork()` prenait **n'importe quel** Wi-Fi : sur le réseau de la maison, la discovery échouait six fois avant d'annoncer « drone introuvable », alors que le vrai problème était le réseau. Remplacé par `obtainDroneNetwork()`.
- Détection de l'AP par l'adresse DHCP (`192.168.42.x`) et non par le SSID : le lire demanderait la permission de localisation, et sonder le port 44444 risquerait de consommer la session de discovery que le drone n'accorde qu'une fois.
- La callback du specifier reste enregistrée toute la session — la désinscrire coupe l'association. `release()` à la déconnexion.
- Wi-Fi éteint : l'app ne peut plus le rallumer (Android 10+), elle le dit et ouvre le panneau système.
- **Validé terrain** : depuis le Wi-Fi de la maison, un tap sur « Téléphone seul » → popup → un tap sur `Bebop2-087996` → `Discovery OK` 56 ms plus tard, batterie 79 %, vidéo 864x480 `drop=0`.

### Batterie du drone en mode manette — cause réelle : les ACK du canal SC2
- Symptôme : en mode SC2, seul le pourcentage de la manette s'affichait.
- **L'acquittement des trames `WITHACK` était malformé sur la voie AOA** : le numéro de séquence acquitté était placé dans l'en-tête, avec un payload vide, au lieu d'être dans le **payload** (l'en-tête portant le compteur propre au buffer d'ACK). Le SC2 n'y reconnaissait pas l'acquittement et attendait son timeout avant chaque message suivant.
- Mesuré sur les traces du 2026-08-05, mêmes états `SettingsState` dans les deux modes : **3 ms** entre deux messages en Wi-Fi direct, **903 ms** via le SC2. La rafale d'`AllStates` fait des dizaines de messages — à ce rythme elle était perdue en quasi-totalité, dont `BatteryStateChanged`.
- Le format correct était déjà implémenté côté Wi-Fi direct (`DirectController.kt:665`) ; `AoaController` s'aligne dessus.
- Ce défaut ralentissait **tout** le canal d2c WITHACK du mode manette, pas seulement la batterie.

#### Fausses pistes écartées en chemin (vérifiées, pas supposées)
- « Le SC2 filtre `AllStates` » : non. `AllStates (0,4,0)` est bien relayé, et le tuple est correct — en Wi-Fi direct la batterie arrive **121 ms** après son envoi, suivie de toute la rafale d'états.
- « C'est la manette qui répond à la place du drone » : non. Le dump des arguments montre le nom `Bebop2-087996`, le serial `PI040384AG7C087996` et le firmware `4.7.1 / HW_03` — c'est bien le drone.
- « Les batteries observées n'étaient que des pushes spontanés sur changement de pourcentage » : non, l'écart de 121 ms le réfute.

#### Envoi au bon moment (nécessaire mais pas suffisant)
- Symptôme : en mode SC2, seul le pourcentage de la manette s'affichait.
- Cause : `AllStates COMMON (0,4,0)` était envoyé juste après le `CONN_RESP`, alors que le SC2 est encore en `searching`. Les traces du 2026-08-05 montrent l'envoi à 16:27:07 et le premier octet venu du drone à **16:28:17** — 70 s plus tard. La demande partait dans le vide, et le drone n'envoie `BatteryStateChanged (0,5,1)` que lorsque le pourcentage change : au sol, batterie stable, il ne l'envoie donc jamais.
- Correctif : `AoaController.onDroneLinkConfirmed()` joue la séquence d'init (`CurrentDate`, `CurrentTime`, `AllSettings`, `AllStates`) **au moment où le drone se manifeste** — première ARCommand `prj=1` reçue, ou `drone_manager` qui passe à `connected`. Rejouée si le drone décroche puis revient.
- `DroneViewModel.autoConnect()` ne demande plus que les états du SC2, seul joignable immédiatement.
- `ArCommand.withString()` remonté dans `ArsdkTransport.kt` : les deux voies partagent le même encodage (il était dupliqué en privé dans `DirectController`).
- Piège général : une ARCommand envoyée à un pair injoignable **ne produit aucune erreur**. Même famille que la régression de tuples de la session 8 — le lien reste vivant, seul le state manque.

### Échecs lisibles
- Chaque étape de la séquence SC2 a un délai maximum de 12 s. Sans lui, une manette muette laissait tourner un spinner sans fin, indiscernable d'une app plantée.
- Messages distincts par étape : « SC2 non détecté — vérifier le câble USB », « SC2 muet — pas de réponse au handshake », « Aucun appareil annoncé par le SC2 », « SC2 OK mais drone injoignable — LED verte sur la manette ? ».
- L'écran debug n'affiche plus les lignes USB/SC2 en mode téléphone seul, et son bandeau de statut se base désormais sur les compteurs toutes voies confondues (il restait sur « en attente de données » avec la vidéo en train de tourner).

### Boutons de l'overlay redevenus cliquables
- Les zones de joystick couvrent la moitié basse de l'écran et sont dessinées **après** l'overlay de connexion : elles avalaient les taps destinés à `RÉESSAYER`. Le bouton était inutilisable depuis son ajout.
- Les joysticks ne sont plus rendus tant qu'on n'est pas connecté — sans drone ils ne servent à rien.

### Manette SC2 réparée (matériel)
- Le nettoyage des composants Wi-Fi du SC2 a rétabli la liaison manette ↔ drone, en panne depuis la session 9 (oxydation des patchs antenne, LED orange perpétuelle). La voie AOA reste **à re-tester de bout en bout** : elle n'a plus été exercée depuis.

## 2026-08-04 (session 10)

### BATTERIE DRONE — enfin affichée (90 % → 87 % en direct)
- **Cause racine : les tuples « corrigés » en session 8 étaient une régression.** Les classes de `common.xml` sont `0=Network, 1=NetworkEvent, 2=Settings, 3=SettingsState, 4=Common, 5=CommonState` — pas ce que la session 8 avait supposé.
- Vérification empirique sur le wire : `(0,3,4)`+`(0,3,5)` (serial high/low, 10 B chacun = 9 chars + `\0`) reconstituent exactement `PI040384AG7C087996`, le serial du drone ; `(0,5,7)` = 2 B = `WifiSignalChanged` (i16). Donc cls 3 = SettingsState et cls 5 = CommonState, sans ambiguïté.
- Conséquence du bug : `AllStates` était envoyé sur `(0,0,0)`, une commande qui n'existe pas. Le drone l'ignorait **en silence** — aucun state renvoyé, batterie jamais reçue. Symptôme trompeur : `AllSettings` répondait normalement, le lien restait vivant, seul `AllStates` ne produisait rien.
- `arsdk/ArsdkTransport.kt` : `COMMON_BATTERY` → `(0,5,1)`, `COMMON_ALL_STATES` → `(0,4,0)`, `COMMON_PRODUCT_VERSION` → `(0,3,3)`, ajout `COMMON_CURRENT_DATE (0,4,1)` / `COMMON_CURRENT_TIME (0,4,2)`
- `network/DirectController.kt` : tuples en dur (CurrentDate/Time envoyés sur `(0,0,1)`/`(0,0,2)`, également faux) remplacés par les constantes `ArsdkIds`
- Corrige aussi le chemin SC2/AOA, qui partage les mêmes constantes
- **Validé terrain** : `AllStatesChanged (0,5,0)` reçu, `Batterie drone : 90%` puis `89%`, `88%`, `87%` (décharge en temps réel), `Firmware drone : 4.7.1 / hw HW_03`

### Batterie affichée sur l'écran pilotage, quelle que soit la voie
- `DroneViewModel.anyDroneBattery` : `combine(direct, aoa)` — le Wi-Fi direct prime quand il est actif
- `PilotScreen` lisait uniquement `droneBatteryPercent` (chemin SC2) : le HUD affichait « — » en connexion directe. Bascule sur `anyDroneBattery`.
- `PilotScreen.connected` prend en compte les deux voies (`aoa && connResp` **ou** `directConnected`) — sinon l'overlay « Recherche du SC2… » masquait l'écran en direct

### Observabilité de la connexion directe
- `DirectController` expose `packetsIn`, `lastPacketAt`, `lastTuple` ; log périodique 2 s du total de paquets
- Sans ça, un lien vivant qui n'échange que des PONG/ACK (filtrés avant le log des ARCommands) est **indiscernable** d'un lien mort — c'est ce qui avait fait conclure à tort à une coupure
- Card debug : « Paquets reçus : N — dernier il y a Xs » (vert si < 3 s), dernier tuple reçu, bouton **Redemander états**
- `DirectController.requestAllStates()` public, réutilisé par la séquence de connexion

### VIDÉO EN WI-FI DIRECT — 864x480 à ~30 fps, drone au sol
- La caméra streame dès que le drone est allumé : **aucun décollage nécessaire**. Le blocage était côté app — le pipeline vidéo n'existait que sur le chemin SC2/MUX.
- `DirectController` : `CONN_REQ` déclare `arstream2_client_stream_port: 55004` + `arstream2_client_control_port: 55005`. Le drone accepte et répond `"arstream2_server_stream_port": 5004, "arstream2_server_control_port": 5005`, puis envoie du RTP depuis `192.168.42.1:5004`.
- **C'est l'inverse de la contrainte SC2** : le CLAUDE.md interdit les clés `arstream2_*` via le SC2 parce que le RTP ne traverse pas le MUX. Sans intermédiaire, c'est au contraire la voie naturelle.
- Socket UDP 55004 → `RtpDepayloader` (déjà validé session 7) → `H264Decoder`. Ouvert **avant** `VideoEnable` pour ne pas rater les SPS/PPS des premiers paquets.
- `VideoStreamMode(0)` + `VideoEnable(1)` envoyés automatiquement à la connexion ; `startVideo()`/`stopVideo()` exposés
- Fallback ARStream v1 implémenté (fragments buffer 125 du canal d2c → `ArStreamReader`, ACK sur buffer 13) au cas où un firmware ignorerait les ports arstream2 — non utilisé par le 4.7.1
- `DroneViewModel` : sink `H264Decoder` partagé entre les deux voies, `anyVideoFrames` pour le HUD
- Card debug : « Vidéo : N frames via rtp|arstream-v1 (RTP in=… drop=… NAL=…) » — dit quelle voie le firmware a retenue
- **Validé terrain** : 1er paquet RTP 962 B, 300 frames en 10 s, `SPS/PPS trouvé (27B / 9B)`, `décodeur output: 864x480`, image live à l'écran

### Commandes de vol en Wi-Fi direct
- Les boutons de `PilotScreen` appelaient tous `aoaController` : en direct, l'écran affichait la vidéo mais ne commandait rien
- `DirectController` : `sendTakeoff/Landing/Emergency/FlatTrim/VideoRecord`, `setPilotingInput()`, `centerSticks()`
- La boucle PCMD envoyait des zéros codés en dur — elle lit maintenant les inputs réels, `flag=1` dès que roll ou pitch est non nul. Elle tourne en permanence à 25 Hz : c'est elle qui maintient la liaison.
- `Emergency` part sur HIGHPRIO (shortcut la queue WITHACK potentiellement saturée) et **centre les sticks d'abord**, pour qu'aucun PCMD résiduel ne suive. `Landing` les centre aussi.
- `DroneViewModel` route chaque commande vers la voie active, le direct prioritaire — envoyer sur les deux enverrait tout en double
- En direct, la boucle PCMD tournant déjà, « ARMER » ne démarre rien : il ne conditionne qu'un flag d'armement explicite, sans lequel DÉCOLLER et URGENCE seraient exposés en permanence à un tap près
- `FlyingStateChanged (1,4,1)` parsé et affiché dans le HUD (posé / décollage / stationnaire / en vol / urgence) — l'info la plus importante en pilotage. `VideoStateChangedV2` pour l'indicateur REC.
- Card Pilotage debug : `canPilot` prend en compte la voie directe (boutons plus grisés)
- **Testé au sol uniquement** : `FlatTrim` routé et acquitté par le drone, `FlyingState: 0 (posé)`, armement OK. **Décollage volontairement non déclenché** — reste à valider en vol.

### Auto-connexion au démarrage
- `autoConnect()` était commenté depuis la session 9 : l'app affichait un `Recherche du SC2…` **figé** qui ne correspondait à aucune action, et il fallait aller sur la page debug pour taper « Connecter »
- `retryAutoConnect()` choisit la voie : SC2 branché en USB → AOA (choix matériel explicite de l'utilisateur), sinon Wi-Fi direct (mode nominal tant que le Wi-Fi du SC2 est HS)
- La progression de `DirectController` est recopiée dans `autoStatus`, sinon l'overlay resterait figé pendant les 6 tentatives de discovery
- Overlay pilotage : le spinner s'arrête sur échec et un bouton **RÉESSAYER** apparaît — plus besoin de passer par la page debug
- **Validé** : lancement à froid → vidéo live à l'écran en 0,9 s, sans aucune interaction (`pas de SC2 — voie Wi-Fi directe` → discovery → batterie 75 % → RTP)

### Garde-fous avant le premier vol
- **Sticks inhibés au sol tant que non armé.** La boucle PCMD tournant en permanence en Wi-Fi direct (c'est elle qui maintient la liaison), les joysticks étaient actifs avant même d'avoir armé — sans effet au sol, mais « ARMER » ne conditionnait en réalité que l'affichage de DÉCOLLER. Dès que le drone est en l'air, les sticks répondent **toujours**, armé ou non : perdre le contrôle en vol serait pire que tout ce que le garde-fou pourrait éviter. Un `flyingState` inconnu compte comme « au sol » → le garde-fou ne peut être trop strict qu'au sol, jamais en vol.
- Message « Sticks inactifs — ARMER pour piloter » affiché tant qu'ils sont inhibés : des sticks volontairement inertes passent sinon pour une panne
- **`STOP` renommé `DÉSARMER`** (et gris au lieu de rouge) : il n'a jamais stoppé le drone, il recentre les sticks et remasque DÉCOLLER. En vol le drone reste en stationnaire — pour couper c'est ATTERRIR ou URGENCE. Le libellé doit rester juste sous stress.

### Mode paysage forcé
- `android:screenOrientation="sensorLandscape"` — pilotage à deux pouces sur une vidéo 16:9. `sensorLandscape` (et pas `landscape`) pour pouvoir retourner le téléphone selon le côté d'où sort le câble.
- `configChanges="orientation|screenSize|screenLayout|keyboardHidden"` : sans ça une rotation recrée l'activité, ce qui **couperait la liaison au drone en plein vol**

### Réglages de performance — le drone était bridé d'usine
- Symptôme : rotation sur soi-même très lente en vol. Cause : `MaxRotationSpeed` à **13 °/s** pour un maximum de **200** (tour complet en 28 s), `MaxTilt` 8°/35, `MaxVerticalSpeed` 1,0/6,0 m/s. Réglages « débutant » d'usine — rien à voir avec le chemin PCMD.
- Les `*SettingsState` portaient déjà l'info : **3 floats (courant, min, max)**. Parsing de `(1,12,0)`, `(1,12,1)` et `(1,6,1)` ; affichage « courant / max » avec alerte orange quand le drone tourne en dessous de sa capacité.
- `applyPerformance(fraction)` interpole sur la plage annoncée au lieu de sauter au plafond : 200 °/s avec 35° d'inclinaison rend l'appareil difficile à tenir, ce qu'on ne met pas entre les mains de quelqu'un pour un premier vol. Deux préréglages : **Modéré** (mi-plage) et **Max**, avec la mise en garde affichée à côté du bouton.
- Encodeurs `ArCommand.maxRotationSpeed/maxVerticalSpeed/maxTilt` (un float LE)
- **Validé** : 13 → 105 °/s, 8 → 20°, 1,0 → 3,25 m/s, chaque valeur réémise par le drone en confirmation. Les settings sont persistés dans le drone.

### Retour au point de départ + libellé d'urgence corrigé
- **`URGENCE` renommé `COUPER MOTEURS`** : la commande envoyée est `Piloting.Emergency` `(1,0,4)`, qui coupe les moteurs — le drone **tombe**. Beaucoup d'apps grand public mettent un retour maison derrière un bouton rouge d'urgence : le libellé invitait à l'hypothèse exactement inverse, au pire moment.
- **Vrai RTH ajouté** : `ArCommand.navigateHome(start)` = `(1,0,5)` + u8. Bouton `RETOUR` distinct, qui sert aussi à annuler pendant un retour en cours, + `RETOUR EN COURS` dans le HUD.
- Le RTH dépend du GPS (sans fix au décollage, pas de position maison et la commande ne fait rien) : l'état est donc **exposé, pas supposé** — `NavigateHomeStateChanged (1,4,3)`, `HomeChanged (1,24,0)` (sentinelle Parrot **500** = pas de position), `GPSFixStateChanged (1,24,2)`
- Le HUD indique si le retour est disponible **avant** le décollage (`GPS ✓ retour OK` / `GPS — pas de retour`), et le bouton est désactivé sinon : un bouton qui échoue en silence en plein vol est pire que pas de bouton
- **Validé au sol** : position maison d'un vol précédent toujours en mémoire, `NavigateHomeState: 2 (indisponible)` en intérieur, bouton correctement grisé

### Post-mortem du premier vol (2026-08-04, ~16:04) — batterie HS
Séquence relevée dans `bebop-log-20260804-155848.txt` :
```
16:04:13.9  vol normal
16:04:14.48 batterie 31%
16:04:15.01 batterie 25%    ← 11 points en 770 ms
16:04:15.06 (1,4,2) AlertStateChanged
16:04:15.10 FlyingState: 5 (urgence)   ← le drone coupe seul
16:04:18.1  posé
```
- **Cause : effondrement de tension**, pas une décharge. Résistance interne trop élevée → sous charge moteurs la tension s'écroule, le drone déclenche son cut-out. La batterie est à remplacer. Le « drone parti d'un coup à droite » est le décrochage asymétrique des moteurs juste avant la coupure — pas une commande partie de travers.
- Indice rétrospectif : 90 % → 54 % en 45 min **au sol**, puis 54 % → 31 % en quelques minutes de vol.

### Deux défauts révélés par l'incident
- **Alertes drone ignorées** : `(1,4,2)` AlertStateChanged était reçu et logué comme un tuple anonyme. Désormais parsé (0=none 1=user 2=cut_out 3=critical_battery 4=low_battery 5=too_much_angle) et affiché en bandeau rouge en haut de l'écran de pilotage.
- **Perte de liaison invisible** : après la coupure, la boucle PCMD a continué d'émettre à 25 Hz dans un réseau mort pendant que l'écran gardait sa dernière image et affichait toujours `Live` — indiscernable d'une app figée, et c'est exactement comme ça que ça a été vécu. Watchdog : 2 s sans paquet → statut `Sans réponse` + bandeau `LIAISON PERDUE`.
- Log des échecs d'envoi limité à une ligne / 2 s : à 25 Hz ils avaient rempli le fichier de milliers de lignes identiques, noyant la fenêtre utile à l'analyse.
- ⚠️ **Non vérifié sur matériel** : aucune alerte n'a été levée au banc et le drone est éteint — ni le bandeau d'alerte ni le watchdog n'ont donc été déclenchés pour de vrai.

### Pièges documentés
- Le drone ne libère le port 44444 que sur déconnexion propre : un `force-stop` laisse la session ouverte (toujours fermée 10 min après). Cliquer « Déconnecter » avant de réinstaller l'APK.
- `ping`/`nc` depuis `adb shell` partent par `rmnet1` (données mobiles) → faux négatifs. Forcer `ping -I wlan0`.
- La batterie peut mettre ~1 min à arriver quand `AllStates` part en même temps que l'activation vidéo (réponse noyée ou perdue en UDP). Le bouton « Redemander états » force le push.

## 2026-05-26 (session 9)

### Connexion directe Wi-Fi Pixel → Bebop 2 (sans SC2)
- `network/DirectController.kt` (nouveau) : connexion TCP discovery (port 44444) + transport UDP ARCommands directement entre le Pixel et le drone via Wi-Fi
- Gestion réseau Android : `ConnectivityManager.requestNetwork()` pour trouver le Wi-Fi, `Network.bindSocket()` pour forcer le socket UDP sur le bon réseau (Android route par défaut sur les données mobiles quand le Wi-Fi n'a pas d'internet)
- Retry automatique (6 tentatives, 3s entre chaque) pour gérer le `ECONNREFUSED` quand la session précédente n'a pas expiré
- Boucle PCMD à 25Hz (40ms) obligatoire pour maintenir la connexion vivante — sans PCMD le drone considère le client mort après ~200ms
- Envoi `CurrentDate (0,0,1)` + `CurrentTime (0,0,2)` avant AllSettings/AllStates — requis par le protocole ARSDK pour l'initialisation complète
- Boucle ping à 500ms + réponse pong aux pings du drone
- Parsing batterie drone `(0,1,1)` et firmware `(0,5,0)` — batterie pas encore confirmée (à tester demain)
- Card UI "Connexion directe Wi-Fi" sur l'écran debug avec statut, batterie, firmware, boutons Connecter/Déconnecter

### Diagnostic SC2 — module Wi-Fi probablement HS
- Le SC2 connaît le drone (serial PI040384AG7C087996, Bebop2-087996) mais reste en état "searching" — ne trouve pas le Wi-Fi du drone
- Feature 137 (drone_manager) supportée par le SC2 : parsing `connection_state`, `drone_list_item`, commandes `discover_drones` et `connect`
- Commandes Wi-Fi legacy `(4,1,0)` RequestWifiList et drone_manager discover envoyées mais **aucune réponse** du SC2
- Ouverture physique du SC2 : carte MPP_MB_07, antennes patch intégrées au PCB (pas de câble U.FL), traces d'oxydation sur les 4 patchs antenne — suspect pour la défaillance Wi-Fi intermittente
- Recherche internet : piste principale = connecteur/ampli Wi-Fi dégradé, nettoyage alcool isopropylique recommandé

### FileLogger — logs persistants sur fichier
- `FileLogger.kt` (nouveau) : écrit les logs dans `/sdcard/Android/data/io.dayd.bebop/files/bebop-log-*.txt` avec autoFlush
- Remplace `android.util.Log` dans `AoaController` et `DroneViewModel` via `import io.dayd.bebop.FileLogger as Log`
- Permet de récupérer les logs via `adb pull` même après débranchement/rebranchement USB

### Nettoyage UI — DebugScreen simplifié
- Card "Statut global" en haut avec message clair (vert/orange/rouge) + un seul bouton principal
- Card "SC2 → Drone" avec drone_manager state, boutons Chercher drones / Scan Wi-Fi
- Viré : champ IP + bouton "Connecter" (approche RNDIS abandonnée), UsbCard, DiagnosticCard réseau, boutons manuels Handshake/Discover/Probe
- Section "Avancé" dépliable pour les boutons debug restants
- Boutons pilotage grisés tant que le drone n'est pas connecté

### Bugfix auto-connect
- `autoConnect()` ne rappelle plus `connectFirstAvailable()` si l'AOA est déjà ouvert — évite un reset de ctrlHistory/devices qui causait un deadlock
- Skip automatique de chaque étape déjà complétée (handshake, discover, connect)

### Permissions Android
- Ajout `CHANGE_NETWORK_STATE` et `ACCESS_WIFI_STATE` dans le manifest (requis pour `ConnectivityManager.requestNetwork`)

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
