package com.example.targetdemo

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.example.targetdemo.db.AppDatabase
import com.example.targetdemo.db.PhotoEntity
import com.example.targetdemo.ui.theme.TargetDemoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var imageCapture: ImageCapture? = null
    private lateinit var db: AppDatabase
    private val logViewModel: LogViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // DB init
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "photos-db"
        ).build()

        // Permissions launchers
        val cameraPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (!isGranted) Log.e("Perm", "CAMERA denied")
            }

        val locationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { map ->
                val ok = (map[Manifest.permission.ACCESS_FINE_LOCATION] == true) ||
                        (map[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
                if (!ok) Log.e("Perm", "LOCATION denied")
            }

        // Request if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        setContent {
            TargetDemoTheme {
                CameraScreen(
                    onImageCapture = { capture -> imageCapture = capture },
                    onTakePhoto = { takePhoto() },
                    logViewModel = logViewModel
                )
            }
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val outputDir = externalMediaDirs.firstOrNull() ?: filesDir
        val fileName = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis()) + ".jpg"
        val photoFile = File(outputDir, fileName)

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraX", "Save error", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("CameraX", "Photo saved: ${photoFile.absolutePath}")

                    val deviceName = android.os.Build.MODEL
                    val location = getLocation()
                    val timestamp = System.currentTimeMillis()

                    val entity = PhotoEntity(
                        imagePath = photoFile.absolutePath,
                        location = location,
                        deviceName = deviceName,
                        timestamp = timestamp
                    )

                    CoroutineScope(Dispatchers.IO).launch {
                        db.photoDao().insert(entity)

                        val formatter = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.getDefault())
                        val formatted = formatter.format(Date(timestamp))
                        val text = "$formatted | Photo ${photoFile.name} | Location: ${location ?: "without location"} | Device: $deviceName"

                        Log.d("DB_LOG", text)

                        withContext(Dispatchers.Main) {
                            logViewModel.addLog(text)
                        }
                    }
                }
            }
        )
    }

    private fun getLocation(): String? {
        return try {
            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) return null

            val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return null
            val loc: Location? = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            loc?.let { "${it.latitude},${it.longitude}" }
        } catch (e: Exception) {
            Log.e("Location", "getLocation error", e)
            null
        }
    }
}

@Composable
fun CameraScreen(
    onImageCapture: (ImageCapture) -> Unit,
    onTakePhoto: () -> Unit,
    logViewModel: LogViewModel
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }
    var cameraEnabled by remember { mutableStateOf(true) }
    val logs by logViewModel.logs.collectAsState()

    LaunchedEffect(cameraEnabled) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            if (cameraEnabled) {
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageCapture = ImageCapture.Builder().build()
                onImageCapture(imageCapture)

                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        context as ComponentActivity,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture
                    )
                } catch (e: Exception) {
                    Log.e("CameraX", "bind error", e)
                }
            } else {
                // выключаем камеру
                provider.unbindAll()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (cameraEnabled) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                items(logs.size) { i ->
                    Text(
                        text = logs[i],
                        fontSize = 10.sp,
                        // убираем ограничение maxLines = 1
                        maxLines = Int.MAX_VALUE,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = onTakePhoto) { Text("Take Photo") }
            Button(onClick = { cameraEnabled = !cameraEnabled }) {
                Text(if (cameraEnabled) "Turn off Camera" else "Turn on Camera")
            }
        }
    }
}