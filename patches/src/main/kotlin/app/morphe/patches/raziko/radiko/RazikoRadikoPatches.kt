package app.morphe.patches.raziko.radiko

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import org.w3c.dom.Element
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

// 対象: com.gmail.jp.raziko.radiko  versionName 1.2.0267
// クラス名は難読化されているため、このバージョン専用。他バージョンでは Fingerprint の見直しが必要。
private const val PKG = "com.gmail.jp.raziko.radiko"
private const val VERSION = "1.2.0267"
private const val APP = "Lcom/gmail/nagamatu/radiko"

/** main.w()V : Tag Manager のリモートコンテナのフラグを読んで分岐するメソッド */
private val MainRemoteConfigFingerprint = Fingerprint(
    definingClass = "$APP/main;",
    name = "w",
    returnType = "V",
    parameters = listOf(),
    strings = listOf("update-available", "self-destruction"),
)

private val EpgActivityLoadAdFingerprint = Fingerprint(
    definingClass = "$APP/RadikoEpgActivity;", name = "o", returnType = "V", parameters = listOf(),
)
private val EpgActivityShowAdFingerprint = Fingerprint(
    definingClass = "$APP/RadikoEpgActivity;", name = "p", returnType = "V", parameters = listOf(),
)
private val EpgFragmentLoadAdFingerprint = Fingerprint(
    definingClass = "$APP/RadikoEpgFragmentActivity;", name = "j", returnType = "V", parameters = listOf(),
)
private val EpgFragmentShowAdFingerprint = Fingerprint(
    definingClass = "$APP/RadikoEpgFragmentActivity;", name = "k", returnType = "V", parameters = listOf(),
)
private val EpgPagerLoadAdFingerprint = Fingerprint(
    definingClass = "$APP/RadikoEpgPager;", name = "j", returnType = "V", parameters = listOf(),
)
private val EpgPagerShowAdFingerprint = Fingerprint(
    definingClass = "$APP/RadikoEpgPager;", name = "k", returnType = "V", parameters = listOf(),
)

/** バナー広告の loadAd を呼ぶ Runnable (eq 内部から呼ばれる er / es) */
private val BannerReloadAFingerprint = Fingerprint(
    definingClass = "$APP/er;", name = "run", returnType = "V", parameters = listOf(),
)
private val BannerReloadBFingerprint = Fingerprint(
    definingClass = "$APP/es;", name = "run", returnType = "V", parameters = listOf(),
)


/** main.s()V : 広告エリア(LinearLayout)を取得し、AdView と AdRequest を組み立てて表示するメソッド */
private val MainSetupAdFingerprint = Fingerprint(
    definingClass = "$APP/main;", name = "s", returnType = "V", parameters = listOf(),
    strings = listOf("ca-app-pub-7817267476333453/8258580322"),
)

/** i-mobile は AdMob メディエーション経由でのみ呼ばれる(アプリ本体からの直接参照なし) */
private const val IMOBILE_ADAPTER = "Lcom/google/ads/mediation/imobile/IMobileAdapter;"
private val IMobileBannerBridgeFingerprint = Fingerprint(
    definingClass = IMOBILE_ADAPTER, name = "requestBannerAd", returnType = "V",
    parameters = listOf(
        "Lcom/google/ads/mediation/MediationBannerListener;", "Landroid/app/Activity;",
        "Lcom/google/ads/mediation/MediationServerParameters;", "Lcom/google/ads/AdSize;",
        "Lcom/google/ads/mediation/MediationAdRequest;", "Lcom/google/ads/mediation/NetworkExtras;",
    ),
)
private val IMobileBannerFingerprint = Fingerprint(
    definingClass = IMOBILE_ADAPTER, name = "requestBannerAd", returnType = "V",
    parameters = listOf(
        "Lcom/google/ads/mediation/MediationBannerListener;", "Landroid/app/Activity;",
        "Lcom/google/ads/mediation/imobile/IMobileServerParameters;", "Lcom/google/ads/AdSize;",
        "Lcom/google/ads/mediation/MediationAdRequest;", "Lcom/google/ads/mediation/imobile/IMobileExtras;",
    ),
)
private val IMobileInterstitialFingerprint = Fingerprint(
    definingClass = IMOBILE_ADAPTER, name = "requestInterstitialAd", returnType = "V",
    parameters = listOf(
        "Lcom/google/ads/mediation/MediationInterstitialListener;", "Landroid/app/Activity;",
        "Lcom/google/ads/mediation/MediationServerParameters;",
        "Lcom/google/ads/mediation/MediationAdRequest;", "Lcom/google/ads/mediation/NetworkExtras;",
    ),
)

/**
 * `const-string <target>` → 直後の invoke → move-result の順で並ぶ箇所を探し、
 * move-result の直後にレジスタへ固定値を書き込んで分岐結果を上書きする。
 * レジスタ番号は move-result から読み取るので、固定値はバージョン差に影響されにくい。
 */
private fun app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.forceFlag(
    key: String,
    value: Int,
) {
    val list = instructions.toList()
    val strIndex = list.indexOfFirst {
        it is ReferenceInstruction && (it.reference as? StringReference)?.string == key
    }
    require(strIndex >= 0) { "const-string \"$key\" が見つかりません" }

    // key の後ろ数命令以内にある最初の move-result* を探す
    val resultIndex = (strIndex + 1..strIndex + 6).first {
        list[it].opcode.name.startsWith("MOVE_RESULT")
    }
    val reg = (list[resultIndex] as OneRegisterInstruction).registerA
    addInstruction(resultIndex + 1, "const/4 v$reg, 0x$value")
}

@Suppress("unused")
val disableRemoteSwitchesPatch = bytecodePatch(
    name = "Disable remote kill switches",
    description = "リモート設定による「更新しないと使えなくなる(Google Playへ飛ばす)」と「自爆(強制終了)」を無効化します。",
) {
    compatibleWith(PKG(VERSION))

    execute {
        MainRemoteConfigFingerprint.method.apply {
            forceFlag("update-available", 0)
            forceFlag("self-destruction", 0)
        }
    }
}

@Suppress("unused")
val disableAnalyticsPatch = bytecodePatch(
    name = "Disable analytics",
    description = "Google Analytics を常にオプトアウト状態にします。",
) {
    compatibleWith(PKG(VERSION))

    execute {
        // main.w() 内の SharedPreferences.getBoolean("analytics_optout", false) の結果を true に固定
        MainRemoteConfigFingerprint.method.forceFlag("analytics_optout", 1)
    }
}

@Suppress("unused")
val removeAdsPatch = bytecodePatch(
    name = "Remove ads",
    description = "AdMob(バナー/インタースティシャル)と i-mobile のメディエーションを無効化し、広告表示エリアも非表示にします。",
) {
    compatibleWith(PKG(VERSION))

    execute {
        // 1) 広告の読み込み・表示の呼び出し箇所
        listOf(
            EpgActivityLoadAdFingerprint, EpgActivityShowAdFingerprint,
            EpgFragmentLoadAdFingerprint, EpgFragmentShowAdFingerprint,
            EpgPagerLoadAdFingerprint, EpgPagerShowAdFingerprint,
            BannerReloadAFingerprint, BannerReloadBFingerprint,
            // 2) i-mobile アダプタ(AdMob が設定に応じて呼ぶ入口)
            IMobileBannerBridgeFingerprint, IMobileBannerFingerprint, IMobileInterstitialFingerprint,
        ).forEach { it.method.addInstruction(0, "return-void") }

        // 3) 広告表示エリア: findViewById した LinearLayout を GONE にして、AdView 生成処理をスキップ
        MainSetupAdFingerprint.method.apply {
            val list = instructions.toList()
            val castIndex = list.indexOfFirst {
                it is ReferenceInstruction && it.opcode.name == "CHECK_CAST" &&
                    it.reference.toString() == "Landroid/widget/LinearLayout;"
            }
            require(castIndex >= 0) { "広告エリアの check-cast が見つかりません" }
            val viewReg = (list[castIndex] as OneRegisterInstruction).registerA
            // viewReg にビュー、v1 を一時的な定数に使う(この時点では v1 は未使用で、直後に return する)
            addInstruction(
                castIndex + 1,
                """
                const/16 v1, 0x8
                invoke-virtual { v$viewReg, v1 }, Landroid/widget/LinearLayout;->setVisibility(I)V
                return-void
                """.trimIndent(),
            )
        }
    }
}

/** マニフェストから広告・解析の各コンポーネントとメタデータを削除 */
@Suppress("unused")
val removeAdAndAnalyticsManifestEntriesPatch = resourcePatch(
    name = "Remove ad/analytics manifest entries",
    description = "AdActivity、Google Analytics 関連の service/receiver、AdMob の meta-data を削除します。",
) {
    compatibleWith(PKG(VERSION))

    execute {
        val removeComponents = setOf(
            "com.google.android.gms.ads.AdActivity",
            "com.google.android.gms.analytics.AnalyticsService",
            "com.google.android.gms.analytics.AnalyticsReceiver",
            "com.google.android.gms.analytics.CampaignTrackingService",
            "com.google.android.gms.analytics.CampaignTrackingReceiver",
            "com.google.android.gms.tagmanager.PreviewActivity",
        )
        val removeMeta = setOf("ADMOB_PUBLISHER_ID", "ADMOB_ALLOW_LOCATION_FOR_ADS")
        val androidNs = "http://schemas.android.com/apk/res/android"

        document("AndroidManifest.xml").use { doc ->
            val app = doc.getElementsByTagName("application").item(0)
            val children = app.childNodes
            val targets = (0 until children.length).map { children.item(it) }.filterIsInstance<Element>()
                .filter { e ->
                    val name = e.getAttributeNS(androidNs, "name")
                    when (e.tagName) {
                        "activity", "service", "receiver" -> name in removeComponents
                        "meta-data" -> name in removeMeta
                        else -> false
                    }
                }
            targets.forEach { app.removeChild(it) }
        }
    }
}

/** main.a(ILjava/lang/String;Landroid/content/Intent;Z)V : サービスからの通知コードに応じてダイアログを出すメソッド */
private val MainShowDialogFingerprint = Fingerprint(
    definingClass = "$APP/main;", name = "a", returnType = "V",
    parameters = listOf("I", "Ljava/lang/String;", "Landroid/content/Intent;", "Z"),
)

@Suppress("unused")
val suppressConnectionErrorDialogPatch = bytecodePatch(
    name = "Suppress connection error dialog",
    description = "「Radikoサービスへの接続が出来ません」ダイアログを表示しません(起動直後の最初の接続失敗で出るもの)。",
) {
    compatibleWith(PKG(VERSION))

    execute {
        MainShowDialogFingerprint.method.apply {
            val index = instructions.toList().indexOfFirst {
                it is ReferenceInstruction &&
                    it.reference.toString().contains("No1E100ConnectionDialogFragment;->P()")
            }
            require(index >= 0) { "No1E100ConnectionDialogFragment の生成箇所が見つかりません" }
            // このケースの分岐先で、ダイアログを作らずに終了する
            replaceInstruction(index, "return-void")
        }
    }
}

// ---------------------------------------------------------------------------
// 別アプリとしてインストールするためのパッケージ名変更
// ---------------------------------------------------------------------------

private const val OLD_PACKAGE = "com.gmail.jp.raziko.radiko"

/** 変更後のパッケージ名。OLD_PACKAGE を含む名前にしておくと、パス検索系の contains() 判定とも整合します */
private const val NEW_PACKAGE = "com.gmail.jp.raziko.radiko.mod"

private const val OLD_RECEIVER_PERMISSION = "com.gmail.nagamatu.radiko.RECEIVER_PERMISSION"
private const val LAUNCHER_LABEL = "radiko (mod)"

/**
 * 文字列の置き換えルール(dex とマニフェスト/XML で共通)。
 * - Google Play へのリンクは対象外
 * - 外部拡張アプリ(aac2mp3 等)向けの ".extensions" は元のまま
 * - カスタム権限は重複インストールエラーを避けるため新パッケージ配下へ
 * - 元パッケージ名を含むもの(authority、アクション、content URI、prefs名など)は部分置換
 */
private fun renameValue(value: String): String? = when {
    value.startsWith("market://") -> null
    value == "$OLD_PACKAGE.extensions" -> null
    value == OLD_RECEIVER_PERMISSION -> "$NEW_PACKAGE.RECEIVER_PERMISSION"
    value.contains(OLD_PACKAGE) -> value.replace(OLD_PACKAGE, NEW_PACKAGE)
    else -> null
}

private val changePackageResourcePatch = resourcePatch {
    execute {
        val androidNs = "http://schemas.android.com/apk/res/android"

        fun org.w3c.dom.Element.renameAttributes() {
            val attrs = attributes
            for (i in 0 until attrs.length) {
                val attr = attrs.item(i)
                renameValue(attr.nodeValue)?.let { attr.nodeValue = it }
            }
            val children = childNodes
            for (i in 0 until children.length) {
                (children.item(i) as? Element)?.renameAttributes()
            }
        }

        document("AndroidManifest.xml").use { doc ->
            val manifest = doc.documentElement
            // package 属性、authorities、アクション名、権限名、meta-data 名などをまとめて置換
            manifest.renameAttributes()
            manifest.setAttribute("package", NEW_PACKAGE)

            // ランチャーの表示名を変えて、元のアプリと見分けられるようにする
            val app = doc.getElementsByTagName("application").item(0) as Element
            app.setAttributeNS(androidNs, "android:label", LAUNCHER_LABEL)
            val activities = doc.getElementsByTagName("activity")
            for (i in 0 until activities.length) {
                val a = activities.item(i) as Element
                if (a.getAttributeNS(androidNs, "name") == "com.gmail.nagamatu.radiko.main" &&
                    a.hasAttributeNS(androidNs, "label")
                ) {
                    a.setAttributeNS(androidNs, "android:label", LAUNCHER_LABEL)
                }
            }
        }

        // 検索サジェストの authority
        document("res/xml/searchable.xml").use { doc ->
            doc.documentElement.renameAttributes()
        }
    }
}

@Suppress("unused")
val changePackageNamePatch = bytecodePatch(
    name = "Change package name (install as separate app)",
    description = "パッケージ名・authority・権限名などを変更し、元のアプリと共存してインストールできるようにします。",
) {
    compatibleWith(PKG(VERSION))
    dependsOn(changePackageResourcePatch)

    execute {
        // アプリ本体のクラスにある const-string のうち、元のパッケージ名に関わるものを置換する
        classDefForEach { classDef ->
            if (!classDef.type.startsWith("Lcom/gmail/nagamatu/")) return@classDefForEach

            // 先に読み取り専用で置換対象を探す(無ければクラスを可変化しない)
            val targets = classDef.methods.mapNotNull { method ->
                val hits = method.implementation?.instructions?.withIndex()?.mapNotNull { (index, ins) ->
                    val str = ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string
                    val renamed = str?.let(::renameValue)
                    if (renamed != null) Triple(index, ins, renamed) else null
                }?.toList().orEmpty()
                if (hits.isEmpty()) null else method to hits
            }
            if (targets.isEmpty()) return@classDefForEach

            val mutableClass = mutableClassDefBy(classDef)
            targets.forEach { (method, hits) ->
                val mutableMethod = mutableClass.methods.first {
                    it.name == method.name &&
                        it.parameterTypes.map { p -> p.toString() } == method.parameterTypes.map { p -> p.toString() }
                }
                hits.forEach { (index, ins, renamed) ->
                    val reg = (ins as OneRegisterInstruction).registerA
                    val op = if (ins.opcode.name == "CONST_STRING_JUMBO") "const-string/jumbo" else "const-string"
                    val escaped = renamed.replace("\\", "\\\\").replace("\"", "\\\"")
                    mutableMethod.replaceInstruction(index, "$op v$reg, \"$escaped\"")
                }
            }
        }
    }
}
