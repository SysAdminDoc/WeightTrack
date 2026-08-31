package com.weighttrack.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weighttrack.R
import com.weighttrack.core.math.UnitConverter
import com.weighttrack.core.model.WeightUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Digit-string entry, the way a till or a calculator app takes an amount.
 *
 * Typing 8, 2, 4 gives 82.4. There is no decimal point to hit and no cursor to place, which
 * is what makes logging a weight a three-tap job rather than a form to fill in.
 */
object KeypadValue {

    const val MAX_DIGITS = 5

    /** Stones are entered as total pounds; nobody wants two fields to fill in on a keypad. */
    fun entryUnit(unit: WeightUnit): WeightUnit =
        if (unit == WeightUnit.ST_LB) WeightUnit.LB else unit

    fun append(digits: String, digit: Char): String {
        if (!digit.isDigit()) return digits
        val trimmed = digits.trimStart('0')
        if (trimmed.length >= MAX_DIGITS) return digits
        val next = trimmed + digit
        return next
    }

    fun backspace(digits: String): String = digits.dropLast(1)

    fun displayValue(digits: String): Double =
        if (digits.isEmpty()) 0.0 else digits.toDouble() / 10.0

    fun toGrams(digits: String, unit: WeightUnit): Int =
        UnitConverter.displayToGrams(displayValue(digits), entryUnit(unit))

    fun fromGrams(grams: Int, unit: WeightUnit): String {
        val value = UnitConverter.gramsToDisplay(grams, entryUnit(unit))
        val tenths = (value * 10).roundToInt().coerceAtLeast(0)
        return tenths.toString()
    }

    /** What the big number on screen reads while someone is typing. */
    fun formatted(digits: String, unit: WeightUnit): String {
        val value = displayValue(digits)
        return when (unit) {
            WeightUnit.ST_LB -> {
                val (stones, pounds) = UnitConverter.gramsToStoneLb(toGrams(digits, unit))
                String.format(Locale.getDefault(), "%d st %.1f", stones, pounds)
            }
            else -> String.format(Locale.getDefault(), "%.1f", value)
        }
    }

    fun isValid(digits: String): Boolean = digits.isNotEmpty() && displayValue(digits) > 0.0
}

@Composable
fun WeightKeypad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("123", "456", "789").forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { digit ->
                    KeypadKey(
                        label = digit.toString(),
                        modifier = Modifier.weight(1f),
                        onClick = { onDigit(digit) },
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            KeypadKey(
                label = stringResource(R.string.keypad_clear),
                modifier = Modifier.weight(1f),
                onClick = onClear,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeypadKey(label = "0", modifier = Modifier.weight(1f), onClick = { onDigit('0') })
            KeypadKey(
                label = null,
                icon = Icons.AutoMirrored.Filled.Backspace,
                contentDescription = stringResource(R.string.common_backspace),
                modifier = Modifier.weight(1f),
                onClick = onBackspace,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun KeypadKey(
    label: String?,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    contentDescription: String? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(64.dp)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(Modifier.padding(4.dp), contentAlignment = Alignment.Center) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor)
            } else if (label != null) {
                Text(
                    text = label,
                    fontSize = 24.sp,
                    color = contentColor,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        }
    }
}
