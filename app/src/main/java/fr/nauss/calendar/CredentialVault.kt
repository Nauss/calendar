package fr.nauss.calendar

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

data class Account(val endpoint: String, val user: String, val password: String, val localHttp: Boolean)

/** Credentials are never backed up, logged, or stored in preferences. */
class CredentialVault(context: Context, filename: String = "account.gcm", private val alias: String = "agenda-account-v1") {
    private val file=AtomicFile(File(context.noBackupFilesDir,filename))
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    @Synchronized fun read(): Account? {
        if(!file.baseFile.exists()) return null
        val bytes=file.readFully(); require(bytes.size>29 && bytes[0].toInt()==1)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(1,13)))
        val plain=cipher.doFinal(bytes.copyOfRange(13,bytes.size))
        try { val json=JSONObject(String(plain,Charsets.UTF_8)); return Account(json.getString("endpoint"),json.getString("user"),json.getString("password"),json.getBoolean("localHttp")) } finally { plain.fill(0) }
    }
    @Synchronized fun save(account: Account) {
        val plain=JSONObject().put("endpoint",account.endpoint).put("user",account.user).put("password",account.password).put("localHttp",account.localHttp).toString().toByteArray(Charsets.UTF_8)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key())
        val encrypted=try { byteArrayOf(1)+cipher.iv+cipher.doFinal(plain) } finally { plain.fill(0) }
        val stream=file.startWrite()
        try { stream.write(encrypted);file.finishWrite(stream) } catch(e: Exception) { file.failWrite(stream);throw e }
    }
    @Synchronized fun clear() { file.delete() }
}
