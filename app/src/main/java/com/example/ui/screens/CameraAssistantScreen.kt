package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.CommanderViewModel
import com.example.ui.components.GlassCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.DeepSpaceBlack
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.NeuralViolet

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CameraAssistantScreen(
    viewModel: CommanderViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findCameraActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val permStatus by viewModel.permissionStatus.collectAsStateWithLifecycle()
    val analysisResult by viewModel.cameraAnalysisResult.collectAsStateWithLifecycle()
    val isBusy by viewModel.cameraAnalysisBusy.collectAsStateWithLifecycle()

    var questionInput by rememberSaveable { mutableStateOf("") }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val previewView = remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.markPermissionsRequested(listOf(Manifest.permission.CAMERA))
        viewModel.refreshPermissions()
        if (!granted) {
            cameraError = "Camera permission not granted. You can still analyze photos safely using Photo Picker below."
        } else {
            cameraError = null
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.analyzePhotoPickerUri(uri, questionInput.ifBlank { null })
        }
    }

    if (permStatus.cameraGranted) {
        DisposableEffect(lifecycleOwner, previewView, imageCapture) {
            val providerFuture = ProcessCameraProvider.getInstance(context)
            var boundProvider: ProcessCameraProvider? = null
            var disposed = false
            val executor = ContextCompat.getMainExecutor(context)

            providerFuture.addListener({
                if (disposed || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                    return@addListener
                }
                runCatching {
                    val provider = providerFuture.get()
                    if (disposed || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                        return@addListener
                    }
                    boundProvider = provider
                    val preview = Preview.Builder().build().apply {
                        surfaceProvider = previewView.surfaceProvider
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture
                    )
                    cameraError = null
                }.onFailure { err ->
                    if (!disposed) {
                        cameraError = "Camera preview unavailable on this device (${err.message ?: "no back camera"})."
                    }
                }
            }, executor)

            onDispose {
                disposed = true
                runCatching { boundProvider?.unbindAll() }
            }
        }
    }

    val captureLiveFrame: () -> Unit = {
        val mainExec = ContextCompat.getMainExecutor(context)
        imageCapture.takePicture(
            mainExec,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val rawBitmap = image.toBitmap()
                        val rotation = image.imageInfo.rotationDegrees
                        val rotated = if (rotation != 0) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true).also {
                                if (it !== rawBitmap && !rawBitmap.isRecycled) rawBitmap.recycle()
                            }
                        } else {
                            rawBitmap
                        }
                        viewModel.analyzeCameraBitmap(rotated, questionInput.ifBlank { null })
                    } catch (_: Exception) {
                        cameraError = "Unable to process captured image. Please try again."
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    cameraError = "Could not capture photo. Please try again."
                }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
        ) {
            TopAppBar(
                title = { Text("Camera AI") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, modifier = Modifier.testTag("camera_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (permStatus.cameraGranted && cameraError == null) {
                        StatusPill(
                            label = "Ready",
                            color = EmeraldSuccess,
                            icon = Icons.Default.CameraAlt,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = ElectricCyan
                )
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("camera_ai_lazy_column"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    if (permStatus.cameraGranted && cameraError == null) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = DeepSpaceBlack,
                            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.55f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                        ) {
                            AndroidView(
                                factory = { previewView },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(18.dp))
                            )
                        }
                    } else {
                        val permanentlyDenied = !permStatus.cameraGranted &&
                            (Manifest.permission.CAMERA in permStatus.requestedPermissions) &&
                            (activity?.let { !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA) } ?: false)
                        GlassCard(accentBorder = AmberWarning.copy(alpha = 0.65f)) {
                            Text(
                                text = cameraError ?: "Camera permission lets you point at signs, receipts, or notes and extract text 100% offline.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        if (permanentlyDenied) {
                                            viewModel.openAppSettings()
                                        } else {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    },
                                    modifier = Modifier.testTag("grant_camera_button")
                                ) {
                                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (permanentlyDenied) "Open App Settings" else "Enable Live Camera")
                                }
                            }
                        }
                    }
                }

                item {
                    OutlinedTextField(
                        value = questionInput,
                        onValueChange = { questionInput = it },
                        label = { Text("Ask about what's in view (optional)") },
                        placeholder = { Text("e.g. What is the total or web link here?") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("camera_question_input")
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (permStatus.cameraGranted) {
                                    captureLiveFrame()
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f).testTag("capture_analyze_button")
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = DeepSpaceBlack)
                            } else {
                                Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("Capture & Read")
                        }

                        OutlinedButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !isBusy,
                            modifier = Modifier.weight(1f).testTag("photo_picker_analyze_button")
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Pick Photo")
                        }
                    }
                }

                analysisResult?.let { res ->
                    item {
                        GlassCard(accentBorder = ElectricCyan.copy(alpha = 0.7f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Visibility, contentDescription = null, tint = ElectricCyan)
                                    Text(res.sourceLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = { viewModel.clearCameraAnalysis() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear result")
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            StatusPill(label = res.brightnessHint, color = NeuralViolet)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = res.summary,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.testTag("camera_analysis_summary")
                            )
                            if (res.detectedEntities.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    res.detectedEntities.forEach { entity ->
                                        StatusPill(label = entity, color = EmeraldSuccess)
                                    }
                                }
                            }
                            if (res.extractedText.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = DeepSpaceBlack.copy(alpha = 0.7f),
                                    border = BorderStroke(1.dp, GlassBorder.copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = res.extractedText.take(1200),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(10.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findCameraActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findCameraActivity()
    else -> null
}
