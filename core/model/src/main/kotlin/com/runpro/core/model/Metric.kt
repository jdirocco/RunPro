package com.runpro.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The kinds of data a source can provide. Each metric has exactly one [Sample] type. */
@Serializable
enum class Metric {
    @SerialName("heart_rate")
    HEART_RATE,

    @SerialName("location")
    LOCATION,

    @SerialName("distance")
    DISTANCE,

    @SerialName("speed")
    SPEED,

    @SerialName("cadence")
    CADENCE,

    @SerialName("power")
    POWER,

    @SerialName("elevation")
    ELEVATION,

    @SerialName("acceleration")
    ACCELERATION
}
