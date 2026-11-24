package com.wildvision.ai

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Bg = Color(0xFF080D0C)
private val Panel = Color(0xFF14201B)
private val Accent = Color(0xFFA5E66B)
private val Muted = Color(0xFF9BAAA2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WildVisionApp() }
    }
}

@Composable
private fun WildVisionApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Bitmap?>(null) }
    var processed by remember { mutableStateOf<Bitmap?>(null) }
    var tab by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("Ready for field work") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val image = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
            selected = image
            processed = null
            message = if (image != null) "Photo imported" else "Could not read photo"
            tab = 1
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Panel)) {
        Scaffold(containerColor = Bg, bottomBar = {
            NavigationBar(containerColor = Panel) {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Text("📷") }, label = { Text("Camera") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Text("✦") }, label = { Text("Lab") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Text("🐾") }, label = { Text("Species") })
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("WILDVISION AI", color = Accent, fontSize = 23.sp)
                Text("NATURE IMAGING · 0.4.0", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(16.dp))
                when (tab) {
                    0 -> CameraPage(onPhoto = { selected = it; processed = null; tab = 1; message = "Photo captured" }, onGallery = { picker.launch("image/*") }, onStatus = { message = it })
                    1 -> LabPage(processed ?: selected, message, onImport = { picker.launch("image/*") }, onProcess = {
                        val source = selected
                        if (source == null) message = "Import or capture a photo first"
                        else scope.launch {
                            message = "Applying local demo adjustment..."
                            processed = withContext(Dispatchers.Default) { adjustContrast(source) }
                            message = "Local contrast adjustment applied"
                        }
                    }, onSave = {
                        val bitmap = processed ?: selected
                        if (bitmap != null) {
                            saveImage(context, bitmap)
                            message = "Saved to Pictures/WildVisionAI"
                        }
                    })
                    else -> SpeciesPage(selected, onImport = { picker.launch("image/*") })
                }
            }
        }
    }
}

@Composable
private fun CameraPage(onPhoto: (Bitmap) -> Unit, onGallery: () -> Unit, onStatus: (String) -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) { if (!allowed) permission.launch(Manifest.permission.CAMERA) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("FIELD CAPTURE", color = Accent)
        if (allowed) {
            AndroidView(Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
                val view = PreviewView(ctx)
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val photo = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                    capture = photo
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(context as ComponentActivity, CameraSelector.DEFAULT_BACK_CAMERA, preview, photo)
                    } catch (e: Exception) { onStatus("Camera unavailable: ${e.message}") }
                }, ContextCompat.getMainExecutor(ctx))
                view
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(Modifier.weight(1f), onClick = {
                    val imageCapture = capture ?: return@Button
                    val file = File(context.cacheDir, "wildvision_${System.currentTimeMillis()}.jpg")
                    val output = ImageCapture.OutputFileOptions.Builder(file).build()
                    imageCapture.takePicture(output, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            BitmapFactory.decodeFile(file.absolutePath)?.let(onPhoto) ?: onStatus("Could not decode photo")
                        }
                        override fun onError(exception: ImageCaptureException) { onStatus("Capture error: ${exception.message}") }
                    })
                }) { Text("CAPTURE") }
                OutlinedButton(onClick = onGallery) { Text("GALLERY") }
            }
        } else {
            Text("Camera permission is needed to capture photos.", color = Muted)
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Grant camera access") }
            OutlinedButton(onClick = onGallery) { Text("Import photo") }
        }
    }
}

@Composable
private fun LabPage(bitmap: Bitmap?, status: String, onImport: () -> Unit, onProcess: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("IMAGE LAB", color = Accent)
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.weight(1f).fillMaxWidth(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        else Box(Modifier.weight(1f).fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Text("No image selected", color = Muted) }
        Text(status, color = Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(Modifier.weight(1f), onClick = onImport) { Text("Import") }
            Button(Modifier.weight(1f), onClick = onProcess) { Text("Enhance") }
            OutlinedButton(onClick = onSave) { Text("Save") }
        }
        Text("Demo operation: mild contrast adjustment only; neural denoise/upscaling are not connected.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun SpeciesPage(bitmap: Bitmap?, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("WILDIDENTIFY", color = Accent)
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.weight(1f).fillMaxWidth(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        else Box(Modifier.weight(1f).fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Text("Import a wildlife photo", color = Muted) }
        Button(Modifier.fillMaxWidth(), onClick = onImport) { Text("Choose photo") }
        Text("Species recognition is not connected yet. Treat future model results as suggestions and verify them with reliable references.", color = Muted, fontSize = 12.sp)
    }
}

private fun adjustContrast(source: Bitmap): Bitmap {
    val width = source.width
    val height = source.height
    val input = IntArray(width * height)
    source.getPixels(input, 0, width, 0, 0, width, height)
    val output = IntArray(input.size)
    for (i in input.indices) {
        val p = input[i]
        fun adjust(v: Int) = (((v - 128) * 1.08f) + 128).toInt().coerceIn(0, 255)
        output[i] = Color.argb(android.graphics.Color.alpha(p), adjust(android.graphics.Color.red(p)), adjust(android.graphics.Color.green(p)), adjust(android.graphics.Color.blue(p))).toArgb()
    }
    return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
}

private fun saveImage(context: android.content.Context, bitmap: Bitmap) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "WildVision_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/WildVisionAI")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
    context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
}
