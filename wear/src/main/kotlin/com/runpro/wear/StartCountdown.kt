package com.runpro.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** Counts down before sending START. Leaving the activity cancels this pre-start UI action. */
@Composable
internal fun StartCountdown(seconds: Int, start: () -> Unit) {
    var remaining by remember { mutableStateOf<Int?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) remaining = null
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(remaining) {
        val current = remaining ?: return@LaunchedEffect
        if (current == 0) {
            remaining = null
            start()
        } else {
            delay(MILLIS_PER_SECOND)
            remaining = current - 1
        }
    }
    val current = remaining
    if (current == null) {
        WideButton(stringResource(R.string.run_start)) {
            if (seconds == 0) start() else remaining = seconds
        }
    } else {
        QuietButton(stringResource(R.string.settings_starting_in, current)) { remaining = null }
    }
}

private const val MILLIS_PER_SECOND = 1000L
