@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
// ImageProxy.image помечен @ExperimentalGetImage - стандартный путь для ML Kit-анализатора.
@file:androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)

package com.freeturn.app.ui.screens.share

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.freeturn.app.R
import com.freeturn.app.data.share.FreeturnLink
import com.freeturn.app.domain.share.LinkImportBus
import com.freeturn.app.data.HapticUtil
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.koin.compose.koinInject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import com.freeturn.app.ui.theme.Spacing

@Composable
fun QrScannerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val linkBus = koinInject<LinkImportBus>()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permanentlyDenied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { result ->
        granted = result
        // "Навсегда" - только когда система больше не покажет диалог
        // (rationale=false после отказа); первый Deny оставляет повторный запрос.
        val activity = context.findActivity()
        permanentlyDenied = !result && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
                granted = now
                if (now) permanentlyDenied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scanner_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(R.drawable.arrow_back_24px),
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (granted) {
                CameraPreview(
                    onResult = { raw ->
                        linkBus.offer(raw)
                        onBack()
                    }
                )
            } else {
                CameraPermissionGate(
                    permanentlyDenied = permanentlyDenied,
                    onRequest = { launcher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun CameraPreview(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Колбэк может смениться при рекомпозиции - analyzer живёт дольше.
    val onResultCurrent by rememberUpdatedState(onResult)

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    // ML Kit видит один QR в нескольких кадрах подряд - результат отдаём один раз.
    val handled = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var boundProvider: ProcessCameraProvider? = null
        val disposed = AtomicBoolean(false)
        providerFuture.addListener({
            if (disposed.get()) return@addListener
            val provider = providerFuture.get()
            boundProvider = provider
            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy ->
                // После успешного скана кадры до onDispose в ML Kit не гоняем.
                val media = if (handled.get()) null else proxy.image
                if (media == null) {
                    proxy.close()
                    return@setAnalyzer
                }
                val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                scanner.process(img)
                    .addOnSuccessListener { codes ->
                        val raw = codes.firstOrNull()?.rawValue
                        if (!raw.isNullOrBlank() &&
                            FreeturnLink.looksLikeLink(raw) &&
                            handled.compareAndSet(false, true)
                        ) {
                            HapticUtil.perform(context, HapticUtil.Pattern.SUCCESS)
                            onResultCurrent(raw)
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (_: Exception) {
                // Гонка с lifecycle stop - экран уже закрывается.
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed.set(true)
            boundProvider?.unbindAll()
            analysisExecutor.shutdown()
            scanner.close()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = Spacing.xxxl, start = Spacing.xxl, end = Spacing.xxl)
        ) {
            Text(
                text = stringResource(R.string.scanner_hint),
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun CameraPermissionGate(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xxxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(
                if (permanentlyDenied) R.string.scanner_permission_denied
                else R.string.scanner_permission_rationale
            ),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        if (permanentlyDenied) {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.scanner_permission_open_settings))
            }
        } else {
            Button(onClick = onRequest, modifier = Modifier.padding(top = Spacing.lg)) {
                Text(stringResource(R.string.scanner_permission_grant))
            }
        }
    }
}
