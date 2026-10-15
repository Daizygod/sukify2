package ru.nepalimsya.sukify.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import ru.nepalimsya.sukify.data.offline.DownloadDao
import ru.nepalimsya.sukify.data.offline.DownloadEntity
import ru.nepalimsya.sukify.data.offline.OfflineArtistEntity
import ru.nepalimsya.sukify.data.offline.OfflineCatalogDao
import ru.nepalimsya.sukify.data.offline.OfflineLyricsDao
import ru.nepalimsya.sukify.data.offline.OfflineLyricsEntity
import ru.nepalimsya.sukify.data.offline.OfflineMetaEntity
import ru.nepalimsya.sukify.data.offline.OfflineReleaseEntity
import ru.nepalimsya.sukify.data.offline.OfflineSourceEntity
import ru.nepalimsya.sukify.data.offline.OfflineTrackEntity
import ru.nepalimsya.sukify.data.offline.OfflineTransitionDao
import ru.nepalimsya.sukify.data.offline.OfflineTransitionEntity
import ru.nepalimsya.sukify.data.offline.OutboxDao
import ru.nepalimsya.sukify.data.offline.OutboxEntity
import ru.nepalimsya.sukify.data.offline.SourceTrackEntity

/** Прослушивание, которое не удалось отправить (не было сети). */
@Entity(tableName = "pending_plays")
data class PendingPlayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    /** Когда трек начал играть, unix-секунды. */
    val playedAt: Long,
)

@Dao
interface PendingPlayDao {
    @Insert
    suspend fun insert(play: PendingPlayEntity)

    @Query("SELECT * FROM pending_plays ORDER BY id LIMIT :limit")
    suspend fun oldest(limit: Int): List<PendingPlayEntity>

    @Query("DELETE FROM pending_plays WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM pending_plays")
    suspend fun count(): Int
}

@Database(
    entities = [
        PendingPlayEntity::class,
        OfflineTrackEntity::class,
        OfflineReleaseEntity::class,
        OfflineArtistEntity::class,
        OfflineSourceEntity::class,
        SourceTrackEntity::class,
        DownloadEntity::class,
        OfflineLyricsEntity::class,
        OfflineMetaEntity::class,
        OutboxEntity::class,
        OfflineTransitionEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class SukifyDatabase : RoomDatabase() {
    abstract fun pendingPlays(): PendingPlayDao

    abstract fun offlineCatalog(): OfflineCatalogDao

    abstract fun downloads(): DownloadDao

    abstract fun offlineLyrics(): OfflineLyricsDao

    abstract fun outbox(): OutboxDao

    abstract fun offlineTransitions(): OfflineTransitionDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SukifyDatabase =
        Room.databaseBuilder(context, SukifyDatabase::class.java, "sukify.db")
            // До первого релиза схема меняется свободно; потом — только миграциями.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun pendingPlayDao(db: SukifyDatabase): PendingPlayDao = db.pendingPlays()

    @Provides
    fun offlineCatalogDao(db: SukifyDatabase): OfflineCatalogDao = db.offlineCatalog()

    @Provides
    fun downloadDao(db: SukifyDatabase): DownloadDao = db.downloads()

    @Provides
    fun offlineLyricsDao(db: SukifyDatabase): OfflineLyricsDao = db.offlineLyrics()

    @Provides
    fun outboxDao(db: SukifyDatabase): OutboxDao = db.outbox()

    @Provides
    fun offlineTransitionDao(db: SukifyDatabase): OfflineTransitionDao = db.offlineTransitions()
}
