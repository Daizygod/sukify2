package ru.nepalimsya.sukify.core.datastore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Шифрование секретов (токена входа) — чтобы его нельзя было просто прочитать из файла. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

/**
 * AES-GCM ключом из Android Keystore: ключ не покидает защищённое хранилище
 * телефона, а в файле лежит только шифротекст. Формат: 12 байт IV + шифротекст.
 */
@Singleton
class KeystoreSecretBox @Inject constructor() : SecretBox {

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    private fun key(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_SIZE))
        return cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "sukify.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
