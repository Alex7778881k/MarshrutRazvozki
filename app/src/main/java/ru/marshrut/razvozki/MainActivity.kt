
package ru.marshrut.razvozki

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var addressInput: EditText
    private lateinit var addressList: LinearLayout

    private val addresses = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Маршрут развозки"
            textSize = 26f
            gravity = Gravity.CENTER
        }

        root.addView(title)

        val description = TextView(this).apply {
            text = "Добавьте адреса доставки"
            textSize = 16f
            setPadding(0, 24, 0, 16)
        }

        root.addView(description)

        addressInput = EditText(this).apply {
            hint = "Введите адрес"
            textSize = 16f
        }

        root.addView(addressInput)

        val addButton = Button(this).apply {
            text = "Добавить адрес"

            setOnClickListener {
                val address = addressInput.text.toString().trim()

                if (address.isNotEmpty()) {
                    addresses.add(address)
                    addressInput.text.clear()
                    updateAddressList()
                } else {
                    showMessage("Введите адрес")
                }
            }
        }

        root.addView(addButton)

        val photoButton = Button(this).apply {
            text = "Выбрать фотографию листа"

            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                }

                try {
                    startActivityForResult(intent, 100)
                } catch (e: Exception) {
                    showMessage("Не удалось открыть галерею")
                }
            }
        }

        root.addView(photoButton)

        val routeButton = Button(this).apply {
            text = "Открыть первый адрес в 2ГИС"

            setOnClickListener {
                val address = addresses.firstOrNull()

                if (address == null) {
                    showMessage("Сначала добавьте адрес")
                } else {
                    val url = "https://2gis.ru/search/" +
                        Uri.encode(address)

                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(url)
                    )

                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        showMessage("Не удалось открыть 2ГИС")
                    }
                }
            }
        }

        root.addView(routeButton)

        val listTitle = TextView(this).apply {
            text = "Список адресов:"
            textSize = 19f
            setPadding(0, 24, 0, 12)
        }

        root.addView(listTitle)

        addressList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(addressList)

        val info = TextView(this).apply {
            text = "Распознавание адресов и оптимизация маршрута будут добавлены далее."
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }

        root.addView(info)

        val scrollView = ScrollView(this).apply {
            addView(root)
        }

        setContentView(scrollView)
    }

    private fun updateAddressList() {
        addressList.removeAllViews()

        addresses.forEachIndexed { index, address ->

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val addressText = TextView(this).apply {
                text = "${index + 1}. $address"
                textSize = 16f
            }

            row.addView(
                addressText,
                LinearLayout.LayoutParams(0, -2, 1f)
            )

            val deleteButton = Button(this).apply {
                text = "Удалить"

                setOnClickListener {
                    addresses.removeAt(index)
                    updateAddressList()
                }
            }

            row.addView(deleteButton)
            addressList.addView(row)
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 100 &&
            resultCode == RESULT_OK &&
            data?.data != null
        ) {
            showMessage("Фотография выбрана")
        }
    }
}
