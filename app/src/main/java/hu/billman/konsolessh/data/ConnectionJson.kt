package hu.billman.konsolessh.data

import hu.billman.konsolessh.model.ConnectionConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The JSON format of the saved connection list.
 *
 * Versions up to 1.1.30 wrote this list with Gson, and their stores must
 * stay readable. The field names and enum values are the same as Gson's;
 * the settings below cover the rest:
 * - a field missing from an older version takes the constructor default;
 * - a field this version does not know is skipped, so a store written by a
 *   newer version can still be opened;
 * - a null or unknown enum value falls back to the default instead of
 *   failing the whole list.
 *
 * Every field is written, defaults included, as Gson did, so an older
 * version can read what this one writes.
 */
internal object ConnectionJson {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    private val listSerializer = ListSerializer(ConnectionConfig.serializer())

    fun encode(connections: List<ConnectionConfig>): String = json.encodeToString(listSerializer, connections)

    fun decode(text: String): List<ConnectionConfig> = json.decodeFromString(listSerializer, text)
}
