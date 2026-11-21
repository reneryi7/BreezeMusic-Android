package com.concept.music.data.kugou

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.google.gson.reflect.TypeToken
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface SessionStore {
    fun accounts(): List<SavedAccount>
    fun saveAccounts(accounts: List<SavedAccount>)
    fun activeId(): String?
    fun setActive(id: String?)
    fun session(id: String): KugouSession?
    fun hasSessionRecord(id: String): Boolean = session(id) != null
    fun saveSession(session: KugouSession)
    fun removeSession(id: String)
    fun device(): DeviceIdentity
    fun saveDfid(dfid: String)
    fun isDfidRegistered(): Boolean = true
}

internal data class ParsedEncryptedSessionRecord(
    val version: Int,
    val ivBase64: String,
    val cipherBase64: String
)

internal fun parseEncryptedSessionRecord(encoded: String?): ParsedEncryptedSessionRecord? {
    val raw = encoded?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val parts = raw.split(":")
    return when {
        parts.size == 3 && parts[0] == "v2" && parts[1].isNotBlank() && parts[2].isNotBlank() ->
            ParsedEncryptedSessionRecord(version = 2, ivBase64 = parts[1], cipherBase64 = parts[2])
        parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank() ->
            ParsedEncryptedSessionRecord(version = 1, ivBase64 = parts[0], cipherBase64 = parts[1])
        else -> null
    }
}

internal fun isKeystoreMasterKeyFatal(error: Throwable): Boolean {
    var cur: Throwable? = error
    var depth = 0
    while (cur != null && depth < 6) {
        if (cur is KeyPermanentlyInvalidatedException ||
            cur is UnrecoverableKeyException ||
            cur is InvalidKeyException
        ) {
            return true
        }
        cur = cur.cause
        depth++
    }
    return false
}

internal fun isLegacySynthesizedDfid(guid: String, dev: String, mid: String, candidate: String): Boolean {
    if (candidate.isBlank() || candidate == "-") return true
    val fake1 = "2" + KugouSigner.md5("kugou_lite_dfid_${guid}_$dev").lowercase().take(23)
    val fake2 = "2" + KugouSigner.md5("kugou_lite_dfid_${guid}_$mid").lowercase().take(23)
    val fake3 = "2" + KugouSigner.md5("concept_dfid_${guid}_$mid").lowercase().take(23)
    val fake4 = "2" + KugouSigner.md5("kugou_lite_${guid}_${mid}_v1").lowercase().take(23)
    return candidate.equals(fake1, ignoreCase = true) ||
        candidate.equals(fake2, ignoreCase = true) ||
        candidate.equals(fake3, ignoreCase = true) ||
        candidate.equals(fake4, ignoreCase = true) ||
        candidate.startsWith("2localfallback", ignoreCase = true)
}

class EncryptedSessionStore(context: Context) : SessionStore {
    companion object {
        private const val LEGACY_KEY_ALIAS_V1 = "concept_music_accounts"
        private const val KEY_ALIAS_V2 = "concept_music_accounts_aes256_v2"
        private const val AES_KEY_SIZE_BITS = 256
    }

    private val appContext = context.applicationContext ?: context
    private val metadata = appContext.getSharedPreferences("kugou_accounts_v2", Context.MODE_PRIVATE)
    private val secrets by lazy {
        appContext.getSharedPreferences("kugou_secrets_v2", Context.MODE_PRIVATE)
    }
    private val gson get() = KugouSigner.gson

    @Volatile
    private var cachedAccounts: List<SavedAccount>? = null

    @Volatile
    private var cachedActiveId: String? = null

    @Volatile
    private var activeIdLoaded: Boolean = false

    @Volatile
    private var cachedDevice: DeviceIdentity? = null

    @Volatile
    private var dfidRegistered: Boolean = false

    @Volatile
    private var cachedSecretKeyV2: SecretKey? = null

    @Volatile
    private var cachedLegacyKeyV1: SecretKey? = null

    private val sessionCache = ConcurrentHashMap<String, KugouSession>()

    private fun generateAes256GcmKey(alias: String): SecretKey {
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_KEY_SIZE_BITS)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun clearOnlyV2EncryptedRecords() {
        val editor = secrets.edit()
        var removedAny = false
        for ((key, rawVal) in secrets.all) {
            if (rawVal is String && rawVal.startsWith("v2:")) {
                editor.remove(key)
                sessionCache.remove(key)
                removedAny = true
            }
        }
        if (removedAny) {
            editor.apply()
        }
    }

    @Synchronized
    private fun getLegacySecretKeyV1(): SecretKey? {
        cachedLegacyKeyV1?.let { return it }
        return runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(LEGACY_KEY_ALIAS_V1, null) as? SecretKey)?.also {
                cachedLegacyKeyV1 = it
            }
        }.getOrNull()
    }

    @Synchronized
    private fun getOrCreateSecretKeyV2(forceRecreate: Boolean = false): SecretKey? {
        if (!forceRecreate && cachedSecretKeyV2 != null) {
            return cachedSecretKeyV2
        }
        return try {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (forceRecreate) {
                runCatching { store.deleteEntry(KEY_ALIAS_V2) }
                cachedSecretKeyV2 = null
                clearOnlyV2EncryptedRecords()
            }
            val existing = if (!forceRecreate) {
                runCatching { store.getKey(KEY_ALIAS_V2, null) as? SecretKey }.getOrNull()
            } else null
            val resolved = existing ?: generateAes256GcmKey(KEY_ALIAS_V2)
            cachedSecretKeyV2 = resolved
            resolved
        } catch (_: Throwable) {
            if (!forceRecreate) {
                runCatching {
                    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    store.deleteEntry(KEY_ALIAS_V2)
                    cachedSecretKeyV2 = null
                    clearOnlyV2EncryptedRecords()
                    generateAes256GcmKey(KEY_ALIAS_V2).also { cachedSecretKeyV2 = it }
                }.getOrNull()
            } else {
                null
            }
        }
    }

    init {
        dfidRegistered = metadata.getBoolean("device.dfid_registered", false)
        if (!metadata.contains("guid")) {
            val guid = KugouSigner.md5(UUID.randomUUID().toString())
            val dev = KugouSigner.random(10).uppercase()
            metadata.edit()
                .putString("guid", guid)
                .putString("dev", dev)
                .putString("dfid", "-")
                .putBoolean("device.dfid_registered", false)
                .putBoolean("legacy_session_migrated", true)
                .apply()
            dfidRegistered = false
            cachedDevice = DeviceIdentity(guid, KugouSigner.mid(guid), dev, dfid = "-")
        } else {
            val guid = metadata.getString("guid", null).orEmpty().ifBlank { KugouSigner.md5(UUID.randomUUID().toString()) }
            val dev = metadata.getString("dev", null).orEmpty().ifBlank { KugouSigner.random(10).uppercase() }
            val mid = KugouSigner.mid(guid)
            val existingDfid = metadata.getString("dfid", "-")?.trim().orEmpty()
            val resolvedDfid = if (!dfidRegistered || isLegacySynthesizedDfid(guid, dev, mid, existingDfid)) {
                metadata.edit().putString("dfid", "-").putBoolean("device.dfid_registered", false).apply()
                dfidRegistered = false
                "-"
            } else {
                existingDfid
            }
            cachedDevice = DeviceIdentity(guid, mid, dev, dfid = resolvedDfid)
        }
        if (!metadata.getBoolean("legacy_session_migrated", false)) {
            val old = appContext.getSharedPreferences("kugou_session", Context.MODE_PRIVATE)
            val id = old.getString("userId", null)
            val token = old.getString("token", null)
            if (!id.isNullOrBlank() && !token.isNullOrBlank()) {
                if (session(id) == null) saveSession(KugouSession(id, token, old.getString("t1", null)))
                if (accounts().none { it.userId == id }) saveAccounts(accounts() + SavedAccount(id))
                if (activeId() == null) setActive(id)
                old.edit().clear().apply()
            }
            metadata.edit().putBoolean("legacy_session_migrated", true).apply()
        }
    }

    @Synchronized
    override fun accounts(): List<SavedAccount> {
        cachedAccounts?.let { return it }
        val rawJson = metadata.getString("accounts", null)
        if (rawJson.isNullOrBlank() || rawJson == "[]") {
            cachedAccounts = emptyList()
            return emptyList()
        }
        val loaded = runCatching {
            gson.fromJson<List<SavedAccount>>(rawJson, object : TypeToken<List<SavedAccount>>() {}.type)
        }.getOrNull().orEmpty()
        cachedAccounts = loaded
        return loaded
    }

    @Synchronized
    override fun saveAccounts(accounts: List<SavedAccount>) {
        cachedAccounts = accounts.toList()
        metadata.edit().putString("accounts", gson.toJson(cachedAccounts)).apply()
    }

    @Synchronized
    override fun activeId(): String? {
        if (activeIdLoaded) return cachedActiveId
        val id = metadata.getString("active", null)
        cachedActiveId = id
        activeIdLoaded = true
        return id
    }

    @Synchronized
    override fun setActive(id: String?) {
        if (activeIdLoaded && cachedActiveId == id) return
        cachedActiveId = id
        activeIdLoaded = true
        metadata.edit().putString("active", id).apply()
    }

    override fun hasSessionRecord(id: String): Boolean {
        if (sessionCache.containsKey(id)) return true
        return parseEncryptedSessionRecord(secrets.getString(id, null)) != null
    }

    private fun decryptRecord(id: String, record: ParsedEncryptedSessionRecord, secretKey: SecretKey): KugouSession? {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, Base64.getDecoder().decode(record.ivBase64)))
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val plainBytes = cipher.doFinal(Base64.getDecoder().decode(record.cipherBase64))
        return gson.fromJson(plainBytes.toString(Charsets.UTF_8), KugouSession::class.java)
            ?.takeIf { it.userId == id && it.token.isNotBlank() }
    }

    @Synchronized
    override fun session(id: String): KugouSession? {
        sessionCache[id]?.let { return it }
        val encoded = secrets.getString(id, null) ?: return null
        val parsed = parseEncryptedSessionRecord(encoded) ?: run {
            secrets.edit().remove(id).apply()
            return null
        }
        return if (parsed.version == 2) {
            val secretKey = getOrCreateSecretKeyV2(forceRecreate = false) ?: return null
            try {
                decryptRecord(id, parsed, secretKey)?.also { sessionCache[id] = it }
            } catch (t: Throwable) {
                // 单条密文损坏或认证标签不匹配时仅清理该单条记录，绝不误伤多账号下的其他正常会话；
                // 仅当 Keystore 主密钥本身发生不可逆失效时才重建 V2 主密钥。
                secrets.edit().remove(id).apply()
                sessionCache.remove(id)
                if (isKeystoreMasterKeyFatal(t)) {
                    getOrCreateSecretKeyV2(forceRecreate = true)
                }
                null
            }
        } else {
            val legacyKey = getLegacySecretKeyV1() ?: run {
                secrets.edit().remove(id).apply()
                return null
            }
            try {
                val restored = decryptRecord(id, parsed, legacyKey)
                if (restored != null) {
                    sessionCache[id] = restored
                    // 无感自动将旧版 V1 密文升级重加密为 AES-256-GCM V2 密文，保持老用户不掉登录态
                    saveSession(restored)
                }
                restored
            } catch (_: Throwable) {
                secrets.edit().remove(id).apply()
                sessionCache.remove(id)
                null
            }
        }
    }

    @Synchronized
    override fun saveSession(session: KugouSession) {
        sessionCache[session.userId] = session
        val encodedValue = fun(secretKey: SecretKey): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            cipher.updateAAD(session.userId.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(gson.toJson(session).toByteArray(Charsets.UTF_8))
            return "v2:" + Base64.getEncoder().encodeToString(cipher.iv) + ":" + Base64.getEncoder().encodeToString(encrypted)
        }
        val key = getOrCreateSecretKeyV2(forceRecreate = false)
        if (key != null) {
            val value = try {
                encodedValue(key)
            } catch (t: Throwable) {
                if (isKeystoreMasterKeyFatal(t)) {
                    val rebuiltKey = getOrCreateSecretKeyV2(forceRecreate = true)
                    rebuiltKey?.let { runCatching { encodedValue(it) }.getOrNull() }
                } else {
                    null
                }
            }
            if (value != null) {
                secrets.edit().putString(session.userId, value).apply()
            }
        }
    }

    @Synchronized
    override fun removeSession(id: String) {
        sessionCache.remove(id)
        secrets.edit().remove(id).apply()
    }

    @Synchronized
    override fun device(): DeviceIdentity {
        cachedDevice?.let { return it }
        val guid = metadata.getString("guid", null).orEmpty().ifBlank {
            KugouSigner.md5(UUID.randomUUID().toString()).also { metadata.edit().putString("guid", it).apply() }
        }
        val dev = metadata.getString("dev", null).orEmpty().ifBlank {
            KugouSigner.random(10).uppercase().also { metadata.edit().putString("dev", it).apply() }
        }
        val mid = KugouSigner.mid(guid)
        val rawDfid = metadata.getString("dfid", "-")?.trim().orEmpty()
        val dfid = if (!dfidRegistered || isLegacySynthesizedDfid(guid, dev, mid, rawDfid)) {
            "-"
        } else {
            rawDfid
        }
        return DeviceIdentity(guid, mid, dev, dfid = dfid).also {
            cachedDevice = it
        }
    }

    @Synchronized
    override fun saveDfid(dfid: String) {
        val clean = dfid.trim()
        if (clean.isNotBlank() && clean != "-") {
            cachedDevice = device().copy(dfid = clean)
            dfidRegistered = true
            metadata.edit().putString("dfid", clean).putBoolean("device.dfid_registered", true).apply()
        } else {
            cachedDevice = device().copy(dfid = "-")
            dfidRegistered = false
            metadata.edit().putString("dfid", "-").putBoolean("device.dfid_registered", false).apply()
        }
    }

    @Synchronized
    override fun isDfidRegistered(): Boolean = dfidRegistered && (cachedDevice?.dfid ?: "-").let { it.isNotBlank() && it != "-" }
}
