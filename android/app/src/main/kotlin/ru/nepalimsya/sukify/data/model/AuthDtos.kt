package ru.nepalimsya.sukify.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Обёртка Laravel API Resource: {"data": …}. */
@Serializable
data class DataEnvelope<T>(val data: T)

@Serializable
data class UserDto(
    val id: Long,
    val name: String,
    val username: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val email: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean = false,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class RegisterRequest(
    val name: String,
    val email: String,
    val password: String,
    @SerialName("password_confirmation") val passwordConfirmation: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class TokenResponse(
    val token: String,
    @SerialName("device_id") val deviceId: Long? = null,
    val user: UserDto,
)
