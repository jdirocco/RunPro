package com.runpro.core.fit

import com.garmin.fit.ActivityMesg
import com.garmin.fit.DateTime
import com.garmin.fit.Decode
import com.garmin.fit.DeviceInfoMesg
import com.garmin.fit.EventMesg
import com.garmin.fit.FileIdMesg
import com.garmin.fit.FitRuntimeException
import com.garmin.fit.LapMesg
import com.garmin.fit.Mesg
import com.garmin.fit.MesgNum
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.runpro.core.model.ModelJson
import com.runpro.core.model.RunEvent
import com.runpro.core.model.SensorEvent
import java.io.ByteArrayInputStream
import kotlinx.serialization.SerializationException

/** The bytes are not a readable FIT file (bad header, CRC or content). */
class FitFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reads a FIT activity file back with the Garmin FIT SDK, checking its header and CRC first. */
object FitDecoder {
    fun decode(bytes: ByteArray): FitActivity {
        if (!hasIntegrity(bytes)) {
            throw FitFormatException("FIT file integrity check failed (header or CRC)")
        }
        val messages = mutableListOf<Mesg>()
        try {
            Decode().read(ByteArrayInputStream(bytes)) { messages += it }
        } catch (e: FitRuntimeException) {
            throw FitFormatException("FIT file cannot be decoded: ${e.message}", e)
        }
        return activity(messages)
    }

    private fun hasIntegrity(bytes: ByteArray): Boolean = try {
        Decode().checkFileIntegrity(ByteArrayInputStream(bytes))
    } catch (e: FitRuntimeException) {
        throw FitFormatException("Not a FIT file: ${e.message}", e)
    }

    private fun activity(messages: List<Mesg>): FitActivity {
        val fileId = messages.firstOrNull { it.num == MesgNum.FILE_ID }?.let(::FileIdMesg)
        val sessions = messages.filter { it.num == MesgNum.SESSION }.map(::SessionMesg)
        val activity = messages.lastOrNull { it.num == MesgNum.ACTIVITY }?.let(::ActivityMesg)
        return FitActivity(
            fileType = fileId?.type?.name,
            timeCreatedMillis = fileId?.timeCreated.millis(),
            devices = messages.filter { it.num == MesgNum.DEVICE_INFO }.map(::DeviceInfoMesg).map {
                FitDevice(it.deviceIndex?.toInt(), it.descriptor, it.sourceType?.name)
            },
            events = messages.filter { it.num == MesgNum.EVENT }.map { event(EventMesg(it)) },
            records = messages.filter { it.num == MesgNum.RECORD }.map { record(RecordMesg(it)) },
            laps = messages.filter { it.num == MesgNum.LAP }.map { lap(LapMesg(it)) },
            session = sessions.firstOrNull()?.let(::session),
            sessionCount = sessions.size,
            runId = sessions.firstOrNull()?.developerString(RunProFit.RUN_ID_NAME),
            activityTimerSeconds = activity?.totalTimerTime?.toDouble()
        )
    }

    private fun event(mesg: EventMesg) = FitEvent(
        timeMillis = mesg.timestamp.millis(),
        type = "${mesg.event?.name}/${mesg.eventType?.name}",
        timerTrigger = mesg.timerTrigger?.name,
        deviceIndex = mesg.deviceIndex?.toInt(),
        runEvent = mesg.developerString(RunProFit.RUN_EVENT_NAME)?.let {
            parse(it) { json -> ModelJson.json.decodeFromString(RunEvent.serializer(), json) }
        },
        sensorEvent = mesg.developerString(RunProFit.SENSOR_EVENT_NAME)?.let {
            parse(it) { json -> ModelJson.json.decodeFromString(SensorEvent.serializer(), json) }
        }
    )

    private fun record(mesg: RecordMesg) = FitRecord(
        timeMillis = mesg.timestamp.millis(),
        heartRateBpm = mesg.heartRate?.toInt(),
        latitude = mesg.positionLat?.let(RunProFit::degrees),
        longitude = mesg.positionLong?.let(RunProFit::degrees),
        altitudeMeters = (mesg.enhancedAltitude ?: mesg.altitude)?.toDouble(),
        distanceMeters = mesg.distance?.toDouble(),
        speedMetersPerSecond = (mesg.enhancedSpeed ?: mesg.speed)?.toDouble(),
        cadenceStepsPerMinute = steps(mesg.cadence, mesg.fractionalCadence),
        powerWatts = mesg.power
    )

    private fun lap(mesg: LapMesg) = FitTotals(
        startMillis = mesg.startTime.millis(),
        endMillis = mesg.timestamp.millis(),
        elapsedSeconds = mesg.totalElapsedTime?.toDouble(),
        timerSeconds = mesg.totalTimerTime?.toDouble(),
        movingSeconds = mesg.totalMovingTime?.toDouble(),
        distanceMeters = mesg.totalDistance?.toDouble(),
        averageHeartRateBpm = mesg.avgHeartRate?.toInt(),
        maxHeartRateBpm = mesg.maxHeartRate?.toInt(),
        averageCadenceStepsPerMinute = steps(mesg.avgCadence, mesg.avgFractionalCadence),
        maxCadenceStepsPerMinute = steps(mesg.maxCadence, mesg.maxFractionalCadence),
        ascentMeters = mesg.totalAscent,
        descentMeters = mesg.totalDescent,
        averageSpeedMetersPerSecond = (mesg.enhancedAvgSpeed ?: mesg.avgSpeed)?.toDouble(),
        sport = mesg.sport?.name,
        lapTrigger = RunProFit.lapTriggerOf(mesg.lapTrigger)
    )

    private fun session(mesg: SessionMesg) = FitTotals(
        startMillis = mesg.startTime.millis(),
        endMillis = mesg.timestamp.millis(),
        elapsedSeconds = mesg.totalElapsedTime?.toDouble(),
        timerSeconds = mesg.totalTimerTime?.toDouble(),
        movingSeconds = mesg.totalMovingTime?.toDouble(),
        distanceMeters = mesg.totalDistance?.toDouble(),
        averageHeartRateBpm = mesg.avgHeartRate?.toInt(),
        maxHeartRateBpm = mesg.maxHeartRate?.toInt(),
        averageCadenceStepsPerMinute = steps(mesg.avgCadence, mesg.avgFractionalCadence),
        maxCadenceStepsPerMinute = steps(mesg.maxCadence, mesg.maxFractionalCadence),
        ascentMeters = mesg.totalAscent,
        descentMeters = mesg.totalDescent,
        averageSpeedMetersPerSecond = (mesg.enhancedAvgSpeed ?: mesg.avgSpeed)?.toDouble(),
        sport = mesg.sport?.name,
        lapCount = mesg.numLaps
    )

    /** Steps per minute from FIT strides per minute plus the 1/128 fraction. */
    private fun steps(strides: Short?, fraction: Float?): Double? =
        strides?.let { (it + (fraction ?: 0f)) * RunProFit.STEPS_PER_STRIDE }

    private fun DateTime?.millis(): Long? = this?.date?.time

    private fun Mesg.developerString(name: String): String? =
        developerFields.firstOrNull { it.name == name }?.stringValue

    // A developer field that does not parse is reported by the verifier as a missing event, not thrown.
    @Suppress("SwallowedException")
    private fun <T> parse(json: String, decode: (String) -> T): T? = try {
        decode(json)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
