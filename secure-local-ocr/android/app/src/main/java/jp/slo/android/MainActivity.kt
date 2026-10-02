package jp.slo.android

import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import jp.slo.android.handoff.HandoffSession
import jp.slo.android.handoff.SloWebViewBridge
import jp.slo.android.log.FileAuditLog
import jp.slo.android.ocr.ImagePrep
import jp.slo.android.ocr.OnDeviceOcr
import jp.slo.android.security.AppLock
import jp.slo.android.security.DeviceIntegrity
import jp.slo.android.ui.CaptureScreen
import jp.slo.android.ui.HandoffScreen
import jp.slo.android.ui.LockScreen
import jp.slo.android.ui.OcrDebugScreen
import jp.slo.android.ui.ReviewScreen
import jp.slo.android.ui.SessionViewModel
import jp.slo.android.ui.SloTheme
import jp.slo.android.ui.VerifyScreen
import jp.slo.core.AuditLog
import jp.slo.core.Extractor

/**
 * 画面遷移とフェーズ分離の実体。
 *
 *   [ロック] → [撮影] → [OCR] → [確認・修正] → [Web入力] → [破棄]
 *
 * Phase A（撮影〜確認）は通信を必要としない。
 * offline フレーバーでは INTERNET 権限自体が無いため、Phase A の通信は仕組みとして起こり得ない。
 */
class MainActivity : FragmentActivity() {

    private lateinit var vm: SessionViewModel
    private lateinit var auditLog: FileAuditLog
    private val ocr = OnDeviceOcr()

    private var handoffSession: HandoffSession? = null
    private var bridge: SloWebViewBridge? = null
    private var lastInteractionAt = 0L

    /** これ未満の行数しか読めなかったら、補正をかけて読み直す。 */
    private val MIN_ACCEPTABLE_LINES = 3

    /**
     * 登録先の業務Webサイトと許可オリジン。
     * 実運用ではMDM配布の設定から読み込む。検証段階ではデバッグビルドのみ端末上で変更できる。
     */
    private val targetUrl: String get() = AppConfig.targetUrl(this)
    private val allowedOrigins: List<String> get() = AppConfig.allowedOrigins(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // スクリーンショット・画面録画・最近使ったアプリのサムネイルを抑止（企画書 15）。
        // 実機検証で結果を記録できるよう、デバッグビルドに限り明示的な設定で外せる。
        // リリースビルドでは AppConfig.allowScreenshots が常に false を返すため、必ず有効になる。
        if (!AppConfig.allowScreenshots(this)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }

        vm = ViewModelProvider(this)[SessionViewModel::class.java]
        auditLog = FileAuditLog(this)

        val integrity = DeviceIntegrity.check()

        setContent {
            SloTheme {
                var lockMessage by mutableStateOf<String?>(null)

                when (vm.step) {
                    SessionViewModel.Step.LOCKED -> LockScreen(
                        integrity = integrity,
                        lockAvailable = AppLock.availability(this) == AppLock.Availability.AVAILABLE,
                        message = lockMessage,
                        onUnlock = {
                            AppLock.prompt(
                                activity = this,
                                title = getString(R.string.unlock_title),
                                subtitle = getString(R.string.unlock_subtitle),
                                onSuccess = {
                                    auditLog.add(AuditLog.Event.APP_UNLOCKED)
                                    touch()
                                    vm.goTo(SessionViewModel.Step.CAPTURE)
                                },
                                onFailure = { code ->
                                    lockMessage = "認証できませんでした（$code）"
                                }
                            )
                        },
                        onVerify = { vm.goTo(SessionViewModel.Step.VERIFY) }
                    )

                    SessionViewModel.Step.CAPTURE, SessionViewModel.Step.OCR -> CaptureScreen(
                        onCaptured = { image -> handleCapture(image) },
                        onError = { code ->
                            if (code == "E_CANCELLED") {
                                vm.statusMessage = null
                            } else {
                                auditLog.add(AuditLog.Event.OCR_FAILED, mapOf("reason" to code))
                                vm.statusMessage = "撮影に失敗しました（$code）"
                            }
                        },
                        statusMessage = vm.statusMessage
                    )

                    SessionViewModel.Step.REVIEW -> ReviewScreen(
                        vm = vm,
                        onProceed = { startHandoff() },
                        onRetake = {
                            touch()
                            vm.discard()
                        },
                        onDiscard = { endSession("discarded_by_user") },
                        onInspectOcr = if (BuildConfig.DEBUG) {
                            { vm.goTo(SessionViewModel.Step.OCR_DEBUG) }
                        } else {
                            null
                        }
                    )

                    SessionViewModel.Step.HANDOFF -> HandoffScreen(
                        vm = vm,
                        webHandoffEnabled = BuildConfig.WEB_HANDOFF_ENABLED,
                        targetUrl = targetUrl,
                        bridge = bridge,
                        onDeliver = { deliver() },
                        onFinish = { endSession("finished_by_user") },
                        onCopied = { key ->
                            auditLog.add(AuditLog.Event.FORM_FILLED, mapOf("field" to key, "filled" to "1"))
                            vm.statusMessage = "コピーしました（画面を離れると自動で破棄されます）"
                        },
                        statusMessage = vm.statusMessage
                    )

                    SessionViewModel.Step.DONE -> LockScreen(
                        integrity = integrity,
                        lockAvailable = true,
                        message = "セッションを終了しました。データは破棄済みです。",
                        onUnlock = { vm.goTo(SessionViewModel.Step.CAPTURE) },
                        onVerify = { vm.goTo(SessionViewModel.Step.VERIFY) }
                    )

                    SessionViewModel.Step.OCR_DEBUG -> OcrDebugScreen(
                        lines = vm.ocrLines,
                        elapsedMillis = vm.lastOcrElapsedMillis,
                        onBack = { vm.goTo(SessionViewModel.Step.REVIEW) }
                    )

                    SessionViewModel.Step.VERIFY -> VerifyScreen(
                        info = AppConfig.describe(this),
                        hasInternetPermission = AppConfig.hasInternetPermission(this),
                        isDebugBuild = BuildConfig.DEBUG,
                        targetUrl = AppConfig.targetUrl(this),
                        allowScreenshots = AppConfig.allowScreenshots(this),
                        auditLogTail = auditLog.tail(20),
                        onTargetUrlChange = { url ->
                            AppConfig.setTargetUrl(this, url)
                            vm.statusMessage = "登録先URLを保存しました。"
                            vm.goTo(SessionViewModel.Step.LOCKED)
                        },
                        onAllowScreenshotsChange = { allow ->
                            AppConfig.setAllowScreenshots(this, allow)
                            // FLAG_SECURE はウィンドウ生成時に決まるため、作り直して反映する
                            recreate()
                        },
                        onBack = { vm.goTo(SessionViewModel.Step.LOCKED) }
                    )
                }
            }
        }
    }

    /**
     * 撮影 → 画像補正 → OCR → 項目抽出。すべて端末内で完結する。
     * 原画像はこのメソッドを抜けるまでに破棄する（企画書 12）。
     *
     * 切り出しは行わない。プレビュー上のガイド枠と撮影画像の座標系が一致しないため、
     * 枠に合わせて切り出すと実際には別の領域を切り取ってしまう（ImagePrep 参照）。
     * 画像全体をOCRへ渡し、ガイド枠は構図の目安に留める。
     */
    private fun handleCapture(image: ImageProxy) {
        auditLog.add(AuditLog.Event.CAPTURE_STARTED)
        val rotation = image.imageInfo.rotationDegrees
        val captured = runCatching { image.toBitmap() }.getOrNull()
        image.close()

        val rotated = captured?.let { raw ->
            runCatching {
                ImagePrep.applyRotation(raw, rotation).also { if (it !== raw) raw.recycle() }
            }.getOrNull()
        }

        if (rotated == null) {
            captured?.recycle()
            runOnUiThread {
                auditLog.add(AuditLog.Event.OCR_FAILED, mapOf("reason" to "E_IMAGE_PREP"))
                vm.statusMessage = "画像の前処理に失敗しました。撮り直してください。"
            }
            return
        }

        runOnUiThread { vm.goTo(SessionViewModel.Step.OCR) }
        auditLog.add(AuditLog.Event.OCR_START, mapOf("engine" to "mlkit-ja-on-device"))

        // 1回目は補正なしで読む。ML Kit は自然な写真を前提に学習されており、
        // 素朴なコントラスト強調はかえって不利に働くことがある。
        ocr.recognize(
            bitmap = rotated,
            onResult = { plain ->
                if (plain.lineCount >= MIN_ACCEPTABLE_LINES) {
                    rotated.recycle()
                    finishOcr(plain)
                } else {
                    // 読めた行が少なすぎる場合だけ、補正をかけて読み直す。
                    // 薄い印字や陰のある紙ではこちらが効く。
                    retryWithEnhancement(rotated, plain)
                }
            },
            onError = { code ->
                rotated.recycle()
                runOnUiThread {
                    auditLog.add(AuditLog.Event.OCR_FAILED, mapOf("reason" to code))
                    vm.statusMessage = "文字を認識できませんでした（$code）。明るさと角度を変えて撮り直してください。"
                    vm.goTo(SessionViewModel.Step.CAPTURE)
                }
            }
        )
    }

    /**
     * 補正をかけて読み直し、行数が多いほうの結果を採用する。
     * どちらでも読めなければ、そのことが分かるように0行の結果を返す。
     */
    private fun retryWithEnhancement(source: Bitmap, plain: OnDeviceOcr.Result) {
        val enhanced = runCatching { ImagePrep.enhance(source) }.getOrNull()
        if (enhanced == null) {
            source.recycle()
            finishOcr(plain)
            return
        }
        ocr.recognize(
            bitmap = enhanced,
            onResult = { boosted ->
                source.recycle()
                enhanced.recycle()
                finishOcr(if (boosted.lineCount > plain.lineCount) boosted else plain)
            },
            onError = {
                source.recycle()
                enhanced.recycle()
                finishOcr(plain)
            }
        )
    }

    /** OCR結果から項目を抽出し、確認画面へ進む。 */
    private fun finishOcr(result: OnDeviceOcr.Result) {
        val extracted = Extractor.extract(result.lines, vm.documentType)
        runOnUiThread {
            auditLog.add(
                AuditLog.Event.OCR_SUCCESS,
                mapOf("count" to result.lineCount.toString(), "elapsed_ms" to result.elapsedMillis.toString())
            )
            auditLog.add(AuditLog.Event.EXTRACT_DONE, AuditLog.fieldKeysAttribute(extracted.keys))
            vm.markOfflineCapture(isAirplaneModeOn() || !BuildConfig.WEB_HANDOFF_ENABLED)
            vm.loadExtracted(extracted, result.elapsedMillis, result.lineCount)
            // 読み取った行そのものは、デバッグビルドでのみ画面確認用に保持する（保存はしない）
            vm.setOcrLines(if (BuildConfig.DEBUG) result.lines else emptyList())
            vm.statusMessage = if (result.lineCount == 0) {
                "文字を認識できませんでした。明るさ・角度・距離を変えて撮り直してください。"
            } else {
                null
            }
            vm.goTo(SessionViewModel.Step.REVIEW)
            touch()
        }
    }

    /** 確認済みの項目を持って、Web入力フェーズ（Phase B）へ移る。 */
    private fun startHandoff() {
        touch()
        auditLog.add(AuditLog.Event.USER_CONFIRMED, AuditLog.fieldKeysAttribute(vm.confirmedKeys()))

        val session = HandoffSession(
            documentType = vm.documentType,
            appName = "SecureLocalOCR-Android",
            appVersion = BuildConfig.VERSION_NAME
        )
        handoffSession = session

        if (BuildConfig.WEB_HANDOFF_ENABLED) {
            bridge = SloWebViewBridge(
                session = session,
                allowedOrigins = allowedOrigins,
                onReady = { fields ->
                    auditLog.add(AuditLog.Event.HANDOFF_REQUESTED, mapOf("fields" to fields.size.toString()))
                    vm.statusMessage = "登録先の準備ができました。内容を確認して「この内容を入力する」を押してください。"
                },
                onFilled = { count ->
                    auditLog.add(AuditLog.Event.FORM_FILLED, mapOf("filled" to count.toString()))
                    vm.statusMessage = "${count}項目を入力しました。内容を確認して、登録ボタンはご自身で押してください。"
                },
                onRejected = { reason ->
                    auditLog.add(AuditLog.Event.HANDOFF_REJECTED, mapOf("reason" to reason))
                    vm.statusMessage = "登録先が受け取りを拒否しました（$reason）"
                },
                onOriginDenied = {
                    auditLog.add(AuditLog.Event.ORIGIN_DENIED, mapOf("reason" to "E_ORIGIN"))
                    vm.statusMessage = "許可されていないサイトです。値は渡していません。"
                }
            )
        }
        vm.goTo(SessionViewModel.Step.HANDOFF)
    }

    /** 人間が「この内容を入力する」を押したときにだけ、値がWebViewへ渡る。 */
    private fun deliver() {
        val session = handoffSession ?: return
        val fields = vm.confirmedFields()
        if (fields.isEmpty()) {
            vm.statusMessage = "確認済みの項目がありません。"
            return
        }
        val envelope = session.buildSignedEnvelope(
            fields = fields,
            offlineCapture = vm.offlineCapture,
            engine = "mlkit-ja-on-device"
        )
        auditLog.add(
            AuditLog.Event.HANDOFF_DELIVERED,
            mapOf("fields" to fields.size.toString(), "handoff_id" to (envelope["handoff_id"]?.asString ?: "none"))
        )
        bridge?.deliver(envelope)
        touch()
    }

    private fun endSession(reason: String) {
        bridge?.detach()
        bridge = null
        handoffSession?.destroy()
        handoffSession = null
        vm.discard()
        vm.goTo(SessionViewModel.Step.DONE)
        auditLog.add(AuditLog.Event.SESSION_ENDED, mapOf("reason" to reason))
        auditLog.add(AuditLog.Event.IMAGE_DISCARDED, mapOf("result" to "ok"))
    }

    private fun touch() {
        lastInteractionAt = System.currentTimeMillis()
    }

    private fun isAirplaneModeOn(): Boolean =
        Settings.Global.getInt(contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    /**
     * 一定時間経過後の再認証（企画書 15）。
     * バックグラウンドから戻ったときに猶予を過ぎていたら、扱っていた値を捨てて認証からやり直す。
     */
    override fun onResume() {
        super.onResume()
        if (vm.step == SessionViewModel.Step.LOCKED) return
        val elapsed = System.currentTimeMillis() - lastInteractionAt
        if (lastInteractionAt > 0 && elapsed > AppLock.REAUTH_AFTER_MILLIS) {
            endSession("reauth_required")
            vm.goTo(SessionViewModel.Step.LOCKED)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge?.detach()
        handoffSession?.destroy()
        ocr.close()
    }
}
