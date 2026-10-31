import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Pinned native toolchain. Installed in CI by sdkmanager (see .github/workflows/build-memo-apk.yml).
val pinnedNdkVersion = "27.2.12479018"
val pinnedCmakeVersion = "3.22.1"

// Pinned offline-speech AAR (sherpa-onnx v1.13.8). Downloaded by fetchSherpaAar, never committed.
val sherpaAarFileName = "sherpa-onnx-1.13.8.aar"
val sherpaAarUrl =
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"
val sherpaAarSha256 = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val fetchSherpaAar by tasks.registering {
    val libsDir = layout.projectDirectory.dir("libs").asFile
    val target = File(libsDir, sherpaAarFileName)
    outputs.file(target)
    doLast {
        libsDir.mkdirs()
        if (target.isFile && sha256Of(target) == sherpaAarSha256) {
            logger.lifecycle("fetchSherpaAar: ${target.name} present with pinned SHA-256, skipping download")
            return@doLast
        }
        val partial = File(libsDir, "$sherpaAarFileName.part")
        partial.delete()
        val connection = URL(sherpaAarUrl).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw GradleException("fetchSherpaAar: HTTP $status downloading $sherpaAarUrl")
            }
            connection.inputStream.use { input -> partial.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
        val actual = sha256Of(partial)
        if (actual != sherpaAarSha256) {
            partial.delete()
            target.delete()
            throw GradleException(
                "fetchSherpaAar: SHA-256 mismatch for $sherpaAarFileName. expected=$sherpaAarSha256 actual=$actual"
            )
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            throw GradleException("fetchSherpaAar: could not move verified download to ${target.path}")
        }
        logger.lifecycle("fetchSherpaAar: downloaded and verified ${target.name} (${target.length()} bytes)")
    }
}

tasks.named("preBuild") {
    dependsOn(fetchSherpaAar)
}

android {
    namespace = "app.memo"
    compileSdk = 35
    ndkVersion = pinnedNdkVersion

    defaultConfig {
        applicationId = "app.memo"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // Always optimise native code: a Debug-built llama.cpp is unusably slow on device
                // and bloats the APK with symbols. AGP passes CMAKE_BUILD_TYPE first; this later value wins.
                // c++_static: libmemo_llama carries its own libc++ built by NDK 27, so it never
                // depends on the libc++_shared.so that the sherpa AAR ships (see D-026).
                // ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES: 16 KB-aligned segments, required by Play for targetSdk 35.
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = pinnedCmakeVersion
        }
    }

    signingConfigs {
        // Committed dev keystore for the debug build type (AGENTS.md rule 10, D-048).
        // This is deliberately NOT a production secret: it only protects local/CI debug
        // artifacts so every published debug APK shares one signing identity and
        // installs in place. Release signing stays a deliberate M6 decision.
        create("memoDebug") {
            storeFile = rootProject.file("keystore/memo-debug.jks")
            storePassword = "memo-debug"
            keyAlias = "memo-debug"
            keyPassword = "memo-debug"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // Shared in-repo keystore (AGENTS.md rule 10): every debug APK — local and CI —
            // is signed with the same key so new builds install over the existing install
            // instead of failing with "package conflicts with an existing package".
            signingConfig = signingConfigs.getByName("memoDebug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // VoiceLoopTest drives the loop on the JVM; the loop logs through android.util.Log,
            // which must become a no-op there instead of throwing.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // sherpa-onnx ships libc++_shared.so. libmemo_llama links libc++ statically (c++_static),
            // so this pick only chooses the sherpa copy and cannot clash with the llama build.
            pickFirsts += listOf("**/libc++_shared.so")
        }
    }

    lint {
        // Advisory until M4: keep CI green while building; tighten to abortOnError=true later.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(files("libs/$sherpaAarFileName"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.glance:glance-appwidget:1.2.0")
    // bzip2 + tar for the sherpa-onnx voice archives (.tar.bz2). Android ships no bzip2 codec.
    // 1.28.0 was released 2025-07-26 and is the newest on Maven Central (see ENGINEERING_LOG D-038).
    implementation("org.apache.commons:commons-compress:1.28.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.21")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.21")

    // Device-verification tests (androidTest): the M2.A2 emulator Build step runs through these.
    // 1.3.0 / 1.7.0 are the newest stable androidx.test releases (verified on Google Maven).
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
