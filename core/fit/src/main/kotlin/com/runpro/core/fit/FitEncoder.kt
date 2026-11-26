package com.runpro.core.fit

import com.garmin.fit.ActivityMesg
import com.garmin.fit.BleDeviceType
import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.DeveloperDataIdMesg
import com.garmin.fit.DeveloperField
import com.garmin.fit.DeviceIndex
import com.garmin.fit.DeviceInfoMesg
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.FieldDescriptionMesg
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Fit
import com.garmin.fit.FitBaseType
import com.garmin.fit.LapMesg
import com.garmin.fit.Manufacturer
import com.garmin.fit.Mesg
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionTrigger
import com.garmin.fit.SourceType
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import com.garmin.fit.TimerTrigger
import com.runpro.core.model.HeartRateSample
import com.runpro.core.model.LapTrigger
import com.runpro.core.model.ModelJson
import com.runpro.core.model.PauseReason
import com.runpro.core.model.RunEvent
import com.runpro.core.model.SensorEvent
import com.runpro.core.model.SourceId
import com.runpro.core.recording.RecordedRun
import com.runpro.core.recording.Totals
import java.util.Date
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Writes a [RecordedRun] as a FIT activity file (FIT protocol 2.0, Garmin FIT SDK, ADR 0009).
 *
 * The file holds `file_id`, the RunPro developer data definitions, one `device_info` per source (the watch
 * itself is device 0), then `event`, `record` and `lap` messages in time order, one `session` and one
 * `activity`. Every journal run event and sensor event becomes an `event` message whose RunPro developer
 * field carries the exact event as JSON, so nothing (source switches included, R5) is lost to the lack of a
 * matching FIT event.
 */
object FitEncoder {
    /**
     * @param utcOffsetSeconds the runner's time zone offset at the start, for the activity's local time.
     */
    fun encode(run: RecordedRun, utcOffsetSeconds: Int = 0): ByteArray {
        val encoder = BufferEncoder(Fit.ProtocolVersion.V2_0)
        val messages = Messages(run)
        messages.header().forEach(encoder::write)
        messages.timeline().forEach(encoder::write)
        messages.trailer(utcOffsetSeconds).forEach(encoder::write)
        return encoder.close()
    }

    private class Messages(private val run: RecordedRun) {
        private val developer = DeveloperDataIdMesg().apply {
            RunProFit.APPLICATION_ID.forEachIndexed { i, b -> setApplicationId(i, b) }
            setDeveloperDataIndex(RunProFit.DEVELOPER_DATA_INDEX)
            setApplicationVersion(RunProFit.APPLICATION_VERSION)
        }
        private val runIdField = description(RunProFit.RUN_ID_FIELD, RunProFit.RUN_ID_NAME)
        private val runEventField = description(RunProFit.RUN_EVENT_FIELD, RunProFit.RUN_EVENT_NAME)
        private val sensorEventField =
            description(RunProFit.SENSOR_EVENT_FIELD, RunProFit.SENSOR_EVENT_NAME)
        private val devices = deviceIndexes(run)
        private val state = run.state
        private val endMillis = run.wallClockMillis(checkNotNull(run.run.stoppedAt))

        fun header(): List<Mesg> = buildList {
            add(
                FileIdMesg().apply {
                    setType(File.ACTIVITY)
                    setManufacturer(Manufacturer.DEVELOPMENT)
                    setProduct(RunProFit.PRODUCT)
                    setProductName(RunProFit.PRODUCT_NAME)
                    setTimeCreated(time(endMillis))
                }
            )
            add(developer)
            add(runIdField)
            add(runEventField)
            add(sensorEventField)
            add(
                DeviceInfoMesg().apply {
                    setTimestamp(time(run.wallClockMillis(run.startedAt)))
                    setDeviceIndex(DeviceIndex.CREATOR)
                    setManufacturer(Manufacturer.DEVELOPMENT)
                    setProduct(RunProFit.PRODUCT)
                    setProductName(RunProFit.PRODUCT_NAME)
                    setSourceType(SourceType.LOCAL)
                }
            )
            devices.forEach { (source, index) -> add(device(source, index)) }
        }

        /** Events, records and laps ordered by their exact wall time (events first within a tie). */
        fun timeline(): List<Mesg> {
            val entries = mutableListOf<Pair<Long, Mesg>>()
            run.run.events.forEach { entries += run.wallClockMillis(it.time) to runEvent(it) }
            run.sensorEvents.forEach { entries += run.wallClockMillis(it.time) to sensorEvent(it) }
            recordContents(run).forEach { entries += it.sortMillis to record(it) }
            state.laps.forEachIndexed { i, lap ->
                val end = run.wallClockMillis(checkNotNull(lap.lap.end))
                entries +=
                    end to
                    lap(i, lap.lap.trigger, run.wallClockMillis(lap.lap.start), end, lap.totals)
            }
            // Stable sort: within one millisecond the order above (events, records, laps) is kept.
            return entries.sortedBy { it.first }.map { it.second }
        }

        fun trailer(utcOffsetSeconds: Int): List<Mesg> {
            val totals = state.totals
            val session = SessionMesg().apply {
                setMessageIndex(0)
                setTimestamp(time(endMillis))
                setStartTime(time(run.wallClockMillis(run.startedAt)))
                setEvent(Event.SESSION)
                setEventType(EventType.STOP)
                setSport(Sport.RUNNING)
                setSubSport(SubSport.GENERIC)
                setTrigger(SessionTrigger.ACTIVITY_END)
                setFirstLapIndex(0)
                setNumLaps(state.laps.size)
                setTotals(totals, this::setTotalElapsedTime, this::setTotalTimerTime)
                setTotalMovingTime(seconds(totals.moving))
                setTotalDistance(totals.distance.meters.toFloat())
                totals.averageSpeed?.let { setEnhancedAvgSpeed(it.metersPerSecond.toFloat()) }
                totals.averageHeartRate?.let { setAvgHeartRate(it.bpm.toShort()) }
                totals.maxHeartRate?.let { setMaxHeartRate(it.bpm.toShort()) }
                totals.averageCadence?.let {
                    cadence(it.stridesPerMinute, ::setAvgCadence, ::setAvgFractionalCadence)
                }
                totals.maxCadence?.let {
                    cadence(it.stridesPerMinute, ::setMaxCadence, ::setMaxFractionalCadence)
                }
                setTotalAscent(totals.ascent.meters.roundToInt())
                setTotalDescent(totals.descent.meters.roundToInt())
                addDeveloperField(string(runIdField, run.id.value))
            }
            val activity = ActivityMesg().apply {
                setTimestamp(time(endMillis))
                setTotalTimerTime(seconds(totals.timer))
                setNumSessions(1)
                setType(com.garmin.fit.Activity.MANUAL)
                setEvent(Event.ACTIVITY)
                setEventType(EventType.STOP)
                setLocalTimestamp(time(endMillis).timestamp + utcOffsetSeconds)
            }
            return listOf(session, activity)
        }

        private fun description(field: Short, name: String) = FieldDescriptionMesg().apply {
            setDeveloperDataIndex(RunProFit.DEVELOPER_DATA_INDEX)
            setFieldDefinitionNumber(field)
            setFitBaseTypeId(FitBaseType.STRING)
            setFieldName(0, name)
        }

        private fun string(description: FieldDescriptionMesg, value: String): DeveloperField {
            require(value.toByteArray(Charsets.UTF_8).size <= RunProFit.MAX_STRING_BYTES) {
                "Developer field ${description.getFieldName(0)} too long: $value"
            }
            return DeveloperField(description, developer).apply { setValue(value) }
        }

        private fun device(source: SourceId, index: Int) = DeviceInfoMesg().apply {
            setTimestamp(time(run.wallClockMillis(run.startedAt)))
            setDeviceIndex(index.toShort())
            setDescriptor(source.value)
            setProductName(source.value)
            if (source.isBle) {
                setSourceType(SourceType.BLUETOOTH_LOW_ENERGY)
                if (run.samples.any {
                        it.sample.source == source && it.sample is HeartRateSample
                    }
                ) {
                    setBleDeviceType(BleDeviceType.HEART_RATE)
                }
            } else {
                setSourceType(SourceType.LOCAL)
            }
        }

        private fun runEvent(event: RunEvent) = EventMesg().apply {
            setTimestamp(time(run.wallClockMillis(event.time)))
            val (fitEvent, type) = RunProFit.fitEventOf(event)
            setEvent(fitEvent)
            setEventType(type)
            when (event) {
                is RunEvent.Started, is RunEvent.Resumed -> setTimerTrigger(TimerTrigger.MANUAL)

                is RunEvent.Paused -> setTimerTrigger(
                    if (event.reason == PauseReason.AUTO) TimerTrigger.AUTO else TimerTrigger.MANUAL
                )

                is RunEvent.Stopped -> setTimerTrigger(TimerTrigger.MANUAL)

                is RunEvent.SensorConnected -> setDeviceIndex(
                    devices.getValue(event.source).toShort()
                )

                is RunEvent.SensorLost -> setDeviceIndex(devices.getValue(event.source).toShort())

                is RunEvent.SourceSwitched -> (event.to ?: event.from)?.let {
                    setDeviceIndex(devices.getValue(it).toShort())
                }

                is RunEvent.LapMarked -> Unit
            }
            addDeveloperField(
                string(runEventField, ModelJson.json.encodeToString(RunEvent.serializer(), event))
            )
        }

        private fun sensorEvent(event: SensorEvent) = EventMesg().apply {
            setTimestamp(time(run.wallClockMillis(event.time)))
            val (fitEvent, type) = RunProFit.fitEventOf(event)
            setEvent(fitEvent)
            setEventType(type)
            setDeviceIndex(devices.getValue(event.source).toShort())
            addDeveloperField(
                string(
                    sensorEventField,
                    ModelJson.json.encodeToString(SensorEvent.serializer(), event)
                )
            )
        }

        private fun record(content: RecordContent) = RecordMesg().apply {
            setTimestamp(time(content.second))
            content.heartRateBpm?.let { setHeartRate(it.toShort()) }
            content.latitude?.let { setPositionLat(RunProFit.semicircles(it)) }
            content.longitude?.let { setPositionLong(RunProFit.semicircles(it)) }
            content.altitudeMeters?.let { setEnhancedAltitude(it.toFloat()) }
            content.distanceMeters?.let { setDistance(it.toFloat()) }
            content.speedMetersPerSecond?.let { setEnhancedSpeed(it.toFloat()) }
            content.cadenceStepsPerMinute?.let {
                cadence(it / RunProFit.STEPS_PER_STRIDE, ::setCadence, ::setFractionalCadence)
            }
            content.powerWatts?.let { setPower(it) }
        }

        private fun lap(index: Int, trigger: LapTrigger?, start: Long, end: Long, totals: Totals) =
            LapMesg().apply {
                setMessageIndex(index)
                setTimestamp(time(end))
                setStartTime(time(start))
                setEvent(Event.LAP)
                setEventType(EventType.STOP)
                setSport(Sport.RUNNING)
                setLapTrigger(RunProFit.fitLapTrigger(checkNotNull(trigger)))
                setTotals(totals, this::setTotalElapsedTime, this::setTotalTimerTime)
                setTotalMovingTime(seconds(totals.moving))
                setTotalDistance(totals.distance.meters.toFloat())
                totals.averageSpeed?.let { setEnhancedAvgSpeed(it.metersPerSecond.toFloat()) }
                totals.averageHeartRate?.let { setAvgHeartRate(it.bpm.toShort()) }
                totals.maxHeartRate?.let { setMaxHeartRate(it.bpm.toShort()) }
                totals.averageCadence?.let {
                    cadence(it.stridesPerMinute, ::setAvgCadence, ::setAvgFractionalCadence)
                }
                totals.maxCadence?.let {
                    cadence(it.stridesPerMinute, ::setMaxCadence, ::setMaxFractionalCadence)
                }
                setTotalAscent(totals.ascent.meters.roundToInt())
                setTotalDescent(totals.descent.meters.roundToInt())
            }
    }

    private fun setTotals(totals: Totals, elapsed: (Float) -> Unit, timer: (Float) -> Unit) {
        elapsed(seconds(totals.elapsed))
        timer(seconds(totals.timer))
    }

    /** FIT cadence is whole strides per minute plus a fraction in 1/128. */
    private fun cadence(strides: Double, whole: (Short) -> Unit, fraction: (Float) -> Unit) {
        val integer = floor(strides).coerceAtMost(RunProFit.MAX_UINT8.toDouble())
        whole(integer.toInt().toShort())
        fraction((strides - integer).toFloat())
    }

    private fun seconds(duration: Duration): Float =
        duration.toDouble(DurationUnit.SECONDS).toFloat()

    private fun time(millis: Long): DateTime = DateTime(Date(wholeSecond(millis)))
}
