package io.github.mangi.eta.ui.screens.stats

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun UsageDateTimePickerDialog(
    show: Boolean,
    title: String,
    current: UsageTimeBound,
    endOfBound: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (UsageTimeBound) -> Unit,
    onClear: () -> Unit,
) {
    if (!show) return
    val initial = remember(current, endOfBound) { current.toDateTime(endOfBound) }
    var picked by remember(initial) { mutableStateOf(initial) }
    val textColor = MiuixTheme.colorScheme.onSurface.toArgb()
    val view = LocalView.current
    WindowDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { context ->
                    SpinnerDateTimePickerView(context, picked, textColor) { picked = it }
                },
                update = { picker ->
                    picker.setTextColor(textColor)
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.action_clear),
                    onClick = {
                        TouchHaptics.click(view)
                        onClear()
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = {
                        TouchHaptics.click(view)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.stats_model_filter_set),
                    onClick = {
                        TouchHaptics.click(view)
                        onConfirm(UsageTimeBound.from(picked))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

private class SpinnerDateTimePickerView(
    context: Context,
    initial: LocalDateTime,
    textColor: Int,
    private val onValueChange: (LocalDateTime) -> Unit,
) : LinearLayout(context) {
    private val yearPicker: NumberPicker
    private val monthPicker: NumberPicker
    private val dayPicker: NumberPicker
    private val hourPicker: NumberPicker
    private val minutePicker: NumberPicker
    private val colonView: TextView
    private val minYear = minOf(2020, initial.year)
    private val maxYear = maxOf(LocalDate.now().year + 1, initial.year)
    private val monthLabels = monthDisplayLabels()
    private val hourLabels = (0..23).map { it.toString().padStart(2, '0') }.toTypedArray()
    private val minuteLabels = (0..59).map { it.toString().padStart(2, '0') }.toTypedArray()
    private var publishing = false

    init {
        orientation = VERTICAL
        val density = resources.displayMetrics.density
        val pickerHeight = (148 * density).toInt()
        val dateRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        yearPicker = numberPicker(
            min = minYear,
            max = maxYear,
            value = initial.year.coerceIn(minYear, maxYear),
            wrap = false,
        )
        monthPicker = numberPicker(
            min = 1,
            max = 12,
            value = initial.monthValue,
            wrap = true,
            labels = monthLabels,
        )
        dayPicker = numberPicker(
            min = 1,
            max = YearMonth.of(initial.year, initial.monthValue).lengthOfMonth(),
            value = initial.dayOfMonth,
            wrap = true,
        )
        dateRow.addPicker(yearPicker, pickerHeight)
        dateRow.addPicker(monthPicker, pickerHeight)
        dateRow.addPicker(dayPicker, pickerHeight)
        addView(dateRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val timeRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        hourPicker = numberPicker(
            min = 0,
            max = 23,
            value = initial.hour,
            wrap = true,
            labels = hourLabels,
        )
        minutePicker = numberPicker(
            min = 0,
            max = 59,
            value = initial.minute,
            wrap = true,
            labels = minuteLabels,
        )
        colonView = TextView(context).apply {
            text = ":"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
        }
        timeRow.addView(android.widget.Space(context), LayoutParams(0, pickerHeight, 1f))
        timeRow.addPicker(hourPicker, pickerHeight)
        timeRow.addView(
            colonView,
            LayoutParams(LayoutParams.WRAP_CONTENT, pickerHeight),
        )
        timeRow.addPicker(minutePicker, pickerHeight)
        timeRow.addView(android.widget.Space(context), LayoutParams(0, pickerHeight, 1f))
        addView(timeRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val listener = NumberPicker.OnValueChangeListener { _, _, _ -> publish() }
        yearPicker.setOnValueChangedListener(listener)
        monthPicker.setOnValueChangedListener(listener)
        dayPicker.setOnValueChangedListener(listener)
        hourPicker.setOnValueChangedListener(listener)
        minutePicker.setOnValueChangedListener(listener)
        setTextColor(textColor)
    }

    fun setTextColor(color: Int) {
        yearPicker.setTextColor(color)
        monthPicker.setTextColor(color)
        dayPicker.setTextColor(color)
        hourPicker.setTextColor(color)
        minutePicker.setTextColor(color)
        colonView.setTextColor(color)
    }

    private fun publish() {
        if (publishing) return
        publishing = true
        try {
            val year = yearPicker.value
            val month = monthPicker.value
            val maxDay = YearMonth.of(year, month).lengthOfMonth()
            if (dayPicker.maxValue != maxDay) {
                if (dayPicker.value > maxDay) dayPicker.value = maxDay
                dayPicker.maxValue = maxDay
            }
            val day = dayPicker.value.coerceIn(1, maxDay)
            onValueChange(
                LocalDateTime.of(year, month, day, hourPicker.value, minutePicker.value),
            )
        } finally {
            publishing = false
        }
    }

    private fun LinearLayout.addPicker(picker: NumberPicker, height: Int) {
        addView(picker, LayoutParams(0, height, 1f))
    }

    private fun numberPicker(
        min: Int,
        max: Int,
        value: Int,
        wrap: Boolean,
        labels: Array<String>? = null,
    ): NumberPicker {
        return NumberPicker(context).apply {
            descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
            wrapSelectorWheel = wrap
            displayedValues = null
            minValue = 0
            maxValue = max
            minValue = min
            if (labels != null) displayedValues = labels
            this.value = value.coerceIn(min, max)
            wrapSelectorWheel = wrap
        }
    }
}


private fun monthDisplayLabels(locale: Locale = Locale.getDefault()): Array<String> {
    return Array(12) { index ->
        val month = Month.of(index + 1)
        if (locale.language == "zh") {
            "${index + 1}月"
        } else {
            month.getDisplayName(TextStyle.SHORT, locale)
        }
    }
}
