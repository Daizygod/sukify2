package ru.nepalimsya.sukify.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import ru.nepalimsya.sukify.data.cache.Loadable

/**
 * Данные экрана: поток «кэш → сеть» с перезапуском по «Повторить» и
 * мгновенным первым кадром, если ответ уже лежит в памяти.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenData<T>(
    scope: CoroutineScope,
    initial: T?,
    private val source: () -> Flow<Loadable<T>>,
) {
    private val generation = MutableStateFlow(0)

    val state: StateFlow<Loadable<T>> = generation
        .flatMapLatest { source() }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5_000),
            initial?.let { Loadable.Ready(it, refreshing = true) } ?: Loadable.Loading,
        )

    fun reload() {
        generation.value++
    }
}
