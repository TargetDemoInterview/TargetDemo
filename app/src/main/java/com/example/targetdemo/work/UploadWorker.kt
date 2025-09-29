package com.example.targetdemo.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.targetdemo.db.AppDatabase
import com.example.targetdemo.db.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class UploadWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = AppDatabase.getInstance(applicationContext)
        val allPhotos = db.photoDao().getAll()
        Log.d("Azure", "UploadWorker старт. Найдено записей: ${allPhotos.size}")

        for ((idx, photo) in allPhotos.withIndex()) {
            Log.d("Azure", "[$idx/${allPhotos.size}] Пытаюсь отправить: ${File(photo.imagePath).name}")

            if (uploadFileToAzure(photo)) {
                if (uploadMetadata(photo)) {
                    db.photoDao().deleteById(photo.id)
                    val formatted = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.getDefault())
                        .format(Date(photo.timestamp))
                    Log.d("Azure", "$formatted | Фото и JSON ${File(photo.imagePath).name} → отправлены и запись удалена")
                } else {
                    Log.e("Azure", "Фото загружено, но JSON не удалось отправить: ${photo.imagePath}")
                    return@withContext Result.retry()
                }
            } else {
                Log.e("Azure", "Не удалось отправить фото: ${photo.imagePath}")
                return@withContext Result.retry()
            }
        }

        Result.success()
    }

    // 📸 Загрузка самого фото
    private fun uploadFileToAzure(photo: PhotoEntity): Boolean {
        return try {
            val file = File(photo.imagePath)
            if (!file.exists()) {
                Log.e("Azure", "Файл не найден: ${photo.imagePath}")
                return false
            }

            val blobUrl = "https://storagelv426.blob.core.windows.net/containerlv426/${file.name}" +
                    "?sp=racwl&st=2025-09-28T21:30:45Z&se=2025-10-29T05:45:45Z&spr=https&sv=2024-11-04&sr=c&sig=gH5UAz29ClqswdTlqytGcT%2BU25vZdOUamjdnGVOgSx4%3D"

            val conn = (URL(blobUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("x-ms-blob-type", "BlockBlob")
                setRequestProperty("x-ms-version", "2024-11-04")
                setRequestProperty("Content-Type", "application/octet-stream")

                if (file.length() <= Int.MAX_VALUE) {
                    setFixedLengthStreamingMode(file.length().toInt())
                } else {
                    setChunkedStreamingMode(64 * 1024)
                }
            }

            file.inputStream().use { input ->
                conn.outputStream.use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            val code = conn.responseCode
            val success = code in 200..299
            if (!success) {
                val err = conn.errorStream?.use { es ->
                    es.readBytes().toString(Charsets.UTF_8)
                }
                Log.e("Azure", "Ошибка загрузки фото ${file.name}: HTTP $code, $err")
            } else {
                Log.d("Azure", "Фото ${file.name} успешно загружено, HTTP $code")
            }

            conn.disconnect()
            success
        } catch (e: Exception) {
            Log.e("Azure", "Ошибка при загрузке фото ${photo.imagePath}", e)
            false
        }
    }

    // 📝 Формируем JSON-метаданные
    private fun buildMetadataJson(photo: PhotoEntity): String {
        val json = JSONObject()
        json.put("fileName", File(photo.imagePath).name)
        json.put("location", photo.location ?: "unknown")
        json.put("deviceName", photo.deviceName)
        json.put("timestamp", photo.timestamp)
        return json.toString()
    }

    // 📑 Загрузка JSON с метаданными
    private fun uploadMetadata(photo: PhotoEntity): Boolean {
        return try {
            val jsonContent = buildMetadataJson(photo)
            val jsonBytes = jsonContent.toByteArray(Charsets.UTF_8)

            val blobUrl = "https://storagelv426.blob.core.windows.net/containerlv426/${File(photo.imagePath).name}.json" +
                    "?sp=racwl&st=2025-09-28T21:30:45Z&se=2025-10-29T05:45:45Z&spr=https&sv=2024-11-04&sr=c&sig=gH5UAz29ClqswdTlqytGcT%2BU25vZdOUamjdnGVOgSx4%3D"

            val conn = (URL(blobUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setRequestProperty("x-ms-blob-type", "BlockBlob")
                setRequestProperty("Content-Type", "application/json")
                setFixedLengthStreamingMode(jsonBytes.size)
            }

            conn.outputStream.use { it.write(jsonBytes) }

            val code = conn.responseCode
            val success = code in 200..299
            if (!success) {
                val err = conn.errorStream?.use { es ->
                    es.readBytes().toString(Charsets.UTF_8)
                }
                Log.e("Azure", "Ошибка загрузки JSON для ${File(photo.imagePath).name}: HTTP $code, $err")
            } else {
                Log.d("Azure", "JSON ${File(photo.imagePath).name}.json успешно загружен")
            }

            conn.disconnect()
            success
        } catch (e: Exception) {
            Log.e("Azure", "Ошибка при отправке JSON для ${photo.imagePath}", e)
            false
        }
    }
}