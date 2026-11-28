package com.runpro.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import com.runpro.core.model.DisplayUnits
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
    val limits = profileLimits(profile, field, weightFactor)
    var value by remember(field) {
        mutableStateOf(
            when (field) {
                ProfileField.MAX_HR -> (profile.maxHeartRate ?: DEFAULT_MAX_HR).toDouble()

                ProfileField.RESTING_HR ->
                    (profile.restingHeartRate ?: DEFAULT_RESTING_HR).toDouble()

                ProfileField.WEIGHT -> (profile.weightKg ?: DEFAULT_WEIGHT_KG) * weightFactor
            }.coerceIn(limits)
        )
    }
    val step = if (field == ProfileField.WEIGHT && !imperial) WEIGHT_STEP_KG else 1.0
    ScalingLazyColumn {
        item {
            val text = if (field ==
                ProfileField.WEIGHT
            ) {
                weightLabel(value / weightFactor, settings.units)
            } else {
                value.roundToInt().toString()
            }
            Paragraph(stringResource(field.label, text))
        }
        item {
            WideButton(stringResource(R.string.settings_increase)) {
                value =
                    (value + step).coerceIn(limits)
            }
        }
        item {
            WideButton(stringResource(R.string.settings_decrease)) {
                value =
                    (value - step).coerceIn(limits)
            }
        }
        item {
            WideButton(stringResource(R.string.settings_save)) {
                update(settings.copy(profile = saveProfile(profile, field, value, weightFactor)))
                close()
            }
        }
        item {
            QuietButton(stringResource(R.string.settings_clear)) {
                update(settings.copy(profile = saveProfile(profile, field, null, weightFactor)))
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

private const val POUNDS_PER_KG = 2.2046226218
private const val DEFAULT_MAX_HR = 190
private const val DEFAULT_RESTING_HR = 60
private const val DEFAULT_WEIGHT_KG = 70.0
private const val WEIGHT_STEP_KG = 0.5

private fun profileLimits(
    profile: UserProfile,
    field: ProfileField,
    weightFactor: Double
): ClosedFloatingPointRange<Double> {
    val minimumWeight = UserProfile.MIN_WEIGHT_KG * weightFactor
    val maximumWeight = UserProfile.MAX_WEIGHT_KG * weightFactor
    return when (field) {
        ProfileField.MAX_HR -> maxOf(
            UserProfile.MIN_MAX_HR,
            (profile.restingHeartRate ?: 0) + 1
        ).toDouble()..UserProfile.MAX_HR.toDouble()

        ProfileField.RESTING_HR -> UserProfile.MIN_RESTING_HR.toDouble()..minOf(
            UserProfile.MAX_RESTING_HR,
            (profile.maxHeartRate ?: UserProfile.MAX_HR) - 1
        ).toDouble()

        ProfileField.WEIGHT -> minimumWeight..maximumWeight
    }
}

private fun saveProfile(
    profile: UserProfile,
    field: ProfileField,
    value: Double?,
    weightFactor: Double
): UserProfile = when (field) {
    ProfileField.MAX_HR -> profile.copy(
        maxHeartRate = value?.roundToInt()
    )

    ProfileField.RESTING_HR -> profile.copy(
        restingHeartRate = value?.roundToInt()
    )

    ProfileField.WEIGHT -> profile.copy(
        weightKg = value?.div(weightFactor)?.coerceIn(
            UserProfile.MIN_WEIGHT_KG,
            UserProfile.MAX_WEIGHT_KG
        )
    )
}
