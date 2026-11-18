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
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import androidx.core.content.FileProvider
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : Activity() {

    companion object {
        private const val CAMERA_REQUEST = 101
        private const val MAX_ADDRESSES = 10

        private const val WAREHOUSE_ADDRESS =
            "Индустриальный проезд, 22/1, Набережные Челны"

        private const val TESSDATA_URL =
            "https://github.com/tesseract-ocr/tessdata_best/raw/main/rus.traineddata"

        private val CITIES = listOf(
            "Набережные Челны",
            "Нижнекамск",
            "Казань",
            "Елабуга",
            "Менделеевск",
            "Мамадыш",
            "Заинск",
            "Альметьевск",
            "Ижевск",
            "Москва"
        )
    }

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var rootLayout: LinearLayout
    private lateinit var addressContainer: LinearLayout
    private lateinit var warehouseInput: EditText
    private lateinit var statusText: TextView
    private lateinit var cameraButton: Button
    private lateinit var addButton: Button
    private lateinit var geocodeButton: Button
    private lateinit var routeButton: Button

    private val addressInputs = mutableListOf<EditText>()
    private val coordinates = mutableListOf<GeoPoint>()

    private var warehousePoint: GeoPoint? = null
    private var photoFile: File? = null
    private var isBusy = false

    data class GeoPoint(
        val lat: Double,
        val lon: Double,
        val objectId: String? = null,
        val address: String = ""
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createInterface()
    }

    private fun createInterface() {
        val scroll = ScrollView(this)

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        scroll.addView(rootLayout)

        val title = TextView(this).apply {
            text = "МАРШРУТ ДОСТАВКИ"
            textSize = 23f
            gravity = Gravity.CENTER
            setPadding(0, 10, 0, 25)
        }
        rootLayout.addView(title)

        addLabel("АДРЕС СКЛАДА")

        warehouseInput = EditText(this).apply {
            setText(WAREHOUSE_ADDRESS)
            textSize = 16f
            minLines = 2
            setPadding(12, 12, 12, 12)
        }
        rootLayout.addView(warehouseInput)

        warehouseInput.addTextChangedListener(SimpleTextWatcher {
            invalidateRoute()
        })

        cameraButton = createButton("СФОТОГРАФИРОВАТЬ ЛИСТ")
        rootLayout.addView(cameraButton)

        cameraButton.setOnClickListener {
            openCamera()
        }

        addLabel("АДРЕСА ДОСТАВКИ")

        addressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        rootLayout.addView(addressContainer)

        addButton = createButton("ДОБАВИТЬ АДРЕС")
        rootLayout.addView(addButton)

        addButton.setOnClickListener {
            if (addressInputs.size >= MAX_ADDRESSES) {
                showMessage("Максимум $MAX_ADDRESSES адресов")
                return@setOnClickListener
            }

            addAddressField("")
            invalidateRoute()
        }

        geocodeButton = createButton("ОПРЕДЕЛИТЬ КООРДИНАТЫ")
        rootLayout.addView(geocodeButton)

        geocodeButton.setOnClickListener {
            geocodeAddresses()
        }

        routeButton = createButton("ПОСТРОИТЬ МАРШРУТ В 2ГИС")
        rootLayout.addView(routeButton)
        routeButton.isEnabled = false

        routeButton.setOnClickListener {
            openRouteIn2Gis()
        }

        statusText = TextView(this).apply {
            text = "Готово к работе"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(5, 20, 5, 20)
        }
        rootLayout.addView(statusText)

        setContentView(scroll)

        addAddressField("")
    }

    private fun addLabel(text: String) {
        val label = TextView(this).apply {
            this.text = text
            textSize = 14f
            setPadding(0, 18, 0, 7)
        }
        rootLayout.addView(label)
    }

    private fun createButton(text: String): Button {
        return Button(this).apply {
            this.text = text
            textSize = 14f
            isAllCaps = false
            setPadding(10, 12, 10, 12)

            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 10, 0, 5)
            layoutParams = params
        }
    }

    private fun addAddressField(value: String) {
        val index = addressInputs.size

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val edit = EditText(this).apply {
            setText(value)
            hint = "Адрес доставки ${index + 1}"
            textSize = 15f
            minLines = 1
            maxLines = 3
            setPadding(8, 8, 8, 8)

            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val deleteButton = Button(this).apply {
            text = "✕"
            textSize = 16f
            isAllCaps = false
        }

        edit.addTextChangedListener(SimpleTextWatcher {
            invalidateRoute()
        })

        deleteButton.setOnClickListener {
            saveCurrentAddresses()

            val position = addressInputs.indexOf(edit)
            if (position >= 0) {
                addressInputs.removeAt(position)
                addressContainer.removeViewAt(position)
            }

            refreshAddressHints()
            invalidateRoute()
        }

        row.addView(edit)
        row.addView(deleteButton)

        addressInputs.add(edit)
        addressContainer.addView(row)

        refreshAddressHints()
    }

    private fun refreshAddressHints() {
        addressInputs.forEachIndexed { index, edit ->
            edit.hint = "Адрес доставки ${index + 1}"
        }
    }

    private fun saveCurrentAddresses() {
        // Тексты хранятся непосредственно в EditText.
        // Метод оставлен для сохранения актуального состояния перед удалением.
        addressInputs.forEach { it.text.toString() }
    }

    private fun invalidateRoute() {
        warehousePoint = null
        coordinates.clear()
        routeButton.isEnabled = false
    }

    // ---------------------------------------------------------
    // КАМЕРА
    // ---------------------------------------------------------

    private fun openCamera() {
        if (isBusy) return

        try {
            val cameraDirectory = File(cacheDir, "camera")

            if (!cameraDirectory.exists()) {
                cameraDirectory.mkdirs()
            }

            photoFile = File(
                cameraDirectory,
                "delivery_${System.currentTimeMillis()}.jpg"
            )

            val photoUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                photoFile!!
            )

            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, photoUri)

                clipData = ClipData.newUri(
                    contentResolver,
                    "Фото листа доставки",
                    photoUri
                )

                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }

            cameraButton.isEnabled = false
            startActivityForResult(intent, CAMERA_REQUEST)

        } catch (e: Exception) {
            cameraButton.isEnabled = true
            showMessage("Не удалось открыть камеру: ${e.message}")
        }
    }

    @Deprecated("Используется совместимость со старыми версиями Android")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != CAMERA_REQUEST) return

        cameraButton.isEnabled = true

        if (resultCode != RESULT_OK) {
            showMessage("Фотографирование отменено")
            return
        }

        val file = photoFile

        if (file == null || !file.exists() || file.length() < 1000) {
            showMessage("Фотография не сохранена. Попробуйте ещё раз")
            return
        }

        recognizePhoto(file)
    }

    // ---------------------------------------------------------
    // OCR
    // ---------------------------------------------------------

    private fun recognizePhoto(file: File) {
        if (isBusy) return

        isBusy = true
        cameraButton.isEnabled = false
        geocodeButton.isEnabled = false
        routeButton.isEnabled = false

        statusText.text = "Обработка фотографии..."

        executor.execute {
            var bitmap: Bitmap? = null
            var gray: Bitmap? = null
            var binary: Bitmap? = null

            try {
                val tessData = prepareTessdata()

                bitmap = loadCorrectedBitmap(file)
                    ?: throw Exception("Не удалось прочитать фотографию")

                gray = createGrayBitmap(bitmap)
                binary = createBinaryBitmap(gray)

                val recognized = recognizeVariants(
                    listOf(bitmap, gray, binary),
                    tessData
                )

                val extracted = extractAddresses(recognized)

                runOnUiThread {
                    if (extracted.isEmpty()) {
                        statusText.text =
                            "Не удалось найти адреса. Попробуйте другое фото"
                    } else {
                        addressContainer.removeAllViews()
                        addressInputs.clear()

                        extracted.take(MAX_ADDRESSES).forEach {
                            addAddressField(it)
                        }

                        invalidateRoute()

                        statusText.text =
                            "Распознано адресов: ${extracted.size}. Проверьте адреса"
                    }

                    cameraButton.isEnabled = true
                    geocodeButton.isEnabled = true
                    isBusy = false
                }

            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Ошибка распознавания: ${e.message}"
                    cameraButton.isEnabled = true
                    geocodeButton.isEnabled = true
                    isBusy = false
                }
            } finally {
                bitmap?.recycle()
                gray?.recycle()
                binary?.recycle()
            }
        }
    }

    private fun prepareTessdata(): String {
        val baseDir = File(filesDir, "tesseract")
        val tessdataDir = File(baseDir, "tessdata")
        val trainedFile = File(tessdataDir, "rus.traineddata")

        if (!tessdataDir.exists()) {
            tessdataDir.mkdirs()
        }

        if (!trainedFile.exists() || trainedFile.length() < 1000000) {
            val connection = URL(TESSDATA_URL)
                .openConnection() as HttpURLConnection

            try {
                connection.connectTimeout = 20000
                connection.readTimeout = 60000
                connection.requestMethod = "GET"

                if (connection.responseCode != 200) {
                    throw Exception(
                        "Не удалось загрузить языковой файл OCR"
                    )
                }

                connection.inputStream.use { input ->
                    FileOutputStream(trainedFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } finally {
                connection.disconnect()
            }
        }

        return baseDir.absolutePath
    }

    private fun loadCorrectedBitmap(file: File): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        BitmapFactory.decodeFile(file.absolutePath, options)

        var sample = 1
        val maxDimension = max(options.outWidth, options.outHeight)

        while (maxDimension / sample > 2600) {
            sample *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val original = BitmapFactory.decodeFile(
            file.absolutePath,
            decodeOptions
        ) ?: return null

        val rotation = try {
            val exif = ExifInterface(file.absolutePath)
            when (
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (_: Exception) {
            0f
        }

        if (rotation == 0f) return original

        val matrix = Matrix().apply {
            postRotate(rotation)
        }

        val corrected = Bitmap.createBitmap(
            original,
            0,
            0,
            original.width,
            original.height,
            matrix,
            true
        )

        if (corrected !== original) {
            original.recycle()
        }

        return corrected
    }

    private fun createGrayBitmap(source: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888
        )

        val pixels = IntArray(source.width * source.height)
        source.getPixels(
            pixels,
            0,
            source.width,
            0,
            0,
            source.width,
            source.height
        )

        for (i in pixels.indices) {
            val color = pixels[i]

            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)

            var grayValue = (
                0.299 * r +
                    0.587 * g +
                    0.114 * b
                ).toInt()

            grayValue = ((grayValue - 128) * 1.35 + 128)
                .toInt()
                .coerceIn(0, 255)

            pixels[i] = Color.rgb(
                grayValue,
                grayValue,
                grayValue
            )
        }

        result.setPixels(
            pixels,
            0,
            source.width,
            0,
            0,
            source.width,
            source.height
        )

        return result
    }

    private fun createBinaryBitmap(source: Bitmap): Bitmap {
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

        for (color in pixels) {
            histogram[Color.red(color)]++
        }

        val total = pixels.size
        var sum = 0L

        for (i in 0..255) {
            sum += i.toLong() * histogram[i]
        }

        var sumBackground = 0L
        var weightBackground = 0
        var bestVariance = -1.0
        var threshold = 150

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
                    weightForeground *
                    (meanBackground - meanForeground) *
                    (meanBackground - meanForeground)

            if (variance > bestVariance) {
                bestVariance = variance
                threshold = i
            }
        }

        for (i in pixels.indices) {
            val value = Color.red(pixels[i])
            pixels[i] = if (value > threshold) {
                Color.WHITE
            } else {
                Color.BLACK
            }
        }

        return Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        ).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun recognizeVariants(
        variants: List<Bitmap>,
        dataPath: String
    ): String {
        val tess = TessBaseAPI()

        try {
            if (!tess.init(dataPath, "rus")) {
                throw Exception("Не удалось запустить Tesseract")
            }

            tess.setVariable("preserve_interword_spaces", "1")

            var bestText = ""
            var bestScore = -1

            val modes = listOf(3, 6, 4)

            for (bitmap in variants) {
                for (mode in modes) {
                    tess.setPageSegMode(mode)
                    tess.setImage(bitmap)

                    val text = tess.utF8Text ?: ""
                    val confidence = tess.meanConfidence()

                    val score =
                        text.length +
                            confidence * 3 +
                            text.count { it.isDigit() } * 2

                    if (score > bestScore) {
                        bestScore = score
                        bestText = text
                    }
                }
            }

            return bestText

        } finally {
            tess.end()
        }
    }

    private fun extractAddresses(text: String): List<String> {
        val normalized = text
            .replace('\u00A0', ' ')
            .replace('|', ' ')
            .replace('—', '-')
            .replace('–', '-')
            .replace(Regex("[ \\t]+"), " ")

        val lines = normalized
            .lines()
            .map {
                it.trim()
                    .replace(Regex("^[^А-Яа-яA-Za-z0-9]+"), "")
                    .replace(Regex("[^А-Яа-яA-Za-z0-9.,/ №-]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            .filter { it.length >= 5 }

        val candidates = mutableListOf<String>()

        for (i in lines.indices) {
            val current = lines[i]

            candidates.add(current)

            if (i + 1 < lines.size) {
                candidates.add("$current ${lines[i + 1]}")
            }
        }

        val result = linkedSetOf<String>()

        for (candidate in candidates) {
            val value = cleanAddress(candidate)

            val hasNumber = Regex(
                """(?:д\.?\s*)?\d{1,4}(?:[/\-]\d{1,4})?[а-яА-Яa-zA-Z]?"""
            ).containsMatchIn(value)

            val hasStreet = Regex(
                """(ул\.?|улица|проспект|пр-т|пр\.?|проезд|пер\.?|переулок|бульвар|шоссе|набережная|микрорайон|мкр\.?|дом|д\.)"""
            ).containsMatchIn(value)

            val hasCity = CITIES.any {
                value.contains(it, ignoreCase = true)
            }

            if (hasNumber && (hasStreet || hasCity) && value.length >= 8) {
                result.add(value)
            }
        }

        return result.take(MAX_ADDRESSES)
    }

    private fun cleanAddress(source: String): String {
        var value = source
            .replace(Regex("""\b[Оо]бл\.?\b"""), "")
            .replace(Regex("""\b[Рр]оссия\b"""), "")
            .replace(Regex("""\b[Рр]еспублика Татарстан\b"""), "")
            .replace(Regex("""\b[Цц]ентральный район\b"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', ',', '.', '-', ':', ';')

        value = value.replace(
            Regex("""(\d)\s*[,;]\s*(\d)"""),
            "$1/$2"
        )

        return value
    }

    // ---------------------------------------------------------
    // ГЕОКОДИРОВАНИЕ 2ГИС
    // ---------------------------------------------------------

    private fun geocodeAddresses() {
        if (isBusy) return

        val warehouse = warehouseInput.text.toString().trim()

        val addresses = addressInputs
            .map { it.text.toString().trim() }
            .filter { it.isNotBlank() }

        if (warehouse.isBlank()) {
            showMessage("Укажите адрес склада")
            return
        }

        if (addresses.isEmpty()) {
            showMessage("Добавьте хотя бы один адрес доставки")
            return
        }

        if (addresses.size > MAX_ADDRESSES) {
            showMessage("Максимум $MAX_ADDRESSES адресов")
            return
        }

        val apiKey = BuildConfig.DGIS_API_KEY

        if (apiKey.isBlank()) {
            showMessage("Не задан ключ API 2ГИС")
            return
        }

        isBusy = true
        routeButton.isEnabled = false
        geocodeButton.isEnabled = false
        cameraButton.isEnabled = false

        statusText.text = "Определение координат..."

        executor.execute {
            var foundWarehouse: GeoPoint? = null
            val foundAddresses = mutableListOf<GeoPoint>()
            var errorMessage: String? = null

            try {
                foundWarehouse = geocodeAddress(
                    warehouse,
                    apiKey
                ) ?: throw Exception(
                    "Не удалось определить координаты склада: $warehouse"
                )

                for ((index, address) in addresses.withIndex()) {
                    runOnUiThread {
                        statusText.text =
                            "Определение адреса ${index + 1} из ${addresses.size}..."
                    }

                    val point = geocodeAddress(address, apiKey)

                    if (point == null) {
                        throw Exception(
                            "Не найден адрес ${index + 1}: $address"
                        )
                    }

                    foundAddresses.add(point)
                }

            } catch (e: Exception) {
                errorMessage = e.message ?: "Ошибка геокодирования"
            }

            val warehouseResult = foundWarehouse
            val successful = errorMessage == null &&
                warehouseResult != null &&
                foundAddresses.size == addresses.size

            runOnUiThread {
                if (successful) {
                    warehousePoint = warehouseResult
                    coordinates.clear()
                    coordinates.addAll(foundAddresses)

                    routeButton.isEnabled = true

                    statusText.text =
                        "Координаты определены. Адресов: ${coordinates.size}"
                } else {
                    warehousePoint = null
                    coordinates.clear()
                    routeButton.isEnabled = false

                    statusText.text = errorMessage ?: "Не удалось определить координаты"
                }

                isBusy = false
                geocodeButton.isEnabled = true
                cameraButton.isEnabled = true
            }
        }
    }

    private fun geocodeAddress(
        originalAddress: String,
        apiKey: String
    ): GeoPoint? {
        val query = prepareGeocodeQuery(originalAddress)

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")

        val requestUrl =
            "https://catalog.api.2gis.com/3.0/items/geocode" +
                "?q=$encodedQuery" +
                "&fields=items.point,items.geometry.centroid" +
                "&page_size=10" +
                "&key=$encodedKey"

        val connection = URL(requestUrl)
            .openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty(
                "Accept",
                "application/json"
            )

            val responseCode = connection.responseCode

            val response = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use {
                    it.readText()
                }
            } else {
                connection.errorStream?.bufferedReader()?.use {
                    it.readText()
                } ?: ""
            }

            if (responseCode !in 200..299) {
                return null
            }

            val json = JSONObject(response)

            val meta = json.optJSONObject("meta")
            val metaCode = meta?.optInt("code", 200) ?: 200

            if (metaCode !in 200..299) {
                return null
            }

            val items = json.optJSONArray("result")
                ?: json.optJSONObject("result")
                    ?.optJSONArray("items")
                ?: json.optJSONArray("items")
                ?: return null

            var bestPoint: GeoPoint? = null
            var bestScore = Int.MIN_VALUE

            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue

                val point = readCoordinates(item) ?: continue

                val fullName = listOf(
                    item.optString("full_name", ""),
                    item.optString("full_address_name", ""),
                    item.optString("address_name", ""),
                    item.optString("name", "")
                ).filter { it.isNotBlank() }.joinToString(" ")

                val score = calculateAddressScore(
                    originalAddress,
                    fullName
                )

                if (score > bestScore) {
                    bestScore = score

                    bestPoint = GeoPoint(
                        lat = point.first,
                        lon = point.second,
                        objectId = item.optString("id")
                            .takeIf { it.isNotBlank() && it != "null" },
                        address = fullName
                    )
                }
            }

            return bestPoint

        } finally {
            connection.disconnect()
        }
    }

    private fun prepareGeocodeQuery(address: String): String {
        var value = cleanAddress(address)

        value = value
            .replace(Regex("""^\d{6}\s*[, ]*"""), "")
            .replace(Regex("""\bРоссия\b""", RegexOption.IGNORE_CASE), "")
            .replace(
                Regex("""\bРеспублика Татарстан\b""", RegexOption.IGNORE_CASE),
                ""
            )
            .trim(' ', ',', '.')

        val hasCity = CITIES.any {
            value.contains(it, ignoreCase = true)
        }

        if (!hasCity) {
            value += ", Набережные Челны"
        }

        return value
    }

    private fun readCoordinates(
        item: JSONObject
    ): Pair<Double, Double>? {
        val point = item.optJSONObject("point")

        if (point != null) {
            val lat = point.optDouble("lat", Double.NaN)
            val lon = point.optDouble("lon", Double.NaN)

            if (lat.isFinite() && lon.isFinite()) {
                return Pair(lat, lon)
            }
        }

        val geometry = item.optJSONObject("geometry")

        if (geometry != null) {
            val centroid = geometry.opt("centroid")

            if (centroid is JSONObject) {
                val lat = centroid.optDouble("lat", Double.NaN)
                val lon = centroid.optDouble("lon", Double.NaN)

                if (lat.isFinite() && lon.isFinite()) {
                    return Pair(lat, lon)
                }
            }

            if (centroid is String) {
                val match = Regex(
                    """POINT\s*\(\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s*\)""",
                    RegexOption.IGNORE_CASE
                ).find(centroid)

                if (match != null) {
                    val lon = match.groupValues[1].toDoubleOrNull()
                    val lat = match.groupValues[2].toDoubleOrNull()

                    if (lat != null && lon != null) {
                        return Pair(lat, lon)
                    }
                }
            }
        }

        return null
    }

    private fun calculateAddressScore(
        requested: String,
        returned: String
    ): Int {
        val request = requested.lowercase(Locale.ROOT)
        val result = returned.lowercase(Locale.ROOT)

        var score = 0

        val requestTokens = request
            .split(Regex("""[\s,./№-]+"""))
            .filter { it.length >= 3 }

        for (token in requestTokens) {
            if (result.contains(token)) {
                score += 3
            }
        }

        val houseNumber = Regex(
            """(?:д\.?\s*)?(\d{1,4}(?:[/\-]\d{1,4})?[а-яa-z]?)""",
            RegexOption.IGNORE_CASE
        ).find(request)?.groupValues?.get(1)

        if (houseNumber != null) {
            if (result.contains(houseNumber)) {
                score += 15
            } else {
                score -= 5
            }
        }

        val city = CITIES.firstOrNull {
            request.contains(it.lowercase(Locale.ROOT))
        }

        if (city != null && result.contains(city.lowercase(Locale.ROOT))) {
            score += 10
        }

        return score
    }

    // ---------------------------------------------------------
    // ПОСТРОЕНИЕ МАРШРУТА
    // ---------------------------------------------------------

    private fun openRouteIn2Gis() {
        val warehouse = warehousePoint

        if (warehouse == null || coordinates.isEmpty()) {
            showMessage("Сначала определите координаты")
            return
        }

        if (coordinates.size > MAX_ADDRESSES) {
            showMessage("Слишком много адресов")
            return
        }

        val points = mutableListOf<GeoPoint>()

        points.add(warehouse)
        points.addAll(coordinates)
        points.add(warehouse)

        val pointsPath = points.joinToString("|") { point ->
            val coordinatesText = String.format(
                Locale.US,
                "%.6f,%.6f",
                point.lon,
                point.lat
            )

            if (!point.objectId.isNullOrBlank()) {
                "$coordinatesText;${point.objectId}"
            } else {
                coordinatesText
            }
        }

        val routeUrl =
            "https://2gis.ru/naberezhnye-chelny/directions/" +
                "tab/car/points/$pointsPath"

        try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(routeUrl)
            )

            intent.setPackage("ru.dublgis.dgismobile")

            try {
                startActivity(intent)
            } catch (_: Exception) {
                val fallback = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(routeUrl)
                )
                startActivity(fallback)
            }

        } catch (e: Exception) {
            showMessage("Не удалось открыть 2ГИС: ${e.message}")
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }

    private class SimpleTextWatcher(
        private val callback: () -> Unit
    ) : android.text.TextWatcher {

        override fun beforeTextChanged(
            s: CharSequence?,
            start: Int,
            count: Int,
            after: Int
        ) = Unit

        override fun onTextChanged(
            s: CharSequence?,
            start: Int,
            before: Int,
            count: Int
        ) {
            callback()
        }

        override fun afterTextChanged(
            s: android.text.Editable?
        ) = Unit
    }
}
