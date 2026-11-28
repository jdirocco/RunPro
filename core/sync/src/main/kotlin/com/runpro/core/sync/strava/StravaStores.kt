package com.runpro.core.sync.strava

import com.runpro.core.sync.syncDirectory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.isRegularFile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

interface StravaTokenStore {
    fun read(): StravaTokens?
    fun save(tokens: StravaTokens)
    fun clear()
}

interface StravaPreferencesStore {
    fun read(): StravaPreferences
    fun save(preferences: StravaPreferences)
}

/** The OAuth `state` of the authorization currently in the browser; single use. */
interface StravaPendingAuthStore {
    fun read(): String?
    fun save(state: String)
    fun clear()
}

data class StravaStores(
    val tokens: StravaTokenStore,
    val preferences: StravaPreferencesStore,
    val ledger: StravaUploadLedger,
    val pendingAuth: StravaPendingAuthStore
) {
    companion object {
        fun fileBacked(stateDir: Path): StravaStores = StravaStores(
            tokens = FileStravaTokenStore(stateDir),
            preferences = FileStravaPreferencesStore(stateDir),
            ledger = StravaUploadLedger(stateDir),
            pendingAuth = FileStravaPendingAuthStore(stateDir)
        )
    }
}

/**
 * Keeps the pending OAuth state on disk: the browser or Strava app round trip can outlive the process.
 */
class FileStravaPendingAuthStore(private val stateDir: Path) : StravaPendingAuthStore {
    private val file: Path = stateDir.resolve(PENDING_FILE)

    override fun read(): String? = synchronized(LOCK) {
        readJsonObject(file)?.str("state")?.takeIf(String::isNotBlank)
    }

    override fun save(state: String) = synchronized(LOCK) {
        writeAtomically(
            stateDir,
            file,
            buildJsonObject {
                put("state", state)
            }.toString(),
            ownerOnly = true
        )
    }

    override fun clear() = synchronized(LOCK) {
        if (Files.deleteIfExists(file)) syncDirectory(stateDir)
    }

    private companion object {
        val LOCK = Any()
        const val PENDING_FILE = "oauth-state.json"
    }
}

/**
 * App-private file-backed token store using atomic temp-file replacement and owner-only permissions.
 */
class FileStravaTokenStore(private val stateDir: Path) : StravaTokenStore {
    private val file: Path = stateDir.resolve(TOKENS_FILE)

    override fun read(): StravaTokens? = synchronized(LOCK) {
        val obj = readJsonObject(file) ?: return null
        val access = obj.str("accessToken")
        val refresh = obj.str("refreshToken")
        val expiresAt = obj.longVal("expiresAtEpochSeconds")
        if (access.isNullOrBlank() || refresh.isNullOrBlank() || expiresAt == null) {
            null
        } else {
            StravaTokens(
                accessToken = access,
                refreshToken = refresh,
                expiresAtEpochSeconds = expiresAt,
                scope = obj.str("scope") ?: StravaConfig.REQUIRED_SCOPE,
                athleteId = obj.longVal("athleteId"),
                athleteName = obj.str("athleteName")
            )
        }
    }

    override fun save(tokens: StravaTokens) = synchronized(LOCK) {
        val json = buildJsonObject {
            put("accessToken", tokens.accessToken)
            put("refreshToken", tokens.refreshToken)
            put("expiresAtEpochSeconds", tokens.expiresAtEpochSeconds)
            put("scope", tokens.scope)
            tokens.athleteId?.let { put("athleteId", it) }
            tokens.athleteName?.let { put("athleteName", it) }
        }
        writeAtomically(stateDir, file, json.toString(), ownerOnly = true)
    }

    override fun clear() = synchronized(LOCK) {
        Files.deleteIfExists(file)
        if (Files.isDirectory(stateDir)) syncDirectory(stateDir)
    }

    private companion object {
        val LOCK = Any()
        const val TOKENS_FILE = "tokens.json"
    }
}

/**
 * Atomic file-backed Strava preferences (auto-upload defaults to false / opt-in).
 */
class FileStravaPreferencesStore(private val stateDir: Path) : StravaPreferencesStore {
    private val file: Path = stateDir.resolve(PREFERENCES_FILE)

    override fun read(): StravaPreferences = synchronized(LOCK) {
        val obj = readJsonObject(file)
        val autoUpload = (obj?.get("autoUpload") as? JsonPrimitive)?.booleanOrNull ?: false
        StravaPreferences(autoUpload = autoUpload)
    }

    override fun save(preferences: StravaPreferences) = synchronized(LOCK) {
        val json = buildJsonObject {
            put("autoUpload", preferences.autoUpload)
        }
        writeAtomically(stateDir, file, json.toString(), ownerOnly = false)
    }

    private companion object {
        val LOCK = Any()
        const val PREFERENCES_FILE = "preferences.json"
    }
}

/**
 * Atomic JSON ledger in `files/strava/uploads.json` recording each FIT file's SHA-256, upload
 * status, Strava upload id, resolved activity id, and last error without touching `files/runs`.
 */
class StravaUploadLedger(private val stateDir: Path) {
    private val file: Path = stateDir.resolve(LEDGER_FILE)

    fun get(fileName: String): StravaLedgerEntry? = synchronized(LOCK) {
        readAllUnlocked()[fileName]
    }

    fun readAll(): Map<String, StravaLedgerEntry> = synchronized(LOCK) {
        readAllUnlocked()
    }

    fun record(entry: StravaLedgerEntry) = synchronized(LOCK) {
        val entries = readAllUnlocked().toMutableMap()
        entries[entry.fileName] = entry
        val array = buildJsonArray {
            entries.toSortedMap().values.forEach { add(encodeEntry(it)) }
        }
        writeAtomically(stateDir, file, array.toString(), ownerOnly = false)
    }

    private fun readAllUnlocked(): Map<String, StravaLedgerEntry> {
        val array = readJsonArray(file) ?: return emptyMap()
        return array.mapNotNull { (it as? JsonObject)?.let(::decodeEntry) }
            .associateBy { it.fileName }
    }

    private fun encodeEntry(entry: StravaLedgerEntry): JsonObject = buildJsonObject {
        put("fileName", entry.fileName)
        put("sha256", entry.sha256)
        put("status", entry.status.name)
        entry.uploadId?.let { put("uploadId", it) }
        entry.activityId?.let { put("activityId", it) }
        entry.error?.let { put("error", it) }
        put("updatedAtEpochMillis", entry.updatedAtEpochMillis)
    }

    private fun decodeEntry(obj: JsonObject): StravaLedgerEntry? {
        val fileName = obj.str("fileName")
        val hash = obj.str("sha256")
        val status = obj.str("status")?.let { raw ->
            StravaUploadStatus.entries.firstOrNull { it.name == raw }
        }
        return if (fileName.isNullOrBlank() || hash.isNullOrBlank() || status == null) {
            null
        } else {
            StravaLedgerEntry(
                fileName = fileName,
                sha256 = hash,
                status = status,
                uploadId = obj.longVal("uploadId"),
                activityId = obj.longVal("activityId"),
                error = obj.str("error"),
                updatedAtEpochMillis = obj.longVal("updatedAtEpochMillis") ?: 0L
            )
        }
    }

    private companion object {
        val LOCK = Any()
        const val LEDGER_FILE = "uploads.json"
    }
}

private fun readJsonObject(file: Path): JsonObject? = readText(file)?.let { raw ->
    try {
        Json.parseToJsonElement(raw).jsonObject
    } catch (@Suppress("SwallowedException") e: SerializationException) {
        null
    } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
        null
    }
}

private fun readJsonArray(file: Path): JsonArray? = readText(file)?.let { raw ->
    try {
        Json.parseToJsonElement(raw).jsonArray
    } catch (@Suppress("SwallowedException") e: SerializationException) {
        null
    } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
        null
    }
}

private fun readText(file: Path): String? = if (!file.isRegularFile()) {
    null
} else {
    try {
        Files.readString(file, Charsets.UTF_8)
    } catch (@Suppress("SwallowedException") e: IOException) {
        null
    }
}

private fun writeAtomically(stateDir: Path, target: Path, content: String, ownerOnly: Boolean) {
    Files.createDirectories(stateDir)
    val temp = Files.createTempFile(stateDir, ".strava-", ".tmp")
    try {
        if (ownerOnly) restrictOwnerOnly(temp)
        Files.writeString(temp, content, Charsets.UTF_8)
        moveIntoPlace(temp, target)
        syncDirectory(stateDir)
    } finally {
        Files.deleteIfExists(temp)
    }
}

@Suppress("SwallowedException")
private fun restrictOwnerOnly(path: Path) {
    try {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    } catch (e: UnsupportedOperationException) {
        // Non-POSIX filesystem.
    } catch (e: IOException) {
        // Best effort when POSIX permissions are unavailable.
    }
}

private fun moveIntoPlace(temp: Path, target: Path) {
    try {
        Files.move(
            temp,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (@Suppress("SwallowedException") e: AtomicMoveNotSupportedException) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun JsonObject.str(key: String): String? {
    val element = this[key]
    return if (element is JsonPrimitive && element !is JsonNull) element.contentOrNull else null
}

private fun JsonObject.longVal(key: String): Long? {
    val element = this[key]
    return if (element is JsonPrimitive && element !is JsonNull) element.longOrNull else null
}
