package ru.nepalimsya.sukify.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import ru.nepalimsya.sukify.core.network.SukifyApi
import ru.nepalimsya.sukify.core.network.apiCall
import ru.nepalimsya.sukify.core.realtime.Realtime
import ru.nepalimsya.sukify.core.realtime.RealtimeChannel
import ru.nepalimsya.sukify.data.cache.Loadable
import ru.nepalimsya.sukify.data.model.FriendDto
import ru.nepalimsya.sukify.data.model.FriendUserDto
import ru.nepalimsya.sukify.data.model.FriendsActivityDto
import ru.nepalimsya.sukify.data.model.ListeningDto
import ru.nepalimsya.sukify.ui.components.AvatarImage
import ru.nepalimsya.sukify.ui.components.ErrorBlock
import ru.nepalimsya.sukify.ui.components.LoadingBlock
import ru.nepalimsya.sukify.ui.components.PageScaffold
import ru.nepalimsya.sukify.ui.components.bounceClick
import ru.nepalimsya.sukify.ui.icons.SukifyIcons
import ru.nepalimsya.sukify.ui.shell.LocalNavigator
import ru.nepalimsya.sukify.ui.shell.ProfileRoute
import ru.nepalimsya.sukify.ui.shell.ReleaseRoute
import ru.nepalimsya.sukify.ui.theme.SukifyColors
import ru.nepalimsya.sukify.ui.theme.SukifyType
import ru.nepalimsya.sukify.util.Format

@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val api: SukifyApi,
    private val realtime: Realtime,
    private val json: Json,
) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<FriendsActivityDto>>(Loadable.Loading)
    val state: StateFlow<Loadable<FriendsActivityDto>> = _state.asStateFlow()

    private val channels = mutableListOf<RealtimeChannel>()
    private val listeners = mutableListOf<Job>()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            apiCall { api.friendsActivity() }
                .onSuccess {
                    _state.value = Loadable.Ready(it)
                    subscribe(it.friends.map { f -> f.user.id })
                }
                .onFailure { if (_state.value !is Loadable.Ready) _state.value = Loadable.Failed(it.message ?: "Не удалось загрузить") }
        }
    }

    /** Живая активность: канал activity:{id} каждого друга. */
    private suspend fun subscribe(ids: List<Long>) {
        val open = channels.map { it.name }.toSet()
        for (id in ids) {
            val name = "activity:$id"
            if (name in open) continue
            val channel = realtime.openChannel(name) ?: return
            channels += channel
            listeners += viewModelScope.launch { channel.events.collect(::onListening) }
        }
    }

    private fun onListening(msg: JsonObject) {
        val now = (_state.value as? Loadable.Ready)?.data ?: return
        val activity = runCatching { json.decodeFromJsonElement(ListeningDto.serializer(), msg) }.getOrNull() ?: return
        val userId = (msg["user_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: return
        _state.value = Loadable.Ready(
            now.copy(friends = now.friends.map { if (it.user.id == userId) it.copy(activity = activity) else it }),
        )
    }

    /** Подписаться в ответ — станете друзьями. */
    fun followBack(user: FriendUserDto) {
        viewModelScope.launch {
            apiCall { api.followUser(user.username ?: user.id.toString()) }
            load()
        }
    }

    override fun onCleared() {
        listeners.forEach { it.cancel() }
        // Экран ушёл — подписки на активность больше не нужны.
        channels.forEach(realtime::closeLater)
        channels.clear()
    }
}

/** «Активность друзей» (FriendsPanel.vue): друг — это взаимная подписка. */
@Composable
fun FriendsScreen(vm: FriendsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val data = state.dataOrNull
    val navigator = LocalNavigator.current
    val openProfile: (FriendUserDto) -> Unit = { navigator.open(ProfileRoute(it.username ?: it.id.toString())) }

    PageScaffold("Активность друзей") {
        when {
            data == null && state is Loadable.Failed -> item { ErrorBlock((state as Loadable.Failed).message, onRetry = vm::load) }
            data == null -> item { LoadingBlock() }
            else -> {
                if (data.friends.isNotEmpty()) {
                    item { Kicker("Друзья") }
                    items(data.friends, key = { "f${it.user.id}" }) { f ->
                        FriendRow(
                            friend = f,
                            onOpen = { openProfile(f.user) },
                            onTrack = { slug -> navigator.open(ReleaseRoute(slug)) },
                        )
                    }
                } else {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text("Смотри, что слушают друзья.", style = SukifyType.BodyBold, color = SukifyColors.Text)
                            Text(
                                "Друзья — это взаимная подписка: активность видна, когда вы подписаны друг на друга.",
                                style = SukifyType.BodySmall,
                                color = SukifyColors.TextSubdued,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                // Подписаны на вас — одного нажатия не хватает до дружбы.
                if (data.followers.isNotEmpty()) {
                    item { Kicker("Подписаны на вас") }
                    items(data.followers, key = { "fr${it.user.id}" }) { f ->
                        PersonRow(f.user, "Подпишись в ответ — станете друзьями", onOpen = { openProfile(f.user) }) {
                            Text(
                                "В ответ",
                                style = SukifyType.BodySmallBold,
                                color = Color.Black,
                                modifier = Modifier
                                    .bounceClick(pressed = 0.92f) { vm.followBack(f.user) }
                                    .background(SukifyColors.Text, CircleShape)
                                    .padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
                // Односторонние подписки: активность недоступна, пока не взаимно.
                if (data.following.isNotEmpty()) {
                    item { Kicker("Вы подписаны") }
                    items(data.following, key = { "fg${it.user.id}" }) { f ->
                        PersonRow(f.user, "Активность появится, когда подпишется в ответ", onOpen = { openProfile(f.user) })
                    }
                }
            }
        }
    }
}

@Composable
private fun Kicker(text: String) {
    Text(text, style = SukifyType.BodyBold, color = SukifyColors.Text, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp))
}

@Composable
private fun Face(user: FriendUserDto) {
    if (user.avatarUrl != null) {
        AvatarImage(user.avatarUrl, 48.dp)
    } else {
        Box(Modifier.size(48.dp).background(SukifyColors.Chip, CircleShape), contentAlignment = Alignment.Center) {
            Text(user.name.firstOrNull()?.uppercase() ?: "?", style = SukifyType.BodyBold.copy(fontSize = 20.sp), color = SukifyColors.Text)
        }
    }
}

@Composable
private fun FriendRow(friend: FriendDto, onOpen: () -> Unit, onTrack: (String) -> Unit) {
    val a = friend.activity
    Row(
        Modifier.fillMaxWidth().bounceClick(pressed = 0.98f, onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Face(friend.user)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(friend.user.name, style = SukifyType.BodyBold, color = SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (a != null) {
                val slug = a.releaseSlug
                Text(
                    "${a.title} • ${a.artists}",
                    style = SukifyType.BodySmall,
                    color = SukifyColors.TextSubdued,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (slug != null) Modifier.bounceClick(pressed = 0.97f) { onTrack(slug) } else Modifier,
                )
                Text(Format.relativeDate(Format.epochSeconds(a.at)), style = SukifyType.Caption, color = SukifyColors.TextMuted)
            } else {
                Text("Давно ничего не слушал(-а)", style = SukifyType.BodySmall, color = SukifyColors.TextMuted)
            }
        }
        if (a != null) Icon(SukifyIcons.Volume, null, tint = SukifyColors.Accent, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun PersonRow(user: FriendUserDto, hint: String, onOpen: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().bounceClick(pressed = 0.98f, onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Face(user)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(user.name, style = SukifyType.BodyBold, color = SukifyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hint, style = SukifyType.BodySmall, color = SukifyColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}
