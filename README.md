# EI Data Collector

A Wear OS app for collecting sensor data from a smartwatch and uploading it directly to [Edge Impulse](https://edgeimpulse.com) for machine learning model training.

## Features

- Scan and select from all available sensors on the device
- Custom label input for each recording session
- Configurable sample duration (2s – 60s)
- Direct upload to Edge Impulse ingestion API
- API key stored securely on-device (never hardcoded)

---

## Setup

### 1. Connect Your Watch via Wireless Debugging

Wireless debugging lets you use ADB over Wi-Fi — no USB cable needed.

**On the watch:**
1. Go to **Settings → About → Software**
2. Tap **Software version** 7 times to enable Developer Options
3. Go to **Settings → Developer Options**
4. Enable **Wireless debugging**
5. Tap **Wireless debugging** to open it — note the **IP address and port** shown (e.g. `192.168.1.42:12345`)
6. Tap **Pair device with pairing code** — note the **pairing code**, **IP**, and **pairing port**

**On your computer:**
```bash
# Step 1: Pair once (use the pairing IP:port and code from the watch)
adb pair 192.168.1.42:37001
# Enter the pairing code when prompted

# Step 2: Connect (use the main IP:port shown on the Wireless debugging screen)
adb connect 192.168.1.42:12345

# Verify the watch appears
adb devices
```

> If you have multiple devices connected, use `adb -s <serial>` for all subsequent commands.

---

### 2. Install the App

Build and install via Android Studio, or via ADB:

```bash
adb -s <serial> install app/build/outputs/apk/debug/app-debug.apk
```

---

### 3. Set Your Edge Impulse API Key

The app requires your Edge Impulse **API key** to upload data. Typing a long key on a watch is impractical, so use ADB to paste it directly:

**Find your API key:**
1. Log in to [Edge Impulse Studio](https://studio.edgeimpulse.com)
2. Open your project → **Dashboard** → **Keys**
3. Copy the API key (starts with `ei_`)

**Set it on the watch:**
1. Open the app on the watch
2. Tap **⚙** (settings icon) in the top-right corner
3. Tap the API key input field to focus it
4. On your computer, run:

```bash
adb -s <serial> shell input text "ei_your_full_api_key_here"
```

5. Tap **SAVE**

> The key is stored in the app's private SharedPreferences and persists across restarts.

---

## Usage

1. Tap **SENSORS** to select which sensors to record
2. Enter a **label** for the data (e.g. `walking`, `idle`, `clap`)
3. Select a **duration** — use 30s or 60s for heart rate, 2–10s for motion sensors
4. Tap **RECORD** — a 3-second countdown plays before recording starts
5. The sample is automatically uploaded to Edge Impulse when recording finishes

---

## Notes

- **Heart rate** requires the **Body Sensors** permission. Grant it when prompted on first launch. If the app shows a permission warning, go to **Settings → Apps → EI Data Collector → Permissions** on the watch and grant it manually.
- Heart rate sensors need 5–10 seconds to warm up. If no heart rate data is collected in a session, the upload proceeds with the remaining sensors rather than failing.
- Sensors with no data in a recording session are silently skipped — they do not cause the upload to fail.
