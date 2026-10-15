import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.baselineprofile)
}

// Скриншот-тесты (Roborazzi): PNG экранов рендерятся на JVM без эмулятора.
roborazzi {
    outputDir.set(file("screenshots"))
}

// Baseline/Startup Profile: снимается руками на эмуляторе со стендом
// (gradlew :app:generateBaselineProfile — со сборки staging, она смотрит на localhost)
// и лежит в src/main/generated — его получает и боевая сборка.
baselineProfile {
    mergeIntoMain = true
    automaticGenerationDuringBuild = false
    variants {
        register("staging") {
            from(project(":baselineprofile"))
        }
    }
}

// Подпись релиза: keystore приходит из CI (секреты), локально — debug-ключ.
val releaseKeystore: String? = System.getenv("SUKIFY_KEYSTORE")

android {
    namespace = "ru.nepalimsya.sukify"
    // Свежие androidx (navigation, hilt, coil) собираются против Android 17 (API 37.2).
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "ru.nepalimsya.sukify"
        minSdk = 31
        targetSdk = 36
        // CI проставляет номер сборки; локально — 1.
        versionCode = (System.getenv("SUKIFY_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("SUKIFY_VERSION_NAME") ?: "0.1.0-dev"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_SERVER", "\"https://sukify.nepalimsya.ru\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SUKIFY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SUKIFY_KEY_ALIAS")
                keyPassword = System.getenv("SUKIFY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Отладочная сборка ставится рядом с боевой и смотрит на локальный
            // стенд. localhost — через adb reverse (8088 nginx, 9000 MinIO,
            // 8001 Centrifugo): так и ссылки на файлы из API (localhost:9000)
            // открываются на эмуляторе и на телефоне по USB.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "DEFAULT_SERVER", "\"http://localhost:8088\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        // Как release (R8, ужатые ресурсы), но смотрит на локальный стенд и ставится
        // рядом: проверить, что минификация ничего не сломала, до публикации.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            buildConfigField("String", "DEFAULT_SERVER", "\"http://localhost:8088\"")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.animation.ExperimentalSharedTransitionApi",
            "androidx.media3.common.util.UnstableApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "kotlinx.coroutines.FlowPreview",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.glance.appwidget)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)
    implementation(libs.profileinstaller)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.database)
    implementation(libs.media3.common.ktx)

    implementation(libs.okhttp)
    // Подключается только в debug (BuildConfig.DEBUG); в релизе R8 его выкинет.
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.collections.immutable)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.centrifuge)
    implementation(libs.reorderable)
    implementation(libs.palette)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
