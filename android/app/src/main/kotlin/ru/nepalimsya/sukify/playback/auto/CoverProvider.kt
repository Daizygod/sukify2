package ru.nepalimsya.sukify.playback.auto

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.util.Base64
import android.os.ParcelFileDescriptor
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import ru.nepalimsya.sukify.R

/**
 * Обложки для Android Auto и экрана блокировки машины: хост не ходит в сеть
 * за картинками, ему нужен content://. Картинку грузит общий Coil — значит,
 * скачанные обложки берутся с диска и работают без интернета.
 */
class CoverProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CoverEntryPoint {
        fun imageLoader(): ImageLoader
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("нет контекста")
        val dir = File(ctx.cacheDir, "auto-covers").apply { mkdirs() }
        val file = if (uri.pathSegments.firstOrNull() == PATH_LIKED) {
            File(dir, "liked-v1.png").also { if (!it.exists()) save(likedCover(ctx), it) }
        } else {
            val url = uri.getQueryParameter(PARAM_URL)
                ?.takeIf { verify(key(ctx), it, uri.getQueryParameter(PARAM_SIG)) }
                ?: throw FileNotFoundException("не обложка")
            File(dir, sha1(url) + ".png").also {
                if (!it.exists()) {
                    save(load(ctx, url), it)
                    trim(dir)
                }
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** openFile зовётся с пула binder-потоков — ждать загрузку здесь можно. */
    private fun load(ctx: Context, url: String): Bitmap {
        val loader = EntryPointAccessors.fromApplication(ctx, CoverEntryPoint::class.java).imageLoader()
        return runBlocking {
            val result = loader.execute(ImageRequest.Builder(ctx).data(url).size(SIZE).allowHardware(false).build())
            (result as? SuccessResult)?.image?.toBitmap()
        } ?: throw FileNotFoundException("обложка не загрузилась")
    }

    /** «Любимые треки» — как в вебе: фиолетовый градиент и белое сердце. */
    private fun likedCover(ctx: Context): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val side = SIZE.toFloat()
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, side, side, LIKED_START, LIKED_END, Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, side, side, paint)
        val heart = SIZE * 3 / 8
        ctx.getDrawable(R.drawable.widget_heart_fill)?.mutate()?.apply {
            setTint(android.graphics.Color.WHITE)
            setBounds((SIZE - heart) / 2, (SIZE - heart) / 2, (SIZE + heart) / 2, (SIZE + heart) / 2)
            draw(canvas)
        }
        return bitmap
    }

    private fun save(bitmap: Bitmap, file: File) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(file)
    }

    override fun getType(uri: Uri): String = "image/png"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /** Держим не больше 300 картинок — старые уходят первыми. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.extension == "png" } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val PARAM_URL = "u"
        private const val PARAM_SIG = "s"
        private const val PATH_LIKED = "liked"
        private const val LIKED_START = 0xFF450AF5.toInt()
        private const val LIKED_END = 0xFF8E8EE5.toInt()
        private const val SIZE = 320
        private const val MAX_FILES = 300

        fun likedUri(): Uri = Uri.Builder().scheme("content").authority(AutoLibrary.AUTHORITY).appendPath(PATH_LIKED).build()

        fun uriFor(context: Context, url: String): Uri = Uri.Builder()
            .scheme("content")
            .authority(AutoLibrary.AUTHORITY)
            .appendPath("cover")
            .appendQueryParameter(PARAM_URL, url)
            .appendQueryParameter(PARAM_SIG, sign(key(context), url))
            .build()

        /**
         * Провайдер открыт наружу (машине), поэтому адрес подписан ключом этой
         * установки: отдаём только выданное нами, а не любую ссылку.
         */
        internal fun sign(key: ByteArray, url: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(url.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
        }

        internal fun verify(key: ByteArray, url: String, signature: String?): Boolean =
            signature != null && MessageDigest.isEqual(sign(key, url).toByteArray(), signature.toByteArray())

        @Volatile private var cachedKey: ByteArray? = null

        private fun key(context: Context): ByteArray = cachedKey ?: synchronized(this) {
            cachedKey ?: run {
                val prefs = context.getSharedPreferences("auto-covers", Context.MODE_PRIVATE)
                val bytes = prefs.getString("key", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
                    ?: ByteArray(32).also {
                        SecureRandom().nextBytes(it)
                        prefs.edit().putString("key", Base64.encodeToString(it, Base64.NO_WRAP)).apply()
                    }
                bytes.also { cachedKey = it }
            }
        }
    }
}
