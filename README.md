# Little Bird Sharing

An Android app that turns a phone into a local file-sharing host. Other devices will connect through a browser on the same Wi-Fi network or the phone's hotspot.

## Stage 1
- App ID: `com.littlebird.sharing`
- Kotlin, Jetpack Compose and Material 3
- Embedded NanoHTTPD server with start/stop controls
- Displays local IPv4 address and port
- Basic browser status page and `/health` endpoint
- Minimum Android 8.0 (API 26), compile SDK 36, JDK 17
- Target ABI: arm64-v8a only

Open in Android Studio, sync Gradle, then build and run on an arm64-v8a device. Connect another device to the same Wi-Fi, start the server, and open the displayed address in its browser.

The server currently runs while the app process is alive. File transfers, QR connection, hotspot support and a foreground service are planned for later stages.
