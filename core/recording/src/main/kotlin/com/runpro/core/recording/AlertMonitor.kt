package com.runpro.core.recording

import com.runpro.core.model.AlertSettings
import com.runpro.core.model.HeartRate
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.Metric
import com.runpro.core.model.Pace
import com.runpro.core.model.Sample
import com.runpro.core.model.SourceId
import com.runpro.core.model.SpeedSample
import com.runpro.core.model.SwitchReason
import com.runpro.core.model.Timestamp
import kotlin.time.Duration.Companion.seconds

/**
 * Evaluates HR, pace, BLE sensor and GPS alerts for one run, with per-kind monotonic rate limiting.
 *
 * Only fresh, live, admitted readings trigger HR/pace alerts (late screen-off batches do not).
 * Sensor and GPS state is still tracked quietly when [emit] is false (paused or journal recovery)
 * so resuming never emits spurious historical alerts.
 */
internal class AlertMonitor(private var settings: AlertSettings = AlertSettings()) {
    private data class AlertKey(val kind: AlertKind, val source: SourceId? = null)

    private var nextId = 1L
    private val emitted = mutableListOf<RunAlert>()
    private val lastFired = mutableMapOf<AlertKey, Timestamp>()
    private val bleConnected = mutableMapOf<SourceId, Boolean>()
    private var gpsAcquired: Boolean? = null

    val alerts: List<RunAlert> get() = emitted.toList()

    fun configure(settings: AlertSettings) {
        this.settings = settings
    }

    fun reset(settings: AlertSettings) {
        this.settings = settings
        nextId = 1L
        emitted.clear()
        lastFired.clear()
        bleConnected.clear()
        gpsAcquired = null
    }

    fun onAdmittedSample(sample: Sample, now: Timestamp, live: Boolean, emit: Boolean) {
        if (sample.source.isBle && !(sample is HeartRateSample && sample.heartRate.bpm == 0)) {
            onBleActive(sample.source, now, emit)
        }
        if (sample.metric == Metric.LOCATION && !emit) {
            onGpsAcquired(now, emit = false)
        }
        if (live && emit && now.elapsedSince(sample.time) <= FRESHNESS_LIMIT) {
            when (sample) {
                is HeartRateSample -> checkHeartRate(sample.heartRate, now)
                is SpeedSample -> checkPace(sample.speed.pace(), now)
                else -> Unit
            }
        }
    }

    fun onBleActive(source: SourceId, time: Timestamp, emit: Boolean) {
        if (!source.isBle) return
        val wasConnected = bleConnected[source]
        bleConnected[source] = true
        if (wasConnected == false && emit && settings.sensorAlerts) {
            fire(AlertKind.SENSOR_BACK, time, source = source)
        }
    }

    fun onBleLost(source: SourceId, time: Timestamp, emit: Boolean) {
        if (!source.isBle) return
        if (bleConnected[source] != true) return
        bleConnected[source] = false
        if (emit && settings.sensorAlerts) {
            fire(AlertKind.SENSOR_LOST, time, source = source)
        }
    }

    fun onGpsAcquired(time: Timestamp, emit: Boolean) {
        val wasAcquired = gpsAcquired
        gpsAcquired = true
        if (wasAcquired == false && emit && settings.gpsAlerts) {
            fire(AlertKind.GPS_BACK, time)
        }
    }

    fun onGpsLost(time: Timestamp, emit: Boolean) {
        if (gpsAcquired != true) return
        gpsAcquired = false
        if (emit && settings.gpsAlerts) {
            fire(AlertKind.GPS_LOST, time)
        }
    }

    fun onArbitration(
        time: Timestamp,
        decisions: Arbitration,
        activeSources: Map<Metric, SourceId>,
        emit: Boolean
    ) {
        val activeBle = activeSources.values.filterTo(mutableSetOf()) { it.isBle }
        activeBle.forEach { onBleActive(it, time, emit) }
        val staleBle = decisions.switches
            .filter { it.reason == SwitchReason.STALE }
            .mapNotNull { it.from }
            .filter { it.isBle } +
            decisions.silencedSources.filter { it.isBle }
        staleBle.distinct().forEach { source ->
            if (source !in activeBle) onBleLost(source, time, emit)
        }
        if (Metric.LOCATION in activeSources) {
            onGpsAcquired(time, emit)
        } else if (Metric.LOCATION in decisions.silenced) {
            onGpsLost(time, emit)
        }
    }

    private fun checkHeartRate(heartRate: HeartRate, time: Timestamp) {
        if (heartRate.bpm == 0) return
        val high = settings.hrHighBpm
        val low = settings.hrLowBpm
        when {
            high != null && heartRate.bpm > high ->
                fire(AlertKind.HR_HIGH, time, heartRate = heartRate)

            low != null && heartRate.bpm < low ->
                fire(AlertKind.HR_LOW, time, heartRate = heartRate)
        }
    }

    private fun checkPace(pace: Pace?, time: Timestamp) {
        if (pace == null || pace.secondsPerKilometer > MAX_VALID_PACE_SECONDS_PER_KM) return
        val fast = settings.paceFast
        val slow = settings.paceSlow
        when {
            fast != null && pace < fast -> fire(AlertKind.PACE_TOO_FAST, time, pace = pace)
            slow != null && pace > slow -> fire(AlertKind.PACE_TOO_SLOW, time, pace = pace)
        }
    }

    private fun fire(
        kind: AlertKind,
        time: Timestamp,
        source: SourceId? = null,
        heartRate: HeartRate? = null,
        pace: Pace? = null
    ) {
        val key = AlertKey(
            kind,
            source = source.takeIf {
                kind == AlertKind.SENSOR_LOST || kind == AlertKind.SENSOR_BACK
            }
        )
        val cooldown = settings.cooldownSeconds.seconds
        val previous = lastFired[key]
        if (previous != null && time.elapsedSince(previous) < cooldown) return
        lastFired[key] = time
        emitted += RunAlert(
            id = nextId++,
            time = time,
            kind = kind,
            source = source,
            heartRate = heartRate,
            pace = pace
        )
    }

    private companion object {
        val FRESHNESS_LIMIT = 3.seconds
        const val MAX_VALID_PACE_SECONDS_PER_KM = 1800.0
    }
}
