
package ru.marshrut.razvozki

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var addressesContainer: LinearLayout
    private lateinit var photoButton: Button
    private lateinit var geocodeButton: Button
    private lateinit var countText: TextView
    private lateinit var coordinatesText: TextView

    private val addresses = mutableListOf<String>()

    private var photoUri: Uri? = null
    private var photoFile: File? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val cameraRequest = 1001

    private val warehouseAddress =
        "Индустриальный проезд, 22/1, 423800, Набережные Челны, Республика Татарстан, Россия"

    data class GeocodeResult(
        val address: String,
        val longitude: Double,
        val latitude: Double
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(this).apply {
            text = "Маршрут развозки"
            textSize = 25f
            gravity = Gravity.CENTER
        }

        photoButton = Button(this).apply {
            text = "СФОТОГРАФИРОВАТЬ ЛИСТ"
            setOnClickListener { takePhoto() }
        }

        status = TextView(this).apply {
            text = "Сфотографируйте лист с адресами"
            textSize = 15f
            setPadding(0, 12, 0, 12)
        }

        countText = TextView(this).apply {
            text = "Найдено адресов: 0"
            textSize = 19f
            setPadding(0, 12, 0, 12)
        }

        addressesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val addressScroll = ScrollView(this).apply {
            addView(addressesContainer)
        }

        geocodeButton = Button(this).apply {
            text = "НАЙТИ КООРДИНАТЫ В 2ГИС"
            isEnabled = false
            setOnClickListener {
                geocodeAddresses()
            }
        }

        coordinatesText = TextView(this).apply {
            text = ""
            textSize = 14f
            setPadding(0, 12, 0, 12)
        }

        val resultScroll = ScrollView(this).apply {
            addView(coordinatesText)
        }

        layout.addView(title)
        layout.addView(photoButton)
        layout.addView(status)
        layout.addView(countText)

        layout.addView(
            addressScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        layout.addView(geocodeButton)

        layout.addView(
            resultScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.7f
            )
        )

        setContentView(layout)
    }

    private fun takePhoto() {
        try {
            val directory = File(cacheDir, "camera")
            directory.mkdirs()

            photoFile = File(
                directory,
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

        if (languageFile.exists() && languageFile.length() > 5000000) {
            return
        }

        languageFile.delete()

        val temporaryFile = File(tessdata, "rus.tmp")

        val connection = URL(
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main/rus.traineddata"
        ).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 30000
            connection.readTimeout = 120000
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

            if (temporaryFile.length() < 5000000) {
                throw Exception("Русский языковой файл загружен не полностью")
            }

            if (!temporaryFile.renameTo(languageFile)) {
                throw Exception("Не удалось сохранить языковой файл")
            }

        } finally {
            connection.disconnect()
        }
    }

    private fun loadOrientedBitmap(file: File): Bitmap {

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeFile(file.absolutePath, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw Exception("Не удалось прочитать фотографию")
        }

        var sample = 1

        while (
            bounds.outWidth / sample > 1800 ||
            bounds.outHeight / sample > 1800
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        var bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            options
        ) ?: throw Exception("Не удалось открыть фотографию")

        val exif = ExifInterface(file.absolutePath)

        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }

        if (rotation != 0f) {

            val matrix = Matrix()
            matrix.postRotate(rotation)

            val rotated = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )

            bitmap.recycle()
            bitmap = rotated
        }

        val maxDimension = maxOf(bitmap.width, bitmap.height)

        if (maxDimension < 1800) {

            val scale = minOf(1.5f, 2700f / maxDimension)

            val enlarged = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )

            if (enlarged !== bitmap) {
                bitmap.recycle()
            }

            bitmap = enlarged
        }

        return bitmap
    }

    private fun createProcessedImages(
        source: Bitmap
    ): Pair<Bitmap, Bitmap> {

        val width = source.width
        val height = source.height

        val pixels = IntArray(width * height)

        source.getPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        val histogram = IntArray(256)

        for (i in pixels.indices) {

            val color = pixels[i]

            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)

            var gray = (
                0.299 * red +
                0.587 * green +
                0.114 * blue
            ).toInt()

            gray = ((gray - 128) * 1.35 + 128)
                .toInt()
                .coerceIn(0, 255)

            pixels[i] = Color.rgb(gray, gray, gray)
            histogram[gray]++
        }

        val total = pixels.size

        var sum = 0L

        for (i in 0..255) {
            sum += i.toLong() * histogram[i]
        }

        var sumBackground = 0L
        var weightBackground = 0
        var maximumVariance = 0.0
        var threshold = 128

        for (i in 0..255) {

            weightBackground += histogram[i]

            if (weightBackground == 0) continue

            val weightForeground = total - weightBackground

            if (weightForeground == 0) break

            sumBackground += i.toLong() * histogram[i]

            val meanBackground =
                sumBackground.toDouble() / weightBackground

            val meanForeground =
                (sum - sumBackground).toDouble() / weightForeground

            val variance =
                weightBackground.toDouble() *
                weightForeground.toDouble() *
                (meanBackground - meanForeground) *
                (meanBackground - meanForeground)

            if (variance > maximumVariance) {
                maximumVariance = variance
                threshold = i
            }
        }

        val grayBitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        grayBitmap.setPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        for (i in pixels.indices) {

            val gray = Color.red(pixels[i])

            pixels[i] = if (gray >= threshold) {
                Color.WHITE
            } else {
                Color.BLACK
            }
        }

        val binaryBitmap = Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        )

        binaryBitmap.setPixels(
            pixels,
            0,
            width,
            0,
            0,
            width,
            height
        )

        return Pair(grayBitmap, binaryBitmap)
    }

    private fun recognizeVariants(
        tess: TessBaseAPI,
        images: List<Bitmap>
    ): String {

        val modes = intArrayOf(
            TessBaseAPI.PageSegMode.PSM_AUTO,
            TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
            TessBaseAPI.PageSegMode.PSM_SINGLE_COLUMN
        )

        var bestText = ""
        var bestConfidence = -1

        for (image in images) {

            for (mode in modes) {

                tess.setPageSegMode(mode)
                tess.setImage(image)

                val text = tess.getUTF8Text() ?: ""
                val confidence = tess.meanConfidence()

                val cleaned = text.trim()

                if (
                    cleaned.isNotEmpty() &&
                    (
                        confidence > bestConfidence ||
                        (
                            confidence == bestConfidence &&
                            cleaned.length > bestText.length
                        )
                    )
                ) {
                    bestConfidence = confidence
                    bestText = cleaned
                }
            }
        }

        return bestText
    }

    private fun extractAddresses(text: String): List<String> {

        val lines = text
            .replace('\r', '\n')
            .split('\n')
            .map {
                it.replace(Regex("""^[\s\-•*]+"""), "")
                    .trim()
            }
            .filter { it.isNotBlank() }

        val streetWords = listOf(
            "ул", "улица",
            "пр", "проспект",
            "пер", "переулок",
            "ш", "шоссе",
            "б-р", "бульвар",
            "пл", "площадь",
            "проезд",
            "наб", "набережная",
            "мкр", "микрорайон"
        )

        val addressWords = streetWords + listOf(
            "д", "дом",
            "корп", "корпус",
            "стр", "строение",
            "кв", "квартира"
        )

        val numberPattern = Regex(
            """\d{1,5}\s*([а-яa-z]|\d+)?""",
            RegexOption.IGNORE_CASE
        )

        fun normalized(line: String): String {
            return line
                .lowercase(Locale("ru"))
                .replace("ё", "е")
                .replace(Regex("""[.,;:]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        fun hasWord(line: String, words: List<String>): Boolean {

            val value = normalized(line)

            return words.any { word ->
                Regex(
                    """(?<![а-яa-z])${Regex.escape(word)}\.?(?![а-яa-z])""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(value)
            }
        }

        fun hasNumber(line: String): Boolean {
            return numberPattern.containsMatchIn(line)
        }

        fun hasCity(line: String): Boolean {

            val value = normalized(line)

            return value.contains("набережные челны") ||
                value.contains("нижнекамск") ||
                value.contains("казань")
        }

        fun isAddress(line: String): Boolean {

            val street = hasWord(line, addressWords)
            val number = hasNumber(line)
            val city = hasCity(line)

            return (
                street && number ||
                city && number
            ) && line.length >= 5
        }

        val combinedLines = mutableListOf<String>()

        var pending = ""

        for (line in lines) {

            if (pending.isEmpty()) {
                pending = line
                continue
            }

            val pendingHasStreet = hasWord(pending, streetWords)
            val pendingHasNumber = hasNumber(pending)
            val pendingHasCity = hasCity(pending)

            val currentHasStreet = hasWord(line, streetWords)
            val currentHasNumber = hasNumber(line)

            val pendingComplete =
                pendingHasStreet && pendingHasNumber ||
                pendingHasCity && pendingHasNumber

            if (pendingComplete) {

                combinedLines.add(pending)
                pending = line
                continue
            }

            if (pendingHasStreet && currentHasStreet) {

                combinedLines.add(pending)
                pending = line
                continue
            }

            val canJoin =
                pendingHasCity && currentHasStreet ||
                pendingHasStreet && currentHasNumber ||
                pendingHasStreet && currentHasStreet ||
                pendingHasCity && currentHasNumber ||
                hasWord(pending, addressWords) && currentHasNumber

            if (canJoin && pending.length + line.length < 180) {

                pending = "$pending, $line"

            } else {

                combinedLines.add(pending)
                pending = line
            }
        }

        if (pending.isNotEmpty()) {
            combinedLines.add(pending)
        }

        val found = mutableListOf<String>()

        for (line in combinedLines) {

            if (isAddress(line)) {

                val cleaned = line
                    .replace(Regex("""^[\s\-•*]+"""), "")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                    .trim(',', ';')

                if (
                    cleaned.isNotBlank() &&
                    cleaned.length >= 5 &&
                    cleaned.length <= 180
                ) {
                    found.add(cleaned)
                }
            }
        }

        return found.distinctBy {
            normalized(it).replace(
                Regex("""[\s.,]+"""),
                ""
            )
        }
    }

    private fun showAddresses(found: List<String>) {

        addresses.clear()
        addresses.addAll(found)

        addressesContainer.removeAllViews()
        coordinatesText.text = ""

        countText.text = "Найдено адресов: ${addresses.size}"
        geocodeButton.isEnabled = addresses.isNotEmpty()

        if (addresses.isEmpty()) {

            val empty = TextView(this).apply {
                text = "Адреса не найдены. Попробуйте сфотографировать лист ещё раз."
                textSize = 16f
                setPadding(0, 16, 0, 16)
            }

            addressesContainer.addView(empty)
            return
        }

        addresses.forEachIndexed { index, address ->

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }

            val number = TextView(this).apply {
                text = "${index + 1}."
                textSize = 16f
                setPadding(4, 0, 10, 0)
            }

            val edit = EditText(this).apply {
                setText(address)
                textSize = 15f
                minLines = 1
                maxLines = 3
                isSingleLine = false
            }

            val delete = Button(this).apply {
                text = "X"

                setOnClickListener {
                    saveEditedAddresses()
                    if (index < addresses.size) {
                        addresses.removeAt(index)
                    }
                    renderAddresses()
                }
            }

            row.addView(number)

            row.addView(
                edit,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            row.addView(delete)

            addressesContainer.addView(row)

            edit.setOnFocusChangeListener { _, hasFocus ->

                if (!hasFocus && index < addresses.size) {
                    addresses[index] = edit.text.toString().trim()
                }
            }
        }

        val saveButton = Button(this).apply {

            text = "СОХРАНИТЬ ИЗМЕНЕНИЯ"

            setOnClickListener {
                saveEditedAddresses()
            }
        }

        addressesContainer.addView(saveButton)
    }

    private fun saveEditedAddresses() {

        for (i in addresses.indices) {

            val row = addressesContainer.getChildAt(i)

            if (row is LinearLayout && row.childCount >= 2) {

                val edit = row.getChildAt(1)

                if (edit is EditText) {
                    addresses[i] = edit.text.toString().trim()
                }
            }
        }

        addresses.removeAll { it.isBlank() }

        renderAddresses()

        Toast.makeText(
            this,
            "Изменения сохранены",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun renderAddresses() {
        showAddresses(addresses.toList())
    }

    // ГЕОКОДИРОВАНИЕ ЧЕРЕЗ 2ГИС

    private fun geocodeAddress(address: String): GeocodeResult? {

        val apiKey = BuildConfig.DGIS_API_KEY

        if (apiKey.isBlank()) {
            throw Exception("Не найден ключ DGIS_API_KEY")
        }

        val query = Uri.parse(
            "https://catalog.api.2gis.com/3.0/items/geocode"
        ).buildUpon()
            .appendQueryParameter("q", address)
            .appendQueryParameter("key", apiKey)
            .appendQueryParameter("locale", "ru_RU")
            .appendQueryParameter(
                "fields",
                "items.point,items.full_address_name"
            )
            .appendQueryParameter("page_size", "5")
            .build()

        val connection = URL(query.toString())
            .openConnection() as HttpURLConnection

        try {

            connection.requestMethod = "GET"
            connection.connectTimeout = 20000
            connection.readTimeout = 30000
            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            connection.connect()

            val responseCode = connection.responseCode

            val responseText = if (
                responseCode in 200..299
            ) {
                connection.inputStream.bufferedReader().use {
                    it.readText()
                }
            } else {
                val error = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: ""

                throw Exception(
                    "2ГИС HTTP $responseCode: ${error.take(200)}"
                )
            }

            val json = JSONObject(responseText)

            val meta = json.optJSONObject("meta")

            val apiCode = meta?.optInt("code", 200) ?: 200

            if (apiCode != 200) {
                throw Exception(
                    "Ошибка 2ГИС: код $apiCode"
                )
            }

            val result = json.optJSONObject("result")
                ?: return null

            val items = result.optJSONArray("items")
                ?: return null

            if (items.length() == 0) {
                return null
            }

            for (i in 0 until items.length()) {

                val item = items.optJSONObject(i)
                    ?: continue

                val point = item.optJSONObject("point")
                    ?: continue

                val longitude = point.optDouble(
                    "lon",
                    Double.NaN
                )

                val latitude = point.optDouble(
                    "lat",
                    Double.NaN
                )

                if (
                    longitude.isFinite() &&
                    latitude.isFinite() &&
                    longitude in -180.0..180.0 &&
                    latitude in -90.0..90.0
                ) {

                    val foundAddress = item.optString(
                        "full_address_name",
                        address
                    )

                    return GeocodeResult(
                        address = foundAddress,
                        longitude = longitude,
                        latitude = latitude
                    )
                }
            }

            return null

        } finally {
            connection.disconnect()
        }
    }

    private fun geocodeAddresses() {

        saveEditedAddresses()

        if (addresses.isEmpty()) {
            Toast.makeText(
                this,
                "Нет адресов для поиска",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val addressCopy = addresses.toList()

        geocodeButton.isEnabled = false
        photoButton.isEnabled = false

        coordinatesText.text = ""

        status.text = "Подключение к 2ГИС..."

        executor.execute {

            val results = mutableListOf<GeocodeResult>()
            val notFound = mutableListOf<String>()

            var errorMessage: String? = null

            try {

                for ((index, address) in addressCopy.withIndex()) {

                    runOnUiThread {
                        status.text =
                            "Поиск адреса ${index + 1} из ${addressCopy.size}..."
                    }

                    try {

                        val result = geocodeAddress(address)

                        if (result != null) {
                            results.add(result)
                        } else {
                            notFound.add(address)
                        }

                    } catch (e: Exception) {

                        errorMessage = e.message
                        notFound.add(address)
                    }
                }

            } catch (e: Exception) {
                errorMessage = e.message
            }

            runOnUiThread {

                val output = StringBuilder()

                output.append("СКЛАД — НАЧАЛО И КОНЕЦ МАРШРУТА\n")
                output.append(warehouseAddress)
                output.append("\n\n")

                output.append("НАЙДЕННЫЕ АДРЕСА: ")
                output.append(results.size)
                output.append("\n\n")

                results.forEachIndexed { index, item ->

                    output.append("${index + 1}. ")
                    output.append(item.address)
                    output.append("\n")

                    output.append("Долгота: ")
                    output.append(item.longitude)
                    output.append("\n")

                    output.append("Широта: ")
                    output.append(item.latitude)
                    output.append("\n\n")
                }

                if (notFound.isNotEmpty()) {

                    output.append("НЕ НАЙДЕНЫ — ПРОВЕРЬТЕ ВРУЧНУЮ:\n")

                    notFound.forEachIndexed { index, address ->
                        output.append("${index + 1}. $address\n")
                    }
                }

                coordinatesText.text = output.toString()

                status.text = if (errorMessage == null) {
                    "Поиск завершён. Найдено: ${results.size}, не найдено: ${notFound.size}"
                } else {
                    "Поиск завершён с ошибкой: $errorMessage"
                }

                geocodeButton.isEnabled = addresses.isNotEmpty()
                photoButton.isEnabled = true
            }
        }
    }

    // РАСПОЗНАВАНИЕ ФОТОГРАФИИ

    private fun recognizePhoto(file: File) {

        photoButton.isEnabled = false
        geocodeButton.isEnabled = false

        status.text = "Подготовка фотографии..."

        executor.execute {

            var original: Bitmap? = null
            var gray: Bitmap? = null
            var binary: Bitmap? = null
            var tess: TessBaseAPI? = null

            try {

                val directory = File(filesDir, "tesseract")
                directory.mkdirs()

                runOnUiThread {
                    status.text = "Загрузка русского языка..."
                }

                downloadRussianLanguage(directory)

                runOnUiThread {
                    status.text = "Обработка фотографии..."
                }

                original = loadOrientedBitmap(file)

                val processed = createProcessedImages(original)

                gray = processed.first
                binary = processed.second

                runOnUiThread {
                    status.text = "Распознавание адресов..."
                }

                tess = TessBaseAPI()

                if (!tess.init(
                    directory.absolutePath,
                    "rus",
                    TessBaseAPI.OEM_LSTM_ONLY
                )) {
                    throw Exception("Не удалось запустить Tesseract")
                }

                tess.setVariable(
                    "preserve_interword_spaces",
                    "1"
                )

                tess.setVariable(
                    "user_defined_dpi",
                    "300"
                )

                val recognizedText = recognizeVariants(
                    tess,
                    listOf(gray, binary)
                )

                val foundAddresses = extractAddresses(recognizedText)

                runOnUiThread {

                    showAddresses(foundAddresses)

                    status.text = if (foundAddresses.isEmpty()) {
                        "Адреса не найдены"
                    } else {
                        "Распознавание завершено"
                    }

                    photoButton.isEnabled = true
                }

            } catch (e: Exception) {

                runOnUiThread {
                    status.text = "Ошибка: ${e.message}"
                    photoButton.isEnabled = true
                }

            } finally {

                tess?.recycle()
                original?.recycle()
                gray?.recycle()
                binary?.recycle()
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
