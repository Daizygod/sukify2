import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

// Генератор Baseline/Startup Profile и замеры (Macrobenchmark) для :app.
// Гоняется на подключённом эмуляторе или телефоне со стендом (adb reverse 8088/9000/8001).
android {
    namespace = "ru.nepalimsya.sukify.baselineprofile"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // На эмуляторе цифры неточные, но сравнить «с профилем / без» можно.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"

    buildTypes {
        // Как в :app: профиль снимается со staging — он смотрит на локальный стенд.
        create("staging") {
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}

androidComponents {
    // Только staging: release смотрит на боевой сервер, входить туда тестам незачем.
    beforeVariants(selector().withBuildType("nonMinifiedRelease")) { it.enable = false }
    beforeVariants(selector().withBuildType("benchmarkRelease")) { it.enable = false }

    // Тестам нужен id приложения проверяемой сборки (у staging — с суффиксом).
    onVariants { v ->
        val artifactsLoader = v.artifacts.getBuiltArtifactsLoader()
        v.instrumentationRunnerArguments.put(
            "targetAppId",
            v.testedApks.map { artifactsLoader.load(it)?.applicationId },
        )
    }
}
