package ru.nepalimsya.sukify.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/*
 * Пути пользователя для профиля и замеров. Элементы ищутся по testTag
 * (в приложении включён testTagsAsResourceId) и по видимому тексту.
 * Шаг, который не нашёл свой экран, пропускается — профиль всё равно снимется.
 */

fun targetAppId(): String = InstrumentationRegistry.getArguments().getString("targetAppId")
    ?: error("targetAppId не передан в аргументах раннера")

private const val TIMEOUT = 10_000L

/** Стенд: демо-аккаунт локального бэкенда. */
fun MacrobenchmarkScope.signInIfNeeded() {
    if (device.wait(Until.hasObject(By.res("home_list")), 3_000) == true) return
    if (device.wait(Until.hasObject(By.text("Войти")), TIMEOUT) != true) return
    val fields = device.findObjects(By.clazz("android.widget.EditText"))
    if (fields.size < 2) return
    fields[0].text = "demo@sukify.test"
    fields[1].text = "password"
    device.findObjects(By.text("Войти")).lastOrNull()?.click()
    device.wait(Until.hasObject(By.res("home_list")), 20_000)
}

/** Главная с данными (плитка «Любимых»), а не просто список со спиннером. */
fun MacrobenchmarkScope.waitForHome() {
    device.wait(Until.hasObject(By.res("home_list").hasDescendant(By.text("Любимые треки"))), TIMEOUT)
    // reportFullyDrawn приходит кадром позже — замер должен его застать.
    device.waitForIdle()
}

fun MacrobenchmarkScope.scrollHome() {
    val list = device.findObject(By.res("home_list")) ?: return
    fling(list, Direction.DOWN)
    fling(list, Direction.UP)
}

fun MacrobenchmarkScope.openLiked() {
    device.findObject(By.text("Моя медиатека"))?.click() ?: return
    if (device.wait(Until.hasObject(By.res("library_list")), TIMEOUT) != true) return
    device.findObject(By.res("library_list"))?.findObject(By.text("Любимые треки"))?.click() ?: return
    device.wait(Until.hasObject(By.res("collection_list")), TIMEOUT)
}

fun MacrobenchmarkScope.scrollCollection() {
    val list = device.findObject(By.res("collection_list")) ?: return
    fling(list, Direction.DOWN)
    fling(list, Direction.UP)
}

/** ▶ в шапке подборки → мини-плеер → «Сейчас играет» → назад. */
fun MacrobenchmarkScope.playAndOpenPlayer() {
    device.findObject(By.desc("Слушать"))?.click() ?: return
    if (device.wait(Until.hasObject(By.res("mini_player")), TIMEOUT) != true) return
    device.findObject(By.res("mini_player"))?.click()
    if (device.wait(Until.hasObject(By.res("now_playing")), TIMEOUT) != true) return
    device.waitForIdle()
    device.pressBack()
    device.wait(Until.hasObject(By.res("mini_player")), TIMEOUT)
    // Музыку — на паузу: следующему проходу нужен тот же старт.
    device.findObject(By.desc("Пауза"))?.click()
}

fun MacrobenchmarkScope.search() {
    device.findObject(By.text("Поиск"))?.click() ?: return
    device.wait(Until.hasObject(By.text("Что хочешь включить?")), TIMEOUT)
    device.findObject(By.text("Что хочешь включить?"))?.click() ?: return
    if (device.wait(Until.hasObject(By.res("search_field")), TIMEOUT) != true) return
    device.findObject(By.res("search_field"))?.text = "paper"
    device.wait(Until.hasObject(By.textContains("Paper")), TIMEOUT)
    device.pressBack()
    device.pressBack()
}

fun MacrobenchmarkScope.goHome() {
    device.findObject(By.text("Главная"))?.click()
    waitForHome()
}

private fun MacrobenchmarkScope.fling(list: UiObject2, direction: Direction) {
    // Отступ от краёв, чтобы жест не стал системным «назад».
    list.setGestureMargin(device.displayWidth / 5)
    list.fling(direction)
    device.waitForIdle()
}
