# Bebop

Android app to fly a **Parrot Bebop 2** via **Skycontroller 2** over USB — because FreeFlight Pro is dead.

![Status](https://img.shields.io/badge/status-working%20prototype-green)
![Android](https://img.shields.io/badge/Android-8.0%2B-blue)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

## What it does

- **Live video** — 864x480 H.264 from the drone camera, decoded in real-time
- **Piloting** — virtual joysticks with 20 Hz PCMD loop (takeoff, landing, emergency)
- **Telemetry** — SC2 battery, drone battery, attitude, stick inputs
- **Recording** — start/stop video recording on the drone's internal storage

## Why

Parrot discontinued FreeFlight Pro. The Bebop 2 + Skycontroller 2 hardware still works fine, but there's no app to fly it. This project reverse-engineered the USB AOA protocol stack to build a replacement from scratch.

## Architecture

```
Phone (USB device) ←— USB AOA ——→ Skycontroller 2 (USB host) ←— Wi-Fi ——→ Bebop 2
```

The app implements 4 protocol layers, all from scratch in Kotlin:

| Layer | Description | Source |
|-------|-------------|--------|
| **libmux** | Multiplexer — 12-byte header (`MUX!` magic + channel ID + size) | `mux/` |
| **libpomp** | Message protocol — varint-encoded typed args | `mux/PompEncoder.kt`, `PompMessage.kt` |
| **ARSDK transport** | Command framing — 7-byte header (type + buffer ID + seq + size) | `arsdk/ArsdkTransport.kt` |
| **ARCommands** | Drone commands — project/class/cmd + typed args | `arsdk/ArsdkTransport.kt` (ArCommand) |

Video arrives as **raw RTP on MUX channel 4** (not POMP-wrapped), depayloaded via RFC 6184 (STAP-A, FU-A, single NAL), and decoded with Android's hardware `MediaCodec`.

## Building

No Android Studio needed. Just the Android SDK command-line tools.

```bash
# Set your SDK path
echo "sdk.dir=$HOME/Android/Sdk" > android/local.properties

# Build
cd android
./gradlew assembleDebug

# Install (phone connected via USB)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Java 17+ and Android SDK with build-tools 35.

## Usage

1. Power on the **drone** first (wait for steady LED)
2. Power on the **Skycontroller 2** (wait for steady green LED = connected to drone)
3. Connect the SC2's USB-A port to the phone with a standard data cable
4. The app launches automatically
5. Tap **Ouvrir AOA** → **Handshake** → **Discover** → **Connect** → **Start video**

**Swipe right** for the debug screen with full protocol diagnostics.

## Screens

### Pilot screen
- Full-screen video feed
- **Floating joysticks** — appear where you touch (left = throttle/yaw, right = pitch/roll)
- HUD — connection status, battery levels, recording indicator
- Takeoff / Landing / Emergency buttons

### Debug screen
- Complete protocol state: MUX channels, ARSDK transport stats, ARCommand tuples
- Raw hex dumps of wire data
- All 170+ ARCommand tuples decoded and counted
- C2D commands sent log

## Key discoveries

Some things we learned the hard way while reverse-engineering the protocol:

- **Transport ACKs are mandatory** — without ACKing WITHACK frames (buf 126), the SC2 considers the client unresponsive and throttles communication. This was the #1 blocker.
- **Video is raw RTP on MUX channel 4**, not ARStream v1 on buffer 125 as the official docs suggest. The SC2 uses the ARStream2 resender path even for the Bebop 2.
- **The SC2 is a pass-through** — `DISCOVER` only lists the SC2 itself. You `CONNECT` to the SC2's device ID, and it transparently routes to the drone.
- **PCMD must be sent at 20 Hz minimum** — silence > 200ms and the drone ignores inputs.
- **Sequence counters are per buffer ID**, not global.

## Project structure

```
android/app/src/main/java/io/dayd/bebop/
├── aoa/            # USB AOA transport + main controller
├── arsdk/          # ARSDK protocol (transport, commands, stream reader)
├── mux/            # libmux framing + libpomp encoding/decoding
├── network/        # Network inspection utilities
├── ui/             # Compose UI (PilotScreen, MainScreen/debug, FloatingJoystick)
└── video/          # H.264 decoder + RTP depayloader
```

## Hardware tested

- **Drone**: Parrot Bebop 2
- **Controller**: Parrot Skycontroller 2 (P2 model, USB-A port)
- **Phone**: Google Pixel, Android 15
- **Cable**: Standard USB-A to USB-C data cable (SC2 is USB host, phone is device)

## References

- [Parrot-Developers/libmux](https://github.com/Parrot-Developers/libmux)
- [Parrot-Developers/libpomp](https://github.com/Parrot-Developers/libpomp)
- [Parrot-Developers/arsdk-ng](https://github.com/Parrot-Developers/arsdk-ng)
- [Parrot-Developers/arsdk-xml](https://github.com/Parrot-Developers/arsdk-xml)
- [Parrot-Developers/libARStream](https://github.com/Parrot-Developers/libARStream)
- [SkyControllerDev.pdf](https://developer.parrot.com/docs/SDK3/SkyControllerDev.pdf) (Parrot SDK3 documentation)

## License

MIT
