# وَقفَة (Waqfa)

Android app (native Kotlin, no AndroidX). After a chosen amount of screen-on time it shows one page of the Quran in a floating window over any app.

- Package: com.madboly.waqfa, minSdk 26, target 34
- Mushaf pages: app/src/main/assets/pages/pNNN.webp (600px, King Fahd Complex print)
- Prayer times: aladhan.com API (Egyptian method), cached monthly
- Backup: Google sign-in (OAuth PKCE) to the hidden Drive appDataFolder
- Updates: in-app updater reads version.json from this repo's update channel

## Build
Needs JDK 17 and the Android SDK (platform 34). Signing uses env vars, never commit the keystore:

    export WAQFA_KS=/path/to/waqfa.jks WAQFA_KS_PASS=...
    gradle assembleRelease

The release key alias is `waqfa`. Keep the same key for every release so updates install over each other.
