package com.runpro.core.fit

import com.garmin.fit.Event
import com.garmin.fit.EventType
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.RunEvent
import com.runpro.core.model.SensorEvent
import kotlin.math.roundToLong

/** How RunPro maps its data onto FIT (ADR 0009). Shared by [FitEncoder] and [FitDecoder]. */
internal object RunProFit {
    const val PRODUCT = 1
    const val PRODUCT_NAME = "RunPro"
    const val DEVELOPER_DATA_INDEX: Short = 0
    const val APPLICATION_VERSION = 1L

    /** A fixed random UUID identifying RunPro as the FIT developer application. */
    val APPLICATION_ID: List<Byte> = "6b0e5f2a9c4d4f1e8a37c2d95e1b0f64".chunked(2).map {
        it.toInt(HEX).toByte()
    }

    const val RUN_ID_FIELD: Short = 0
    const val RUN_EVENT_FIELD: Short = 1
    const val SENSOR_EVENT_FIELD: Short = 2
    const val RUN_ID_NAME = "runpro_run_id"
    const val RUN_EVENT_NAME = "runpro_run_event"
    const val SENSOR_EVENT_NAME = "runpro_sensor_event"

    /** A FIT field holds at most 255 bytes, one of which is the string terminator. */
    const val MAX_STRING_BYTES = 254
    const val MAX_UINT8 = 254
    const val STEPS_PER_STRIDE = 2.0
    private const val HEX = 16
    private const val SEMICIRCLES_PER_DEGREE = 2_147_483_648.0 / 180.0

    fun semicircles(degrees: Double): Int = (degrees * SEMICIRCLES_PER_DEGREE).roundToLong()
        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    fun degrees(semicircles: Int): Double = semicircles / SEMICIRCLES_PER_DEGREE

    /** The closest standard FIT event; the exact RunPro event is in the developer field. */
    fun fitEventOf(event: RunEvent): Pair<Event, EventType> = when (event) {
        is RunEvent.Started, is RunEvent.Resumed -> Event.TIMER to EventType.START

        is RunEvent.Paused, is RunEvent.Stopped -> Event.TIMER to EventType.STOP_ALL

        is RunEvent.LapMarked -> Event.LAP to EventType.MARKER

        is RunEvent.SensorLost -> Event.COMM_TIMEOUT to EventType.MARKER

        is RunEvent.SensorConnected, is RunEvent.SourceSwitched ->
            Event.USER_MARKER to
                EventType.MARKER
    }

    fun fitEventOf(event: SensorEvent): Pair<Event, EventType> = when (event) {
        is SensorEvent.Disconnected, is SensorEvent.GpsLost ->
            Event.COMM_TIMEOUT to
                EventType.MARKER

        is SensorEvent.Connected, is SensorEvent.GpsAcquired ->
            Event.USER_MARKER to
                EventType.MARKER
    }

    fun fitLapTrigger(trigger: LapTrigger): com.garmin.fit.LapTrigger = when (trigger) {
        LapTrigger.MANUAL -> com.garmin.fit.LapTrigger.MANUAL
        LapTrigger.DISTANCE -> com.garmin.fit.LapTrigger.DISTANCE
        LapTrigger.TIME -> com.garmin.fit.LapTrigger.TIME
        LapTrigger.STOP -> com.garmin.fit.LapTrigger.SESSION_END
    }

    fun lapTriggerOf(trigger: com.garmin.fit.LapTrigger?): LapTrigger? = when (trigger) {
        com.garmin.fit.LapTrigger.MANUAL -> LapTrigger.MANUAL
        com.garmin.fit.LapTrigger.DISTANCE -> LapTrigger.DISTANCE
        com.garmin.fit.LapTrigger.TIME -> LapTrigger.TIME
        com.garmin.fit.LapTrigger.SESSION_END -> LapTrigger.STOP
        else -> null
    }
}
