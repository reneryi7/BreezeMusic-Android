# Third-Party Notices

**BreezeMusic Android** is licensed under the GNU General Public License v3.0 (GPL-3.0).
This project incorporates or references open-source research and libraries from the community. We gratefully acknowledge the following projects and their authors:

---

## 1. Protocol & Design Research References

* **MakcRe/KuGouMusicApi**
  * Repository: https://github.com/MakcRe/KuGouMusicApi
  * License: MIT License (`Copyright (c) 2023 MakcRe`)
  * Note: The client-side networking and signing stack is an independent pure-Kotlin implementation informed by the protocol studies in `MakcRe/KuGouMusicApi` (no Node.js server runtime is bundled). Standalone verification helper assets under `app/src/main/assets/verification/` preserve the upstream MIT license notice in `app/src/main/assets/verification/LICENSE`.
* **lisalee23042605/kgmusicbot**
  * Repository: https://github.com/lisalee23042605/kgmusicbot
  * Note: Referenced for multi-quality audio download flow and binary cover/lyric metadata embedding architecture; reimplemented independently in pure Kotlin with zero native tagging library dependencies.
* **umr-xiaomai/kgka_Music_hl** & **zzyoxml/md3Music**
  * Note: Studied for UI and account-flow interaction design inspiration. Their source code and assets are not linked into or bundled with the Android build.

---

## 2. Core Open-Source Dependencies (Android / Kotlin)

### Kotlin & Coroutines
* **Kotlin Standard Library & Kotlinx Coroutines** — Apache-2.0 License (https://kotlinlang.org/)

### AndroidX & Jetpack Compose
* **Jetpack Compose (`ui`, `material3`, `material-icons-extended`)** — Apache-2.0 License (https://developer.android.com/jetpack/compose)
* **AndroidX Core KTX / Lifecycle / Activity Compose / AppCompat / Navigation Compose / WebKit** — Apache-2.0 License (https://developer.android.com/jetpack/androidx)

### Media Playback & Imaging
* **AndroidX Media3 (`media3-exoplayer`, `media3-session`, `media3-common`)** — Apache-2.0 License (https://developer.android.com/media/media3)
* **Coil Compose & Coil GIF (`io.coil-kt:coil-compose`, `coil-gif`)** — Apache-2.0 License (https://coil-kt.github.io/coil/)

### Networking & Serialization
* **OkHttp (`com.squareup.okhttp3:okhttp`)** — Apache-2.0 License (https://square.github.io/okhttp/)
* **Retrofit & Gson Converter (`com.squareup.retrofit2:retrofit`, `converter-gson`)** — Apache-2.0 License (https://square.github.io/retrofit/)
* **Google Gson (`com.google.code.gson:gson`)** — Apache-2.0 License (https://github.com/google/gson)

### Testing
* **JUnit 4** — Eclipse Public License 1.0 (https://junit.org/junit4/)
* **OkHttp MockWebServer** — Apache-2.0 License
* **AndroidX Test & Espresso** — Apache-2.0 License

Exact dependency coordinates and versions are declared in `app/build.gradle.kts` and `build.gradle.kts`. Additional third-party asset license notices (where applicable) are preserved alongside the corresponding assets (such as `app/src/main/assets/verification/LICENSE`).
