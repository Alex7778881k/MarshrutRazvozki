
package ru.marshrut.razvozki

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var photoButton: Button

    private var photoUri: Uri? = null
    private var photoFile: File? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val cameraRequest = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 24)
        }

        val title = TextView(this).apply {
            text = "Маршрут развозки"
            textSize = 25f
            gravity = Gravity.CENTER
        }

        photoButton = Button(this).apply {
            text = "СФОТОГРАФИРОВАТЬ ЛИСТ"
            textSize = 16f
            setOnClickListener { takePhoto() }
        }

        status = TextView(this).apply {
            text = "Сфотографируйте лист с адресами"
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }

        result = TextView(this).apply {
            text = "Распознанный текст появится здесь"
            textSize = 16f
            setPadding(0, 12, 0, 12)
        }

        val scroll = ScrollView(this).apply {
            addView(result)
        }

        layout.addView(title)
        layout.addView(photoButton)
        layout.addView(status)

        layout.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(layout)
    }

    private fun takePhoto() {
        try {
            val cameraDirectory = File(cacheDir, "camera")
            if (!cameraDirectory.exists()) {
                cameraDirectory.mkdirs()
            }

            photoFile = File(
                cameraDirectory,
                "photo_${System.currentTimeMillis()}.jpg"
            )

            photoUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                photoFile!!
            )

            val intent = Intent(
                android.provider.MediaStore.ACTION_IMAGE_CAPTURE
            ).apply {
                putExtra(
                    android.provider.MediaStore.EXTRA_OUTPUT,
                    photoUri
                )

                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

                clipData = ClipData.newUri(
                    contentResolver,
                    "Фотография листа",
                    photoUri
                )
            }

            if (intent.resolveActivity(packageManager) == null) {
                throw Exception("Камера не найдена")
            }

            startActivityForResult(intent, cameraRequest)

        } catch (e: Exception) {
            status.text = "Ошибка камеры: ${e.message}"
        }
    }

    @Deprecated("Используется для совместимости")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == cameraRequest) {

            if (resultCode == Activity.RESULT_OK) {

                val file = photoFile

                if (file != null && file.exists() && file.length() > 0) {
                    recognizePhoto(file)
                } else {
                    status.text = "Камера не сохранила фотографию"
                    photoButton.isEnabled = true
                }

            } else {
                status.text = "Фотографирование отменено"
                photoButton.isEnabled = true
            }
        }
    }

    private fun downloadRussianLanguage(directory: File) {

        val tessdata = File(directory, "tessdata")
        tessdata.mkdirs()

        val languageFile = File(tessdata, "rus.traineddata")

        if (languageFile.exists() && languageFile.length() > 1000000) {
            return
        }

        languageFile.delete()

        val temporaryFile = File(tessdata, "rus.tmp")

        val connection = URL(
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/rus.traineddata"
        ).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception(
                    "Ошибка загрузки русского языка: HTTP ${connection.responseCode}"
                )
            }

            connection.inputStream.use { input ->
                FileOutputStream(temporaryFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (temporaryFile.length() < 1000000) {
                throw Exception("Русский языковой файл повреждён")
            }

            if (!temporaryFile.renameTo(languageFile)) {
                throw Exception("Не удалось сохранить языковой файл")
            }

        } finally {
            connection.disconnect()
        }
    }

    private fun recognizePhoto(file: File) {

        photoButton.isEnabled = false
        status.text = "Подготовка распознавания..."
        result.text = ""

        executor.execute {

            var bitmap: Bitmap? = null
            var tess: TessBaseAPI? = null

            try {
                val directory = File(filesDir, "tesseract")
                directory.mkdirs()

                runOnUiThread {
                    status.text = "Проверка русского языка..."
                }

                downloadRussianLanguage(directory)

                runOnUiThread {
                    status.text = "Загрузка фотографии..."
                }

                val bounds = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }

                BitmapFactory.decodeFile(file.absolutePath, bounds)

                var sampleSize = 1

                while (
                    bounds.outWidth / sampleSize > 2500 ||
                    bounds.outHeight / sampleSize > 2500
                ) {
                    sampleSize *= 2
                }

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }

                bitmap = BitmapFactory.decodeFile(
                    file.absolutePath,
                    options
                ) ?: throw Exception("Не удалось прочитать фотографию")

                runOnUiThread {
                    status.text = "Запуск распознавания..."
                }

                tess = TessBaseAPI()

                if (!tess.init(directory.absolutePath, "rus")) {
                    throw Exception("Не удалось запустить Tesseract")
                }

                tess.setPageSegMode(
                    TessBaseAPI.PageSegMode.PSM_AUTO
                )

                tess.setImage(bitmap)

                runOnUiThread {
                    status.text = "Распознавание текста..."
                }

                val recognizedText = tess.getUTF8Text() ?: ""

                val lines = recognizedText
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                val outputText = if (lines.isEmpty()) {
                    "Текст не распознан. Попробуйте сделать более чёткую фотографию."
                } else {
                    lines.joinToString("\n")
                }

                runOnUiThread {
                    status.text = "Распознавание завершено"
                    result.text = outputText
                    photoButton.isEnabled = true
                }

            } catch (e: Exception) {

                runOnUiThread {
                    status.text = "Ошибка: ${e.message}"
                    photoButton.isEnabled = true
                }

            } finally {
                tess?.recycle()
                bitmap?.recycle()
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
