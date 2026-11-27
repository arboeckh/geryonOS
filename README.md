# geryonOS
<img width="850" height="850" alt="image" src="https://github.com/user-attachments/assets/5e59c47e-069b-4bda-9b32-5c79cac22266" />

NFC-tag app control. Tap the phone on a tag to trigger behaviour.

- `android/`: Kotlin + Compose
- `ios/`: Swift + SwiftUI (not started)

App ID `io.github.arboeckh.geryon` · tags hold `geryon://tag/<id>`

## Android

Needs JDK 17 and the Android SDK (platform 37.2), with `sdk.dir` set in `android/local.properties`.

```bash
cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :app:installDebug
```
