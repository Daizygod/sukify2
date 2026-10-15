package ru.nepalimsya.sukify.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Джем — совместное прослушивание (ListeningSessionResource). */
@Serializable
data class JamSessionDto(
    val id: Long,
    @SerialName("join_code") val joinCode: String,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("host_user_id") val hostUserId: Long? = null,
    @SerialName("is_host") val isHost: Boolean = false,
    val channel: String? = null,
    val host: UserDto? = null,
    val members: List<UserDto> = emptyList(),
)

@Serializable
data class SubscriptionTokenDto(val token: String)

/** GET /me/friends-activity: друзья (взаимная подписка) с тем, что слушают, и односторонние связи. */
@Serializable
data class FriendsActivityDto(
    val friends: List<FriendDto> = emptyList(),
    val following: List<FriendDto> = emptyList(),
    val followers: List<FriendDto> = emptyList(),
)

@Serializable
data class FriendDto(
    val user: FriendUserDto,
    val relation: String? = null,
    val activity: ListeningDto? = null,
)

@Serializable
data class FriendUserDto(
    val id: Long,
    val name: String,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/** Что слушает друг — из кэша сервера и из канала activity:{id}. */
@Serializable
data class ListeningDto(
    @SerialName("track_id") val trackId: Long? = null,
    val title: String = "",
    val artists: String = "",
    @SerialName("release_slug") val releaseSlug: String? = null,
    val at: String? = null,
)

/** Компьютер ждёт подтверждения привязки (LinkDevicePage.vue). */
@Serializable
data class PendingDeviceDto(
    @SerialName("user_code") val userCode: String,
    val name: String = "",
    val platform: String? = null,
)
