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

        routeButton = Button(this).apply {
            text = "ПОСТРОИТЬ МАРШРУТ В 2ГИС"
            isEnabled = false
            setOnClickListener {
                openRouteIn2Gis()
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

                if (score > bestScore ||
                    (score == bestScore && text.length > bestText.length)
                ) {
                    bestScore = score
                    bestText = text
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

            return listOf(
                "набережные челны",
                "нижнекамск",
                "казань",
                "елабуга",
                "менделеевск",
                "мамадыш",
                "заинск",
                "альметьевск",
                "ижевск",
                "москву",
                "москва"
            ).any { value.contains(it) }
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

    private fun showAddresses(found: List<String>) {

        addresses.clear()
        addresses.addAll(found)
        invalidateRouteData()

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

        val normalizedInput = address.lowercase(Locale("ru"))
            .replace("ё", "е")
            .replace(Regex("""[^а-яa-z0-9/ -]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val cityNames = listOf(
            "набережные челны", "нижнекамск", "казань", "елабуга",
            "менделеевск", "мамадыш", "заинск", "альметьевск",
            "ижевск", "москва"
        )
        val hasCity = cityNames.any { normalizedInput.contains(it) }

        val queries = buildList {
            add(address.trim())
            if (!hasCity &&
                !normalizedInput.contains("республика") &&
                !normalizedInput.contains("татарстан")
            ) {
                add("$address, Набережные Челны, Республика Татарстан")
            }
        }.distinct()

        val houseMatches = Regex(
            """\b\d{1,5}(?:\s*[/\-]\s*\d{1,5})?\s*[а-яa-z]?\b""",
            RegexOption.IGNORE_CASE
        ).findAll(normalizedInput)
            .map { it.value.replace(Regex("""\s+"""), "").lowercase(Locale("ru")) }
            .toList()

        val streetTokens = normalizedInput
            .split(Regex("""[^а-яa-z0-9]+"""))
            .filter { it.length >= 3 && it !in setOf(
                "улица", "проезд", "проспект", "переулок", "шоссе",
                "бульвар", "набережная", "микрорайон", "дом",
                "корпус", "строение", "квартира", "набережные",
                "челны", "республика", "татарстан", "россия"
            ) }
            .distinct()

        var best: GeocodeResult? = null
        var bestScore = Int.MIN_VALUE

        for (searchText in queries) {
            val requestUrl = Uri.parse(
                "https://catalog.api.2gis.com/3.0/items/geocode"
            ).buildUpon()
                .appendQueryParameter("q", searchText)
                .appendQueryParameter("key", apiKey)
                .appendQueryParameter("locale", "ru_RU")
                .appendQueryParameter(
                    "fields",
                    "items.point,items.full_address_name"
                )
                .appendQueryParameter("page_size", "10")
                .build()

            val connection = URL(requestUrl.toString())
                .openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 20000
                connection.readTimeout = 30000
                connection.setRequestProperty("Accept", "application/json")
                connection.connect()

                val responseCode = connection.responseCode
                val responseText = if (responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val error = connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() } ?: ""
                    throw Exception("2ГИС HTTP $responseCode: ${error.take(200)}")
                }

                val json = JSONObject(responseText)
                val apiCode = json.optJSONObject("meta")?.optInt("code", 200) ?: 200
                if (apiCode != 200) {
                    throw Exception("Ошибка 2ГИС: код $apiCode")
                }

                val items = json.optJSONObject("result")?.optJSONArray("items")
                    ?: continue

                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val point = item.optJSONObject("point") ?: continue
                    val longitude = point.optDouble("lon", Double.NaN)
                    val latitude = point.optDouble("lat", Double.NaN)

                    if (!longitude.isFinite() || !latitude.isFinite() ||
                        longitude !in -180.0..180.0 || latitude !in -90.0..90.0
                    ) continue

                    val foundAddress = item.optString("full_address_name", "").trim()
                    if (foundAddress.isBlank()) continue

                    val normalizedFound = foundAddress.lowercase(Locale("ru"))
                        .replace("ё", "е")
                        .replace(Regex("""[^а-яa-z0-9/ -]"""), " ")
                        .replace(Regex("""\s+"""), " ")
                        .trim()

                    val foundNumberTokens = Regex(
                        """\b\d{1,5}(?:\s*[/\-]\s*\d{1,5})?\s*[а-яa-z]?\b""",
                        RegexOption.IGNORE_CASE
                    ).findAll(normalizedFound)
                        .map { it.value.replace(Regex("""\s+"""), "").lowercase(Locale("ru")) }
                        .toSet()

                    val houseMatched = houseMatches.any { it in foundNumberTokens }
                    if (houseMatches.isNotEmpty() && !houseMatched) continue

                    val matchedStreetTokens = streetTokens.count {
                        normalizedFound.split(Regex("""[^а-яa-z0-9]+""")).contains(it)
                    }

                    if (streetTokens.isNotEmpty() && matchedStreetTokens == 0) continue

                    var score = matchedStreetTokens * 10
                    if (houseMatched) score += 100
                    if (normalizedFound.contains("набережные челны")) score += 2
                    if (score > bestScore) {
                        bestScore = score
                        best = GeocodeResult(
                            address = foundAddress,
                            longitude = longitude,
                            latitude = latitude
                        )
                    }
                }
            } finally {
                connection.disconnect()
            }
        }

        return best
    }

    // ПОСТРОЕНИЕ МАРШРУТА В 2ГИС

    private fun openRouteIn2Gis() {

        val warehouse = warehousePoint
        if (warehouse == null) {
            Toast.makeText(
                this,
                "Сначала найдите координаты склада",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (geocodedAddresses.isEmpty()) {
            Toast.makeText(
                this,
                "Нет найденных адресов для маршрута",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (geocodedAddresses.size > 10) {
            Toast.makeText(
                this,
                "Для одного маршрута можно передать не более 10 адресов доставки",
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

            val dgIsIntent = Intent(Intent.ACTION_VIEW, routeUri).apply {
                setPackage("ru.dublgis.dgismobile")
            }

            try {
                startActivity(dgIsIntent)
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, routeUri))
            }

        } catch (e: Exception) {
            status.text = "Не удалось открыть маршрут: ${e.message}"
            Toast.makeText(
                this,
                "Не удалось передать маршрут в 2ГИС",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ПОИСК КООРДИНАТ АДРЕСОВ

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
        routeButton.isEnabled = false
        photoButton.isEnabled = false

        geocodedAddresses = emptyList()
        warehousePoint = null

        coordinatesText.text = ""
        status.text = "Подключение к 2ГИС..."

        executor.execute {

            val results = mutableListOf<GeocodeResult>()
            val notFound = mutableListOf<String>()

            var errorMessage: String? = null
            var foundWarehouse: GeocodeResult? = null

            try {

                runOnUiThread {
                    status.text = "Поиск координат склада..."
                }

                foundWarehouse = geocodeAddress(warehouseAddress)
                    ?: throw Exception(
                        "Не удалось определить координаты склада: $warehouseAddress"
                    )

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

                warehousePoint =
                    if (errorMessage == null) foundWarehouse else null

                geocodedAddresses = results.toList()

                val output = StringBuilder()

                output.append(
                    "СКЛАД — НАЧАЛО И КОНЕЦ МАРШРУТА\n"
                )

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

                    output.append(
                        "НЕ НАЙДЕНЫ — ПРОВЕРЬТЕ ВРУЧНУЮ:\n"
                    )

                    notFound.forEachIndexed { index, address ->
                        output.append("${index + 1}. $address\n")
                    }
                }

                coordinatesText.text = output.toString()

                status.text = when {
                    errorMessage != null ->
                        "Поиск завершён с ошибкой: $errorMessage"

                    results.size > 10 ->
                        "Найдено ${results.size}. Для одной ссылки 2ГИС поддерживает максимум 10 адресов доставки"

                    notFound.isNotEmpty() ->
                        "Найдено: ${results.size}, не найдено: ${notFound.size}. Исправьте адреса и повторите поиск"

                    else ->
                        "Поиск завершён. Найдено: ${results.size}, не найдено: 0"
                }

                geocodeButton.isEnabled = addresses.isNotEmpty()

                routeButton.isEnabled =
                    warehousePoint != null &&
                    results.size == addressCopy.size &&
                    results.isNotEmpty() &&
                    results.size <= 10

                photoButton.isEnabled = true
            }
        }
    }

    // РАСПОЗНАВАНИЕ ФОТОГРАФИИ

    private fun recognizePhoto(file: File) {

        photoButton.isEnabled = false
        geocodeButton.isEnabled = false
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
