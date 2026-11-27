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

## Blocking

Code lives in `android/app/src/main/java/io/github/arboeckh/geryon/`.

- `block/`: the rule engine. It has no Android dependencies apart from `BlockRepository`.
  - **Catalogue** (`assets/catalog.json`): *targets* (one app plus its domains, e.g. YouTube) and *categories* (groups of targets plus domains). A category marked `hard` is always blocked.
  - **Config**: *profiles* (block or allow rules on targets and categories), the always-on base profile, and *tag bindings* (tag id → action).
  - **State**: active profiles, timed grants, and whether the base profile is paused.
  - **Policy**: decides each app or domain. The stack, top layer wins: hard → grants → active profiles (newest first) → base → allow.
- `enforce/`: carries out the policy. `AppBlocker` is an accessibility service that sends blocked apps home. Domain blocking is a DNS VPN (in progress).

Tag actions: start a profile on a timer, toggle a profile, grant (pick one target to unlock for N minutes), and lockdown (clear grants and resume the base profile).
