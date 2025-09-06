package com.example.jetsonapp.composables

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.jetsonapp.JetsonViewModel
import com.example.jetsonapp.R
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import coil3.compose.AsyncImage
import com.example.jetsonapp.utils.CameraUtil.checkFrontCamera
import com.example.jetsonapp.utils.CameraUtil.createTempPictureUri
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.delay

@OptIn(
    ExperimentalPermissionsApi::class,
    ExperimentalMaterial3Api::class
)
@Composable
fun MainScreen(jetsonViewModel: JetsonViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageUri by remember { mutableStateOf("".toUri()) }
    var capturedBitmap by remember {
        mutableStateOf(
            createBitmap(1, 1)
        )
    }
    val jetsonIsWorking by jetsonViewModel.jetsonIsWorking.collectAsStateWithLifecycle()
    val microphoneIsRecording by jetsonViewModel.microphoneIsRecording.collectAsStateWithLifecycle()
    val cameraFunctionTriggered by jetsonViewModel.cameraFunctionTriggered.collectAsStateWithLifecycle()
    val phoneGalleryTriggered by jetsonViewModel.phoneGalleryTriggered.collectAsStateWithLifecycle()
    val vlmResult by jetsonViewModel.vlmResult.collectAsStateWithLifecycle()
    var showCameraCaptureBottomSheet by remember { mutableStateOf(false) }
    val cameraCaptureSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tempPhotoUri by remember { mutableStateOf(value = Uri.EMPTY) }

    val takePicturePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionGranted ->
        if (permissionGranted) {
            tempPhotoUri = context.createTempPictureUri()
            showCameraCaptureBottomSheet = true
        }
    }

    val imagePickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                scope.launch {
                    delay(1500)
                    jetsonViewModel.updateSelectedImage(context, uri)
                    imageUri = uri
                    jetsonViewModel.updatePhoneGalleryTriggered(false)
                }
            }
        }

    LaunchedEffect(phoneGalleryTriggered) {
        if (phoneGalleryTriggered) {
            imagePickerLauncher.launch(
                arrayOf(
                    "image/*"
                )
            )
        }
    }

    LaunchedEffect(cameraFunctionTriggered) {
        if (cameraFunctionTriggered) {
            when (PackageManager.PERMISSION_GRANTED) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.CAMERA
                ) -> {
                    tempPhotoUri = context.createTempPictureUri()
                    showCameraCaptureBottomSheet = true
                }
                else -> {
                    takePicturePermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }
    }

    var hasFrontCamera by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        checkFrontCamera(context = context, callback = { hasFrontCamera = it })
    }

    val permissionAudio = rememberPermissionState(
        permission = Manifest.permission.RECORD_AUDIO
    )
    val launcherAudio = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { permissionGranted ->
        if (permissionGranted) {
            jetsonViewModel.stopRecordingWav()
            jetsonViewModel.updateMicrophoneIsRecording(false)
        } else {
            if (permissionAudio.status.shouldShowRationale) {
                Toast.makeText(
                    context,
                    "You have to grant access to use the microphone",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    context,
                    "You have to grant access to use the microphone",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 28.dp, bottom = 48.dp, start = 0.dp, end = 0.dp)
                .background(Color.LightGray)
                .border(BorderStroke(2.dp, Color.Black))
        ) {
            // Top content section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 16.dp, end = 16.dp)
            ) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    text = "Observer Mode:",
                    color = Color.Black,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 28.sp,
                    textAlign = TextAlign.Center,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    ImageFromUri(imageUri, capturedBitmap)
                }

                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    text = "Hold the microphone button and speak!",
                    color = Color.Black,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        text = vlmResult,
                        color = Color.Black,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Start,
                    )
                }
            }

            // Microphone row at the bottom
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .height(90.dp)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(8.dp))
                // Stop button at the far left with size slightly smaller than the row height
                Image(
                    painter = painterResource(id = R.drawable.baseline_stop_circle_24),
                    contentDescription = "stop generating",
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable { jetsonViewModel.stopGenerating() },
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.tint(colorResource(id = R.color.black))
                )

                Spacer(modifier = Modifier.width(48.dp))

                // Microphone icon at the far right with size slightly smaller than the row height
                if (jetsonIsWorking) {
                    Box(
                        modifier = Modifier
                            .size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = Color.Black
                            )
                        }
                    }
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.baseline_mic_24),
                        contentDescription = "hold the microphone and speak",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable { /* Do something */ }
                            .pointerInteropFilter {
                                when (it.action) {
                                    MotionEvent.ACTION_UP -> {
                                        launcherAudio.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                    else -> {
                                        jetsonViewModel.startRecordingWav()
                                        jetsonViewModel.updateMicrophoneIsRecording(true)
                                    }
                                }
                                true
                            },
                        contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(
                            if (microphoneIsRecording) colorResource(id = R.color.teal_200)
                            else colorResource(id = R.color.black)
                        )
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
        }

        // RAM Usage Indicator overlay
        RamUsageIndicator(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        )
    }

    if (showCameraCaptureBottomSheet) {
        ModalBottomSheet(
            sheetState = cameraCaptureSheetState,
            onDismissRequest = { showCameraCaptureBottomSheet = false }) {

            val lifecycleOwner = LocalLifecycleOwner.current
            val previewUseCase = remember { androidx.camera.core.Preview.Builder().build() }
            val imageCaptureUseCase = remember {
                val preferredSize = Size(512, 512)
                val resolutionStrategy = ResolutionStrategy(
                    preferredSize,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(resolutionStrategy)
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()

                ImageCapture.Builder().setResolutionSelector(resolutionSelector).build()
            }
            var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
            var cameraControl by remember { mutableStateOf<CameraControl?>(null) }
            val localContext = LocalContext.current
            var cameraSide by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
            val executor = remember { Executors.newSingleThreadExecutor() }

            fun rebindCameraProvider() {
                cameraProvider?.let { cameraProvider ->
                    val cameraSelector = CameraSelector.Builder()
                        .requireLensFacing(cameraSide)
                        .build()
                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner = lifecycleOwner,
                            cameraSelector = cameraSelector,
                            previewUseCase,
                            imageCaptureUseCase
                        )
                        cameraControl = camera.cameraControl
                    } catch (e: Exception) {
                        Log.d("MainScreen", "Failed to bind camera", e)
                    }
                }
            }

            LaunchedEffect(Unit) {
                cameraProvider = ProcessCameraProvider.awaitInstance(localContext)
                rebindCameraProvider()
            }

            LaunchedEffect(cameraSide) {
                rebindCameraProvider()
            }

            DisposableEffect(Unit) {
                onDispose {
                    cameraProvider?.unbindAll()
                    if (!executor.isShutdown) {
                        executor.shutdown()
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).also {
                            previewUseCase.surfaceProvider = it.surfaceProvider
                            rebindCameraProvider()
                        }
                    },
                )

                IconButton(
                    onClick = {
                        scope.launch {
                            cameraCaptureSheetState.hide()
                            showCameraCaptureBottomSheet = false
                            jetsonViewModel.updateCameraFunctionTriggered(false)
                        }
                    }, colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ), modifier = Modifier
                        .offset(x = (-8).dp, y = 8.dp)
                        .align(Alignment.TopEnd)
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "close the camera",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp)
                        .size(64.dp)
                        .border(2.dp, MaterialTheme.colorScheme.onPrimary, CircleShape),
                    onClick = {
                        val callback = object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                try {
                                    var bitmap = image.toBitmap()
                                    val rotation = image.imageInfo.rotationDegrees
                                    bitmap = if (rotation != 0) {
                                        val matrix = Matrix().apply {
                                            postRotate(rotation.toFloat())
                                        }
                                        Log.d(
                                            "MainScreen",
                                            "image size: ${bitmap.width}, ${bitmap.height}"
                                        )
                                        Bitmap.createBitmap(
                                            bitmap,
                                            0,
                                            0,
                                            bitmap.width,
                                            bitmap.height,
                                            matrix,
                                            true
                                        )
                                    } else bitmap

                                    imageUri = "".toUri()
                                    capturedBitmap = bitmap
                                    scope.launch {
                                        delay(1500)
                                        jetsonViewModel.convertBitmapToBase64(bitmap)
                                    }
                                } catch (e: Exception) {
                                    Log.e("MainScreen", "Failed to process image", e)
                                } finally {
                                    image.close()
                                    scope.launch {
                                        cameraCaptureSheetState.hide()
                                        showCameraCaptureBottomSheet = false
                                        jetsonViewModel.updateCameraFunctionTriggered(false)
                                    }
                                }
                            }
                        }
                        imageCaptureUseCase.takePicture(executor, callback)
                    },
                ) {
                    Icon(
                        Icons.Rounded.PhotoCamera,
                        contentDescription = "capture image",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }

                if (hasFrontCamera) {
                    IconButton(
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 40.dp, end = 32.dp)
                            .size(48.dp),
                        onClick = {
                            cameraSide = when (cameraSide) {
                                CameraSelector.LENS_FACING_BACK -> CameraSelector.LENS_FACING_FRONT
                                else -> CameraSelector.LENS_FACING_BACK
                            }
                        },
                    ) {
                        Icon(
                            Icons.Rounded.FlipCameraAndroid,
                            contentDescription = "change to front camera",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ImageFromUri(uri: Uri?, bitmap: Bitmap) {
    if (uri != null && uri != "".toUri() || bitmap.width == 1) {
        AsyncImage(
            model = uri,
            contentDescription = "Loaded image",
            placeholder = painterResource(R.drawable.image_icon),
            error = painterResource(R.drawable.image_icon),
            modifier = Modifier
                .size(if (uri == "".toUri()) 72.dp else 300.dp)
                .clip(RoundedCornerShape(8.dp))
        )
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Loaded image",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
        )
    }
}

@Composable
fun RamUsageIndicator(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var ramInfo by remember { mutableStateOf("0/0 MB") }

    LaunchedEffect(Unit) {
        while (true) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            val usedMemoryInMB = (memoryInfo.totalMem - memoryInfo.availMem) / (1024 * 1024)
            val totalMemoryInMB = memoryInfo.totalMem / (1024 * 1024)

            ramInfo = "${usedMemoryInMB}/${totalMemoryInMB} MB"
            delay(1000) // Update every second
        }
    }

    Text(
        text = ramInfo,
        color = Color.Black,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}