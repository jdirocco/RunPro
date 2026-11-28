package com.runpro.wear

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold

/** Watch-only editor: every completed selection is saved by the host, outside the recording path. */
@Composable
internal fun DataScreenEditor(
    screens: DataScreens,
    update: (DataScreens) -> Unit,
    close: () -> Unit
) {
    var page by rememberSaveable { mutableStateOf<Int?>(null) }
    var slot by rememberSaveable { mutableStateOf<Int?>(null) }
    val selectedPage = page
    val selectedSlot = slot
    val back = {
        when {
            slot != null -> slot = null
            page != null -> page = null
            else -> close()
        }
    }
    BackHandler(onBack = back)
    MaterialTheme {
        ScreenScaffold {
            ScalingLazyColumn {
                item { Paragraph(stringResource(R.string.data_screens_title)) }
                when {
                    selectedPage == null -> pageList(screens, update) { page = it }

                    selectedSlot != null -> DataField.entries.forEach { field ->
                        item {
                            WideButton(stringResource(field.label)) {
                                update(screens.replaceField(selectedPage, selectedSlot, field))
                                slot = null
                            }
                        }
                    }

                    else -> {
                        fieldItems(screens, selectedPage, update) { slot = it }
                        pageActions(screens, selectedPage, update) { page = it }
                    }
                }
                item { QuietButton(stringResource(R.string.history_back), back) }
            }
        }
    }
}

private fun ScalingLazyListScope.pageList(
    screens: DataScreens,
    update: (DataScreens) -> Unit,
    select: (Int) -> Unit
) {
    screens.pages.forEachIndexed { index, fields ->
        item {
            WideButton(
                pluralStringResource(
                    R.plurals.data_page_fields,
                    fields.size,
                    index + 1,
                    fields.size
                )
            ) {
                select(index)
            }
        }
    }
    item {
        WideButton(
            stringResource(R.string.data_add_page),
            screens.pages.size < DataScreens.MAX_PAGES
        ) {
            update(DataScreens(screens.pages + listOf(listOf(DataField.TIME))))
        }
    }
}

private fun ScalingLazyListScope.fieldItems(
    screens: DataScreens,
    page: Int,
    update: (DataScreens) -> Unit,
    select: (Int) -> Unit
) {
    val fields = screens.pages[page]
    item { Paragraph(stringResource(R.string.data_page, page + 1, screens.pages.size)) }
    fields.forEachIndexed { index, field ->
        item {
            WideButton(stringResource(R.string.data_slot, index + 1, stringResource(field.label))) {
                select(index)
            }
        }
    }
    item {
        WideButton(stringResource(R.string.data_add_field), fields.size < DataScreens.MAX_FIELDS) {
            update(screens.replacePage(page, fields + DataField.TIME))
        }
    }
    item {
        WideButton(stringResource(R.string.data_remove_field), fields.size > 1) {
            update(screens.replacePage(page, fields.dropLast(1)))
        }
    }
}

private fun ScalingLazyListScope.pageActions(
    screens: DataScreens,
    page: Int,
    update: (DataScreens) -> Unit,
    select: (Int?) -> Unit
) {
    item {
        WideButton(stringResource(R.string.data_move_up), page > 0) {
            update(screens.movePage(page, page - 1))
            select(page - 1)
        }
    }
    item {
        WideButton(stringResource(R.string.data_move_down), page < screens.pages.lastIndex) {
            update(screens.movePage(page, page + 1))
            select(page + 1)
        }
    }
    item {
        WideButton(stringResource(R.string.data_remove_page), screens.pages.size > 1) {
            update(DataScreens(screens.pages.toMutableList().apply { removeAt(page) }))
            select(null)
        }
    }
}

private fun DataScreens.replaceField(page: Int, slot: Int, field: DataField) = replacePage(
    page,
    pages[page].toMutableList().apply { this[slot] = field }
)

private fun DataScreens.replacePage(page: Int, fields: List<DataField>) = DataScreens(
    pages.toMutableList().apply { this[page] = fields }
)

private fun DataScreens.movePage(from: Int, to: Int) = DataScreens(
    pages.toMutableList().apply { add(to, removeAt(from)) }
)
