import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

// ── Release signing ─────────────────────────────────────────────────────────
// 🔑 A release has to be signed before a device will install it, and this is an app you install
//    on your own phone rather than one you fetch from a store. If `keystore.properties` exists
//    (untracked, 0600) the release is signed with that key; otherwise it falls back to the debug
//    key, which is the everyday build here. The presence of one file decides it.
//    🚫 A debug-signed artefact cannot be published to a store.
// 🔑 In the Gradle Kotlin DSL `java` is shadowed by an extension name, so java.util.* will not
//    resolve without the import above.
val signProps = Properties().apply {
  rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
  namespace = "io.github.zirize.screamdroid"
  compileSdk = 36

  defaultConfig {
    // 🔴 **Permanent once registered with a store.** Decided along with the name:
    //    screamdroid = screamd (the PC-side daemon) + droid, so both ends of the chain share it.
    applicationId = "io.github.zirize.screamdroid"

    // 🔑 minSdk 26 is what the adaptive launcher icon needs, and it is also where
    //    AudioTrack.PERFORMANCE_MODE_LOW_LATENCY starts. Nothing here asks for more:
    //    the receiver is 16-bit only, so no API 31 encodings are involved.
    minSdk = 26
    targetSdk = 36
    // 🔴 **versionCode only ever goes up, and a store refuses one it has already seen.** A
    //    skipped number costs nothing; a repeated one costs a rebuild.
    versionCode = 1
    versionName = "1.0.0"
  }

  signingConfigs {
    if (signProps.getProperty("storeFile") != null) {
      create("upload") {
        storeFile = file(signProps.getProperty("storeFile"))
        storePassword = signProps.getProperty("storePassword")
        keyAlias = signProps.getProperty("keyAlias")
        keyPassword = signProps.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
      // 🔑 R8. The store asks for it, and it takes the APK from 8.6 MB to 1.5 MB. No keep
      //    rules were needed - app/proguard-rules.pro says what was checked and what to re-check.
      // 🚫 Unit tests do not run through R8, so a green test run is not evidence about a
      //    release build. Install one and look at it: bash scripts/build.sh install.
      isMinifyEnabled = true
      // ℹ️ From AGP 9 this is all that optimised resource shrinking needs; the older
      //    android.r8.optimizedResourceShrinking flag is for 8.12-8.13 and is not used here.
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    buildConfig = true
    aidl = false
    renderScript = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // 🔑 The QR code on the main screen. Encoding one by hand is Reed-Solomon, masking and a
  //    version table - a week of somebody else's bugs - and this is Apache-2.0, the same licence
  //    as this app, so it carries no obligation beyond attribution (docs/prior-art.md).
  // 🚫 Only the `core` artefact: `android-core` pulls in the camera side, which this never does.
  implementation(libs.zxing.core)

  // 🔑 The protocol and buffering logic is deliberately plain Kotlin so it can be tested on the
  //    JVM without a device (docs/architecture.md). These are what run it.
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
