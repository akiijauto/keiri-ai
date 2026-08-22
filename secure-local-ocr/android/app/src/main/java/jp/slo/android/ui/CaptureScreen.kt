package jp.slo.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import jp.slo.android.ocr.ImagePrep
import java.util.concurrent.Executors

/**
 * OCR専用カメラ画面（企画書 Step 1）。
 *
 * 通常の写真アプリを経由しない。撮影した画像はギャラリーにも端末ストレージにも保存せず、
 * メモリ上でOCRへ渡してすぐ破棄する（企画書 11, 12）。
 */
@Composable
fun CaptureScreen(
    onCaptured: (ImageProxy) -> Unit,
    onError: (String) -> Unit,
    statusMessage: String?
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var capturing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (hasPermission) {
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        // 既定の FILL_CENTER はセンサー画像を切り詰めて画面を埋めるため、
                        // 画面に見えている範囲と実際に撮影される範囲がずれる。
                        // 枠に収めたつもりが写っていない、という事故を避けるため全体を表示する。
                        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .build()
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture
                            )
                            imageCapture = capture
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    }
                )
                DocumentGuideOverlay()
            } else {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("カメラの権限が必要です", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "撮影した画像は端末外へ送信しません。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )
                    Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("権限を許可する")
                    }
                }
            }
        }

        Column(Modifier.padding(16.dp)) {
            if (statusMessage != null) {
                Text(statusMessage, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "書類が画面いっぱいになるよう近づけて撮影してください。" +
                    "枠は目安です（画像全体を読み取ります）。撮影画像は保存されません。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = hasPermission && imageCapture != null && !capturing,
                    onClick = {
                        val capture = imageCapture ?: return@Button
                        capturing = true
                        capture.takePicture(
                            executor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    capturing = false
                                    // 画像はここから先もメモリ上だけで扱う
                                    onCaptured(image)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    capturing = false
                                    onError("E_CAPTURE_${exception.imageCaptureError}")
                                }
                            }
                        )
                    }
                ) { Text(if (capturing) "撮影中…" else "撮影してOCR") }

                OutlinedButton(onClick = { onError("E_CANCELLED") }) { Text("やり直す") }
            }
        }
    }
}

/** 撮影ガイド枠。ImagePrep.RelativeRect.DOCUMENT_GUIDE と同じ比率を描く。 */
@Composable
private fun DocumentGuideOverlay() {
    val guide = ImagePrep.RelativeRect.DOCUMENT_GUIDE
    Canvas(Modifier.fillMaxSize()) {
        val left = size.width * guide.left
        val top = size.height * guide.top
        val width = size.width * (guide.right - guide.left)
        val height = size.height * (guide.bottom - guide.top)
        drawRect(
            color = Color(0xAAFFFFFF),
            topLeft = Offset(left, top),
            size = Size(width, height),
            style = Stroke(width = 4f)
        )
    }
}
