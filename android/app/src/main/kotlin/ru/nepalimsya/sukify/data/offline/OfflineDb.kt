package ru.nepalimsya.sukify.data.offline

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Офлайн-копия медиатеки из манифеста. Её хватает, чтобы без сети открыть
 * «Любимые» на тысячи строк, плейлисты и альбомы, а плееру — собрать трек
 * (название, исполнители, обложка, громкость) без единого запроса.
 */

@Entity(tableName = "offline_tracks")
data class OfflineTrackEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val releaseId: Long?,
    val trackNumber: Int?,
    val durationMs: Long,
    /** id исполнителей через запятую в порядке трека. */
    val artistIds: String,
    val lufs: Double?,
    val bpm: Int?,
    val camelot: String?,
    val preview: Boolean,
    val unofficial: Boolean,
    val ownCover: Boolean,
    /** Ревизия и размер аудио; null — у трека нет звука. */
    val audioRev: String?,
    val audioSize: Long?,
)

@Entity(tableName = "offline_releases")
data class OfflineReleaseEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val slug: String,
    val type: String?,
    val year: Int?,
    val artistId: Long?,
    val coverV: Long?,
    val bg: String?,
)

@Entity(tableName = "offline_artists")
data class OfflineArtistEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val slug: String,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val bg: String?,
)

/** Подборка из манифеста: «Любимые», плейлист, альбом, исполнитель, трек. */
@Entity(tableName = "offline_sources")
data class OfflineSourceEntity(
    @PrimaryKey val key: String,
    val type: String,
    val refId: Long?,
    /** Порядок, в котором сервер прислал подборки. */
    val position: Int,
    val title: String?,
    val description: String?,
    val coverUrl: String?,
    val isPublic: Boolean,
    val isOwner: Boolean,
    val isCollaborative: Boolean,
    val ownerName: String?,
    val ownerUsername: String?,
    val updatedAt: Long?,
    val trackCount: Int,
)

/** Состав подборки в её порядке. */
@Entity(
    tableName = "offline_source_tracks",
    primaryKeys = ["sourceKey", "position"],
    indices = [Index("trackId")],
)
data class SourceTrackEntity(
    val sourceKey: String,
    val position: Int,
    val trackId: Long,
    /** id записи плейлиста (для удаления и перестановки). */
    val itemId: Long?,
    /** Когда добавлен (лайк, строка плейлиста), unix-секунды. */
    val addedAt: Long?,
)

/** Состояние загрузки трека. */
object DownloadState {
    const val QUEUED = 0
    const val DOWNLOADING = 1
    const val DONE = 2
    const val FAILED = 3
}

/**
 * Скачанный (или ждущий загрузки) трек. [rev] — какая ревизия нужна сейчас,
 * [fileRev] — какая лежит на диске: пока новая не докачана, играет старая.
 */
@Entity(tableName = "downloads", indices = [Index("state")])
data class DownloadEntity(
    @PrimaryKey val trackId: Long,
    val rev: String,
    val size: Long,
    val state: Int,
    val bytes: Long = 0,
    val fileRev: String? = null,
    val path: String? = null,
    val error: String? = null,
    val attempts: Int = 0,
    val queuedAt: Long = System.currentTimeMillis(),
    val doneAt: Long? = null,
)

/** Тексты скачанных треков — чтобы «Текст» работал без сети. */
@Entity(tableName = "offline_lyrics")
data class OfflineLyricsEntity(
    @PrimaryKey val trackId: Long,
    val synced: String?,
    val plain: String?,
)

/** Служебное: ETag манифеста, время сверки, шаблоны ссылок. */
@Entity(tableName = "offline_meta")
data class OfflineMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** Строка состава подборки вместе с треком — для офлайн-списков. */
data class SourceTrackRow(
    val position: Int,
    val itemId: Long?,
    val addedAt: Long?,
    val id: Long,
    val title: String,
    val releaseId: Long?,
    val trackNumber: Int?,
    val durationMs: Long,
    val artistIds: String,
    val lufs: Double?,
    val bpm: Int?,
    val camelot: String?,
    val preview: Boolean,
    val unofficial: Boolean,
    val ownCover: Boolean,
    val audioRev: String?,
    val audioSize: Long?,
)

@Dao
interface OfflineCatalogDao {

    @Transaction
    suspend fun replaceAll(
        tracks: List<OfflineTrackEntity>,
        releases: List<OfflineReleaseEntity>,
        artists: List<OfflineArtistEntity>,
        sources: List<OfflineSourceEntity>,
        sourceTracks: List<SourceTrackEntity>,
    ) {
        clearSourceTracks()
        clearSources()
        clearTracks()
        clearReleases()
        clearArtists()
        insertTracks(tracks)
        insertReleases(releases)
        insertArtists(artists)
        insertSources(sources)
        insertSourceTracks(sourceTracks)
    }

    @Query("DELETE FROM offline_tracks") suspend fun clearTracks()

    @Query("DELETE FROM offline_releases") suspend fun clearReleases()

    @Query("DELETE FROM offline_artists") suspend fun clearArtists()

    @Query("DELETE FROM offline_sources") suspend fun clearSources()

    @Query("DELETE FROM offline_source_tracks") suspend fun clearSourceTracks()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTracks(rows: List<OfflineTrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertReleases(rows: List<OfflineReleaseEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertArtists(rows: List<OfflineArtistEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSources(rows: List<OfflineSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSourceTracks(rows: List<SourceTrackEntity>)

    @Query("SELECT * FROM offline_sources ORDER BY position")
    suspend fun sources(): List<OfflineSourceEntity>

    @Query("SELECT * FROM offline_sources ORDER BY position")
    fun observeSources(): Flow<List<OfflineSourceEntity>>

    @Query("SELECT * FROM offline_source_tracks")
    suspend fun allSourceTracks(): List<SourceTrackEntity>

    @Query("SELECT * FROM offline_tracks")
    suspend fun tracks(): List<OfflineTrackEntity>

    @Query("SELECT * FROM offline_tracks WHERE id IN (:ids)")
    suspend fun tracksByIds(ids: List<Long>): List<OfflineTrackEntity>

    @Query("SELECT * FROM offline_releases")
    suspend fun releases(): List<OfflineReleaseEntity>

    @Query("SELECT * FROM offline_releases WHERE id IN (:ids)")
    suspend fun releasesByIds(ids: List<Long>): List<OfflineReleaseEntity>

    @Query("SELECT * FROM offline_artists")
    suspend fun artists(): List<OfflineArtistEntity>

    @Query("SELECT * FROM offline_artists WHERE id IN (:ids)")
    suspend fun artistsByIds(ids: List<Long>): List<OfflineArtistEntity>

    @Query(
        """
        SELECT st.position, st.itemId, st.addedAt, t.id, t.title, t.releaseId, t.trackNumber, t.durationMs,
               t.artistIds, t.lufs, t.bpm, t.camelot, t.preview, t.unofficial, t.ownCover, t.audioRev, t.audioSize
        FROM offline_source_tracks st JOIN offline_tracks t ON t.id = st.trackId
        WHERE st.sourceKey = :key ORDER BY st.position
        """,
    )
    suspend fun sourceTracks(key: String): List<SourceTrackRow>

    @Query("SELECT COUNT(*) FROM offline_tracks")
    suspend fun trackCount(): Int

    @Query("SELECT * FROM offline_meta WHERE `key` = :key")
    suspend fun meta(key: String): OfflineMetaEntity?

    @Upsert
    suspend fun putMeta(meta: OfflineMetaEntity)

    @Query("DELETE FROM offline_meta")
    suspend fun clearMeta()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY queuedAt, trackId")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE trackId = :trackId")
    suspend fun get(trackId: Long): DownloadEntity?

    /** Что осталось скачать: новые, недокачанные и упавшие не слишком много раз. */
    @Query(
        """
        SELECT * FROM downloads
        WHERE state != ${DownloadState.DONE} AND attempts < :maxAttempts
        ORDER BY state = ${DownloadState.DOWNLOADING} DESC, queuedAt, trackId
        LIMIT :limit
        """,
    )
    suspend fun pending(limit: Int, maxAttempts: Int): List<DownloadEntity>

    @Query("SELECT COUNT(*) FROM downloads WHERE state != ${DownloadState.DONE} AND attempts < :maxAttempts")
    suspend fun pendingCount(maxAttempts: Int): Int

    @Upsert
    suspend fun upsert(rows: List<DownloadEntity>)

    @Query("DELETE FROM downloads WHERE trackId IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM downloads")
    suspend fun clear()

    @Query("UPDATE downloads SET state = ${DownloadState.DOWNLOADING}, bytes = :bytes WHERE trackId = :trackId")
    suspend fun progress(trackId: Long, bytes: Long)

    @Query(
        """
        UPDATE downloads SET state = ${DownloadState.DONE}, bytes = size, fileRev = :rev, path = :path,
               error = NULL, attempts = 0, doneAt = :now
        WHERE trackId = :trackId AND rev = :rev
        """,
    )
    suspend fun done(trackId: Long, rev: String, path: String, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE downloads SET state = ${DownloadState.FAILED}, error = :error, attempts = attempts + 1 WHERE trackId = :trackId")
    suspend fun failed(trackId: Long, error: String)

    @Query("UPDATE downloads SET path = :path WHERE trackId = :trackId")
    suspend fun movePath(trackId: Long, path: String)

    /** «Повторить»: упавшие — снова в очередь. */
    @Query("UPDATE downloads SET state = ${DownloadState.QUEUED}, attempts = 0, error = NULL WHERE state = ${DownloadState.FAILED}")
    suspend fun retryFailed()

    /** Прерванные посреди загрузки (убили процесс) — снова в очередь. */
    @Query("UPDATE downloads SET state = ${DownloadState.QUEUED} WHERE state = ${DownloadState.DOWNLOADING}")
    suspend fun resetInterrupted()
}

@Dao
interface OfflineLyricsDao {
    @Query("SELECT * FROM offline_lyrics WHERE trackId = :trackId")
    suspend fun get(trackId: Long): OfflineLyricsEntity?

    @Upsert
    suspend fun put(row: OfflineLyricsEntity)

    @Query("SELECT trackId FROM offline_lyrics")
    suspend fun ids(): List<Long>

    @Query("DELETE FROM offline_lyrics WHERE trackId NOT IN (SELECT trackId FROM downloads)")
    suspend fun pruneOrphans()

    @Query("DELETE FROM offline_lyrics")
    suspend fun clear()
}

/** Действие с медиатекой, сделанное без сети: уйдёт на сервер, когда связь появится. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** like_track | unlike_track | like_album | unlike_album | follow | unfollow */
    val action: String,
    /** id трека/релиза или slug исполнителя. */
    val ref: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(row: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY id")
    suspend fun all(): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    /** Лайк и снятие лайка одного и того же без сети гасят друг друга. */
    @Query("DELETE FROM outbox WHERE ref = :ref AND action IN (:actions)")
    suspend fun deleteFor(ref: String, actions: List<String>): Int

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

/** Переход пары треков для игры без сети (null-переход — пары без своего перехода). */
@Entity(tableName = "offline_transitions", primaryKeys = ["fromId", "toId"])
data class OfflineTransitionEntity(
    val fromId: Long,
    val toId: Long,
    /** TransitionResource в JSON; null — у пары своего перехода нет. */
    val json: String?,
    val fetchedAt: Long = System.currentTimeMillis(),
)

@Dao
interface OfflineTransitionDao {
    @Query("SELECT * FROM offline_transitions WHERE fromId = :from AND toId = :to")
    suspend fun get(from: Long, to: Long): OfflineTransitionEntity?

    @Upsert
    suspend fun put(rows: List<OfflineTransitionEntity>)

    @Query("SELECT fromId || ':' || toId FROM offline_transitions")
    suspend fun keys(): List<String>

    @Query("DELETE FROM offline_transitions")
    suspend fun clear()
}
