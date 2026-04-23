package com.example.raspberrycontroller

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateManager(private val context: Context) {

    fun downloadAndInstall(url: String, onProgress: (Int) -> Unit) {

        val downloadDir = File(context.getExternalFilesDir(null), "my_download").apply {
            if (!exists()) mkdirs()
        }

        val apkFile = File(downloadDir, "update.apk")
        if (apkFile.exists()) apkFile.delete()

        CoroutineScope(Dispatchers.IO).launch {
            try {

                var connection = openConnection(url)
                var redirects = 0

                // gestion redirections GitHub
                while (connection.responseCode in 300..399 && redirects < 10) {
                    val newUrl = connection.getHeaderField("Location")
                        ?: throw Exception("Redirect sans Location")

                    connection.disconnect()
                    connection = openConnection(newUrl)
                    redirects++
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    connection.disconnect()
                    withContext(Dispatchers.Main) { onProgress(-1) }
                    return@launch
                }

                val contentType = connection.contentType
                println("CONTENT-TYPE = $contentType")

                val total = connection.contentLengthLong
                var downloaded = 0L

                connection.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(8192)
                        var bytes: Int

                        while (input.read(buffer).also { bytes = it } != -1) {
                            output.write(buffer, 0, bytes)
                            downloaded += bytes

                            if (total > 0) {
                                val progress = (downloaded * 100 / total).toInt()
                                withContext(Dispatchers.Main) {
                                    onProgress(progress)
                                }
                            }
                        }
                    }
                }

                connection.disconnect()

                // 🔴 Vérification critique
                if (!apkFile.exists() || apkFile.length() < 100000) {
                    throw Exception("APK invalide (trop petit ou inexistant)")
                }

                withContext(Dispatchers.Main) {
                    onProgress(100)
                    installApk(apkFile)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) { onProgress(-1) }
            }
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection

        connection.apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 60000
            instanceFollowRedirects = true

            // 🔥 IMPORTANT pour GitHub
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Accept", "*/*")
        }

        return connection
    }

    private fun installApk(file: File) {

        if (!file.exists()) {
            throw Exception("APK introuvable: ${file.absolutePath}")
        }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
    }
}