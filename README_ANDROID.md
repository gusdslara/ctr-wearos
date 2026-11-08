# CTR Native - Android & Wear OS Ports

This project brings Crash Team Racing to Android devices via SDL3, supporting both standard Android phones/tablets (`:app`) and **Wear OS smartwatches** (`:wear`).

---

## ⌚ Wear OS Smartwatch Port (`:wear`)

Features **Gyroscope Steering** (rotate your wrist left/right like a steering wheel to drive) and a **Circular Touch Overlay** mapped along the watch bezel.

### Building Wear OS APK
```bash
./gradlew :wear:assembleDebug
```
Output: `android/wear/build/outputs/apk/debug/wear-debug.apk`

### Installing on Watch via Wi-Fi ADB
```bash
adb connect <WATCH_IP>:<PORT>
adb install -r wear/build/outputs/apk/debug/wear-debug.apk
```

---

## 📱 Android Phone Port (`:app`)

### Building Phone APK
```bash
./gradlew :app:assembleDebug
```
Output: `android/app/build/outputs/apk/debug/app-debug.apk`

---

## 📦 Assets Setup

The game requires retail NTSC-U assets to run:
1. Copy `ctr-u.bin` or `BIGFILE.BIG` to your device storage.
2. Select it using the in-game file picker, or place manually at:
   `/storage/emulated/0/Android/data/com.ctrnative/files/assets/`
3. Restart the game.

## 🛠️ Troubleshooting

- **Logs**: Run `adb logcat -s CTR-Native:V CTRNativeInput:V SDL:V AndroidRuntime:E`
- **OpenGL**: Requires an **OpenGL ES 3.0** compatible GPU driver.
