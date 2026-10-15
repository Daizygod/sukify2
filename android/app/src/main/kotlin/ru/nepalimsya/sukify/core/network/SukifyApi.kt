package ru.nepalimsya.sukify.core.network

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import ru.nepalimsya.sukify.data.model.AnalysisPairDto
import ru.nepalimsya.sukify.data.model.AppReleaseEnvelope
import ru.nepalimsya.sukify.data.model.ArtistDto
import ru.nepalimsya.sukify.data.model.ArtistLikedDto
import ru.nepalimsya.sukify.data.model.ConnectionTokenDto
import ru.nepalimsya.sukify.data.model.DailyMixDetailDto
import ru.nepalimsya.sukify.data.model.DailyMixDto
import ru.nepalimsya.sukify.data.model.DataEnvelope
import ru.nepalimsya.sukify.data.model.DesktopDeviceDto
import ru.nepalimsya.sukify.data.model.DiscordDto
import ru.nepalimsya.sukify.data.model.DiscordEnvelope
import ru.nepalimsya.sukify.data.model.FriendsActivityDto
import ru.nepalimsya.sukify.data.model.FullTransitions
import ru.nepalimsya.sukify.data.model.GenreDetailDto
import ru.nepalimsya.sukify.data.model.GenreDto
import ru.nepalimsya.sukify.data.model.HistoryItemDto
import ru.nepalimsya.sukify.data.model.HomeDto
import ru.nepalimsya.sukify.data.model.ImportJobDto
import ru.nepalimsya.sukify.data.model.InviteDto
import ru.nepalimsya.sukify.data.model.JamSessionDto
import ru.nepalimsya.sukify.data.model.LikedImportDto
import ru.nepalimsya.sukify.data.model.LikesDto
import ru.nepalimsya.sukify.data.model.LoginRequest
import ru.nepalimsya.sukify.data.model.LyricsDto
import ru.nepalimsya.sukify.data.model.NullableData
import ru.nepalimsya.sukify.data.model.Page
import ru.nepalimsya.sukify.data.model.PendingDeviceDto
import ru.nepalimsya.sukify.data.model.PinDto
import ru.nepalimsya.sukify.data.model.PlaybackSettingsDto
import ru.nepalimsya.sukify.data.model.PlaylistDto
import ru.nepalimsya.sukify.data.model.PlaylistRequest
import ru.nepalimsya.sukify.data.model.ProfileDto
import ru.nepalimsya.sukify.data.model.RegisterRequest
import ru.nepalimsya.sukify.data.model.ReleaseDto
import ru.nepalimsya.sukify.data.model.SearchDto
import ru.nepalimsya.sukify.data.model.SpotifyStatsDto
import ru.nepalimsya.sukify.data.model.StatsDto
import ru.nepalimsya.sukify.data.model.SubscriptionTokenDto
import ru.nepalimsya.sukify.data.model.TokenResponse
import ru.nepalimsya.sukify.data.model.TrackDto
import ru.nepalimsya.sukify.data.model.TransitionDto
import ru.nepalimsya.sukify.data.model.TransitionRequest
import ru.nepalimsya.sukify.data.model.TransitionSummaries
import ru.nepalimsya.sukify.data.model.UrlDto
import ru.nepalimsya.sukify.data.model.UserDto

/** API Sukify (backend/routes/api.php). Пути — относительно /api/. */
interface SukifyApi {

    // --- Вход ----------------------------------------------------------------

    @POST("mobile/login")
    suspend fun login(@Body body: LoginRequest): TokenResponse

    @POST("mobile/register")
    suspend fun register(@Body body: RegisterRequest): TokenResponse

    @POST("mobile/logout")
    suspend fun logout()

    @GET("me")
    suspend fun me(): DataEnvelope<UserDto>

    // --- Каталог -------------------------------------------------------------

    @GET("home")
    suspend fun home(): HomeDto

    @GET("search")
    suspend fun search(@Query("q") query: String, @Query("limit") limit: Int = 12): SearchDto

    @GET("genres")
    suspend fun genres(): DataEnvelope<List<GenreDto>>

    @GET("genres/{genre}")
    suspend fun genre(@Path("genre") genre: String): GenreDetailDto

    @GET("mixes/daily")
    suspend fun dailyMixes(): DataEnvelope<List<DailyMixDto>>

    @GET("mixes/daily/{n}")
    suspend fun dailyMix(@Path("n") n: Int): DailyMixDetailDto

    /** Весь каталог страницами по 48: sort = added | date | name. */
    @GET("releases")
    suspend fun releases(@Query("sort") sort: String, @Query("page") page: Int): Page<ReleaseDto>

    @GET("releases/{slug}")
    suspend fun release(@Path("slug") slug: String): DataEnvelope<ReleaseDto>

    /** Треки по id в заданном порядке (перенос воспроизведения, восстановление сессии). */
    @GET("tracks-bulk")
    suspend fun tracksBulk(@Query("ids") ids: String): DataEnvelope<List<TrackDto>>

    @GET("tracks/{id}/lyrics")
    suspend fun lyrics(@Path("id") trackId: Long): LyricsDto

    @GET("tracks/{id}/radio")
    suspend fun trackRadio(@Path("id") trackId: Long): DataEnvelope<List<TrackDto>>

    @GET("artists/{slug}")
    suspend fun artist(@Path("slug") slug: String): DataEnvelope<ArtistDto>

    @GET("artists/{slug}/top-tracks")
    suspend fun artistTopTracks(@Path("slug") slug: String): DataEnvelope<List<TrackDto>>

    @GET("artists/{slug}/liked")
    suspend fun artistLiked(@Path("slug") slug: String): ArtistLikedDto

    @POST("artists/{slug}/follow")
    suspend fun followArtist(@Path("slug") slug: String): JsonObject

    @DELETE("artists/{slug}/follow")
    suspend fun unfollowArtist(@Path("slug") slug: String): JsonObject

    @GET("users/{username}")
    suspend fun profile(@Path("username") username: String): ProfileDto

    @POST("users/{username}/follow")
    suspend fun followUser(@Path("username") username: String): JsonObject

    @DELETE("users/{username}/follow")
    suspend fun unfollowUser(@Path("username") username: String): JsonObject

    /** Привязка настольного компаньона: что за компьютер ждёт подтверждения по коду. */
    @GET("desktop/pending/{code}")
    suspend fun pendingDevice(@Path("code") code: String): DataEnvelope<PendingDeviceDto>

    @POST("desktop/approve")
    suspend fun approveDevice(@Body body: JsonObject): JsonObject

    // --- Настройки -----------------------------------------------------------

    @PUT("playback-settings")
    suspend fun savePlaybackSettings(@Body body: JsonObject): JsonObject

    @PUT("me/profile")
    suspend fun saveProfile(@Body body: JsonObject): DataEnvelope<UserDto>

    @Multipart
    @POST("me/avatar")
    suspend fun uploadAvatar(@Part avatar: MultipartBody.Part): DataEnvelope<UserDto>

    @DELETE("me/avatar")
    suspend fun deleteAvatar(): DataEnvelope<UserDto>

    @GET("me/discord")
    suspend fun discord(): DiscordEnvelope

    @PUT("me/discord")
    suspend fun saveDiscord(@Body body: JsonObject): DataEnvelope<DiscordDto>

    @DELETE("me/discord")
    suspend fun unlinkDiscord(): JsonObject

    @GET("discord/redirect")
    suspend fun discordRedirect(): UrlDto

    @GET("me/desktop-devices")
    suspend fun desktopDevices(): DataEnvelope<List<DesktopDeviceDto>>

    @DELETE("me/desktop-devices/{id}")
    suspend fun unlinkDevice(@Path("id") id: Long): JsonObject

    @GET("me/stats")
    suspend fun stats(): StatsDto

    /** Статистика по архиву Spotify; часы — в часовом поясе телефона. */
    @GET("me/spotify-stats")
    suspend fun spotifyStats(@Query("tz") tz: String, @Query("year") year: Int? = null): SpotifyStatsDto

    // --- Импорт из Spotify ---------------------------------------------------

    @Multipart
    @POST("import/spotify")
    suspend fun uploadImport(@Part archive: MultipartBody.Part): ImportJobDto

    /** Последний импорт или null (тогда тело — JSON null, разбираем сами). */
    @GET("import/spotify")
    suspend fun latestImport(): JsonElement

    @GET("import/spotify/{id}")
    suspend fun importJob(@Path("id") id: Long): ImportJobDto

    @POST("import/spotify/{id}/cancel")
    suspend fun cancelImport(@Path("id") id: Long): ImportJobDto

    @POST("import/liked")
    suspend fun importLiked(@Body body: JsonObject): LikedImportDto

    /** Свежая сборка приложения — для автообновления. */
    @GET("app/android")
    suspend fun androidRelease(): AppReleaseEnvelope

    /** Друзья и что они слушают (FriendsPanel.vue). */
    @GET("me/friends-activity")
    suspend fun friendsActivity(): FriendsActivityDto

    @GET("me/history")
    suspend fun history(): DataEnvelope<List<HistoryItemDto>>

    /** «Что нового»: свежие релизы исполнителей из подписок. */
    @GET("me/notifications")
    suspend fun notifications(): DataEnvelope<List<ReleaseDto>>

    // --- Воспроизведение -----------------------------------------------------

    @GET("playback-settings")
    suspend fun playbackSettings(): DataEnvelope<PlaybackSettingsDto>

    @POST("tracks/{id}/play")
    suspend fun logPlay(@Path("id") trackId: Long): JsonObject

    /** Пачка прослушиваний, накопленных без сети: {"plays":[{track_id, played_at}]}. */
    @POST("me/plays")
    suspend fun logPlays(@Body body: JsonObject): JsonObject

    @GET("transitions")
    suspend fun transition(@Query("from") from: Long, @Query("to") to: Long): NullableData<TransitionDto>

    /** Все переходы пары — «Варианты для этой пары» в редакторе. */
    @GET("transitions/all")
    suspend fun transitionsAll(@Query("from") from: Long, @Query("to") to: Long): DataEnvelope<List<TransitionDto>>

    @POST("transitions")
    suspend fun saveTransition(@Body body: TransitionRequest): DataEnvelope<TransitionDto>

    @DELETE("transitions/{id}")
    suspend fun deleteTransition(@Path("id") id: Long): JsonObject

    @POST("transitions/{id}/like")
    suspend fun likeTransition(@Path("id") id: Long): LikesDto

    @DELETE("transitions/{id}/like")
    suspend fun unlikeTransition(@Path("id") id: Long): LikesDto

    /** «Использовать этот переход» — личный выбор поверх сообщества. */
    @POST("transitions/{id}/prefer")
    suspend fun preferTransition(@Path("id") id: Long): JsonObject

    @DELETE("transitions/{id}/prefer")
    suspend fun unpreferTransition(@Path("id") id: Long): JsonObject

    /** Темп, доли, тональность и волна пары — для редактора переходов. */
    @GET("analysis/pair")
    suspend fun analysisPair(@Query("from") from: Long, @Query("to") to: Long): DataEnvelope<AnalysisPairDto>

    /** Сводка переходов соседних пар — чипы между строками в режиме микса. */
    @GET("transitions/for-context")
    suspend fun transitionSummaries(@Query("pairs") pairs: String): TransitionSummaries

    /** Переходы для соседних пар подборки (до 200 пар); full=1 — целиком, для игры без сети. */
    @GET("transitions/for-context")
    suspend fun transitionsForContext(@Query("pairs") pairs: String, @Query("full") full: Int = 1): FullTransitions

    // --- Реалтайм ------------------------------------------------------------

    /** Токен Centrifugo + адрес сокета + личный канал пользователя. */
    @POST("realtime/connection-token")
    suspend fun connectionToken(): ConnectionTokenDto

    /** Токен подписки на комнату джема session:{id}. */
    @POST("realtime/subscription-token")
    suspend fun subscriptionToken(@Body body: JsonObject): SubscriptionTokenDto

    // --- Джем ----------------------------------------------------------------

    @POST("jam/sessions")
    suspend fun jamStart(): DataEnvelope<JamSessionDto>

    @POST("jam/sessions/join")
    suspend fun jamJoin(@Body body: JsonObject): DataEnvelope<JamSessionDto>

    @GET("jam/sessions/{id}")
    suspend fun jamSession(@Path("id") id: Long): DataEnvelope<JamSessionDto>

    @POST("jam/sessions/{id}/leave")
    suspend fun jamLeave(@Path("id") id: Long): JsonObject

    @POST("jam/sessions/{id}/end")
    suspend fun jamEnd(@Path("id") id: Long): JsonObject

    // --- Загрузки ------------------------------------------------------------

    /** Манифест загрузок: ETag в If-None-Match → 304, если ничего не поменялось. */
    @Streaming
    @GET("offline/manifest")
    suspend fun offlineManifest(
        @Query("include") include: String?,
        @Query("artists") artists: Int?,
        @Header("If-None-Match") etag: String?,
    ): Response<ResponseBody>

    // --- Медиатека -----------------------------------------------------------

    @GET("library/liked-track-ids")
    suspend fun likedTrackIds(): List<Long>

    @GET("library/liked-tracks")
    suspend fun likedTracks(@Query("page") page: Int, @Query("per_page") perPage: Int = 200): Page<TrackDto>

    @GET("library/playlists")
    suspend fun libraryPlaylists(): DataEnvelope<List<PlaylistDto>>

    @GET("library/liked-albums")
    suspend fun likedAlbums(): DataEnvelope<List<ReleaseDto>>

    @GET("library/followed-artists")
    suspend fun followedArtists(): DataEnvelope<List<ArtistDto>>

    @GET("library/pins")
    suspend fun pins(): DataEnvelope<List<PinDto>>

    @POST("library/pins")
    suspend fun pin(@Body body: PinDto): JsonObject

    @HTTP(method = "DELETE", path = "library/pins", hasBody = true)
    suspend fun unpin(@Body body: PinDto): JsonObject

    @POST("tracks/{id}/like")
    suspend fun likeTrack(@Path("id") trackId: Long): JsonObject

    @DELETE("tracks/{id}/like")
    suspend fun unlikeTrack(@Path("id") trackId: Long): JsonObject

    @POST("releases/{id}/like")
    suspend fun likeRelease(@Path("id") releaseId: Long): JsonObject

    @DELETE("releases/{id}/like")
    suspend fun unlikeRelease(@Path("id") releaseId: Long): JsonObject

    // --- Плейлисты -----------------------------------------------------------

    @GET("playlists/{id}")
    suspend fun playlist(@Path("id") id: Long): DataEnvelope<PlaylistDto>

    @GET("playlists/{id}/recommendations")
    suspend fun playlistRecommendations(@Path("id") id: Long): DataEnvelope<List<TrackDto>>

    @POST("playlists")
    suspend fun createPlaylist(@Body body: PlaylistRequest): DataEnvelope<PlaylistDto>

    /** Тело — JsonObject: description нужно уметь явно обнулить. */
    @PUT("playlists/{id}")
    suspend fun updatePlaylist(@Path("id") id: Long, @Body body: JsonObject): DataEnvelope<PlaylistDto>

    @DELETE("playlists/{id}")
    suspend fun deletePlaylist(@Path("id") id: Long): JsonObject

    @POST("playlists/{id}/tracks")
    suspend fun addToPlaylist(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @DELETE("playlists/{id}/tracks/{item}")
    suspend fun removeFromPlaylist(@Path("id") id: Long, @Path("item") itemId: Long): JsonObject

    @PUT("playlists/{id}/order")
    suspend fun reorderPlaylist(@Path("id") id: Long, @Body body: JsonObject): JsonObject

    @Multipart
    @POST("playlists/{id}/cover")
    suspend fun uploadPlaylistCover(@Path("id") id: Long, @Part cover: MultipartBody.Part): DataEnvelope<PlaylistDto>

    @DELETE("playlists/{id}/cover")
    suspend fun deletePlaylistCover(@Path("id") id: Long): DataEnvelope<PlaylistDto>

    @PUT("playlists/{id}/mix")
    suspend fun setPlaylistMix(@Path("id") id: Long, @Body body: JsonObject): DataEnvelope<PlaylistDto>

    @POST("playlists/{id}/invite")
    suspend fun invite(@Path("id") id: Long): InviteDto

    @POST("playlists/{id}/join/{token}")
    suspend fun joinPlaylist(@Path("id") id: Long, @Path("token") token: String): DataEnvelope<PlaylistDto>
}
