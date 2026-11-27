import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.concept.music"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.concept.music"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        versionName = "1.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // 手机版 (phone) 与平板版 (tablet) 签名配置：
    // 1. 手机版通过本地隔离的 keystore.properties 绑定高强度口令保护的 app/keystore/breezemusic-phone.jks (phoneSign)，
    //    保留与 v1.0.0 ~ v1.3.5 一致的证书指纹以确保所有手机端及国产 ROM 平滑覆盖升级零冲突；外部克隆者无本地凭据时自动回退 debug 签名。
    // 2. 平板版默认使用独立的 app/keystore/breezemusic-tablet.jks (tabletSign) 签名，并配合独立包名后缀 .tablet，
    //    从签名与包名双维度彻底隔离，杜绝与手机版产生 INSTALL_FAILED_UPDATE_INCOMPATIBLE 签名冲突。
    signingConfigs {
        create("phoneSign") {
            val phoneStorePath = keystoreProps.getProperty("phone.storeFile")
                ?: System.getenv("PHONE_KEYSTORE_PATH")
                ?: "keystore/breezemusic-phone.jks"
            val resolvedPhoneStore = file(phoneStorePath)
            val resolvedStorePassword = (keystoreProps.getProperty("phone.storePassword")
                ?: System.getenv("PHONE_KEYSTORE_PASSWORD"))?.takeIf { it.isNotBlank() }
            val resolvedKeyAlias = (keystoreProps.getProperty("phone.keyAlias")
                ?: System.getenv("PHONE_KEY_ALIAS"))?.takeIf { it.isNotBlank() }
            val resolvedKeyPassword = (keystoreProps.getProperty("phone.keyPassword")
                ?: System.getenv("PHONE_KEY_PASSWORD"))?.takeIf { it.isNotBlank() }
            if (resolvedPhoneStore.exists() &&
                resolvedStorePassword != null &&
                resolvedKeyAlias != null &&
                resolvedKeyPassword != null
            ) {
                storeFile = resolvedPhoneStore
                storePassword = resolvedStorePassword
                keyAlias = resolvedKeyAlias
                keyPassword = resolvedKeyPassword
            }
        }
        create("tabletSign") {
            val tabletStorePath = keystoreProps.getProperty("tablet.storeFile")
                ?: System.getenv("TABLET_KEYSTORE_PATH")
                ?: "keystore/breezemusic-tablet.jks"
            val resolvedTabletStore = file(tabletStorePath)
            val resolvedStorePassword = (keystoreProps.getProperty("tablet.storePassword")
                ?: System.getenv("TABLET_KEYSTORE_PASSWORD"))?.takeIf { it.isNotBlank() }
            val resolvedKeyAlias = (keystoreProps.getProperty("tablet.keyAlias")
                ?: System.getenv("TABLET_KEY_ALIAS"))?.takeIf { it.isNotBlank() }
            val resolvedKeyPassword = (keystoreProps.getProperty("tablet.keyPassword")
                ?: System.getenv("TABLET_KEY_PASSWORD"))?.takeIf { it.isNotBlank() }
            if (resolvedTabletStore.exists() &&
                resolvedStorePassword != null &&
                resolvedKeyAlias != null &&
                resolvedKeyPassword != null
            ) {
                storeFile = resolvedTabletStore
                storePassword = resolvedStorePassword
                keyAlias = resolvedKeyAlias
                keyPassword = resolvedKeyPassword
            }
        }
    }

    // 双端平台构建变体（Product Flavors）：
    // - phone：面向安卓手机构建（applicationId = com.concept.music），未配置外部正式证书凭据时仅在本地开发回退 debug 签名
    // - tablet：面向安卓平板与大屏设备构建（applicationId = com.concept.music.tablet），绑定 tabletSign 独立签名证书
    flavorDimensions += "device"
    productFlavors {
        create("phone") {
            dimension = "device"
            val disableDedicatedPhoneJks =
                keystoreProps.getProperty("phone.useDedicatedKeystore")?.equals("false", ignoreCase = true) == true ||
                    System.getenv("USE_DEDICATED_PHONE_KEYSTORE")?.equals("false", ignoreCase = true) == true
            val phoneSignCfg = signingConfigs.getByName("phoneSign")
            val isPhoneSignReady = phoneSignCfg.storeFile?.exists() == true &&
                !phoneSignCfg.storePassword.isNullOrBlank() &&
                !phoneSignCfg.keyPassword.isNullOrBlank()
            signingConfig = if (!disableDedicatedPhoneJks && isPhoneSignReady) {
                phoneSignCfg
            } else {
                signingConfigs.getByName("debug")
            }
            buildConfigField("String", "DEVICE_FLAVOR", "\"phone\"")
            buildConfigField("boolean", "IS_TABLET_FLAVOR", "false")
        }
        create("tablet") {
            dimension = "device"
            applicationIdSuffix = ".tablet"
            versionNameSuffix = "-tablet"
            val tabletSignCfg = signingConfigs.getByName("tabletSign")
            val isTabletSignReady = tabletSignCfg.storeFile?.exists() == true &&
                !tabletSignCfg.storePassword.isNullOrBlank() &&
                !tabletSignCfg.keyPassword.isNullOrBlank()
            signingConfig = if (isTabletSignReady) {
                tabletSignCfg
            } else {
                signingConfigs.getByName("debug")
            }
            buildConfigField("String", "DEVICE_FLAVOR", "\"tablet\"")
            buildConfigField("boolean", "IS_TABLET_FLAVOR", "true")
        }
    }

    buildTypes {
        debug {
            // 置空 buildType 级默认签名，使 phoneDebug 与 tabletDebug 各自继承所属 Flavor 的签名
            signingConfig = null
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 置空 buildType 级默认签名，使 phoneRelease 与 tabletRelease 各自使用所属 Flavor 的签名
            signingConfig = null
            val requestedTasks = gradle.startParameter.taskNames.joinToString(" ")
            val isRunningReleaseTask = Regex("(?:assemble|bundle|package).*Release", RegexOption.IGNORE_CASE)
                .containsMatchIn(requestedTasks)
            if (isRunningReleaseTask) {
                val phoneReady = !signingConfigs.getByName("phoneSign").storePassword.isNullOrBlank()
                val tabletReady = !signingConfigs.getByName("tabletSign").storePassword.isNullOrBlank()
                if (!phoneReady || !tabletReady) {
                    val msg = "Release signing credentials missing in keystore.properties / environment; falling back to debug keystore for local verification only."
                    if (System.getenv("REQUIRE_RELEASE_SIGNING")?.equals("true", ignoreCase = true) == true) {
                        throw GradleException("REQUIRE_RELEASE_SIGNING=true: $msg")
                    } else {
                        logger.warn("[Security] $msg")
                    }
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Core Android & Lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Jetpack Compose & Material 3
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation & ViewModel
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    // Image loading with Coil (including animated GIF / WebP support for comment stickers & images)
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-gif:2.6.0")

    // AndroidX Media3 (ExoPlayer & Background MediaSession)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")

    // Network & Serialization
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
