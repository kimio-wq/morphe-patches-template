package teke.patches.acestream.node

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

// 対象: Ace Stream (org.acestream.node) 3.2.17.1 (versionCode 302170113)
// 静的解析のみで作成。ビルド・実機確認は未実施です。
// Morphe の版で Compatibility / document() / Fingerprint(custom=) の形が違う場合は、その行だけ合わせてください。
// 注意: このAPKは base split (requiredSplitTypes=base__abi) です。ABI split と一緒に
//       バンドル(APKM/XAPK等)としてパッチするか、マージ済みAPKを使ってください。

private val COMPATIBILITY_NODE = Compatibility(
    packageName = "org.acestream.node",
    name = "Ace Stream",
    appIconColor = 0x1E88E5,
    targets = listOf(AppTarget(version = "3.2.17.1")),
)

// ---------------------------------------------------------------------------
// Fingerprint helpers
// ---------------------------------------------------------------------------

private fun target(classType: String, name: String, params: String, ret: String) = Fingerprint(
    custom = { method, classDef ->
        classDef.type == classType &&
            method.name == name &&
            method.returnType == ret &&
            method.parameterTypes.joinToString("") { it.toString() } == params
    }
)

/** 先頭で即 return-void する (戻り値 V のメソッド専用)。 */
private fun Fingerprint.stubVoid() {
    check(method.returnType == "V") { "${method.name} is not void" }
    method.addInstructions(0, "return-void")
}

private const val CONTENT_START = "Lorg/acestream/engine/ContentStartActivity;"
private const val AD_MANAGER = "Lorg/acestream/sdk/ads/AdManager;"

// --- 強制広告 (ContentStartActivity) ---
// showAdMobAds(next, restoreUi):
//   プレイリスト再生、またはリワード広告がプリロード済みなら「広告を見る/プレミアム/戻る」の
//   選択画面 (askAdsConsent*) を出して再生を止める。インタースティシャル有効なら広告を表示。
//   それ以外は next.run() で再生続行。
//   広告完了時のハンドラ (lambda$askAdsConsent$43) は `if (restoreUi) restoreUi(); next.run()` なので、
//   同じ処理を直接実行する。
private val showAdMobAdsFp = target(CONTENT_START, "showAdMobAds", "Ljava/lang/Runnable;Z", "V")

// startVideoAds(int, Runnable) は videoAdsCallback を保存して startVideoAdsWhenReady() を呼ぶ。
// IMA/VAST のプリロール広告は、最終的に videoAdsResumeContent() が callback を実行して再生に戻る。
// そこで待機処理を飛ばして直接 videoAdsResumeContent() を呼ぶ。
private val startVideoAdsWhenReadyFp = target(CONTENT_START, "startVideoAdsWhenReady", "", "V")

// --- AdMob 初期化と広告キャッシュ ---
private val adManagerStartFp = target(AD_MANAGER, "start", "", "V")
private val adManagerInitAdMobFp = target(AD_MANAGER, "initAdMob", "", "V")
private val adManagerUpdateInterstitialFp = target(AD_MANAGER, "updateInterstitialCache", "", "V")
private val adManagerUpdateRewardedFp = target(AD_MANAGER, "updateRewardedAdCache", "", "V")

// --- Telemetry ---
private val analyticsLogEventFp = target(
    "Lorg/acestream/sdk/Analytics;", "logEvent",
    "Landroid/content/Context;Ljava/lang/String;Landroid/os/Bundle;", "V"
)

// --- 自己アップデート通知 ---
private val selfUpdateStartFp = target("Lorg/acestream/engine/SelfUpdateNotifier;", "start", "", "V")

// ---------------------------------------------------------------------------
// Resource patch: Firebase 収集の無効化と広告ID系権限の削除
// ---------------------------------------------------------------------------

@Suppress("unused")
val nodeDisableAnalyticsCollectionPatch = resourcePatch(
    name = "Disable analytics collection (manifest)",
    description = "Turns off Firebase Analytics and Crashlytics collection and removes advertising-ID permissions.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_NODE)

    execute {
        document("AndroidManifest.xml").use { doc ->
            val app = doc.getElementsByTagName("application").item(0) as Element

            fun putMeta(name: String, value: String) {
                (0 until app.childNodes.length)
                    .map { app.childNodes.item(it) }
                    .filterIsInstance<Element>()
                    .filter { it.tagName == "meta-data" && it.getAttribute("android:name") == name }
                    .forEach { app.removeChild(it) }

                app.appendChild(doc.createElement("meta-data").apply {
                    setAttribute("android:name", name)
                    setAttribute("android:value", value)
                })
            }

            putMeta("firebase_analytics_collection_deactivated", "true")
            putMeta("firebase_analytics_collection_enabled", "false")
            putMeta("firebase_crashlytics_collection_enabled", "false")
            putMeta("google_analytics_adid_collection_enabled", "false")

            val removable = setOf(
                "com.google.android.gms.permission.AD_ID",
                "android.permission.ACCESS_ADSERVICES_AD_ID",
                "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
            )
            val perms = doc.getElementsByTagName("uses-permission")
            (0 until perms.length)
                .map { perms.item(it) as Element }
                .filter { it.getAttribute("android:name") in removable }
                .forEach { it.parentNode.removeChild(it) }
        }
    }
}

// ---------------------------------------------------------------------------
// Bytecode patches
// ---------------------------------------------------------------------------

@Suppress("unused")
val nodeSkipForcedAdsPatch = bytecodePatch(
    name = "Skip forced ads",
    description = "Skips the ad wall (watch an ad or activate premium) and the pre-roll ads, and starts playback directly. " +
        "Does not change premium or license checks.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_NODE)

    execute {
        // 広告完了時と同じ後処理 (必要なら UI を戻し、次の処理を実行) だけを行う
        showAdMobAdsFp.method.addInstructionsWithLabels(
            0,
            """
                if-eqz p2, :run
                invoke-direct { p0 }, $CONTENT_START->restoreUi()V
                :run
                invoke-interface { p1 }, Ljava/lang/Runnable;->run()V
                return-void
            """
        )

        // IMA/VAST プリロール: 待機をやめて直接再生に戻す
        startVideoAdsWhenReadyFp.method.addInstructions(
            0,
            """
                invoke-direct { p0 }, $CONTENT_START->videoAdsResumeContent()V
                return-void
            """
        )

        // AdMob SDK の初期化と広告のプリロードを止める
        adManagerStartFp.stubVoid()
        adManagerInitAdMobFp.stubVoid()
        adManagerUpdateInterstitialFp.stubVoid()
        adManagerUpdateRewardedFp.stubVoid()
    }
}

@Suppress("unused")
val nodeDisableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Stops in-app Firebase event logging. Engine-side reporting inside the native engine is not covered.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_NODE)
    dependsOn(nodeDisableAnalyticsCollectionPatch)

    execute {
        analyticsLogEventFp.stubVoid()
    }
}

@Suppress("unused")
val nodeDisableSelfUpdatePatch = bytecodePatch(
    name = "Disable update prompt",
    description = "Disables the in-app self-update notifier so the patched app is not prompted to reinstall the original.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_NODE)

    execute {
        selfUpdateStartFp.stubVoid()
    }
}
