package com.runpro.core.model

import kotlinx.serialization.json.Json

/**
 * The JSON configuration for model types (replay traces, test fixtures, diagnostics).
 * Polymorphic types carry a `type` discriminator. Unknown fields are ignored so older readers accept
 * files from newer writers; null fields are omitted.
 */
object ModelJson {
    val json: Json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }
}
