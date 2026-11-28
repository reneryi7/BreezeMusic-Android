package com.concept.music.widget

import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.RemoteViews
import com.concept.music.util.SecurityGuards

/**
 * 主流国产与原生 Android ROM 识别枚举（用于桌面小组件一键添加适配与权限引导）
 */
enum class WidgetHostRom(val displayName: String) {
    COLOR_OS("ColorOS / OPPO · 一加 · 真我"),
    HYPER_OS_MIUI("小米澎湃OS / MIUI"),
    ORIGIN_OS("vivo OriginOS / iQOO"),
    HARMONY_EMUI("华为 HarmonyOS / EMUI"),
    MAGIC_OS("荣耀 MagicOS"),
    ONE_UI("三星 One UI"),
    FLYME("魅族 Flyme"),
    AOSP_OTHER("Android 桌面")
}

enum class WidgetPinPermissionState {
    GRANTED,
    DENIED,
    UNKNOWN
}

enum class WidgetPinAttemptStatus {
    /** 已成功向桌面发出请求（通常弹出系统确认框，需结合数量增长或回调核实结果） */
    REQUESTED_AWAIT_CONFIRM,
    /** 澎湃OS/MIUI 等系统可能不弹窗直接静默添加 */
    REQUESTED_MAY_PIN_SILENTLY,
    /** 检测到「创建桌面快捷方式」权限被系统拒绝 */
    BLOCKED_BY_PERMISSION,
    /** 当前桌面声明不支持或已知 ROM（如 OriginOS 非原子组件）拦截原生添加 */
    LAUNCHER_UNSUPPORTED
}

enum class WidgetPinStrategy(val label: String) {
    /** 零载荷标准通道：extras=null（由桌面直接读取静态 previewImage/previewLayout，规避 ColorOS Binder 过载丢弃）+ IMMUTABLE 回调 */
    STANDARD_CLEAN("标准零载荷通道"),
    /** 极简直通通道：extras=null 且 successCallback=null（纯原生 AOSP 最小调用，规避 ROM 对 PendingIntent 回调的拦截） */
    MINIMAL_NO_CALLBACK("极简直通通道")
}

data class WidgetPinAttemptResult(
    val status: WidgetPinAttemptStatus,
    val rom: WidgetHostRom,
    val launcherPackage: String,
    val beforeWidgetCount: Int,
    val requestAccepted: Boolean,
    val isSupported: Boolean = requestAccepted,
    val strategy: WidgetPinStrategy = WidgetPinStrategy.STANDARD_CLEAN
)

data class OemWidgetPinGuidance(
    val rom: WidgetHostRom,
    val title: String,
    val subtitle: String,
    val permissionTip: String,
    val manualSteps: List<String>,
    val supportsDirectPermissionJump: Boolean
)

/**
 * 全厂商桌面小组件添加适配器：
 * 1. 自动解包前台 Activity 上下文调用 AppWidgetManager.requestPinAppWidget（修复 ColorOS 传 applicationContext 静默失效问题）；
 * 2. 默认传递 extras = null（避免携带多张大 Bitmap 的 EXTRA_APPWIDGET_PREVIEW 在 ColorOS 桌面跨进程解包时被异步静默丢弃），
 *    直接由系统桌面原生加载高分静态 WebP previewImage / 极简 previewLayout，零卡顿、零 Binder 溢出；
 * 3. 使用 FLAG_IMMUTABLE 签名回调，并提供「极简直通通道（null, null）」二次兜底，彻底规避 Android 14+ / ColorOS 回调限制；
 * 4. 对 ColorOS / 澎湃OS / OriginOS / HarmonyOS / MagicOS / OneUI / Flyme 提供权限检测、直达权限页与精准手动引导。
 */
object BreezeWidgetPinAdapter {

    private const val MIUI_OP_INSTALL_SHORTCUT = 10017

    fun detectCurrentRom(): WidgetHostRom {
        return detectWidgetHostRom(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            display = Build.DISPLAY.orEmpty(),
            fingerprint = Build.FINGERPRINT.orEmpty(),
            oplusProp = readSystemProperty("ro.build.version.oplusrom"),
            oppoProp = readSystemProperty("ro.build.version.opporom"),
            miuiProp = readSystemProperty("ro.miui.ui.version.name"),
            vivoProp = readSystemProperty("ro.vivo.os.version"),
            harmonyProp = readSystemProperty("hw_sc.build.platform.version"),
            emuiProp = readSystemProperty("ro.build.version.emui"),
            magicProp = readSystemProperty("ro.build.version.magic"),
            flymeProp = readSystemProperty("ro.build.display.id")
        )
    }

    internal fun detectWidgetHostRom(
        manufacturer: String,
        brand: String,
        display: String = "",
        fingerprint: String = "",
        oplusProp: String = "",
        oppoProp: String = "",
        miuiProp: String = "",
        vivoProp: String = "",
        harmonyProp: String = "",
        emuiProp: String = "",
        magicProp: String = "",
        flymeProp: String = ""
    ): WidgetHostRom {
        val m = manufacturer.lowercase()
        val b = brand.lowercase()
        val combined = "$m $b ${display.lowercase()} ${fingerprint.lowercase()}"

        return when {
            oplusProp.isNotBlank() || oppoProp.isNotBlank() ||
                m.contains("oppo") || m.contains("oneplus") || m.contains("realme") ||
                b.contains("oppo") || b.contains("oneplus") || b.contains("realme") -> WidgetHostRom.COLOR_OS

            miuiProp.isNotBlank() ||
                m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ||
                b.contains("xiaomi") || b.contains("redmi") || b.contains("poco") -> WidgetHostRom.HYPER_OS_MIUI

            vivoProp.isNotBlank() ||
                m.contains("vivo") || m.contains("iqoo") ||
                b.contains("vivo") || b.contains("iqoo") -> WidgetHostRom.ORIGIN_OS

            magicProp.isNotBlank() || m.contains("honor") || b.contains("honor") -> WidgetHostRom.MAGIC_OS

            harmonyProp.isNotBlank() || emuiProp.isNotBlank() ||
                m.contains("huawei") || b.contains("huawei") -> WidgetHostRom.HARMONY_EMUI

            m.contains("samsung") || b.contains("samsung") -> WidgetHostRom.ONE_UI

            flymeProp.contains("flyme", ignoreCase = true) ||
                combined.contains("meizu") || combined.contains("flyme") -> WidgetHostRom.FLYME

            else -> WidgetHostRom.AOSP_OTHER
        }
    }

    fun resolveDefaultLauncherPackage(context: Context): String {
        return runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.resolveActivity(
                    homeIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            resolved?.activityInfo?.packageName.orEmpty()
        }.getOrDefault("")
    }

    fun checkShortcutPinPermission(context: Context, rom: WidgetHostRom = detectCurrentRom()): WidgetPinPermissionState {
        if (rom == WidgetHostRom.HYPER_OS_MIUI) {
            return checkAppOpInt(context, MIUI_OP_INSTALL_SHORTCUT)
        }
        return WidgetPinPermissionState.UNKNOWN
    }

    private fun checkAppOpInt(context: Context, opCode: Int): WidgetPinPermissionState {
        return runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return WidgetPinPermissionState.UNKNOWN
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val mode = method.invoke(appOps, opCode, Process.myUid(), context.packageName) as? Int
            when (mode) {
                AppOpsManager.MODE_ALLOWED -> WidgetPinPermissionState.GRANTED
                AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> WidgetPinPermissionState.DENIED
                else -> WidgetPinPermissionState.UNKNOWN
            }
        }.getOrDefault(WidgetPinPermissionState.UNKNOWN)
    }

    /**
     * 使用前台 Activity 上下文向系统桌面发起添加小组件请求：
     * - 严禁携带大 Bitmap RemoteViews 进入 EXTRA_APPWIDGET_PREVIEW（ColorOS 桌面在跨进程 Activity Intent 传输中会静默丢弃）；
     * - STANDARD_CLEAN：extras = null + FLAG_IMMUTABLE 回调；
     * - MINIMAL_NO_CALLBACK：extras = null + successCallback = null（纯原生 AOSP 极简直通，配合 Provider onUpdate 确认落地）。
     */
    fun requestPinWidgetAdapted(
        context: Context,
        sizeClass: BreezeWidgetSizeClass,
        previewRemoteViews: RemoteViews? = null,
        strategy: WidgetPinStrategy = WidgetPinStrategy.STANDARD_CLEAN
    ): WidgetPinAttemptResult {
        val rom = detectCurrentRom()
        val launcherPkg = resolveDefaultLauncherPackage(context)
        val beforeCount = BreezeWidgetManager.countActiveWidgets(context)
        val hostActivity = findHostActivity(context)
        val effectiveContext = hostActivity ?: context.applicationContext

        val permState = checkShortcutPinPermission(effectiveContext, rom)
        if (permState == WidgetPinPermissionState.DENIED) {
            return WidgetPinAttemptResult(
                status = WidgetPinAttemptStatus.BLOCKED_BY_PERMISSION,
                rom = rom,
                launcherPackage = launcherPkg,
                beforeWidgetCount = beforeCount,
                requestAccepted = false,
                isSupported = true,
                strategy = strategy
            )
        }

        val manager = runCatching { AppWidgetManager.getInstance(effectiveContext) }.getOrNull()
        val supported = runCatching { manager?.isRequestPinAppWidgetSupported == true }.getOrDefault(false)
        if (manager == null || !supported) {
            return WidgetPinAttemptResult(
                status = WidgetPinAttemptStatus.LAUNCHER_UNSUPPORTED,
                rom = rom,
                launcherPackage = launcherPkg,
                beforeWidgetCount = beforeCount,
                requestAccepted = false,
                isSupported = false,
                strategy = strategy
            )
        }

        val providerClass = BreezeWidgetManager.providerClassFor(sizeClass)
        val component = ComponentName(effectiveContext.applicationContext, providerClass)
        val successCallback = if (strategy == WidgetPinStrategy.MINIMAL_NO_CALLBACK) {
            null
        } else {
            buildPinSuccessPendingIntent(effectiveContext.applicationContext, providerClass, sizeClass)
        }

        // 默认不传 EXTRA_APPWIDGET_PREVIEW（previewRemoteViews 仅保留给极简零 Bitmap 场景），
        // 让 ColorOS / MIUI / OneUI / AOSP 桌面直接读取 XML 中的 previewImage / previewLayout
        val extras = if (strategy == WidgetPinStrategy.MINIMAL_NO_CALLBACK) {
            null
        } else {
            previewRemoteViews?.let { rv ->
                Bundle().apply {
                    putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, rv)
                }
            }
        }

        var usedStrategy = strategy
        var accepted = runCatching {
            manager.requestPinAppWidget(component, extras, successCallback)
        }.getOrElse {
            false
        }
        if (!accepted && (extras != null || successCallback != null)) {
            usedStrategy = WidgetPinStrategy.MINIMAL_NO_CALLBACK
            accepted = runCatching {
                manager.requestPinAppWidget(component, null, null)
            }.getOrDefault(false)
        }

        if (!accepted) {
            return WidgetPinAttemptResult(
                status = WidgetPinAttemptStatus.LAUNCHER_UNSUPPORTED,
                rom = rom,
                launcherPackage = launcherPkg,
                beforeWidgetCount = beforeCount,
                requestAccepted = false,
                isSupported = supported,
                strategy = usedStrategy
            )
        }

        val status = when (rom) {
            WidgetHostRom.HYPER_OS_MIUI -> WidgetPinAttemptStatus.REQUESTED_MAY_PIN_SILENTLY
            else -> WidgetPinAttemptStatus.REQUESTED_AWAIT_CONFIRM
        }
        return WidgetPinAttemptResult(
            status = status,
            rom = rom,
            launcherPackage = launcherPkg,
            beforeWidgetCount = beforeCount,
            requestAccepted = true,
            isSupported = supported,
            strategy = usedStrategy
        )
    }

    private fun buildPinSuccessPendingIntent(
        appCtx: Context,
        providerClass: Class<*>,
        sizeClass: BreezeWidgetSizeClass
    ): PendingIntent {
        val callbackIntent = Intent(appCtx, providerClass).apply {
            action = BreezeWidgetActions.ACTION_WIDGET_PINNED
            setPackage(appCtx.packageName)
            putExtra(BreezeWidgetActions.EXTRA_PINNED_SIZE_CLASS, sizeClass.name)
            SecurityGuards.signInternalIntent(appCtx, this)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val reqCode = 45_000 + sizeClass.ordinal
        return PendingIntent.getBroadcast(appCtx, reqCode, callbackIntent, flags)
    }

    /**
     * 针对当前 ROM 生成精准的权限开启说明与手动添加路径
     */
    fun buildOemPinGuidance(
        rom: WidgetHostRom,
        sizeClass: BreezeWidgetSizeClass
    ): OemWidgetPinGuidance {
        val spec = "${sizeClass.gridSpec} ${sizeClass.label}"
        return when (rom) {
            WidgetHostRom.COLOR_OS -> OemWidgetPinGuidance(
                rom = rom,
                title = "ColorOS 桌面添加适配指引",
                subtitle = "OPPO / 一加 / 真我桌面需同时满足「创建桌面快捷方式：使用时允许」且未「锁定桌面布局」，才允许应用内拉起添加弹窗；部分侧载安装包如仍被系统桌面安全策略拦截，可直接从桌面插件页一键放置。",
                permissionTip = "请检查两项设置：① 在「应用信息 → 权限管理 → 创建桌面快捷方式」中选择「使用时允许」；② 前往「系统设置 → 桌面与锁屏」确认关闭「锁定桌面布局」。完成后可点击下方「极简模式重试」。",
                manualSteps = listOf(
                    "返回桌面，双指在空白处向内捏合进入编辑模式（或长按桌面空白处）",
                    "点击底部「卡片」，向下滑动到底部进入「插件」",
                    "找到「微风音乐」，点击「$spec」即可直出桌面"
                ),
                supportsDirectPermissionJump = true
            )
            WidgetHostRom.HYPER_OS_MIUI -> OemWidgetPinGuidance(
                rom = rom,
                title = "澎湃OS / MIUI 桌面添加适配指引",
                subtitle = "小米系统要求开启「桌面快捷方式」权限才能从应用内添加小组件；部分版本开启后会在桌面自动静默落地。",
                permissionTip = "请点击下方按钮前往「其他权限 → 桌面快捷方式」，开启「始终允许」后再次点击添加。",
                manualSteps = listOf(
                    "返回桌面双指捏合，点击底部「添加小部件」",
                    "下拉至页面底部进入「Android 小部件」",
                    "找到「微风音乐」，拖放「$spec」到桌面"
                ),
                supportsDirectPermissionJump = true
            )
            WidgetHostRom.ORIGIN_OS -> OemWidgetPinGuidance(
                rom = rom,
                title = "OriginOS 桌面添加适配指引",
                subtitle = "vivo / iQOO 原生桌面限制非原子组件 SDK 的应用内一键添加，建议直接从系统原子组件库添加。",
                permissionTip = "如开启了应用权限限制，也可先在「应用信息 → 权限」中打开「桌面快捷方式」后重试。",
                manualSteps = listOf(
                    "返回桌面双指捏合（或从底部上滑进入原子组件库）",
                    "滑至页面最底部找到「Android 经典小组件」",
                    "展开「微风音乐」，选择「$spec」拖放至桌面"
                ),
                supportsDirectPermissionJump = true
            )
            WidgetHostRom.HARMONY_EMUI, WidgetHostRom.MAGIC_OS -> OemWidgetPinGuidance(
                rom = rom,
                title = "${rom.displayName} 桌面添加指引",
                subtitle = "华为 / 荣耀桌面在当前屏剩余空间不足时不会自动新建分页，大尺寸卡片请先确保桌面有足够空位。",
                permissionTip = "如未弹出确认框，请检查应用「创建桌面快捷方式」权限，或先腾出桌面空位后重试。",
                manualSteps = listOf(
                    "返回桌面并滑动到有足够空位的页面，双指捏合",
                    "点击「服务卡片 / 窗口小工具」，拉至底部「经典小工具」",
                    "选择「微风音乐」下的「$spec」添加到桌面"
                ),
                supportsDirectPermissionJump = true
            )
            WidgetHostRom.ONE_UI -> OemWidgetPinGuidance(
                rom = rom,
                title = "三星 One UI 桌面添加指引",
                subtitle = "若当前桌面锁定了主屏幕布局，系统将拦截小组件添加请求。",
                permissionTip = "请确认主屏幕未开启「锁定主屏幕布局」，或按下方步骤手动放置。",
                manualSteps = listOf(
                    "长按桌面空白处，点击底部「小组件」",
                    "在列表中展开「微风音乐」",
                    "选择「$spec」并点击「添加」"
                ),
                supportsDirectPermissionJump = true
            )
            WidgetHostRom.FLYME, WidgetHostRom.AOSP_OTHER -> OemWidgetPinGuidance(
                rom = rom,
                title = "添加到系统桌面指引",
                subtitle = "当前桌面启动器未响应应用内一键添加请求，可通过系统桌面小组件面板快速放置。",
                permissionTip = "也可先在系统「应用信息 → 权限」中确认已允许桌面快捷方式/插件权限。",
                manualSteps = listOf(
                    "返回手机或平板桌面，长按空白区域",
                    "点击「小组件 / 窗口小工具」面板",
                    "找到「微风音乐」，选择「$spec」拖放至桌面"
                ),
                supportsDirectPermissionJump = true
            )
        }
    }

    /**
     * 一键跳转各家 ROM 的应用权限管理页（优先直达权限页，异常自动安全回退标准应用详情页）
     */
    fun openOemWidgetPermissionSettings(context: Context, rom: WidgetHostRom = detectCurrentRom()): Boolean {
        val candidates = buildList {
            when (rom) {
                WidgetHostRom.HYPER_OS_MIUI -> {
                    add(
                        Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                            putExtra("extra_pkgname", context.packageName)
                        }
                    )
                }
                WidgetHostRom.COLOR_OS -> {
                    add(
                        Intent().apply {
                            component = ComponentName(
                                "com.oplus.securitypermission",
                                "com.oplus.securitypermission.permission.PermissionGroupsActivity"
                            )
                            putExtra("packageName", context.packageName)
                        }
                    )
                    add(
                        Intent().apply {
                            component = ComponentName(
                                "com.coloros.securitypermission",
                                "com.coloros.securitypermission.permission.PermissionAppAllPermissionActivity"
                            )
                            putExtra("packageName", context.packageName)
                        }
                    )
                }
                else -> {}
            }
            add(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            )
        }

        val hostActivity = findHostActivity(context)
        for (intent in candidates) {
            val started = runCatching {
                if (hostActivity != null) {
                    hostActivity.startActivity(intent)
                } else {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                true
            }.getOrDefault(false)
            if (started) return true
        }
        return false
    }

    fun openSystemHomeLauncher(context: Context): Boolean {
        return runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
            true
        }.getOrDefault(false)
    }

    internal fun findHostActivity(context: Context): Activity? {
        var cur: Context? = context
        for (depth in 0 until 8) {
            val current = cur ?: return null
            when (current) {
                is Activity -> return current
                is ContextWrapper -> cur = current.baseContext
                else -> return null
            }
        }
        return null
    }

    private fun readSystemProperty(key: String): String {
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            (get.invoke(null, key, "") as? String)?.trim().orEmpty()
        }.getOrDefault("")
    }
}
