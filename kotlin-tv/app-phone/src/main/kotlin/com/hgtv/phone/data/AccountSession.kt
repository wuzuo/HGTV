// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.phone.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hgtv.core.AccountCredentials
import com.hgtv.core.CloudAccountClient
import com.hgtv.core.CloudFavorite
import com.hgtv.core.CloudProfile
import com.hgtv.core.CloudProgress
import com.hgtv.core.PhoneLogin
import com.hgtv.core.Series
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * 手机号登录与云端进度。
 *
 * 凭据放在 Android Keystore 加密的分区里（与 TV 端同一套做法，但独立文件：
 * 手机和电视是两条会话，一边退出不该把另一边踢下线）。
 *
 * 这里只做「登录 + 读写云端记录」，不碰界面；停在哪个协程由调用方决定，
 * 所有网络调用都切到 [Dispatchers.IO]。
 */
@Stable
class AccountSession(context: Context, private val http: OkHttpClient) {
    private val store = CredentialStore(context.applicationContext)
    private val client = CloudAccountClient(http)

    /** 当前一次登录尝试；验证码与设备身份是绑定的，不能跨实例复用。 */
    private var pending: PhoneLogin? = null

    var credentials by mutableStateOf<AccountCredentials?>(null)
        private set

    var profile by mutableStateOf<CloudProfile?>(null)
        private set

    var cloudHistory by mutableStateOf<List<CloudProgress>>(emptyList())
        private set

    var cloudFavorites by mutableStateOf<List<CloudFavorite>>(emptyList())
        private set

    /** 恢复会话、刷新资料过程中的提示；成功后清空。 */
    var message by mutableStateOf<String?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    val signedIn get() = credentials != null

    /** 冷启动时恢复登录态。失败不弹错，只把提示留给「我的」页展示。 */
    suspend fun restore() {
        if (credentials != null) return
        val saved = withContext(Dispatchers.IO) { runCatching { store.read() }.getOrNull() } ?: return
        credentials = saved
        refresh()
    }

    suspend fun sendCode(mobile: String): PhoneLogin.Sent =
        withContext(Dispatchers.IO) {
            val login = PhoneLogin(http)
            pending = login
            login.send(mobile)
        }

    /** 验证码换会话；成功后立刻拉一次资料与云端记录。 */
    suspend fun submitCode(code: String) {
        val login = pending ?: error("请先获取验证码")
        busy = true
        try {
            val obtained = withContext(Dispatchers.IO) { login.verify(code) }
            withContext(Dispatchers.IO) { store.save(obtained) }
            credentials = obtained
            pending = null
            refresh()
        } finally {
            busy = false
        }
    }

    suspend fun refresh() {
        val current = credentials ?: return
        busy = true
        try {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val who = client.profile(current)
                        val history = runCatching { client.history(current) }.getOrDefault(emptyList())
                        val favorites =
                            runCatching { client.favorites(current) }.getOrDefault(emptyList())
                        Triple(who, history, favorites)
                    }
                }
            result
                .onSuccess { (who, history, favorites) ->
                    profile = who
                    cloudHistory = history
                    cloudFavorites = favorites
                    message = null
                }
                .onFailure { problem ->
                    Log.w(TAG, "账号资料刷新失败", problem)
                    message = problem.message ?: "账号资料读取失败"
                }
        } finally {
            busy = false
        }
    }

    fun signOut() {
        runCatching { store.clear() }
        credentials = null
        profile = null
        cloudHistory = emptyList()
        cloudFavorites = emptyList()
        pending = null
        message = null
    }

    /** 云端这部剧的进度；没有则返回 null。 */
    fun cloudProgress(seriesId: String) = cloudHistory.firstOrNull { it.series.id == seriesId }

    /** 收藏/取消收藏同步到云端。未登录或失败都不影响本机状态。 */
    suspend fun pushFavorite(series: Series, add: Boolean) {
        val current = credentials ?: return
        withContext(Dispatchers.IO) {
            runCatching { client.setFavorite(current, series.id, add) }
                .onFailure { Log.w(TAG, "云端收藏同步失败", it) }
        }
    }

    /** 播放进度上传；失败静默，下一次心跳会再试。 */
    suspend fun uploadProgress(progress: CloudProgress, episodeCount: Int) {
        val current = credentials ?: return
        withContext(Dispatchers.IO) {
            runCatching { client.upload(current, progress, episodeCount) }
                .onFailure { Log.w(TAG, "进度上传失败", it) }
        }
    }

    private companion object {
        const val TAG = "HgtvPhoneAccount"
    }
}

/** 凭据的加密存储：AES/GCM，密钥在 Android Keystore 里，磁盘上只有密文。 */
private class CredentialStore(context: Context) {
    private val prefs =
        context.getSharedPreferences("phone-account-secure", Context.MODE_PRIVATE)
    private val alias = "hgtv.phone.account.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    fun read(): AccountCredentials? {
        val raw = prefs.getString("session", null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return AccountCredentials.fromJson(
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        )
    }

    fun save(credentials: AccountCredentials) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val raw = cipher.iv + cipher.doFinal(credentials.toJson().toByteArray())
        check(prefs.edit().putString("session", Base64.encodeToString(raw, Base64.NO_WRAP)).commit()) {
            "保存登录态失败"
        }
    }

    fun clear() {
        check(prefs.edit().clear().commit()) { "清除登录态失败" }
    }
}
