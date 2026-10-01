import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

/** Reads KEY=value from .env, falling back to .env.example (same files the secrets plugin uses). */
fun envFileValue(key: String): String? =
  listOf(".env", ".env.example")
    .map { rootProject.file(it) }
    .filter { it.exists() }
    .firstNotNullOfOrNull { file ->
      file.readLines()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("=")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    }

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.sndmart.delivery"
    minSdk = 24
    targetSdk = 36
    // CI passes VERSION_CODE / VERSION_NAME; local builds fall back to the defaults below.
    // VERSION_NAME is also what the force-update check compares against app_versions.
    // 1.1.0 = RPC-only release. Every partner must be on it before the Phase 2 migration.
    versionCode = (System.getenv("VERSION_CODE") ?: "2").toInt()
    versionName = System.getenv("VERSION_NAME") ?: "1.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // The manifest needs this placeholder in every variant (including unit-test manifests);
    // CI env > .env > .env.example.
    manifestPlaceholders["GOOGLE_MAPS_API_KEY"] =
      System.getenv("GOOGLE_MAPS_API_KEY")?.takeIf { it.isNotBlank() } ?: envFileValue("GOOGLE_MAPS_API_KEY") ?: ""
  }

  val releaseKeystore = file(System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks")
  val hasReleaseSigning =
    releaseKeystore.exists() &&
      !System.getenv("STORE_PASSWORD").isNullOrBlank() &&
      !System.getenv("KEY_PASSWORD").isNullOrBlank()
  val sharedDebugKeystore = file("${rootDir}/debug.keystore")

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = releaseKeystore
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
    // Optional team-shared debug keystore (keeps the Maps/Firebase SHA-1 stable across machines).
    // Without it, the Android default debug keystore is used.
    if (sharedDebugKeystore.exists()) {
      create("debugConfig") {
        storeFile = sharedDebugKeystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      // R8 stays off until the release build has been smoke-tested with minification
      // (Moshi reflection + Retrofit need the keep rules in proguard-rules.pro).
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
    }
    debug {
      if (sharedDebugKeystore.exists()) signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.messaging)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.play.services.location)
  implementation(libs.play.services.maps)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.moshi.kotlin.codegen)
}
