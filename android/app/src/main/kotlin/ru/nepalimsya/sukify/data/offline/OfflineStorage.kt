package ru.nepalimsya.sukify.data.offline

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Где лежат загрузки. Только app-specific папки: разрешений не нужно,
 * другим приложениям файлы не видны, при удалении приложения стираются.
 *
 *  offline/audio/{id}.{rev}.mp3        — трек (ревизия в имени: новая версия
 *                                        файла не затирает играющую старую)
 *  offline/audio/{id}.{rev}.mp3.part   — недокачанный
 *  offline/covers/r{id}_{size}_{v}.webp — обложка релиза
 *  offline/covers/t{id}_{size}.webp     — своя обложка трека
 */
@Singleton
class OfflineStorage @Inject constructor(@ApplicationContext private val context: Context) {

    fun root(volume: StorageVolume): File? = when (volume) {
        StorageVolume.Phone -> File(context.filesDir, ROOT)
        StorageVolume.SdCard -> sdCardDir()?.let { File(it, ROOT) }
    }

    /** Карта памяти вставлена и доступна для записи. */
    val sdCardAvailable: Boolean get() = sdCardDir() != null

    private fun sdCardDir(): File? = context.getExternalFilesDirs(null)
        .drop(1)
        .firstOrNull { it != null && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }

    /** Куда писать: выбранный том, а если карты нет — память телефона. */
    fun writableRoot(volume: StorageVolume): File = root(volume) ?: root(StorageVolume.Phone)!!

    fun audioDir(volume: StorageVolume): File = File(writableRoot(volume), "audio").apply { mkdirs() }

    fun coverDir(volume: StorageVolume): File = File(writableRoot(volume), "covers").apply { mkdirs() }

    fun audioFile(volume: StorageVolume, trackId: Long, rev: String): File = File(audioDir(volume), "$trackId.$rev.mp3")

    fun partFile(volume: StorageVolume, trackId: Long, rev: String): File = File(audioDir(volume), "$trackId.$rev.mp3.part")

    /** Все папки загрузок на всех томах — для подсчёта места и полной очистки. */
    fun allRoots(): List<File> = listOfNotNull(root(StorageVolume.Phone), root(StorageVolume.SdCard)).filter { it.exists() }

    fun usedBytes(): Long = allRoots().sumOf { dir -> dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    fun freeBytes(volume: StorageVolume): Long = writableRoot(volume).let { it.mkdirs(); it.usableSpace }

    fun totalBytes(volume: StorageVolume): Long = writableRoot(volume).let { it.mkdirs(); it.totalSpace }

    private data class CoverEntry(val size: Int, val version: Long?, val file: File)

    /**
     * Указатель обложек на диске: Coil спрашивает его на каждую картинку,
     * поэтому папку не перечитываем — только после загрузки обложек.
     */
    @Volatile
    private var covers: Map<String, List<CoverEntry>> = emptyMap()

    fun refreshCoverIndex() {
        val index = HashMap<String, MutableList<CoverEntry>>()
        for (root in allRoots()) {
            for (f in File(root, "covers").listFiles().orEmpty()) {
                if (!f.isFile || f.name.endsWith(".part")) continue
                val name = f.nameWithoutExtension
                val kind = name.firstOrNull() ?: continue
                val parts = name.drop(1).split('_')
                val id = parts.getOrNull(0)?.toLongOrNull() ?: continue
                val size = parts.getOrNull(1)?.toIntOrNull() ?: continue
                index.getOrPut("$kind$id") { mutableListOf() } += CoverEntry(size, parts.getOrNull(2)?.toLongOrNull(), f)
            }
        }
        covers = index.mapValues { (_, list) -> list.sortedBy { it.size } }
    }

    /** Обложка на диске: релиз — той же версии, свой трек — любая; ближайший размер не меньше нужного. */
    fun coverFile(kind: Char, id: Long, size: Int, version: Long?): File? {
        val list = covers["$kind$id"]?.filter { version == null || it.version == null || it.version == version } ?: return null
        return (list.firstOrNull { it.size >= size } ?: list.lastOrNull())?.file
    }

    fun coverTarget(volume: StorageVolume, kind: Char, id: Long, size: Int, version: Long?): File =
        File(coverDir(volume), if (version != null) "$kind${id}_${size}_$version.webp" else "$kind${id}_$size.webp")

    fun wipe() {
        allRoots().forEach { it.deleteRecursively() }
        covers = emptyMap()
    }

    private companion object {
        const val ROOT = "offline"
    }
}
