package com.runpro.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import com.runpro.core.model.DisplayUnits
import com.runpro.core.model.Pace
import com.runpro.core.model.RunSettings
import com.runpro.core.model.UserProfile
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun ProfileEditor(
    settings: RunSettings,
    field: ProfileField,
    update: (RunSettings) -> Unit,
    close: () -> Unit
) {
    val profile = settings.profile
    val imperial = settings.units == DisplayUnits.IMPERIAL
    val weightFactor = if (imperial) POUNDS_PER_KG else 1.0
    val paceFactor = paceScale(settings.units)
    val limits = profileLimits(profile, field, weightFactor, paceFactor)
    var value by remember(field) {
        mutableStateOf(
            initialProfileValue(profile, field, weightFactor, paceFactor).coerceIn(limits)
        )
    }
    val step = profileStep(field, imperial)
    ScalingLazyColumn {
        item {
            val text = formattedProfileValue(field, value, settings.units, weightFactor, paceFactor)
            Paragraph(stringResource(field.label, text))
        }
        item {
            WideButton(stringResource(R.string.settings_increase)) {
                value = (value + step).coerceIn(limits)
            }
        }
        item {
            WideButton(stringResource(R.string.settings_decrease)) {
                value = (value - step).coerceIn(limits)
            }
        }
        item {
            WideButton(stringResource(R.string.settings_save)) {
                update(
                    settings.copy(
                        profile = saveProfile(profile, field, value, weightFactor, paceFactor)
                    )
                )
                close()
            }
        }
        item {
            QuietButton(stringResource(R.string.settings_clear)) {
                update(
                    settings.copy(
                        profile = saveProfile(profile, field, null, weightFactor, paceFactor)
                    )
                )
                close()
            }
        }
        item { QuietButton(stringResource(R.string.history_cancel), close) }
    }
}

internal fun weightLabel(kg: Double, units: DisplayUnits): String = if (units ==
    DisplayUnits.IMPERIAL
) {
    "%.1f lb".format(Locale.getDefault(), kg * POUNDS_PER_KG)
} else {
    "%.1f kg".format(Locale.getDefault(), kg)
}

@Composable
internal fun paceWithUnit(pace: Pace, units: DisplayUnits): String {
    val unit = stringResource(
        if (units == DisplayUnits.IMPERIAL) R.string.unit_pace_mi else R.string.unit_pace
    )
    return "${pace(pace, units.distance)} $unit"
}

internal fun paceScale(units: DisplayUnits): Double = units.distance.meters / METERS_PER_KILOMETER

private fun initialProfileValue(
    profile: UserProfile,
    field: ProfileField,
    weightFactor: Double,
    paceFactor: Double
): Double = when (field) {
    ProfileField.MAX_HR -> (profile.maxHeartRate ?: DEFAULT_MAX_HR).toDouble()

    ProfileField.LTHR -> (profile.lactateThresholdHeartRate ?: DEFAULT_LTHR).toDouble()

    ProfileField.RESTING_HR -> (profile.restingHeartRate ?: DEFAULT_RESTING_HR).toDouble()

    ProfileField.THRESHOLD_PACE ->
        (profile.thresholdPace?.secondsPerKilometer ?: DEFAULT_THRESHOLD_PACE_SEC) * paceFactor

    ProfileField.WEIGHT -> (profile.weightKg ?: DEFAULT_WEIGHT_KG) * weightFactor
}

private fun profileStep(field: ProfileField, imperial: Boolean): Double = when (field) {
    ProfileField.WEIGHT -> if (imperial) 1.0 else WEIGHT_STEP_KG
    ProfileField.THRESHOLD_PACE -> PACE_STEP_SECONDS
    else -> 1.0
}

@Composable
private fun formattedProfileValue(
    field: ProfileField,
    value: Double,
    units: DisplayUnits,
    weightFactor: Double,
    paceFactor: Double
): String = when (field) {
    ProfileField.WEIGHT -> weightLabel(value / weightFactor, units)

    ProfileField.THRESHOLD_PACE -> paceWithUnit(
        Pace(
            (value / paceFactor).coerceIn(
                UserProfile.MIN_THRESHOLD_PACE_SEC,
                UserProfile.MAX_THRESHOLD_PACE_SEC
            )
        ),
        units
    )

    else -> value.roundToInt().toString()
}

private fun profileLimits(
    profile: UserProfile,
    field: ProfileField,
    weightFactor: Double,
    paceFactor: Double
): ClosedFloatingPointRange<Double> = when (field) {
    ProfileField.MAX_HR -> maxOf(
        UserProfile.MIN_MAX_HR,
        (profile.restingHeartRate ?: 0) + 1,
        (profile.lactateThresholdHeartRate ?: 0) + 1
    ).toDouble()..UserProfile.MAX_HR.toDouble()

    ProfileField.LTHR -> maxOf(
        UserProfile.MIN_LTHR,
        (profile.restingHeartRate ?: 0) + 1
    ).toDouble()..minOf(
        UserProfile.MAX_HR,
        (profile.maxHeartRate ?: (UserProfile.MAX_HR + 1)) - 1
    ).toDouble()

    ProfileField.RESTING_HR -> UserProfile.MIN_RESTING_HR.toDouble()..minOf(
        UserProfile.MAX_RESTING_HR,
        (profile.maxHeartRate ?: UserProfile.MAX_HR) - 1,
        (profile.lactateThresholdHeartRate ?: UserProfile.MAX_HR) - 1
    ).toDouble()

    ProfileField.THRESHOLD_PACE ->
        (UserProfile.MIN_THRESHOLD_PACE_SEC * paceFactor)..(
            UserProfile.MAX_THRESHOLD_PACE_SEC * paceFactor
            )

    ProfileField.WEIGHT ->
        (UserProfile.MIN_WEIGHT_KG * weightFactor)..(UserProfile.MAX_WEIGHT_KG * weightFactor)
}

private fun saveProfile(
    profile: UserProfile,
    field: ProfileField,
    value: Double?,
    weightFactor: Double,
    paceFactor: Double
): UserProfile = when (field) {
    ProfileField.MAX_HR -> profile.copy(maxHeartRate = value?.roundToInt())

    ProfileField.LTHR -> profile.copy(lactateThresholdHeartRate = value?.roundToInt())

    ProfileField.RESTING_HR -> profile.copy(restingHeartRate = value?.roundToInt())

    ProfileField.THRESHOLD_PACE -> profile.copy(
        thresholdPace = value?.let {
            Pace(
                (it / paceFactor).coerceIn(
                    UserProfile.MIN_THRESHOLD_PACE_SEC,
                    UserProfile.MAX_THRESHOLD_PACE_SEC
                )
            )
        }
    )

    ProfileField.WEIGHT -> profile.copy(
        weightKg = value?.div(weightFactor)?.coerceIn(
            UserProfile.MIN_WEIGHT_KG,
            UserProfile.MAX_WEIGHT_KG
        )
    )
}

private const val METERS_PER_KILOMETER = 1000.0
private const val POUNDS_PER_KG = 2.2046226218
private const val DEFAULT_MAX_HR = 190
private const val DEFAULT_LTHR = 170
private const val DEFAULT_RESTING_HR = 60
private const val DEFAULT_THRESHOLD_PACE_SEC = 300.0
private const val DEFAULT_WEIGHT_KG = 70.0
private const val WEIGHT_STEP_KG = 0.5
private const val PACE_STEP_SECONDS = 5.0
