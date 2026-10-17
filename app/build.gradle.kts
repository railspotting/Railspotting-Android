import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from keystore.properties (git-ignored) or the environment; see README.
val signing = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = signing.getProperty(key) ?: System.getenv(env)

// The app's version (app/version.properties), which releases and the updater compare.
val version = Properties().apply { file("version.properties").inputStream().use { load(it) } }

// The Rust core (core/), built for each ABI with cargo-ndk into build/rustJniLibs.
val abis = listOf("arm64-v8a", "x86_64")
val rustOut = layout.buildDirectory.dir("rustJniLibs")
// The SDR driver (native/): libusb and the patched librtlsdr, built with the NDK.
val sdrOut = layout.buildDirectory.dir("sdrJniLibs")

android {
    namespace = "com.railspotting.android"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.railspotting.android"
        minSdk = 29
        targetSdk = 36
        // tools/release.sh bumps these
        versionCode = version.getProperty("code").toInt()
        versionName = version.getProperty("name")
        ndk { abiFilters += abis }
    }

    signingConfigs {
        create("release") {
            signingValue("storeFile", "RS_KEYSTORE")?.let { storeFile = rootProject.file(it) }
            storePassword = signingValue("storePassword", "RS_KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "RS_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "RS_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingValue("storeFile", "RS_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].jniLibs.directories.add(rustOut.get().asFile.path)
    sourceSets["main"].jniLibs.directories.add(sdrOut.get().asFile.path)

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

val ndkDir = androidComponents.sdkComponents.ndkDirectory
val cargoNdk = tasks.register<Exec>("cargoNdk") {
    description = "Builds the Rust core (core/) for each ABI with cargo-ndk."
    workingDir = rootProject.file("core")
    val args = mutableListOf("cargo", "ndk", "-P", "29", "-o", rustOut.get().asFile.absolutePath)
    abis.forEach { args += listOf("-t", it) }
    args += listOf("build", "--release")
    commandLine(args)
    doFirst { environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath) }
    inputs.dir(rootProject.file("core/src"))
    inputs.file(rootProject.file("core/Cargo.toml"))
    inputs.file(rootProject.file("core/Cargo.lock"))
    inputs.dir(rootProject.file("receiver/crates"))
    outputs.dir(rustOut)
}

val sdrDriver = tasks.register<Exec>("sdrDriver") {
    description = "Builds libusb and the patched librtlsdr (native/) for each ABI."
    commandLine(listOf("sh", rootProject.file("native/build.sh").absolutePath, "29", sdrOut.get().asFile.absolutePath) + abis)
    doFirst { environment("ANDROID_NDK_HOME", ndkDir.get().asFile.absolutePath) }
    inputs.file(rootProject.file("native/build.sh"))
    inputs.dir(rootProject.file("native/patches"))
    inputs.dir(rootProject.file("native/libusb/libusb"))
    inputs.dir(rootProject.file("native/rtl-sdr/src"))
    outputs.dir(sdrOut)
}

tasks.named("preBuild") { dependsOn(cargoNdk, sdrDriver) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.googleid)
    implementation(libs.play.services.location)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
