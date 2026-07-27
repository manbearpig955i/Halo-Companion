# Halo Second-Screen Companion — Prototype (Steps 1 & 2)

This is the first working slice of the project: a **mock PC server** that
simulates Halo game-state data, and an **Android app** that connects to it
over WebSocket and renders a live HUD (health, shields, ammo, radar, objective)
— like a 3DS bottom screen. No MCC hook exists yet; this proves out the
whole pipeline with fake data so you can build the Android UI without
needing the game running.

## 1. Run the mock server (on your PC)

```bash
cd server
pip install websockets
python mock_server.py
```

It prints something like:
```
Mock Halo companion server running on ws://0.0.0.0:8765
```

Find your PC's LAN IP:
- Windows: `ipconfig` → look for IPv4 Address (e.g. 192.168.1.100)
- Mac/Linux: `ifconfig` or `ip addr`

Make sure your phone and PC are on the **same Wi-Fi network**, and that
your PC's firewall allows inbound connections on port 8765.

## 2. Build and run the Android app

Open `android-app/` in Android Studio (Hedgehog or newer):
- Let Gradle sync (it'll pull Compose, OkHttp, kotlinx.serialization)
- Run on a physical device or emulator on the same network
  - **Emulator note**: if using the Android emulator instead of a real phone,
    use `10.0.2.2:8765` instead of your PC's LAN IP — that's the emulator's
    alias for the host machine.
- In the app, enter `<your-pc-ip>:8765` in the address field and tap **Connect**

You should see the connection status turn green ("CONNECTED") and the
health/shield bars, ammo counter, and radar start updating with the
simulated data within a second or two.

## What's next (not in this step)

- Replacing `mock_server.py`'s fake data generator with a real reader of
  MCC/Halo CE memory or an injected hook — this is step 3+ and needs to
  happen against your actual game install.
- Everything downstream (WebSocket transport, JSON schema, Android HUD)
  stays the same — you'd just swap what populates `GameState` on the
  server side.

## Project layout

```
halo-second-screen/
├── server/
│   └── mock_server.py       # simulated game-state WebSocket server
└── android-app/
    ├── app/
    │   └── src/main/
    │       ├── java/com/haloscreen/companion/
    │       │   ├── GameState.kt           # data model (matches server JSON)
    │       │   ├── GameStateViewModel.kt  # WebSocket client + reconnect logic
    │       │   ├── HudScreen.kt           # Compose UI: bars, ammo, radar
    │       │   └── MainActivity.kt
    │       ├── res/values/themes.xml
    │       └── AndroidManifest.xml
    ├── app/build.gradle.kts
    ├── build.gradle.kts
    └── settings.gradle.kts
```
