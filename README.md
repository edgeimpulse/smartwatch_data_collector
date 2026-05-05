# WizDataCollector

A Wear OS app for collecting sensor data from a Galaxy Watch and uploading it directly to [Edge Impulse](https://edgeimpulse.com) for machine learning model training.

## Features

- Select from all available Android sensors on the device (accelerometer, gyroscope, heart rate, magnetometer, barometer, and more)
- Raw PPG (photoplethysmography) via Samsung Health Sensor SDK at 100 Hz — green, IR, and red channels
- Custom label input for each recording session
- Configurable sample duration (2 s – 60 s)
- Direct upload to Edge Impulse ingestion API
- API key stored on-device (never hardcoded)

**Tested on:** Galaxy Watch 4 Classic (SM-R890) · Wear OS · Android 16 (API 36)

---

## Prerequisites

### Samsung Health Sensor SDK (required for PPG)

The PPG sensor uses the Samsung Health Sensor SDK, which is a proprietary AAR that **cannot be included in this repository**. You must download it manually from Samsung.

1. Go to [developer.samsung.com/health/sensor](https://developer.samsung.com/health/sensor/overview.html)
2. Sign in with a Samsung developer account (free)
3. Click **Download SDK** → download `samsung-health-sensor-sdk-v1.4.1.zip` (or latest)
4. Unzip it — the AAR is at:
   ```
   samsung-health-sensor-sdk-v1.4.1/1.4.1/libs/samsung-health-sensor-api-1.4.1.aar
   ```
5. Copy the AAR into the project:
   ```
   app/libs/samsung-health-sensor-api-1.4.1.aar
   ```
6. The `app/build.gradle.kts` already references it:
   ```kotlin
   implementation(files("libs/samsung-health-sensor-api-1.4.1.aar"))
   ```

> The AAR is gitignored. Without it the project will not compile.

---

## One-Time Watch Setup

These steps survive reboots and only need to be done once.

### 1. Enable Developer Options

On the watch:
1. **Settings → About → Software**
2. Tap **Software version** 7 times until "Developer mode enabled" appears

### 2. Enable Wireless Debugging

1. **Settings → Developer Options**
2. Enable **Wireless debugging**
3. Tap **Wireless debugging** to open it — note the **IP address** and **port**

### 3. Enable Samsung Health Platform Developer Mode

This is required for the PPG sensor to work. Without it you will get an `SDK_POLICY_ERROR`.

On the watch:
1. **Settings → Apps → Health Platform**
2. Tap the **"Health Platform" title text 10 times**
3. `[Dev mode]` appears below the title — setup is complete

> **Important:** The ADB command `adb shell settings put global health_platform_developer_mode 1` is NOT sufficient on its own. The UI tap is required to unlock the tracker-level policy check.

---

## First-Time ADB Setup

### Pair and connect

```bash
# Pair once (use the pairing IP:port and code shown on the watch)
adb pair 192.168.1.x:XXXXX
# Enter the pairing code when prompted

# Connect (use the main IP:port from the Wireless debugging screen)
adb connect 192.168.1.x:XXXXX

# Verify the watch appears
adb devices
```

> The **pairing port** is different from the **connection port**. The connection port changes every session.

---

## After Every Android Studio Deploy

Permissions reset on each install. Run these three grants every time you deploy:

```bash
adb -s <ip:port> shell pm grant com.example.eidatacollector \
  "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"

adb -s <ip:port> shell pm grant com.example.eidatacollector \
  android.permission.BODY_SENSORS

adb -s <ip:port> shell pm grant com.example.eidatacollector \
  android.permission.ACTIVITY_RECOGNITION
```

**Why `READ_ADDITIONAL_HEALTH_DATA`?**
Android 16 (API 36) changed the permission model for Samsung biometric sensors. `BODY_SENSORS` alone no longer grants access to the Samsung Health Sensor SDK trackers. The Samsung-specific permission is required in addition to `BODY_SENSORS`.

---

## Set Your Edge Impulse API Key

Typing a long key on a watch is impractical — use ADB to paste it directly.

1. Log in to [Edge Impulse Studio](https://studio.edgeimpulse.com) → your project → **Dashboard → Keys**
2. Copy the API key (starts with `ei_`)
3. Open the app → tap **⚙** → tap the API key field to focus it
4. On your computer:
   ```bash
   adb -s <ip:port> shell input text "ei_your_full_api_key_here"
   ```
5. Tap **SAVE**

---

## Usage

1. Tap **SENSORS** to select which sensors to record
   - Standard Android sensors are listed by name
   - **PPG Sensor** (Samsung Health · 100 Hz) appears at the top — requires Samsung Health Platform developer mode (see above)
2. Enter a **label** for the data (e.g. `walking`, `resting`, `elevated`)
3. Select a **duration**
   - 2–10 s for motion / IMU sensors
   - 15–30 s for PPG / heart rate
4. Tap **RECORD** — a 3-second countdown plays before recording starts
5. The sample uploads automatically to Edge Impulse when recording finishes

---

## Sensor Notes

### PPG (Samsung Health Sensor SDK)
- Uses `PPG_ON_DEMAND` tracker at 100 Hz — provides true continuous raw waveform data
- Uploads green, IR, and red channels as a 3-axis signal at 10 ms interval
- Samsung Health Platform developer mode must be enabled on the watch (see setup above)
- Permissions must be re-granted after every Android Studio deploy (see above)
- Requires the watch to be worn on the wrist during recording

### Microphone (Audio)
- Records raw PCM audio at 16 kHz mono and uploads as a WAV file — ready for keyword spotting and audio classification projects in Edge Impulse
- Requires `RECORD_AUDIO` permission (prompted on first launch)
- Use durations of 1–2 s per sample for keyword spotting

### Heart Rate (Android SensorManager)
- Processed BPM value, not a raw waveform
- Needs 5–10 seconds to warm up — use recordings of at least 30 s
- Requires `BODY_SENSORS` permission (prompted on first launch)

### Other sensors
- Sensors with no data in a session are silently skipped — they do not cause the upload to fail
