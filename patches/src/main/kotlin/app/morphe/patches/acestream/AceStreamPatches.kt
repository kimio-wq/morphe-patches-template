package teke.patches.acestream

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import app.morphe.patcher.patch.BytecodePatchContext

// 対象: AceStream Media (org.acestream.media.web) 3.1.80.0 arm64-v8a
// 注意: 静的解析だけで書いたパッチです。実機での動作確認は未実施。
// Morphe のバージョンによって Compatibility / document() / Fingerprint(custom=) の
// シグネチャが違う場合は、その部分だけ合わせてください。

private val COMPATIBILITY_ACESTREAM = Compatibility(
    packageName = "org.acestream.media.web",
    name = "AceStream Media",
    apkFileType = ApkFileType.APK,
    appIconColor = 0x1E88E5,
    targets = listOf(AppTarget(version = "3.1.80.0")),
)

// ---------------------------------------------------------------------------
// Fingerprint helpers (クラス名 + メソッド名 + シグネチャで一意に特定)
// ---------------------------------------------------------------------------

private fun target(classType: String, name: String, params: String, ret: String) = Fingerprint(
    custom = { method, classDef ->
        classDef.type == classType &&
            method.name == name &&
            method.returnType == ret &&
            method.parameterTypes.joinToString("") { it.toString() } == params
    }
)

/** メソッドを即 return する。Z は false、V は return-void。 */
context(_: BytecodePatchContext)
private fun Fingerprint.stub(returnTrue: Boolean = false) {
    val m = method
    when (m.returnType) {
        "V" -> m.addInstructions(0, "return-void")
        "Z" -> m.addInstructions(0, "const/4 v0, 0x${if (returnTrue) 1 else 0}\nreturn v0")
        else -> error("Unsupported return type ${m.returnType} for ${m.name}")
    }
}

private const val ACESTREAM = "Lorg/acestream/sdk/AceStream;"
private const val AD_MANAGER = "Lorg/acestream/engine/ads/AdManager;"
private const val AD_PREFS = "Lorg/acestream/sdk/preferences/AdPreferences;"
private const val MANAGER_IMPL = "Lorg/acestream/engine/AceStreamManagerImpl;"
private const val UNINSTALL_WORKER = "Lorg/acestream/sdk/utils/UninstallNotificationsWorker;"

// --- Ads ---
private val adsEnabledFp = target(ACESTREAM, "adsEnabled", "", "Z")
private val allowRewardedAdsFp = target(ACESTREAM, "allowRewardedAds", "", "Z")
private val openBonusAdsFp = target(ACESTREAM, "openBonusAdsActivity", "Landroid/content/Context;", "V")

private val adManagerInitFp = target(AD_MANAGER, "init", "Landroid/content/Context;", "V")
private val adManagerShowInterstitialFp = target(AD_MANAGER, "showInterstitial", "Ljava/lang/String;", "Z")
private val adManagerLoadInterstitialFp = target(AD_MANAGER, "loadInterstitial", "Ljava/lang/String;", "Z")
private val adManagerLoadInterstitial2Fp = target(AD_MANAGER, "loadInterstitial", "Ljava/lang/String;Z", "Z")
private val adManagerInitInterstitialFp = target(
    AD_MANAGER, "initInterstitial",
    "Ljava/lang/String;Ljava/lang/String;Lcom/google/android/gms/ads/AdListener;", "V"
)
private val adManagerShowRewardedFp = target(AD_MANAGER, "showRewardedVideo", "", "Z")
private val adManagerLoadRewardedFp = target(AD_MANAGER, "loadRewardedVideoAd", "Z", "V")
private val adManagerProviderEnabledFp = target(AD_MANAGER, "isProviderEnabled", "Ljava/lang/String;", "Z")

private val adPrefsFps = listOf("showAdsOnClose", "showAdsOnMainScreen", "showAdsOnPause", "showAdsOnPreroll", "showRewardedAds")
    .map { target(AD_PREFS, it, "Landroid/content/Context;", "Z") }

private val showBonusAdsFp = target(MANAGER_IMPL, "showBonusAds", "", "V")
private val managerOpenBonusAdsFp = target(MANAGER_IMPL, "openBonusAdsActivity", "Landroid/content/Context;", "V")

// --- Telemetry ---
private val logEventFp = target(ACESTREAM, "logEvent", "Ljava/lang/String;Landroid/os/Bundle;", "V")
private val uninstallWorkerInitFp = target(UNINSTALL_WORKER, "init", "Landroid/content/Context;I", "V")
private val uninstallWorkerEnqueueFp = target(UNINSTALL_WORKER, "enqueue", "Landroid/content/Context;I", "V")

// ---------------------------------------------------------------------------
// Resource patch: Firebase / Google Analytics 収集を無効化し AD_ID 権限を削除
// ---------------------------------------------------------------------------

@Suppress("unused")
val disableAnalyticsCollectionPatch = resourcePatch(
    name = "Disable analytics collection (manifest)",
    description = "Turns off Firebase Analytics and Crashlytics collection and removes the AD_ID permission.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ACESTREAM)

    execute {
        document("AndroidManifest.xml").use { doc ->
            val app = doc.getElementsByTagName("application").item(0) as Element

            fun putMeta(name: String, value: String) {
                val existing = (0 until app.childNodes.length)
                    .map { app.childNodes.item(it) }
                    .filterIsInstance<Element>()
                    .filter { it.tagName == "meta-data" && it.getAttribute("android:name") == name }
                existing.forEach { app.removeChild(it) }

                app.appendChild(doc.createElement("meta-data").apply {
                    setAttribute("android:name", name)
                    setAttribute("android:value", value)
                })
            }

            putMeta("firebase_analytics_collection_deactivated", "true")
            putMeta("firebase_analytics_collection_enabled", "false")
            putMeta("firebase_crashlytics_collection_enabled", "false")
            putMeta("google_analytics_adid_collection_enabled", "false")

            val perms = doc.getElementsByTagName("uses-permission")
            (0 until perms.length)
                .map { perms.item(it) as Element }
                .filter { it.getAttribute("android:name") == "com.google.android.gms.permission.AD_ID" }
                .forEach { it.parentNode.removeChild(it) }
        }
    }
}

// ---------------------------------------------------------------------------
// Bytecode patches
// ---------------------------------------------------------------------------

@Suppress("unused")
val disableAdsPatch = bytecodePatch(
    name = "Disable ads",
    description = "Disables interstitial, rewarded and bonus ads (AdMob / MoPub / AdColony paths) in AceStream Media.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ACESTREAM)

    execute {
        // アプリ全体の広告フラグ
        adsEnabledFp.stub()
        allowRewardedAdsFp.stub()
        openBonusAdsFp.stub()

        // AdManager: SDK 初期化とロード/表示をすべて無効化
        adManagerInitFp.stub()
        adManagerInitInterstitialFp.stub()
        adManagerLoadInterstitialFp.stub()
        adManagerLoadInterstitial2Fp.stub()
        adManagerShowInterstitialFp.stub()
        adManagerShowRewardedFp.stub()
        adManagerLoadRewardedFp.stub()
        adManagerProviderEnabledFp.stub()

        // 表示タイミング設定 (起動時/終了時/一時停止/プリロール/リワード)
        adPrefsFps.forEach { it.stub() }

        // ボーナス広告 (報酬動画) 画面
        showBonusAdsFp.stub()
        managerOpenBonusAdsFp.stub()
    }
}

@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Stops in-app event logging and the periodic uninstall-notification poll. " +
        "Engine-side reporting inside the native Python engine is not covered.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ACESTREAM)
    dependsOn(disableAnalyticsCollectionPatch)

    execute {
        logEventFp.stub()
        uninstallWorkerInitFp.stub()
        uninstallWorkerEnqueueFp.stub()
    }
}
