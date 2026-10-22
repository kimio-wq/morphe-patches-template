package teke.patches.abema

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

// 対象: ABEMA (tv.abema) 10.188.0 (versionCode 2120401)
// 静的解析のみで作成。ビルド・実機確認は未実施です。
// Morphe の版で Compatibility / document() / Fingerprint(custom=) の形が違う場合は、その行だけ合わせてください。
// 注意: XAPK (base + config split) です。分割APKをまとめてパッチできる形(APKM/XAPK対応)で渡してください。
// 注意: 難読化(R8)された名前を含むため、10.188.0 以外のバージョンでは使えません。

private val COMPATIBILITY_ABEMA = Compatibility(
    packageName = "tv.abema",
    name = "ABEMA",
    appIconColor = 0x00C853,
    targets = listOf(AppTarget(version = "10.188.0")),
)

// ---------------------------------------------------------------------------
// Fingerprint helpers
// ---------------------------------------------------------------------------

/** クラス + メソッド名 + 戻り値型で特定 (引数は問わない。同名オーバーロードが無いものだけに使う)。 */
private fun target(classType: String, name: String, ret: String) = Fingerprint(
    custom = { method, classDef ->
        classDef.type == classType && method.name == name && method.returnType == ret
    }
)

/** 引数まで指定して特定。 */
private fun target(classType: String, name: String, params: String, ret: String) = Fingerprint(
    custom = { method, classDef ->
        classDef.type == classType &&
            method.name == name &&
            method.returnType == ret &&
            method.parameterTypes.joinToString("") { it.toString() } == params
    }
)

/** 先頭で即 return する。V は return-void、オブジェクト型は null を返す。 */
private fun Fingerprint.stub() {
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

// --- 広告挿入 ---
// PlaybackResource.Manifest.getAdInsertion() が null のとき、PlaybackResourcesApiClient は
// 広告挿入モードを「なし」として扱う (Manifest の url をそのまま再生。MediaTailor のセッション取得を行わない)。
// サーバー側挿入 (MEDIATAILOR / YOSPACE) とクライアント側 (CSAI) のいずれもこの値で決まる。
private val manifestGetAdInsertionFp = target(
    "Ltv/abema/fluffy/protos/PlaybackResource\$Manifest;", "getAdInsertion", "",
    "Ltv/abema/fluffy/protos/PlaybackResource\$AdInsertion;"
)

// CDN バランサー (NPAW)。null のとき CDN バランシングなしで再生する。
private val manifestGetCdnBalancingFp = target(
    "Ltv/abema/fluffy/protos/PlaybackResource\$Manifest;", "getCdnBalancing", "",
    "Ltv/abema/fluffy/protos/PlaybackResource\$CdnBalancing;"
)

// --- Telemetry ---
private const val ADJUST = "Lcom/adjust/sdk/Adjust;"
private const val NEWRELIC = "Lcom/newrelic/agent/android/NewRelic;"
private const val NPAW_NQS = "Lcom/npaw/analytics/video/VideoPlayerNqsRequestHandler;"
private const val MINE_TRACK = "Ltv/abema/data/api/tracking/DefaultMineTrackApi;"

private val adjustInitFp = target(ADJUST, "initSdk", "Lcom/adjust/sdk/AdjustConfig;", "V")
private val newRelicStartFp = target(NEWRELIC, "start", "Landroid/content/Context;", "V")

private val npawFps = listOf(
    target(NPAW_NQS, "execute", "Ljava/lang/String;", "V"),
    target(NPAW_NQS, "execute", "Ljava/lang/String;Ljava/util/Map;", "V"),
    target(NPAW_NQS, "execute", "Ljava/lang/String;Ljava/util/Map;Lms/a;", "V"),
    target(NPAW_NQS, "execute", "Ljava/lang/String;Ljava/util/Map;Lms/a;Lms/a;", "V"),
    target(NPAW_NQS, "startPings", "", "V"),
)

// ABEMA 自社の行動ログ送信 (DefaultMineTrackApi)。a〜j は戻り値 void のイベント送信。
private val mineTrackFps = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
    .map { target(MINE_TRACK, it, "V") }

// ---------------------------------------------------------------------------
// Resource patch: Firebase 収集の無効化と広告ID系権限の削除
// ---------------------------------------------------------------------------

@Suppress("unused")
val abemaDisableAnalyticsCollectionPatch = resourcePatch(
    name = "Disable analytics collection (manifest)",
    description = "Turns off Firebase Analytics and Crashlytics collection and removes advertising-ID permissions.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ABEMA)

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
val abemaDisableAdInsertionPatch = bytecodePatch(
    name = "Disable ad insertion",
    description = "Plays the manifest directly instead of requesting an ad-stitched session (MediaTailor / Yospace / CSAI). " +
        "Fixes playback not starting when ad servers are blocked. DRM and subscription checks are not changed.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ABEMA)

    execute {
        manifestGetAdInsertionFp.stub()
    }
}

@Suppress("unused")
val abemaDisableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Disables Adjust, New Relic, NPAW video analytics and ABEMA's own behavior-log uploads. " +
        "Braze (push notifications), watch-time and feature-flag calls are left as they are.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ABEMA)
    dependsOn(abemaDisableAnalyticsCollectionPatch)

    execute {
        adjustInitFp.stub()
        newRelicStartFp.stub()
        npawFps.forEach { it.stub() }
        mineTrackFps.forEach { it.stub() }
    }
}

@Suppress("unused")
val abemaDisableCdnBalancerPatch = bytecodePatch(
    name = "Disable CDN balancer (NPAW)",
    description = "Stops the NPAW CDN balancer from being used. Optional: off by default because it may change which CDN is used.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_ABEMA)

    execute {
        manifestGetCdnBalancingFp.stub()
    }
}
