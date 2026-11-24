package com.concept.music.data.kugou

import com.concept.music.data.vip.ListeningGradeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

class AccountRepository(private val store: SessionStore, private val auth: AuthRepository,
    private val transport: KugouTransport, private val clock: Clock = Clock.systemUTC()) {
    private val generation = AtomicLong(0L)
    private val refreshMutex = Mutex()
    // 记录最近一次成功获取/刷新会话的时间戳（按 userId 隔离），防止并发微服务的 20010 在短时间内连环轰炸 /v5/login_by_token
    private val lastVerifiedSessionByUser = mutableMapOf<String, Pair<String, Long>>()
    private val mutable = MutableStateFlow(AccountState(accounts = store.accounts(), currentId = store.activeId()))
    val state = mutable.asStateFlow()
    init { select(store.activeId()) }
    fun device(): DeviceIdentity = store.device()
    fun context(): AccountContext? = state.value.currentId?.let { id -> store.session(id)?.let { AccountContext(it, generation.get()) } }
    fun isCurrent(context: AccountContext): Boolean = state.value.currentId == context.session.userId && generation.get() == context.generation
    fun ensureCurrent(context: AccountContext) { if (!isCurrent(context)) throw CancellationException("账号已切换") }
    fun select(id: String?) {
        generation.incrementAndGet()
        store.setActive(id)
        val saved = store.accounts().find { it.userId == id }
        mutable.value = AccountState(store.accounts(), id, saved?.let { AccountProfile(it.userId, it.nickname, it.avatarUrl) },
            if (id == null) AuthState.GUEST else if (!store.hasSessionRecord(id) || saved?.needsLogin == true) AuthState.NEEDS_LOGIN else AuthState.RESTORING)
    }
    fun accept(result: LoginResult.Success) {
        store.saveSession(result.session)
        val list = store.accounts()
        val old = list.find { it.userId == result.session.userId }
        val saved = (old ?: SavedAccount(result.session.userId)).copy(nickname = result.profile.nickname,
            avatarUrl = result.profile.avatarUrl, needsLogin = false)
        store.saveAccounts(list.filterNot { it.userId == saved.userId } + saved)
        generation.incrementAndGet()
        store.setActive(saved.userId)
        synchronized(lastVerifiedSessionByUser) {
            lastVerifiedSessionByUser[saved.userId] = result.session.token to clock.millis()
        }
        // 新登录已直接拿到酷狗登录网关颁发的最新有效会话，直接进入 READY 状态，
        // 避免刚登录 50ms 内立刻被 restore() 再次冗余调用 /v5/login_by_token
        mutable.value = AccountState(
            accounts = store.accounts(),
            currentId = saved.userId,
            profile = AccountProfile(saved.userId, saved.nickname, saved.avatarUrl),
            auth = AuthState.READY,
            profileFresh = false,
            message = null
        )
    }
    fun remove(id: String) {
        synchronized(lastVerifiedSessionByUser) { lastVerifiedSessionByUser.remove(id) }
        store.removeSession(id)
        store.saveAccounts(store.accounts().filterNot { it.userId == id })
        if (id == state.value.currentId) select(null) else mutable.update { it.copy(accounts = store.accounts()) }
    }
    fun setAuto(enabled: Boolean) { updateCurrent { it.copy(autoClaim = enabled) } }
    fun completed(context: AccountContext, day: String) { ensureCurrent(context); updateCurrent { it.copy(completedDay = day) } }
    private fun updateCurrent(transform: (SavedAccount) -> SavedAccount) {
        val curId = state.value.currentId ?: return
        val existing = store.accounts()
        if (existing.none { it.userId == curId }) return
        store.saveAccounts(existing.map { if (it.userId == curId) transform(it) else it })
        mutable.update { it.copy(accounts = store.accounts()) }
    }
    suspend fun restore() {
        val context = context() ?: return
        if (state.value.auth == AuthState.NEEDS_LOGIN) return
        try {
            refreshSession(context)
            ensureCurrent(context)
            mutable.update { it.copy(auth = AuthState.READY, message = null) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            ensureCurrent(context)
            if (e is ApiFailure && e.invalidSession) {
                invalidate(context)
            } else {
                // 当本地存有合法登录凭证且并非会话过期 (invalidSession) 时，容忍 /v5/login_by_token 的瞬时失败并进入 READY 状态，
                // 避免冷启动因 token 刷新接口偶发波动而卡在 RESTORING 导致每日自动领取 VIP 不触发
                val hasValidLocalToken = context.session.token.isNotBlank()
                mutable.update {
                    it.copy(
                        auth = if (hasValidLocalToken) AuthState.READY else it.auth,
                        message = "会话刷新失败，可重试：${e.message}"
                    )
                }
            }
        }
    }
    suspend fun refreshSession(context: AccountContext): KugouSession = refreshMutex.withLock {
        ensureCurrent(context)
        val latest = store.session(context.session.userId) ?: throw ApiFailure(20010, "请重新登录")
        if (latest.token != context.session.token) return@withLock latest
        val nowMs = clock.millis()
        val recentlyVerified = synchronized(lastVerifiedSessionByUser) {
            val rec = lastVerifiedSessionByUser[context.session.userId]
            rec != null && rec.first == latest.token && (nowMs - rec.second) in 0L..15_000L
        }
        if (recentlyVerified && state.value.auth == AuthState.READY) {
            return@withLock latest
        }
        val refreshed = auth.refresh(latest)
        ensureCurrent(context)
        if (store.accounts().none { it.userId == context.session.userId }) {
            throw CancellationException("账号已移除")
        }
        store.saveSession(refreshed.session)
        synchronized(lastVerifiedSessionByUser) {
            lastVerifiedSessionByUser[context.session.userId] = refreshed.session.token to clock.millis()
        }
        updateCurrent { it.copy(needsLogin = false) }
        refreshed.session
    }
    suspend fun <T> authenticated(context: AccountContext, block: suspend (KugouSession) -> T): T {
        ensureCurrent(context)
        val latest = store.session(context.session.userId) ?: throw ApiFailure(20010, "请重新登录")
        try { return block(latest).also { ensureCurrent(context) } }
        catch (e: ApiFailure) {
            if (!e.invalidSession) throw e
            // 仅当权威登录刷新接口 (/v5/login_by_token) 本身明确返回 20010 时才认定账号会话真正失效并置为 NEEDS_LOGIN；
            // 一旦刷新成功（或刚确认会话有效），单一业务微服务重试仍报 20010 属于该子服务的参数/权限错误，绝不误杀全局登录态！
            val refreshed = try { refreshSession(context.copy(session = latest)) }
            catch (failure: ApiFailure) { if (failure.invalidSession) invalidate(context); throw failure }
            return block(refreshed).also { ensureCurrent(context) }
        }
    }
    private fun invalidate(context: AccountContext) {
        ensureCurrent(context)
        synchronized(lastVerifiedSessionByUser) { lastVerifiedSessionByUser.remove(context.session.userId) }
        updateCurrent { it.copy(needsLogin = true) }
        mutable.update { it.copy(auth = AuthState.NEEDS_LOGIN, profileFresh = false, message = "登录已失效，请重新登录") }
    }
    suspend fun refreshProfile(context: AccountContext) {
        try {
            val response = authenticated(context) { session ->
                val time = clock.instant().epochSecond
                transport.call("/v3/get_my_info", session, query = mapOf("plat" to "1"), method = "POST",
                    body = json("visit_time" to time, "usertype" to 1,
                        "p" to KugouSigner.rsa(KugouSigner.gson.toJson(json("token" to session.token, "clienttime" to time))).uppercase(),
                        "userid" to session.userId.toLong()), headers = mapOf("x-router" to "usercenter.kugou.com")).requireSuccess()
            }
            ensureCurrent(context)
            val data = response.data
            check(data.text("userid") == null || data.text("userid") == context.session.userId) { "账号资料不匹配" }
            val profile = AccountProfile(context.session.userId, data.text("nickname") ?: state.value.profile?.nickname ?: "酷狗用户", imageUrl(data.text("pic")))
            updateCurrent { it.copy(nickname = profile.nickname, avatarUrl = profile.avatarUrl, needsLogin = false) }
            mutable.update { it.copy(profile = profile, auth = AuthState.READY, profileFresh = true, message = null) }

            val officialMinutes = listOfNotNull(
                data.text("r_duration")?.toLongOrNull(),
                data.text("duration")?.toLongOrNull(),
                data.text("listen_duration")?.toLongOrNull(),
                data.text("user_duration")?.toLongOrNull()
            ).maxOrNull() ?: 0L
            val officialLevel = listOfNotNull(
                data.number("user_grade"),
                data.number("p_grade"),
                data.number("grade")
            ).maxOrNull() ?: 0
            if (officialMinutes > 0L || officialLevel > 0) {
                ListeningGradeManager.onOfficialProfileSynced(context.session.userId, officialMinutes, officialLevel)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ensureCurrent(context); mutable.update { it.copy(profileFresh = false, message = "资料尚未刷新：${e.message}") } }
    }
}
