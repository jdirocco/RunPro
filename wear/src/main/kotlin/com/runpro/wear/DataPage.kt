package com.runpro.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.runpro.core.model.DisplayUnits
import com.runpro.core.recording.RunState
import kotlin.math.abs
import kotlin.math.min

/** One complete page fits together; vertical touch scrolling reaches the existing run controls. */
@Composable
internal fun DataPage(state: RunState, screens: DataScreens) {
    var page by rememberSaveable(state.run?.id?.value) { mutableIntStateOf(0) }
    val selected = page.coerceIn(screens.pages.indices)
    val focus = remember { FocusRequester() }
    val rotary = remember { RotaryPages() }
    val change: (Int) -> Unit = { direction ->
        page = Math.floorMod(selected + direction, screens.pages.size)
    }
    val height = (LocalConfiguration.current.screenHeightDp * PAGE_HEIGHT_FRACTION).coerceAtMost(
        MAX_PAGE_HEIGHT
    ).dp
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .background(MaterialTheme.colorScheme.background)
            .testTag("data-page")
            .onRotaryScrollEvent {
                rotary.direction(it.verticalScrollPixels, it.uptimeMillis)?.let(change)
                true
            }
            .focusRequester(focus)
            .focusable()
            .pointerInput(selected, screens) {
                var drag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { event, amount ->
                        event.consume()
                        drag += amount
                    },
                    onDragEnd = {
                        if (abs(drag) >=
                            SWIPE_DISTANCE.dp.toPx()
                        ) {
                            change(if (drag < 0) 1 else -1)
                        }
                    }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DataGrid(screens.pages[selected], state, Modifier.weight(1f))
        PageNavigation(selected, screens.pages.size, change)
    }
}

@Composable
private fun PageNavigation(page: Int, total: Int, change: (Int) -> Unit) {
    val previousLabel = stringResource(R.string.data_previous_page)
    val nextLabel = stringResource(R.string.data_next_page)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        TextButton(
            onClick = {
                change(-1)
            },
            modifier = Modifier.testTag("previous-page").semantics {
                contentDescription =
                    previousLabel
            }
        ) { Text("‹") }
        Text(
            stringResource(R.string.data_page, page + 1, total),
            fontSize = 11.sp
        )
        TextButton(
            onClick = {
                change(1)
            },
            modifier = Modifier.testTag("next-page").semantics {
                contentDescription = nextLabel
            }
        ) { Text("›") }
    }
}

@Composable
private fun DataGrid(fields: List<DataField>, state: RunState, modifier: Modifier) {
    val rows = when (fields.size) {
        1, 2 -> fields.map { listOf(it) }
        3, 5 -> listOf(fields.take(1)) + fields.drop(1).chunked(2)
        else -> fields.chunked(2)
    }
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        rows.forEach { row ->
            Row(
                Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row.forEach { field ->
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val value = fieldValue(field, state)
                        val baseSize = when {
                            fields.size == 1 -> 42
                            fields.size == 5 -> 22
                            row.size == 1 -> 30
                            else -> 24
                        }
                        val valueSize = min(
                            baseSize,
                            (if (row.size == 1) 240 else 120) / value.length.coerceAtLeast(1)
                        )
                        Text(
                            value,
                            fontSize = valueSize.sp,
                            lineHeight = valueSize.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                        val label = fieldLabel(field, state)
                        val labelSize = if (row.size > 1 && label.length > 16) 8 else 10
                        Text(
                            label,
                            fontSize = labelSize.sp,
                            lineHeight = 11.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun fieldLabel(field: DataField, state: RunState): String {
    val imperial = state.workoutSettings.runSettings.units == DisplayUnits.IMPERIAL
    val unit = when (field) {
        DataField.DISTANCE -> if (imperial) R.string.unit_mi else R.string.unit_km

        DataField.CURRENT_PACE, DataField.AVERAGE_PACE, DataField.LAP_PACE ->
            if (imperial) R.string.unit_pace_mi else R.string.unit_pace

        DataField.HEART_RATE -> R.string.unit_bpm

        DataField.CADENCE -> R.string.field_spm

        DataField.POWER -> R.string.field_watts

        DataField.ELEVATION -> if (imperial) R.string.field_feet else R.string.field_meters

        else -> null
    }
    val label = stringResource(field.label)
    return if (unit == null) label else "$label · ${stringResource(unit)}"
}

/** A detent changes one page; ignore the rest of its short burst, but allow immediate reversal. */
internal class RotaryPages {
    private var lastTime = Long.MIN_VALUE
    private var lastDirection = 0

    fun direction(pixels: Float, time: Long): Int? {
        if (pixels == 0f || !pixels.isFinite()) return null
        val direction = if (pixels > 0) 1 else -1
        return if (direction != lastDirection || time - lastTime >= ROTARY_INTERVAL_MS) {
            lastTime = time
            lastDirection = direction
            direction
        } else {
            null
        }
    }
}

private const val PAGE_HEIGHT_FRACTION = 0.85f
private const val MAX_PAGE_HEIGHT = 280f
private const val SWIPE_DISTANCE = 24
private const val ROTARY_INTERVAL_MS = 250
