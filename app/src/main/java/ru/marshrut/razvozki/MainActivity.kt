
package ru.marshrut.razvozki

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.ContentValues
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
            setOnClickListener { takePhoto() }
        }

        status = TextView(this).apply {
            text = "Сфотографируйте лист с адресами"
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }

        result = TextView(this).apply {
            text = "Распознанные адреса появятся здесь"
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
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }

            photoUri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            )

            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            }

            startActivityForResult(intent, cameraRequest)

        } catch (e: Exception) {
            status.text = "Не удалось открыть камеру: ${e.message}"
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
            if (resultCode == Activity.RESULT_OK && photoUri != null) {
                recognizePhoto(photoUri!!)
            } else {
                status.text = "Фотография не сделана"
            }
        }
    }

    private fun recognizePhoto(uri: Uri) {
        photoButton.isEnabled = false
        status.text = "Подготовка распознавания..."
        result.text = ""

        executor.execute {
            try {
                val dataDirectory = File(filesDir, "tesseract")
                val tessdataDirectory = File(dataDirectory, "tessdata")

                if (!tessdataDirectory.exists()) {
                    tessdataDirectory.mkdirs()
                }

                val languageFile = File(
                    tessdataDirectory,
                    "rus.traineddata"
                )

                if (!languageFile.exists()) {
                    runOnUiThread {
                        status.text = "Загрузка русского языка..."
                    }

                    val url = URL(
                        "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/rus.traineddata"
                    )

                    url.openStream().use { input ->
                        languageFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                runOnUiThread {
                    status.text = "Распознавание текста..."
                }

                val bitmap = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: throw Exception("Не удалось прочитать фотографию")

                val tess = TessBaseAPI()

                if (!tess.init(dataDirectory.absolutePath, "rus")) {
                    throw Exception("Не удалось запустить распознавание")
                }

                tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                tess.setImage(bitmap)

                val recognizedText = tess.utF8Text ?: ""
                tess.end()
                bitmap.recycle()

                val lines = recognizedText
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                val outputText = if (lines.isEmpty()) {
                    "Текст не распознан. Попробуйте сфотографировать лист ещё раз."
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
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
