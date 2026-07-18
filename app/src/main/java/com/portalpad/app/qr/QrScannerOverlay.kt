package com.portalpad.app.qr

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * Rear-camera QR/barcode scanner shown inside the wheel overlay. Live feed with
 * an animated accent scan line and a camera-style torch control (status glyph
 * above the feed; tapping opens an in-feed pill with Auto/On/Off). A decoded
 * code UNBINDS the camera and swaps the feed for a compact typed result card:
 * Wi-Fi → Join, URL → Open (external display), text → Copy + Search (Google on
 * the external display). Rescan rebinds with the torch mode re-applied.
 */
@Composable
fun QrScannerOverlay(
    lifecycleOwner: LifecycleOwner,
    accent: Color,
    onOpenOnDisplay: (String) -> Unit,
    onJoinWifi: (QrResult) -> Unit,
    onCopyText: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<QrResult?>(null) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    var pendingHits by remember { mutableStateOf(0) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val providerRef = remember { java.util.concurrent.atomic.AtomicReference<ProcessCameraProvider?>(null) }
    val cameraRef = remember { java.util.concurrent.atomic.AtomicReference<androidx.camera.core.Camera?>(null) }
    var torchMode by remember { mutableStateOf(TorchMode.OFF) }
    var torchPillOpen by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { providerRef.get()?.unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val feedW = if (screenW - 48.dp < 300.dp) screenW - 48.dp else 300.dp
    val hairline = Color(0x73E5D8FF)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (result == null) {
            // Torch status glyph — bolt (on) or slashed bolt (off). Tap opens
            // the in-feed on/off pill.
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xE0221A38))
                    .border(1.dp, hairline, RoundedCornerShape(14.dp))
                    .clickable { torchPillOpen = !torchPillOpen }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Icon(
                    when (torchMode) {
                        TorchMode.ON -> Icons.Default.FlashOn
                        TorchMode.OFF -> Icons.Default.FlashOff
                    },
                    contentDescription = "Flashlight",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .size(feedW, 210.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xF20E0A18))
                    .border(1.dp, hairline, RoundedCornerShape(18.dp)),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            // TextureView mode: SurfaceView (default) renders on
                            // a separate hardware layer that ignores Compose
                            // size/clip/z-order.
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                        bindCamera(ctx, previewView, lifecycleOwner, analysisExecutor,
                            onBound = { provider, camera ->
                                providerRef.set(provider)
                                cameraRef.set(camera)
                                // Re-apply the chosen mode after a (re)bind —
                                // Rescan otherwise silently reset the torch.
                                when (torchMode) {
                                    TorchMode.ON -> camera.cameraControl.enableTorch(true)
                                    TorchMode.OFF -> camera.cameraControl.enableTorch(false)
                                }
                            }) { text ->
                            if (result == null) {
                                if (text == pendingText) {
                                    pendingHits++
                                    if (pendingHits >= 3) {
                                        // Camera OFF the moment a result lands —
                                        // it was still capturing behind the
                                        // result card (field suspicion, real).
                                        runCatching { providerRef.get()?.unbindAll() }
                                        result = QrResult.classify(text)
                                    }
                                } else {
                                    pendingText = text
                                    pendingHits = 1
                                }
                            }
                        }
                        previewView
                    },
                )
                ScanLine(accent)
                Text(
                    "Point at a QR code or barcode",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp),
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x55000000))
                        .clickable { onClose() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("✕", color = Color.White, fontSize = 14.sp) }
                if (torchPillOpen) {
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xE0221A38))
                            .border(1.dp, hairline, RoundedCornerShape(18.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        TorchPillIcon(Icons.Default.FlashOn, torchMode == TorchMode.ON, accent) {
                            torchMode = TorchMode.ON; torchPillOpen = false
                            cameraRef.get()?.cameraControl?.enableTorch(true)
                        }
                        TorchPillIcon(Icons.Default.FlashOff, torchMode == TorchMode.OFF, accent) {
                            torchMode = TorchMode.OFF; torchPillOpen = false
                            cameraRef.get()?.cameraControl?.enableTorch(false)
                        }
                    }
                }
            }
        } else {
            // Compact wrap-height result card (the fixed-feed-height version
            // left dead space above the title).
            val r = result!!
            Column(
                Modifier
                    .width(feedW)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xF20E0A18))
                    .border(1.dp, hairline, RoundedCornerShape(18.dp))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (r.kind == QrResult.Kind.TEXT) "Scanned results" else r.title,
                    color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    r.detail,
                    color = Color(0xFFCFC6E0),
                    fontSize = 13.sp,
                    maxLines = 3,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (r.kind == QrResult.Kind.URL) {
                    Spacer(Modifier.height(4.dp))
                    Text("Opens on the external display", color = Color(0xFF9C8FBE), fontSize = 11.sp)
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            when (r.kind) {
                                QrResult.Kind.URL -> r.url?.let(onOpenOnDisplay)
                                QrResult.Kind.WIFI -> onJoinWifi(r)
                                QrResult.Kind.TEXT -> onCopyText(r.detail)
                            }
                            onClose()
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = accent, contentColor = Color.White,
                        ),
                    ) {
                        Text(
                            when (r.kind) {
                                QrResult.Kind.URL -> "Open"
                                QrResult.Kind.WIFI -> "Join"
                                QrResult.Kind.TEXT -> "Copy"
                            },
                            maxLines = 1, softWrap = false,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = {
                        result = null; pendingText = null; pendingHits = 0
                        // Feed recomposes → fresh bind (factory runs again).
                    }) {
                        Text("Rescan", color = Color.White, maxLines = 1, softWrap = false)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onClose) {
                        Text("Cancel", color = Color(0xFFA9A4B4), maxLines = 1, softWrap = false)
                    }
                }
                if (r.kind == QrResult.Kind.TEXT) {
                    TextButton(onClick = {
                        val q = java.net.URLEncoder.encode(r.detail, "UTF-8")
                        onOpenOnDisplay("https://www.google.com/search?q=$q")
                        onClose()
                    }) {
                        Text("Search on external display", color = accent,
                            maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}

private enum class TorchMode { ON, OFF }

@Composable
private fun TorchPillIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Icon(
        icon,
        contentDescription = null,
        tint = if (selected) accent else Color(0xFFCFC6E0),
        modifier = Modifier
            .size(22.dp)
            .clickable { onClick() },
    )
}

@Composable
private fun ScanLine(accent: Color) {
    val transition = rememberInfiniteTransition(label = "scan")
    val y by transition.animateFloat(
        initialValue = 0.12f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scanY",
    )
    Canvas(Modifier.fillMaxSize()) {
        val lineY = size.height * y
        drawLine(
            color = accent,
            start = Offset(size.width * 0.08f, lineY),
            end = Offset(size.width * 0.92f, lineY),
            strokeWidth = 4f,
        )
    }
}

private fun bindCamera(
    context: Context,
    previewView: PreviewView,
    lifecycleOwner: LifecycleOwner,
    executor: java.util.concurrent.Executor,
    onBound: (ProcessCameraProvider, androidx.camera.core.Camera) -> Unit,
    onDecoded: (String) -> Unit,
) {
    val future = ProcessCameraProvider.getInstance(context)
    future.addListener({
        val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build().also { a ->
                a.setAnalyzer(executor) { proxy ->
                    val text = runCatching { QrDecoder.decode(proxy) }.getOrNull()
                    proxy.close()
                    if (text != null) {
                        ContextCompat.getMainExecutor(context).execute { onDecoded(text) }
                    }
                }
            }
        runCatching {
            provider.unbindAll()
            val camera = provider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
            )
            onBound(provider, camera)
        }
    }, ContextCompat.getMainExecutor(context))
}
