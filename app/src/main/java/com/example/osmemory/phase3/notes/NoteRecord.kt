package com.example.osmemory.phase3.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * A deliberately small, title-free note model.
 *
 * [linkedMemoId] is the stable bridge to OS Memory.  The content hash records
 * exactly which local revision was last accepted by the memory service, so a
 * later local edit can offer an explicit memory update instead of silently
 * overwriting the associated memory.
 */
internal data class NoteRecord(
    val id: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val linkedMemoId: String? = null,
    val linkedContentHash: String? = null
) {
    val isLinked: Boolean get() = !linkedMemoId.isNullOrBlank()

    val memoryIsCurrent: Boolean
        get() = isLinked && linkedContentHash == content.contentHash()
}

/** SharedPreferences + JSON storage kept private to the notes mini-app. */
internal class NotesStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun all(): List<NoteRecord> = synchronized(STORE_LOCK) {
        readUnsafe().sortedByDescending(NoteRecord::updatedAt)
    }

    fun find(id: String): NoteRecord? = synchronized(STORE_LOCK) {
        readUnsafe().firstOrNull { it.id == id }
    }

    fun create(): NoteRecord = synchronized(STORE_LOCK) {
        val now = System.currentTimeMillis()
        val note = NoteRecord(
            id = "NOTE-$now-${UUID.randomUUID().toString().take(8)}",
            content = "",
            createdAt = now,
            updatedAt = now
        )
        writeUnsafe(readUnsafe() + note)
        note
    }

    fun upsert(note: NoteRecord) = synchronized(STORE_LOCK) {
        val notes = readUnsafe().toMutableList()
        val index = notes.indexOfFirst { it.id == note.id }
        if (index >= 0) notes[index] = note else notes += note
        writeUnsafe(notes)
    }

    fun delete(id: String) = synchronized(STORE_LOCK) {
        writeUnsafe(readUnsafe().filterNot { it.id == id })
    }

    private fun readUnsafe(): List<NoteRecord> {
        val raw = preferences.getString(KEY_NOTES_JSON, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val id = json.optString(FIELD_ID).trim()
                    if (id.isEmpty()) continue

                    val createdAt = json.optLong(FIELD_CREATED_AT, 0L)
                        .takeIf { it > 0L } ?: System.currentTimeMillis()
                    add(
                        NoteRecord(
                            id = id,
                            content = json.optString(FIELD_CONTENT, ""),
                            createdAt = createdAt,
                            updatedAt = json.optLong(FIELD_UPDATED_AT, createdAt),
                            linkedMemoId = json.optionalString(FIELD_LINKED_MEMO_ID),
                            linkedContentHash = json.optionalString(FIELD_LINKED_CONTENT_HASH)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // A damaged preference must not crash the mini-app.  It remains
            // untouched until the next explicit user edit creates valid JSON.
            emptyList()
        }
    }

    private fun writeUnsafe(notes: List<NoteRecord>) {
        val array = JSONArray()
        notes.forEach { note ->
            array.put(
                JSONObject()
                    .put(FIELD_ID, note.id)
                    .put(FIELD_CONTENT, note.content)
                    .put(FIELD_CREATED_AT, note.createdAt)
                    .put(FIELD_UPDATED_AT, note.updatedAt)
                    .put(FIELD_LINKED_MEMO_ID, note.linkedMemoId ?: JSONObject.NULL)
                    .put(FIELD_LINKED_CONTENT_HASH, note.linkedContentHash ?: JSONObject.NULL)
            )
        }
        preferences.edit().putString(KEY_NOTES_JSON, array.toString()).apply()
    }

    private fun JSONObject.optionalString(name: String): String? {
        if (isNull(name)) return null
        return optString(name).trim().takeIf(String::isNotEmpty)
    }

    private companion object {
        val STORE_LOCK = Any()
        const val PREFERENCES_NAME = "phase3_notes"
        const val KEY_NOTES_JSON = "notes_json_v1"
        const val FIELD_ID = "id"
        const val FIELD_CONTENT = "content"
        const val FIELD_CREATED_AT = "createdAt"
        const val FIELD_UPDATED_AT = "updatedAt"
        const val FIELD_LINKED_MEMO_ID = "linkedMemoId"
        const val FIELD_LINKED_CONTENT_HASH = "linkedContentHash"
    }
}

internal fun String.contentHash(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
