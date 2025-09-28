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
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.example.targetdemo.db.AppDatabase
import com.example.targetdemo.db.PhotoEntity
import com.example.targetdemo.ui.theme.TargetDemoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var imageCapture: ImageCapture? = null
    private lateinit var db: AppDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Инициализация базы данных
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "photos-db"
        ).build()

        // Запрос разрешения камеры
        val requestPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
                if (isGranted) {
                    Log.d("CameraX", "Разрешение на камеру получено")
                } else {
                    Log.e("CameraX", "Нет разрешения на камеру")
                }
            }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        val locationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
                val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
                if (fineGranted || coarseGranted) {
                    Log.d("Location", "Разрешение на геолокацию получено")
                } else {
                    Log.e("Location", "Нет разрешения на геолокацию")
                }
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
                    onTakePhoto = { takePhoto() }
                )
            }
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val photoFile = File(
            externalMediaDirs.first(),
            SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
                .format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraX", "Ошибка сохранения фото", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("CameraX", "Фото сохранено: ${photoFile.absolutePath}")

                    // Собираем данные
                    val deviceName = android.os.Build.MODEL
                    val location = getLocation()
                    val timestamp = System.currentTimeMillis()

                    val entity = PhotoEntity(
                        imagePath = photoFile.absolutePath,
                        location = location,
                        deviceName = deviceName,
                        timestamp = timestamp
                    )

                    // Сохраняем в БД
                    CoroutineScope(Dispatchers.IO).launch {
                        db.photoDao().insert(entity)
                        Log.d("DB", "Запись сохранена: $entity")


                            val all = db.photoDao().getAll()
                            all.forEach {
                                Log.d("DB", "Запись в БД: $it")
                            }


                    }
                }
            }
        )
    }

    private fun getLocation(): String? {
        return try {
            val lm = getSystemService(LOCATION_SERVICE) as? LocationManager
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                return null // разрешения нет
            }
            val loc: Location? = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            loc?.let { "${it.latitude},${it.longitude}" }
        } catch (e: Exception) {
            Log.e("Location", "Ошибка получения геопозиции", e)
            null
        }
    }
}

@Composable
fun CameraScreen(onImageCapture: (ImageCapture) -> Unit, onTakePhoto: () -> Unit) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageCapture = ImageCapture.Builder().build()
            onImageCapture(imageCapture)

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    context as ComponentActivity,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                Log.e("CameraX", "Ошибка запуска камеры", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Камера превью
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        // Кнопка сделать фото
        Button(
            onClick = { onTakePhoto() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text("Сделать фото")
        }
    }
}