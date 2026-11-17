
package ru.marshrut.razvozki

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
            setOnClickListener {
                takePhoto()
            }
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
            val values = ContentValues().apply {
                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    "marshrut_${System.currentTimeMillis()}.jpg"
                )
                put(
                    MediaStore.Images.Media.MIME_TYPE,
                    "image/jpeg"
                )
            }

            photoUri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw Exception("Не удалось создать файл фотографии")

            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
                val uri = photoUri

                if (uri != null) {
                    recognizePhoto(uri)
                } else {
                    status.text = "Фотография не найдена"
                }

            } else {
                status.text = "Фотографирование отменено"
                photoButton.isEnabled = true
            }
        }
    }

    private fun downloadRussianLanguage(directory: File) {

        val tessdata = File(directory, "tessdata")

        if (!tessdata.exists()) {
            tessdata.mkdirs()
        }

        val languageFile = File(
            tessdata,
            "rus.traineddata"
        )

        if (languageFile.exists() && languageFile.length() > 1000000) {
            return
        }

        languageFile.delete()

        val temporaryFile = File(
            tessdata,
            "rus.traineddata.tmp"
        )

        val connection = URL(
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/rus.traineddata"
        ).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.requestMethod = "GET"
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

    private fun recognizePhoto(uri: Uri) {

        photoButton.isEnabled = false
        status.text = "Подготовка распознавания..."
        result.text = ""

        executor.execute {

            var bitmap: Bitmap? = null
            var tess: TessBaseAPI? = null

            try {

                val directory = File(
                    filesDir,
                    "tesseract"
                )

                if (!directory.exists()) {
                    directory.mkdirs()
                }

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

                contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                } ?: throw Exception("Не удалось открыть фотографию")

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

                bitmap = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: throw Exception("Не удалось прочитать изображение")

                runOnUiThread {
                    status.text = "Запуск распознавания..."
                }

                tess = TessBaseAPI()

                val initialized = tess.init(
                    directory.absolutePath,
                    "rus"
                )

                if (!initialized) {
                    throw Exception(
                        "Tesseract не смог загрузить русский язык. Проверьте файл rus.traineddata"
                    )
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
