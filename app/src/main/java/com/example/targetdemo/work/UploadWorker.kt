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

        for (photo in allPhotos) {
            if (uploadFileToAzure(photo)) {
                db.photoDao().deleteById(photo.id)

                val formatted = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.getDefault())
                    .format(Date(photo.timestamp))
                Log.d("Azure", "$formatted | Файл ${File(photo.imagePath).name} отправлен и удалён из БД")
            } else {
                Log.e("Azure", "Ошибка загрузки ${photo.imagePath}, повторим позже")
                return@withContext Result.retry()
            }
        }

        Result.success()
    }

    private fun uploadFileToAzure(photo: PhotoEntity): Boolean {
        return try {
            val file = File(photo.imagePath)
            if (!file.exists()) return false

            val blobUrl = "https://storagelv426.blob.core.windows.net/containerlv426/${file.name}" +
                    "?sp=racwl&st=2025-09-28T21:30:45Z&se=2025-10-29T05:45:45Z&spr=https&sv=2024-11-04&sr=c&sig=gH5UAz29ClqswdTlqytGcT%2BU25vZdOUamjdnGVOgSx4%3D"

            val conn = URL(blobUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("x-ms-blob-type", "BlockBlob")
            conn.doOutput = true

            file.inputStream().use { input ->
                conn.outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            val success = conn.responseCode in 200..299
            conn.disconnect()
            success
        } catch (e: Exception) {
            Log.e("Azure", "Ошибка загрузки", e)
            false
        }
    }
}