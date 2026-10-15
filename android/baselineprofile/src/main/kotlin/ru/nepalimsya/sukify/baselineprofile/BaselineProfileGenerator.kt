package ru.nepalimsya.sukify.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Снимает Baseline Profile (что заранее скомпилировать) и Startup Profile
 * (что положить в первый dex): gradlew :app:generateBaselineProfile — со сборки
 * staging на эмуляторе или телефоне со стендом (adb reverse 8088/9000/8001).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    /** Холодный старт до главной с данными — он же Startup Profile. */
    @Test
    fun startup() = rule.collect(packageName = targetAppId(), includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        signInIfNeeded()
        waitForHome()
    }

    /** Основные пути: главная, «Любимые», плеер, поиск. */
    @Test
    fun journeys() = rule.collect(packageName = targetAppId()) {
        pressHome()
        startActivityAndWait()
        signInIfNeeded()
        waitForHome()
        scrollHome()
        openLiked()
        scrollCollection()
        playAndOpenPlayer()
        search()
        goHome()
    }
}
