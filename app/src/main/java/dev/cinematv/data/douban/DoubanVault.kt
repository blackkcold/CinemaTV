package dev.cinematv.data.douban

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private, backup-excluded authenticated encryption. No credential-bearing toString/logging. */
interface DoubanStorage { fun read(): JSONObject?; fun write(value: JSONObject); fun clear() }

class DoubanVault(context: Context) : DoubanStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "douban-account.v1"))
    private val alias = "cinematv.douban.session.v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized override fun read(): JSONObject? {
        if (!file.baseFile.exists()) return null
        val bytes = file.readFully(); require(bytes.size in 29..2_000_000)
        require(bytes[0].toInt() == 1)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD("CinemaTV Douban v1".toByteArray())
        val plain = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        return try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
    }
    @Synchronized override fun write(value: JSONObject) {
        val plain = value.toString().toByteArray(); require(plain.size <= 1_900_000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD("CinemaTV Douban v1".toByteArray())
        val encrypted = try { byteArrayOf(1) + cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        var output: java.io.FileOutputStream? = null
        try { output = file.startWrite(); output.write(encrypted); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    @Synchronized override fun clear() { file.delete(); KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
}
