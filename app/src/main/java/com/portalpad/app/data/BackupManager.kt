package com.portalpad.app.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backs up and restores PortalPad preferences.
 *
 * **Storage:** uses the Storage Access Framework via DocumentFile. The user
 * picks any folder they want (internal, SD card, Google Drive-synced folder,
 * Dropbox folder, etc.) and we persist the URI permission for future writes.
 *
 * **Format:** single JSON file `portalpad-backup-YYYYMMDD-HHmmss.json`.
 * Contains all preference values keyed by their string name. Versioned via
 * [BACKUP_FORMAT_VERSION] so future format changes can migrate cleanly.
 *
 * **Not backed up on purpose:**
 *  - The backup-folder URI itself (would create a chicken-and-egg if a user
 *    restored on a fresh install — they'd point at a now-stale URI)
 *  - The "last backup time" tracking field
 *  - Granted Android permissions (Shizuku auth, overlay, notification access)
 *    are managed by Android and have to be re-granted after restore
 */
class BackupManager(private val context: Context) {

    private val prefs = PreferencesRepository(context)
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** Stable list of keys excluded from backup payload — see class doc. */
    private val excludedKeyNames = setOf(
        "backup_folder_uri",
        "backup_frequency",
        "backup_last_success",
    )

    /**
     * Serialize the full preferences map and write to the user's backup folder.
     *
     * Priority order:
     *  1. The user-picked SAF folder URI (if set in prefs)
     *  2. Fallback to the public `Documents/PortalPad/` folder via MediaStore.
     *     This works without runtime permission on Android 10+ and shows up in
     *     any file manager + the Google Drive Android app (if Drive is syncing
     *     the Documents folder).
     *
     * @return display name of the written file, or null on failure.
     */
    suspend fun backupNow(): String? {
        val folderUriStr = prefs.rawDataStore.data.first()[PreferencesRepository.Keys.BACKUP_FOLDER_URI]
        return if (folderUriStr != null) {
            backupToSafFolder(Uri.parse(folderUriStr))
        } else {
            backupToDocumentsPortalPad()
        }
    }

    /** Write via DocumentFile to the SAF tree the user picked. */
    private suspend fun backupToSafFolder(folderUri: Uri): String? {
        val folder = DocumentFile.fromTreeUri(context, folderUri) ?: run {
            Log.w(TAG, "Could not resolve folder URI $folderUri")
            return null
        }
        if (!folder.canWrite()) {
            Log.w(TAG, "No write access to $folderUri — was permission revoked?")
            return null
        }

        val payload = buildPayloadJson()
        val filename = nextFilename()
        val newFile = folder.createFile("application/json", filename) ?: run {
            Log.w(TAG, "createFile returned null for $filename")
            return null
        }

        return try {
            context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
            }
            markSuccess()
            filename
        } catch (t: Throwable) {
            Log.e(TAG, "Backup write failed", t)
            newFile.delete()
            null
        }
    }

    /**
     * Fallback path: write to `Documents/PortalPad/<filename>` via MediaStore.
     * Uses the Files collection (`MediaStore.Files`) — works on Android 10+
     * without requiring WRITE_EXTERNAL_STORAGE.
     */
    private suspend fun backupToDocumentsPortalPad(): String? {
        val payload = buildPayloadJson()
        val filename = nextFilename()
        val resolver = context.contentResolver

        return try {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/json")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOCUMENTS + "/PortalPad",
                    )
                }
            }
            val targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.insert(android.provider.MediaStore.Files.getContentUri("external"), values)
            } else null

            if (targetUri == null) {
                Log.w(TAG, "MediaStore insert returned null — falling back to app-private location")
                return backupToAppPrivate(payload, filename)
            }

            resolver.openOutputStream(targetUri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            markSuccess()
            filename
        } catch (t: Throwable) {
            Log.e(TAG, "MediaStore backup failed; falling back to app-private", t)
            backupToAppPrivate(payload, filename)
        }
    }

    /**
     * Last-resort fallback: write to the app's private external files directory.
     * Always works, doesn't survive uninstall, but never visible to other apps.
     */
    private suspend fun backupToAppPrivate(payload: String, filename: String): String? {
        return try {
            val dir = context.getExternalFilesDir(null) ?: return null
            val backupDir = java.io.File(dir, "backups").apply { mkdirs() }
            val outFile = java.io.File(backupDir, filename)
            outFile.writeText(payload, Charsets.UTF_8)
            markSuccess()
            "${outFile.absolutePath} (app-private)"
        } catch (t: Throwable) {
            Log.e(TAG, "App-private backup failed", t)
            null
        }
    }

    private suspend fun markSuccess() {
        prefs.rawDataStore.edit {
            it[PreferencesRepository.Keys.BACKUP_LAST_SUCCESS_MS] = System.currentTimeMillis()
        }
    }

    private fun nextFilename(): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "portalpad-backup-$ts.json"
    }

    /**
     * Read a backup file and write its contents into DataStore, overwriting
     * any existing keys. Returns true if at least one key was restored.
     */
    suspend fun restoreFrom(fileUri: Uri): Boolean {
        val raw = try {
            context.contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
        } catch (t: Throwable) {
            Log.e(TAG, "Read failed", t); return false
        } ?: return false

        val text = raw.toString(Charsets.UTF_8)
        val backup = try {
            json.decodeFromString<BackupPayload>(text)
        } catch (t: Throwable) {
            Log.e(TAG, "Parse failed (not a valid backup file?)", t); return false
        }

        // Apply to DataStore. We map each entry by name back to its typed Key.
        prefs.rawDataStore.edit { store ->
            for ((name, entry) in backup.values) {
                if (name in excludedKeyNames) continue
                applyEntry(store, name, entry)
            }
        }
        return backup.values.isNotEmpty()
    }

    /** Build the JSON payload that gets written to the backup file. */
    private suspend fun buildPayloadJson(): String {
        val all = prefs.rawDataStore.data.first().asMap()
        val entries = mutableMapOf<String, BackupEntry>()
        for ((key, value) in all) {
            val name = key.name
            if (name in excludedKeyNames) continue
            entries[name] = when (value) {
                is Boolean -> BackupEntry("boolean", boolValue = value)
                is Int -> BackupEntry("int", intValue = value)
                is Long -> BackupEntry("long", longValue = value)
                is String -> BackupEntry("string", stringValue = value)
                is Float -> BackupEntry("float", floatValue = value)
                else -> continue  // skip unknown types (sets, doubles, etc.) — not used by us
            }
        }
        val payload = BackupPayload(
            version = BACKUP_FORMAT_VERSION,
            createdAtMs = System.currentTimeMillis(),
            appVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName,
            values = entries,
        )
        return json.encodeToString(BackupPayload.serializer(), payload)
    }

    /** Reverse of buildPayloadJson — write an entry back into the DataStore. */
    private fun applyEntry(
        store: androidx.datastore.preferences.core.MutablePreferences,
        name: String,
        entry: BackupEntry,
    ) {
        when (entry.type) {
            "boolean" -> entry.boolValue?.let {
                store[androidx.datastore.preferences.core.booleanPreferencesKey(name)] = it
            }
            "int" -> entry.intValue?.let {
                store[androidx.datastore.preferences.core.intPreferencesKey(name)] = it
            }
            "long" -> entry.longValue?.let {
                store[androidx.datastore.preferences.core.longPreferencesKey(name)] = it
            }
            "string" -> entry.stringValue?.let {
                store[androidx.datastore.preferences.core.stringPreferencesKey(name)] = it
            }
            "float" -> entry.floatValue?.let {
                store[androidx.datastore.preferences.core.floatPreferencesKey(name)] = it
            }
        }
    }

    @Serializable
    data class BackupPayload(
        val version: Int,
        val createdAtMs: Long,
        val appVersion: String?,
        val values: Map<String, BackupEntry>,
    )

    @Serializable
    data class BackupEntry(
        val type: String,
        val boolValue: Boolean? = null,
        val intValue: Int? = null,
        val longValue: Long? = null,
        val stringValue: String? = null,
        val floatValue: Float? = null,
    )

    companion object {
        private const val TAG = "BackupManager"
        const val BACKUP_FORMAT_VERSION = 1
    }
}
