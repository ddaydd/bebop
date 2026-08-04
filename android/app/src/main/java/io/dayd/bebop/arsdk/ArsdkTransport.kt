package io.dayd.bebop.arsdk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodage des trames arsdk_transport v1 (canal MUX 1).
 * Header 7 bytes : type(u8) + id(u8) + seq(u8) + size(u32 LE incluant le header).
 * Référence : `arsdk-ng/libarsdk/src/mux/arsdk_transport_mux.c:434-442`.
 */
object ArsdkTransport {

    const val DATA_TYPE_ACK = 1
    const val DATA_TYPE_NOACK = 2
    const val DATA_TYPE_LOWLATENCY = 3
    const val DATA_TYPE_WITHACK = 4

    const val BUFFER_ID_PING = 0
    const val BUFFER_ID_PONG = 1
    const val BUFFER_ID_C2D_CMD_NOACK = 10
    const val BUFFER_ID_C2D_CMD_WITHACK = 11
    const val BUFFER_ID_C2D_CMD_HIGHPRIO = 12
    const val BUFFER_ID_D2C_CMD_WITHACK = 126
    const val BUFFER_ID_D2C_CMD_NOACK = 127
    const val BUFFER_ID_ACKOFF = 128

    const val HEADER_SIZE = 7
    const val HEADER_EXTENDED_SIZE = 11   // certaines variantes ajoutent un timestamp u32

    fun encode(dataType: Int, bufferId: Int, seq: Int, payload: ByteArray): ByteArray {
        val total = HEADER_SIZE + payload.size
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(dataType.toByte())
        buf.put(bufferId.toByte())
        buf.put(seq.toByte())
        buf.putInt(total)
        buf.put(payload)
        return buf.array()
    }

    /**
     * Décode toutes les trames arsdk_transport présentes dans un buffer.
     * Un MUX frame chan 1 peut contenir plusieurs ARCommands concaténées
     * (chacune avec son header transport). On itère jusqu'à épuisement.
     */
    fun decodeAll(buf: ByteArray): List<ArsdkFrame> {
        val out = ArrayList<ArsdkFrame>()
        var i = 0
        while (i + HEADER_SIZE <= buf.size) {
            val dataType = buf[i].toInt() and 0xff
            val bufferId = buf[i + 1].toInt() and 0xff
            val seq = buf[i + 2].toInt() and 0xff
            val size = ByteBuffer.wrap(buf, i + 3, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (size < HEADER_SIZE || i + size > buf.size) break
            val body = buf.copyOfRange(i + HEADER_SIZE, i + size)
            out += ArsdkFrame(dataType, bufferId, seq, body)
            i += size
        }
        return out
    }
}

data class ArsdkFrame(
    val dataType: Int,
    val bufferId: Int,
    val seq: Int,
    val body: ByteArray,
)

/**
 * Encodage d'une ARCommand : prj(u8) + cls(u8) + cmd(u16 LE) + args.
 * Pas de magic, pas de POMP — le buffer brut devient le payload de la frame transport.
 * Référence : `arsdk-ng/libarsdk/src/arsdk_encoder.c:256-269`.
 */
object ArCommand {

    /** Encode une ARCommand sans args (juste le header). */
    fun noArgs(prj: Int, cls: Int, cmd: Int): ByteArray {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(prj.toByte())
        buf.put(cls.toByte())
        buf.putShort(cmd.toShort())
        return buf.array()
    }

    fun videoEnable(enable: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(1.toByte())              // project ardrone3 = 1
        buf.put(21.toByte())             // class MediaStreaming = 21
        buf.putShort(0.toShort())        // cmd VideoEnable = 0
        buf.put(if (enable) 1 else 0)    // arg enable u8
        return buf.array()
    }

    fun videoStreamMode(mode: Int): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(1.toByte())              // project ardrone3 = 1
        buf.put(21.toByte())             // class MediaStreaming = 21
        buf.putShort(1.toShort())        // cmd VideoStreamMode = 1
        buf.putInt(mode)                 // arg mode enum u32 (0=low_latency, 1=high_reliability)
        return buf.array()
    }

    fun videoRecord(start: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(1.toByte())              // project ardrone3 = 1
        buf.put(3.toByte())              // class MediaRecord = 3
        buf.putShort(1.toShort())        // cmd VideoV2 = 1
        buf.putInt(if (start) 1 else 0)  // arg record enum u32 (0=stop, 1=start)
        return buf.array()
    }

    /** Encode une ARCommand à un seul arg float (IEEE 754 LE). */
    private fun oneFloat(prj: Int, cls: Int, cmd: Int, value: Float): ByteArray {
        val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(prj.toByte())
        buf.put(cls.toByte())
        buf.putShort(cmd.toShort())
        buf.putFloat(value)
        return buf.array()
    }

    // Réglages de performance. Les valeurs par défaut du Bebop 2 sont
    // volontairement basses ; le drone annonce lui-même ses bornes via les
    // *SettingsState (3 floats : courant, min, max).

    /** ardrone3.SpeedSettings.MaxRotationSpeed — °/s (vitesse de yaw). */
    fun maxRotationSpeed(degPerSec: Float): ByteArray = oneFloat(1, 11, 1, degPerSec)

    /** ardrone3.SpeedSettings.MaxVerticalSpeed — m/s. */
    fun maxVerticalSpeed(mPerSec: Float): ByteArray = oneFloat(1, 11, 0, mPerSec)

    /** ardrone3.PilotingSettings.MaxTilt — degrés (inclinaison max = vitesse horizontale). */
    fun maxTilt(deg: Float): ByteArray = oneFloat(1, 2, 1, deg)

    // ardrone3.Piloting.* (prj=1 cls=0)
    fun takeoff(): ByteArray = noArgs(1, 0, 1)
    fun landing(): ByteArray = noArgs(1, 0, 3)
    fun emergency(): ByteArray = noArgs(1, 0, 4)
    fun flatTrim(): ByteArray = noArgs(1, 0, 0)

    /**
     * ardrone3.Piloting.PCMD (prj=1 cls=0 cmd=2) — args :
     *  flag u8, roll i8, pitch i8, yaw i8, gaz i8, timestamp u32 LE.
     * `flag` = 1 si on demande explicitement à bouger (sticks actifs), 0 sinon.
     * Référence : `arsdk-xml/xml/ardrone3.xml` (Piloting.PCMD).
     */
    fun pcmd(flag: Int, roll: Int, pitch: Int, yaw: Int, gaz: Int, timestamp: Int): ByteArray {
        val buf = ByteBuffer.allocate(4 + 1 + 1 + 1 + 1 + 1 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(1.toByte())               // prj ardrone3
        buf.put(0.toByte())               // cls Piloting
        buf.putShort(2.toShort())         // cmd PCMD
        buf.put(flag.toByte())
        buf.put(roll.toByte())
        buf.put(pitch.toByte())
        buf.put(yaw.toByte())
        buf.put(gaz.toByte())
        buf.putInt(timestamp)
        return buf.array()
    }

    // drone_manager.connect (137,0,3) — args : serial(str) + key(str)
    fun dmConnect(serial: String, key: String = ""): ByteArray {
        val serialBytes = serial.toByteArray(Charsets.UTF_8)
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val size = 4 + serialBytes.size + 1 + keyBytes.size + 1
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(137.toByte())
        buf.put(0.toByte())
        buf.putShort(3.toShort())
        buf.put(serialBytes); buf.put(0)
        buf.put(keyBytes); buf.put(0)
        return buf.array()
    }

    // skyctrl.Wifi.ConnectToWifi (4,1,2) — args : bssid(str) + ssid(str) + passphrase(str)
    fun sc2WifiConnect(bssid: String, ssid: String, passphrase: String): ByteArray {
        val bssidBytes = bssid.toByteArray(Charsets.UTF_8)
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        val passBytes = passphrase.toByteArray(Charsets.UTF_8)
        val size = 4 + bssidBytes.size + 1 + ssidBytes.size + 1 + passBytes.size + 1
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(4.toByte())              // prj skyctrl
        buf.put(1.toByte())              // cls Wifi
        buf.putShort(2.toShort())        // cmd ConnectToWifi
        buf.put(bssidBytes); buf.put(0)
        buf.put(ssidBytes); buf.put(0)
        buf.put(passBytes); buf.put(0)
        return buf.array()
    }

    /** Décode l'entête `prj_u8 + cls_u8 + cmd_u16 LE` ; renvoie null si trop court. */
    fun decodeHeader(body: ByteArray): ArCommandHeader? {
        if (body.size < 4) return null
        val prj = body[0].toInt() and 0xff
        val cls = body[1].toInt() and 0xff
        val cmd = (body[2].toInt() and 0xff) or ((body[3].toInt() and 0xff) shl 8)
        return ArCommandHeader(prj, cls, cmd, body.copyOfRange(4, body.size))
    }
}

/** En-tête ARCommand + arguments bruts (encodés selon la signature de la commande). */
data class ArCommandHeader(
    val prj: Int,
    val cls: Int,
    val cmd: Int,
    val args: ByteArray,
) {
    fun tuple(): Triple<Int, Int, Int> = Triple(prj, cls, cmd)
}

/**
 * Tailles d'args (en bytes) pour les ARCommands connues. Permet d'itérer
 * dans une trame transport qui contient plusieurs ARCommands concaténées.
 * Sources : XML Parrot (`arsdk-xml/xml/skyctrl.xml`, `common.xml`).
 */
object ArCommandSizes {
    private val table: Map<Triple<Int, Int, Int>, Int> = mapOf(
        // skyctrl.SkyControllerState.* (prj=4 cls=8)
        Triple(4, 8, 0) to 1,   // BatteryChanged : u8 percent
        Triple(4, 8, 1) to 1,   // GpsFixChanged : u8 fixed
        Triple(4, 8, 2) to 16,  // GpsPositionChanged : 2 double
        Triple(4, 8, 4) to 16,  // AttitudeChanged : 4 float
        Triple(4, 8, 5) to 4,   // ChargerType : enum (u32)
        Triple(4, 8, 6) to 2,   // ChargeTimeLeft : u16
        // skyctrl.CommonState.* (prj=4 cls=7)
        Triple(4, 7, 0) to 0,   // AllStatesChanged
        Triple(4, 7, 3) to 16,  // BootId : string 16 max — variable, mais ~16
        // Common.CommonState.* (prj=0 cls=5)
        Triple(0, 5, 1) to 1,   // BatteryStateChanged (drone) : u8 percent
        // ardrone3.MediaRecordState.* (prj=1 cls=7)
        Triple(1, 7, 3) to 5,   // VideoStateChangedV2 : enum u32 state + u8 mass_storage_id
        // ardrone3.MediaStreamingState.* (prj=1 cls=22)
        Triple(1, 22, 0) to 4,  // VideoEnableChanged : enum u32
        Triple(1, 22, 1) to 4,  // VideoStreamModeChanged : enum u32
        // Note : (0, 5, 0) ProductVersionChanged = 2 strings (taille variable),
        // capturée séparément via le path "tuple inconnu" dans tryIterate.
    )
    fun argsSize(tuple: Triple<Int, Int, Int>): Int? = table[tuple]
}

/**
 * IDs ARSDK extraits des XML Parrot (`Parrot-Developers/arsdk-xml`).
 * Confirmé empiriquement : sur ce firmware SC2, `skyctrl` est project_id=4.
 */
object ArsdkIds {
    const val PRJ_COMMON = 0
    const val PRJ_ARDRONE3 = 1
    const val PRJ_SKYCTRL = 4

    // Common.CommonState.BatteryStateChanged (drone) — 1 arg u8 percent.
    // Classes de common.xml : 0=Network, 1=NetworkEvent, 2=Settings,
    // 3=SettingsState, 4=Common, 5=CommonState. Donc CommonState = cls 5.
    // Confirmé sur le wire (session 10) : (0,3,4)+(0,3,5) reconstruisent le
    // serial drone PI040384AG7C087996, et (0,5,7)=2B = WifiSignalChanged i16.
    val COMMON_BATTERY = Triple(0, 5, 1)

    // SkyController.SkyControllerState.BatteryChanged (SC2) — 1 arg u8 percent.
    val SKYCTRL_BATTERY = Triple(4, 8, 0)

    // SkyController.SkyControllerState.AttitudeChanged (SC2) — high freq NOACK.
    val SKYCTRL_ATTITUDE = Triple(4, 8, 4)

    // Requête : Common.Common.AllStates → device répond avec tous ses *StateChanged.
    // cls=4 (Common). Envoyée sur (0,0,0) le drone ne répond RIEN (vérifié session 10).
    val COMMON_ALL_STATES = Triple(0, 4, 0)

    // Common.Common.CurrentDate / CurrentTime — même classe 4.
    val COMMON_CURRENT_DATE = Triple(0, 4, 1)
    val COMMON_CURRENT_TIME = Triple(0, 4, 2)

    // Requête : skyctrl.Common.AllStates → SC2 répond avec tous ses SkyControllerState.*.
    val SKYCTRL_ALL_STATES = Triple(4, 6, 0)

    // common.SettingsState.ProductVersionChanged → 2 strings (software, hardware).
    // cls=3 (SettingsState) — observé 12 B sur le wire.
    val COMMON_PRODUCT_VERSION = Triple(0, 3, 3)

    // ardrone3.MediaRecordState.VideoStateChangedV2 → enum u32 state (0=stopped,1=started,2=failed,3=autostopped) + u8.
    val ARDRONE3_VIDEO_RECORD_STATE = Triple(1, 7, 3)

    // ardrone3.MediaStreamingState.VideoEnableChanged → enum u32 (0=enabled, 1=disabled, 2=error).
    val ARDRONE3_VIDEO_ENABLE_CHANGED = Triple(1, 22, 0)

    // ardrone3.MediaStreamingState.VideoStreamModeChanged → enum u32 (low_latency / high_reliability / …).
    val ARDRONE3_VIDEO_STREAM_MODE_CHANGED = Triple(1, 22, 1)

    // drone_manager (feature 137) — gestion connexion drone depuis le SC2
    val DM_DISCOVER_DRONES = Triple(137, 0, 1)     // cmd: no args
    val DM_DRONE_LIST_ITEM = Triple(137, 0, 2)     // evt: serial(str) + model(u16) + name(str) + connection_order(u8) + active(u8) + visible(u8) + security(u32) + has_saved_key(u8) + rssi(i8) + list_flags(u8)
    val DM_CONNECT = Triple(137, 0, 3)             // cmd: serial(str) + key(str)
    val DM_FORGET = Triple(137, 0, 4)              // cmd: serial(str)
    val DM_CONNECTION_STATE = Triple(137, 0, 5)    // evt: state(u32 enum) + serial(str) + model(u16) + name(str)
    val DM_AUTH_FAILED = Triple(137, 0, 6)         // evt: serial(str) + model(u16) + name(str)
    val DM_KNOWN_DRONE_ITEM = Triple(137, 0, 8)   // evt: serial(str) + model(u16) + name(str) + security(u32) + has_saved_key(u8) + list_flags(u8)

    // skyctrl.Wifi (prj=4 cls=1) — commandes Wi-Fi du SC2
    val SKYCTRL_WIFI_REQUEST_LIST = Triple(4, 1, 0)
    val SKYCTRL_WIFI_REQUEST_CURRENT = Triple(4, 1, 1)
    // skyctrl.WifiState (prj=4 cls=0) — événements Wi-Fi du SC2
    val SKYCTRL_WIFI_LIST = Triple(4, 0, 0)           // WifiList: bssid(str) + ssid(str) + secured(u8) + saved(u8) + rssi(i32) + freq(i32)
    val SKYCTRL_WIFI_CONNEXION_CHANGED = Triple(4, 0, 1)  // ConnexionChanged: ssid(str) + status enum u32 (0=connected,1=error,2=disconnected)
    // skyctrl.DeviceState (prj=4 cls=3) — état connexion drone
    val SKYCTRL_DEVICE_CONNEXION_CHANGED = Triple(4, 3, 1)  // ConnexionChanged: name(str) + productID(u16) + status enum u32 (0=notConnected,1=connecting,2=connected,3=disconnecting)
}
