package com.v2plus.app.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.v2plus.app.R
import com.v2plus.app.handler.CustomizationManager.opaque

fun Activity.showColorPickerSheet(
    title: String,
    currentColor: Int,
    onPick: (Int) -> Unit
) {
    val dp = resources.displayMetrics.density
    val dialog = BottomSheetDialog(this, com.google.android.material.R.style.ThemeOverlay_Material3_BottomSheetDialog)
    val content = LayoutInflater.from(this).inflate(R.layout.dialog_color_picker, null)
    dialog.setContentView(content)

    val hsv = FloatArray(3)
    Color.colorToHSV(opaque(currentColor), hsv)

    val titleView = content.findViewById<TextView>(R.id.picker_title)
    val svView = content.findViewById<SaturationValueView>(R.id.picker_sv)
    val hueView = content.findViewById<HueBarView>(R.id.picker_hue)
    val preview = content.findViewById<View>(R.id.picker_preview)
    val hexInput = content.findViewById<EditText>(R.id.picker_hex)
    val swatches = content.findViewById<GridLayout>(R.id.picker_swatches)
    val done = content.findViewById<MaterialButton>(R.id.picker_done)

    titleView.text = title
    svView.hue = hsv[0]
    svView.saturation = hsv[1]
    svView.value = hsv[2]
    hueView.hue = hsv[0]

    var selected = currentColor and 0xFFFFFF
    var hexEditing = false

    fun colorFromHsv(): Int {
        return Color.HSVToColor(floatArrayOf(svView.hue, svView.saturation, svView.value)) and 0xFFFFFF
    }

    fun paintPreview(color: Int) {
        preview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12 * dp
            setColor(opaque(color))
            setStroke((1 * dp).toInt(), 0x33FFFFFF)
        }
        if (!hexEditing) {
            hexInput.setText(String.format("%06X", color and 0xFFFFFF))
            hexInput.setSelection(hexInput.text.length)
        }
        done.backgroundTintList = ColorStateList.valueOf(opaque(color))
        val luminance = 0.299 * Color.red(opaque(color)) +
            0.587 * Color.green(opaque(color)) +
            0.114 * Color.blue(opaque(color))
        done.setTextColor(if (luminance > 160) Color.parseColor("#0B1220") else Color.WHITE)
    }

    fun commit(color: Int, fromUser: Boolean) {
        selected = color and 0xFFFFFF
        paintPreview(selected)
        if (fromUser) onPick(selected)
    }

    paintPreview(selected)

    svView.onChanged = { _, _, committed ->
        hueView.hue = svView.hue
        commit(colorFromHsv(), committed)
    }
    hueView.onHueChanged = { hue, committed ->
        svView.hue = hue
        commit(colorFromHsv(), committed)
    }

    hexInput.filters = arrayOf(InputFilter.LengthFilter(6), InputFilter { source, _, _, _, _, _ ->
        source.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    })
    hexInput.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            val parsed = s?.toString()?.trim().orEmpty()
            if (parsed.length != 6) return
            val color = parsed.toIntOrNull(16) ?: return
            if (color == selected) return
            hexEditing = true
            Color.colorToHSV(opaque(color), hsv)
            svView.hue = hsv[0]
            svView.saturation = hsv[1]
            svView.value = hsv[2]
            hueView.hue = hsv[0]
            commit(color, true)
            hexEditing = false
        }
    })

    val suggested = intArrayOf(
        0x2F81F7, 0x7C4DFF, 0x00C853, 0xE53935, 0xFF6D00, 0xE91E63,
        0x00BCD4, 0xFFAB00, 0x3F51B5, 0x009688, 0xFF4081, 0x9E9E9E,
        0x050A14, 0x0F172A, 0x1E2A44, 0x111C33, 0x000000, 0xE8EAF6
    )
    val gap = (8 * dp).toInt()
    val cell = (36 * dp).toInt()
    suggested.forEachIndexed { index, color ->
        val swatch = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 10 * dp
                setColor(opaque(color))
                setStroke((1 * dp).toInt(), 0x33FFFFFF)
            }
            setOnClickListener {
                Color.colorToHSV(opaque(color), hsv)
                svView.hue = hsv[0]
                svView.saturation = hsv[1]
                svView.value = hsv[2]
                hueView.hue = hsv[0]
                commit(color, true)
            }
        }
        val params = GridLayout.LayoutParams().apply {
            width = 0
            height = cell
            columnSpec = GridLayout.spec(index % 6, 1f)
            rowSpec = GridLayout.spec(index / 6)
            setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
        }
        swatches.addView(swatch, params)
    }

    done.setOnClickListener {
        onPick(selected)
        dialog.dismiss()
    }

    dialog.setOnShowListener {
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.isFitToContents = true
    }
    dialog.show()
}
