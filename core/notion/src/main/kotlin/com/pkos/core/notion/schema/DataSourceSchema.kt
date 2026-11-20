package com.pkos.core.notion.schema

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

data class NotionPropertyDef(
    val id: String,
    /** Exact name as stored in Notion, including any trailing spaces (e.g. "category "). */
    val name: String,
    val type: String,
    val options: List<String> = emptyList(),
)

data class DataSourceSchema(
    val dataSourceId: String,
    val properties: Map<String, NotionPropertyDef>,
) {
    fun property(name: String): NotionPropertyDef? = properties[name]
}

object OptionMatcher {
    fun normalize(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    /** Returns the exact live option name matching any candidate, or null. Never invents options. */
    fun resolve(def: NotionPropertyDef, candidates: List<String>): String? {
        val byNormalized = def.options.associateBy { normalize(it) }
        return candidates.asSequence().map { byNormalized[normalize(it)] }.firstOrNull { it != null }
    }
}

object DataSourceSchemaParser {
    private val optionTypes = setOf("select", "multi_select", "status")

    fun parse(json: JsonObject): DataSourceSchema {
        val id = json["id"]?.jsonPrimitive?.contentOrNull ?: error("Data source id missing")
        val props = json["properties"]?.jsonObject ?: error("Data source properties missing")
        val defs = props.mapValues { (key, value) ->
            val obj = value.jsonObject
            val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val options = if (type in optionTypes) {
                obj[type]?.jsonObject?.get("options")?.jsonArray
                    ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                    .orEmpty()
            } else {
                emptyList()
            }
            NotionPropertyDef(
                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: key,
                name = key,
                type = type,
                options = options,
            )
        }
        return DataSourceSchema(id, defs)
    }

    /** For a database response (2025-09-03+), returns its data source ids. */
    fun dataSourceIds(databaseJson: JsonObject): List<String> =
        databaseJson["data_sources"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
}
