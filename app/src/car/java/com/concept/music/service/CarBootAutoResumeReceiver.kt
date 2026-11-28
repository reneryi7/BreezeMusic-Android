package com.concept.music.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 车机独立包受控点火/开机自启续播接收器：
 * - 仅响应系统 BOOT_COMPLETED / QUICKBOOT_POWERON；
 * - 严格检查用户「点火/启动自动续播」偏好（车机包默认开启，设置卡可随时关闭），
 *   开启时安全拉起前台媒体服务并派发内部签名续播指令，关闭时零唤醒直接返回。
 */
class CarBootAutoResumeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        val appCtx = context.applicationContext ?: context
        val prefs = appCtx.getSharedPreferences("concept_player_prefs", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("auto_play_on_startup", true)
        if (!enabled) return
        if (!AppPermissionManager.isPermissionEnabled(AppPermissionType.BACKGROUND_GUARDIAN)) return

        runCatching {
            val svcIntent = Intent(appCtx, PlaybackService::class.java).apply {
                this.action = PlaybackService.ACTION_CAR_BOOT_AUTO_RESUME
                setPackage(appCtx.packageName)
                com.concept.music.util.SecurityGuards.signInternalIntent(appCtx, this)
            }
            when {
                // Android 14+ (API 34+) 严禁在 BOOT_COMPLETED 直接拉起 mediaPlayback 前台服务（会触发 ForegroundServiceStartNotAllowedException）；
                // 优先尝试受豁免车机 ROM 的普通后台启动，若系统拦截则静默回退至 Media3 onPlaybackResumption 与打开 App 自动续播。
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    runCatching { appCtx.startService(svcIntent) }
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    appCtx.startForegroundService(svcIntent)
                }
                else -> {
                    appCtx.startService(svcIntent)
                }
            }
        }
    }
}
