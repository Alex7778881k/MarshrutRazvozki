
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
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var addressesContainer: LinearLayout
    private lateinit var photoButton: Button
    private lateinit var routeButton: Button
    private lateinit var countText: TextView
    private lateinit var coordinatesText: TextView

    private val addresses = mutableListOf<String>()

    private var geocodedAddresses: List<GeocodeResult> = emptyList()
    private var warehousePoint: GeocodeResult? = null

    private var photoUri: Uri? = null
    private var photoFile: File? = null

    private val executor = Executors.newSingleThreadExecutor()
    private val cameraRequest = 1001

    private val warehouseAddress =
        "423800, Россия, Республика Татарстан, Набережные Челны, Центральный район, Индустриальный проезд, 22/1"

    data class GeocodeResult(
        val address: String,
        val longitude: Double,
        val latitude: Double,
        val objectId: String = ""
    )

    private val cities = listOf(
        "набережные челны",
        "нижнекамск",
        "казань",
        "елабуга",
        "менделеевск",
        "мамадыш",
        "заинск",
        "альметьевск",
        "ижевск",
        "москва"
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

        routeButton = Button(this).apply {
            text = "ОТКРЫТЬ МАРШРУТ В 2ГИС"
            isEnabled = false
            setOnClickListener { openRouteIn2Gis() }
        }

        coordinatesText = TextView(this).apply {
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

        layout.addView(routeButton)

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

    // ФОТОГРАФИРОВАНИЕ

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

            photoButton.isEnabled = false
            startActivityForResult(intent, cameraRequest)

        } catch (e: Exception) {
            status.text = "Ошибка камеры: ${e.message}"
            photoButton.isEnabled = true
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

    // ЗАГРУЗКА РУССКОГО TESSERACT

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

    // ПОДГОТОВКА ФОТОГРАФИИ

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
            bounds.outWidth / sample > 2600 ||
            bounds.outHeight / sample > 2600
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

        if (maxDimension < 2600) {

            val scale = minOf(1.35f, 2600f / maxDimension)

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

    // ОБРАБОТКА ФОТОГРАФИИ

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

    // РАСПОЗНАВАНИЕ РАЗНЫХ ВАРИАНТОВ ФОТО

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
        var bestScore = Int.MIN_VALUE

        for (image in images) {
            for (mode in modes) {

                tess.setPageSegMode(mode)
                tess.setImage(image)

                val text = (tess.getUTF8Text() ?: "").trim()

                if (text.isBlank()) continue

                val confidence = tess.meanConfidence()
                val addressCount = extractAddresses(text).size

                val usefulLines = text.lines().count { line ->
                    line.any { it.isLetterOrDigit() }
                }

                val score = addressCount * 1000 +
                    confidence * 2 +
                    usefulLines.coerceAtMost(100)

                if (
                    score > bestScore ||
                    (score == bestScore && text.length > bestText.length)
                ) {
                    bestScore = score
                    bestText = text
                }
            }
        }

        return bestText
    }

    // ВЫДЕЛЕНИЕ АДРЕСОВ

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
            "проезд", "пр-д",
            "наб", "набережная",
            "мкр", "микрорайон",
            "тер", "территория",
            "тракт"
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
            return cities.any { value.contains(it) }
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
            normalized(it).replace(Regex("""[\s.,]+"""), "")
        }
    }

    // СБРОС КООРДИНАТ

    private fun invalidateRouteData() {

        geocodedAddresses = emptyList()
        warehousePoint = null

        if (::routeButton.isInitialized) {
            routeButton.isEnabled = false
        }

        if (::coordinatesText.isInitialized) {
            coordinatesText.text = ""
        }
    }

    // ОТОБРАЖЕНИЕ АДРЕСОВ

    private fun showAddresses(found: List<String>) {

        addresses.clear()
        addresses.addAll(found)

        invalidateRouteData()

        addressesContainer.removeAllViews()
        coordinatesText.text = ""

        countText.text = "Найдено адресов: ${addresses.size}"

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

                    saveEditedAddresses(startSearch = false)

                    if (index < addresses.size) {
                        addresses.removeAt(index)
                    }

                    renderAddresses()

                    if (addresses.isNotEmpty()) {
                        geocodeAddresses()
                    }
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

                    val updatedAddress = edit.text.toString().trim()

                    if (addresses[index] != updatedAddress) {
                        addresses[index] = updatedAddress
                        invalidateRouteData()
                    }
                }
            }
        }

        val saveButton = Button(this).apply {

            text = "СОХРАНИТЬ ИЗМЕНЕНИЯ"

            setOnClickListener {
                saveEditedAddresses(startSearch = true)
            }
        }

        addressesContainer.addView(saveButton)
    }

    // СОХРАНЕНИЕ ИЗМЕНЕНИЙ

    private fun saveEditedAddresses(startSearch: Boolean = true) {

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

        if (startSearch && addresses.isNotEmpty()) {
            geocodeAddresses()
        }
    }

    private fun renderAddresses() {
        showAddresses(addresses.toList())
    }

    // НОРМАЛИЗАЦИЯ АДРЕСА

    private fun normalizeAddress(value: String): String {

        return value
            .lowercase(Locale("ru"))
            .replace("ё", "е")
            .replace(Regex("""[.,;:]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    // ОПРЕДЕЛЕНИЕ ГОРОДА

    private fun detectCity(address: String): String? {

        val value = normalizeAddress(address)

        return cities.firstOrNull {
            value.contains(it)
        }
    }

    // ВЫДЕЛЕНИЕ НОМЕРА ДОМА

    private fun extractHouseNumbers(address: String): Set<String> {

        val pattern = Regex(
            """(?i)(?:\bд\.?\s*)?(\d{1,5}\s*(?:[/\-]\s*\d{1,5})?\s*[а-яa-z]?)"""
        )

        return pattern.findAll(address)
            .mapNotNull { match ->

                match.groupValues[1]
                    .lowercase(Locale("ru"))
                    .replace(Regex("""\s+"""), "")
                    .replace("-", "/")
                    .takeIf { it.isNotBlank() }
            }
            .toSet()
    }

    // ГЕОКОДИРОВАНИЕ ЧЕРЕЗ API 2ГИС 3.0

    private fun geocodeAddress(address: String): GeocodeResult? {

        val apiKey = BuildConfig.DGIS_API_KEY

        if (apiKey.isBlank()) {
            throw Exception("Не найден ключ DGIS_API_KEY")
        }

        val cleanedAddress = address
            .replace(Regex("""\b\d{6}\b"""), " ")
            .replace(Regex("""(?i)\bроссия\b"""), " ")
            .replace(Regex("""(?i)\bреспублика\b"""), " ")
            .replace(Regex("""(?i)\bтатарстан\b"""), " ")
            .replace(Regex("""(?i)\bцентральный район\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim(',', ';')

        if (cleanedAddress.isBlank()) {
            throw Exception("Пустой адрес")
        }

        val requestedCity = detectCity(cleanedAddress)
        val houseNumbers = extractHouseNumbers(cleanedAddress)

        val excludedWords = setOf(
            "улица", "ул", "проезд", "пр", "проспект",
            "переулок", "пер", "шоссе", "ш",
            "бульвар", "б-р", "набережная", "наб",
            "площадь", "пл", "микрорайон", "мкр",
            "дом", "д", "корпус", "корп", "строение",
            "стр", "квартира", "кв", "россия",
            "республика", "татарстан",
            "набережные", "челны",
            "нижнекамск", "казань", "елабуга",
            "менделеевск", "мамадыш", "заинск",
            "альметьевск", "ижевск", "москва",
            "центральный", "район", "индекс"
        )

        val streetTokens = normalizeAddress(cleanedAddress)
            .split(Regex("""[^а-яa-z]+"""))
            .filter {
                it.length >= 3 && it !in excludedWords
            }
            .distinct()

        if (streetTokens.isEmpty()) {
            throw Exception("Не удалось определить улицу: $address")
        }

        val searchCities = if (requestedCity != null) {
            listOf(requestedCity)
        } else {
            cities
        }

        data class Candidate(
            val result: GeocodeResult,
            val score: Int,
            val city: String
        )

        val candidates = mutableListOf<Candidate>()

        for (searchCity in searchCities) {

            val searchText = "$cleanedAddress, $searchCity"

            val requestUrl = Uri.parse(
                "https://catalog.api.2gis.com/3.0/items/geocode"
            ).buildUpon()
                .appendQueryParameter("q", searchText)
                .appendQueryParameter("key", apiKey)
                .appendQueryParameter("locale", "ru_RU")
                .appendQueryParameter(
                    "fields",
                    "items.point,items.geometry.centroid"
                )
                .appendQueryParameter("page_size", "10")
                .build()

            val connection = URL(requestUrl.toString())
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

                val responseText = if (responseCode in 200..299) {

                    connection.inputStream.bufferedReader().use {
                        it.readText()
                    }

                } else {

                    val error = connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() } ?: ""

                    throw Exception(
                        "2ГИС HTTP $responseCode: ${error.take(500)}"
                    )
                }

                val json = JSONObject(responseText)

                val meta = json.optJSONObject("meta")

                val apiCode = meta?.optInt("code", 200) ?: 200

                if (apiCode != 200) {

                    val message = meta?.optJSONObject("error")
                        ?.optString("message", "Неизвестная ошибка")
                        ?: "Неизвестная ошибка"

                    throw Exception(
                        "Ошибка API 2ГИС: $message (код $apiCode)"
                    )
                }

                val items = json.optJSONObject("result")
                    ?.optJSONArray("items") ?: continue

                for (i in 0 until items.length()) {

                    val item = items.optJSONObject(i) ?: continue

                    val point = item.optJSONObject("point")
                        ?: continue

                    val longitude = point.optDouble("lon", Double.NaN)
                    val latitude = point.optDouble("lat", Double.NaN)

                    if (
                        !longitude.isFinite() ||
                        !latitude.isFinite() ||
                        longitude !in -180.0..180.0 ||
                        latitude !in -90.0..90.0
                    ) continue

                    val foundAddress = item.optString("full_name", "")
                        .ifBlank {
                            item.optString("full_address_name", "")
                        }
                        .ifBlank {
                            item.optString("address_name", "")
                        }
                        .ifBlank {
                            item.optString("name", "")
                        }
                        .trim()

                    if (foundAddress.isBlank()) continue

                    val normalizedFound = normalizeAddress(foundAddress)

                    val foundCity = detectCity(normalizedFound)

                    if (foundCity != searchCity) continue

                    val foundTokens = normalizedFound.split(
                        Regex("""[^а-яa-z0-9]+""")
                    )

                    val matchedStreetTokens = streetTokens.count {
                        foundTokens.contains(it)
                    }

                    if (matchedStreetTokens == 0) continue

                    val foundHouseNumbers =
                        extractHouseNumbers(foundAddress)

                    if (houseNumbers.isNotEmpty()) {

                        val houseMatched = houseNumbers.any { requested ->

                            foundHouseNumbers.any { actual ->
                                actual == requested
                            }
                        }

                        if (!houseMatched) continue
                    }

                    var score = matchedStreetTokens * 100

                    if (houseNumbers.isNotEmpty()) {
                        score += 500
                    }

                    if (item.optString("type") == "building") {
                        score += 50
                    }

                    if (foundCity == requestedCity) {
                        score += 100
                    }

                    candidates.add(
                        Candidate(
                            result = GeocodeResult(
                                address = foundAddress,
                                longitude = longitude,
                                latitude = latitude,
                                objectId = item.optString("id", "").trim()
                            ),
                            score = score,
                            city = searchCity
                        )
                    )
                }

            } finally {
                connection.disconnect()
            }
        }

        if (candidates.isEmpty()) {
            return null
        }

        val sorted = candidates.sortedByDescending {
            it.score
        }

        if (requestedCity == null && sorted.size > 1) {

            val first = sorted[0]
            val second = sorted[1]

            if (
                first.score == second.score &&
                first.city != second.city
            ) {
                throw Exception(
                    "Неоднозначный адрес. Укажите город: $address"
                )
            }
        }

        return sorted.first().result
    }

    // АВТОМАТИЧЕСКИЙ ПОИСК КООРДИНАТ

    private fun geocodeAddresses() {

        if (addresses.isEmpty()) {
            routeButton.isEnabled = false
            photoButton.isEnabled = true
            return
        }

        val addressCopy = addresses.toList()

        routeButton.isEnabled = false
        photoButton.isEnabled = false

        geocodedAddresses = emptyList()
        warehousePoint = null

        coordinatesText.text = ""
        status.text = "Подключение к 2ГИС..."

        executor.execute {

            val results = mutableListOf<GeocodeResult>()
            val notFound = mutableListOf<String>()

            var warehouseError: String? = null
            var foundWarehouse: GeocodeResult? = null

            try {

                runOnUiThread {
                    status.text = "Поиск координат склада..."
                }

                foundWarehouse = geocodeAddress(warehouseAddress)
                    ?: throw Exception(
                        "Не удалось определить координаты склада"
                    )

            } catch (e: Exception) {
                warehouseError = e.message
            }

            if (foundWarehouse != null) {

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
                        notFound.add("$address (ошибка: ${e.message})")
                    }
                }
            }

            runOnUiThread {

                warehousePoint = foundWarehouse
                geocodedAddresses = results.toList()

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
                    output.append("\n")

                    output.append("ID 2ГИС: ")
                    output.append(
                        item.objectId.ifBlank { "не предоставлен API" }
                    )
                    output.append("\n\n")
                }

                if (notFound.isNotEmpty()) {

                    output.append(
                        "НЕ НАЙДЕНЫ — ПРОВЕРЬТЕ ВРУЧНУЮ:\n"
                    )

                    notFound.forEachIndexed { index, address ->
                        output.append("${index + 1}. $address\n")
                    }
                }

                coordinatesText.text = output.toString()

                status.text = when {

                    warehouseError != null ->
                        "Ошибка определения склада: $warehouseError"

                    results.size > 10 ->
                        "Найдено ${results.size}. Максимум 10 адресов доставки"

                    notFound.isNotEmpty() ->
                        "Найдено: ${results.size}, не найдено: ${notFound.size}. Проверьте адреса"

                    results.size == addressCopy.size ->
                        "Все адреса найдены. Маршрут готов к открытию в 2ГИС"

                    else ->
                        "Не удалось подготовить маршрут"
                }

                routeButton.isEnabled =
                    foundWarehouse != null &&
                    results.size == addressCopy.size &&
                    results.isNotEmpty() &&
                    results.size <= 10

                photoButton.isEnabled = true
            }
        }
    }

    // ПОСТРОЕНИЕ И ОТКРЫТИЕ МАРШРУТА В 2ГИС

    private fun openRouteIn2Gis() {

        val warehouse = warehousePoint

        if (warehouse == null) {
            Toast.makeText(
                this,
                "Не удалось определить координаты склада",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (geocodedAddresses.isEmpty()) {
            Toast.makeText(
                this,
                "Нет найденных адресов",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (geocodedAddresses.size > 10) {
            Toast.makeText(
                this,
                "Максимум 10 адресов доставки",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val points = buildList {
            add(warehouse)
            addAll(geocodedAddresses)
            add(warehouse)
        }

        val pointsPath = points.joinToString("|") { point ->

            String.format(
                Locale.US,
                "%.6f,%.6f",
                point.longitude,
                point.latitude
            )
        }

        val routeUrl =
            "https://2gis.ru/naberezhnye-chelny/directions/tab/car/points/$pointsPath"

        try {

            val routeUri = Uri.parse(routeUrl)

            val dgIsIntent = Intent(
                Intent.ACTION_VIEW,
                routeUri
            ).apply {
                setPackage("ru.dublgis.dgismobile")
            }

            try {

                startActivity(dgIsIntent)

            } catch (e: Exception) {

                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        routeUri
                    )
                )
            }

        } catch (e: Exception) {

            status.text = "Не удалось открыть маршрут: ${e.message}"

            Toast.makeText(
                this,
                "Ошибка передачи маршрута в 2ГИС",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // РАСПОЗНАВАНИЕ ФОТОГРАФИИ

    private fun recognizePhoto(file: File) {

        photoButton.isEnabled = false
        routeButton.isEnabled = false

        invalidateRouteData()

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
                    listOf(original, gray, binary)
                )

                val foundAddresses = extractAddresses(recognizedText)

                runOnUiThread {

                    showAddresses(foundAddresses)

                    if (foundAddresses.isEmpty()) {

                        status.text = "Адреса не найдены"
                        photoButton.isEnabled = true

                    } else {

                        status.text =
                            "Адреса распознаны. Поиск координат..."

                        geocodeAddresses()
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {
                    status.text = "Ошибка: ${e.message}"
                    photoButton.isEnabled = true
                    routeButton.isEnabled = false
                }

            } finally {

                tess?.recycle()
                original?.recycle()
                gray?.recycle()
                binary?.recycle()
            }
        }
    }

    // ЗАКРЫТИЕ ПРИЛОЖЕНИЯ

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
