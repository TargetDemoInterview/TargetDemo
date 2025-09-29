package com.example.targetdemo.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.targetdemo.db.AppDatabase
import com.example.targetdemo.db.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                db.photoDao().deleteById(photo.id)
                val formatted = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.getDefault())
                    .format(Date(photo.timestamp))
                Log.d("Azure", "$formatted | ${File(photo.imagePath).name} → отправлен и удалён из БД")
            } else {
                Log.e("Azure", "Не удалось отправить: ${photo.imagePath}. Повторим позже.")
                return@withContext Result.retry()
            }
        }

        Result.success()
    }

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
                doInput = true

                // ВАЖНО: Azure ожидает тип блоба
                setRequestProperty("x-ms-blob-type", "BlockBlob")

                // Рекомендуемая версия — пусть совпадает с SAS sv
                setRequestProperty("x-ms-version", "2024-11-04")

                // Не обязательно, но полезно
                setRequestProperty("Content-Type", "application/octet-stream")

                // Потоковый режим (чтобы не падало на больших файлах и не требовало Content-Length вручную)
                if (file.length() <= Int.MAX_VALUE) {
                    setFixedLengthStreamingMode(file.length().toInt())
                } else {
                    // на очень больших — chunked
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
                Log.e("Azure", "HTTP $code при загрузке ${file.name}. error=$err")
            } else {
                // Удобный короткий лог успеха
                Log.d("Azure", "Uploaded ${file.name}, HTTP $code")
            }

            conn.disconnect()
            success
        } catch (e: Exception) {
            Log.e("Azure", "Ошибка загрузки ${photo.imagePath}", e)
            false
        }
    }
}