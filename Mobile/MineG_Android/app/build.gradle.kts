import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}

fun isPrivateDevelopmentHost(host: String): Boolean {
  if (host == "localhost" || host == "::1" || host == "[::1]") return true
  val octets = host.split('.').map { it.toIntOrNull() ?: return false }
  if (octets.size != 4 || octets.any { it !in 0..255 }) return false
  return octets[0] == 10 ||
    octets[0] == 127 ||
    (octets[0] == 172 && octets[1] in 16..31) ||
    (octets[0] == 192 && octets[1] == 168)
}

fun apiBaseUrlBuildConfigField(propertyName: String, value: String, allowPrivateHttp: Boolean): String {
  val parsed = URI(value)
  val credentialFreeBase = parsed.host != null && parsed.userInfo == null &&
    parsed.query == null && parsed.fragment == null && (parsed.path.isNullOrEmpty() || parsed.path == "/")
  val acceptedScheme = parsed.scheme == "https" ||
    (allowPrivateHttp && parsed.scheme == "http" && isPrivateDevelopmentHost(parsed.host.orEmpty()))
  require(credentialFreeBase && acceptedScheme) {
    if (allowPrivateHttp) {
      "$propertyName must be credential-free HTTPS or private-network HTTP"
    } else {
      "$propertyName must be credential-free HTTPS"
    }
  }
  val escaped = value.trimEnd('/').replace("\\", "\\\\").replace("\"", "\\\"")
  return "\"$escaped\""
}

val libsodiumAar = configurations.create("libsodiumAar")
val sodiumOutput = layout.buildDirectory.dir("generated/libsodium")
val extractLibsodium by tasks.registering(Sync::class) {
  from({ libsodiumAar.files.map { zipTree(it) } }) {
    include("jni/**/libsodium.so")
  }
  into(sodiumOutput)
}

android {
  namespace = "com.mineg.mobile"
  compileSdk = 36
  ndkVersion = "27.0.12077973"

  defaultConfig {
    applicationId = "com.mineg.mobile"
    minSdk = 29
    targetSdk = 36
    versionCode = 4
    versionName = "0.3.0-m3"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    externalNativeBuild {
      cmake {
        arguments("-DMINEG_SODIUM_ROOT=${sodiumOutput.get().asFile.absolutePath}", "-DMINEG_BUILD_TESTS=OFF")
        cppFlags("-std=c++17")
        targets("mineg_core")
      }
    }
    ndk {
      abiFilters += setOf("arm64-v8a", "x86_64")
    }
  }

  buildTypes {
    debug {
      val debugApiBaseUrl = providers.gradleProperty("minegDebugApiBaseUrl")
        .orElse("https://api.invalid")
        .get()
      buildConfigField(
        "String",
        "MINEG_API_BASE_URL",
        apiBaseUrlBuildConfigField("minegDebugApiBaseUrl", debugApiBaseUrl, allowPrivateHttp = true),
      )
    }
    release {
      val releaseApiBaseUrl = providers.gradleProperty("minegReleaseApiBaseUrl")
        .orElse("https://api.invalid")
        .get()
      buildConfigField(
        "String",
        "MINEG_API_BASE_URL",
        apiBaseUrlBuildConfigField("minegReleaseApiBaseUrl", releaseApiBaseUrl, allowPrivateHttp = false),
      )
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }
  sourceSets {
    getByName("main").jniLibs.srcDir(sodiumOutput.map { it.dir("jni") })
    getByName("test").resources.srcDir("../../contracts")
    getByName("test").resources.srcDir("../../core/migrations")
  }
  packaging {
    jniLibs.useLegacyPackaging = false
    resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
  }
  testOptions.unitTests.isIncludeAndroidResources = true
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
    freeCompilerArgs.add("-Xjsr305=strict")
  }
}

tasks.configureEach {
  if (name.contains("CMake") || name.contains("JniLibFolders") ||
    (name.startsWith("merge") && name.endsWith("NativeLibs"))
  ) {
    dependsOn(extractLibsodium)
  }
}

dependencies {
  libsodiumAar("com.goterl:lazysodium-android:5.2.0@aar")

  val composeBom = platform("androidx.compose:compose-bom:2025.08.01")
  implementation(composeBom)
  androidTestImplementation(composeBom)
  implementation("androidx.core:core-ktx:1.17.0")
  implementation("androidx.activity:activity-compose:1.11.0")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
  implementation("androidx.work:work-runtime-ktx:2.10.5")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

  debugImplementation("androidx.compose.ui:ui-tooling")
  debugImplementation("androidx.compose.ui:ui-test-manifest")
  testImplementation(kotlin("test"))
  testImplementation("junit:junit:4.13.2")
  androidTestImplementation("androidx.test.ext:junit:1.3.0")
  androidTestImplementation("androidx.test:core:1.7.0")
  androidTestImplementation("androidx.test:runner:1.7.0")
  androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
