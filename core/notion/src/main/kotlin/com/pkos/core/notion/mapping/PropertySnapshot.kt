package com.pkos.core.notion.mapping

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * Normalizes the PKOS-managed property values of a retrieved Notion page and hashes them.
 * If the hash differs from the one stored after our last write, someone edited the page in Notion,
 * so PKOS must not overwrite it silently (conflict). Unlike last_edited_time this is not minute-rounded.
 */
object PropertySnapshot {

    fun hash(page: JsonObject, propertyNames: Collection<String>): String {
        val props = page["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val canonical = propertyNames.sorted().joinToString("\u001F") { name ->
            name + "=" + normalize(props[name])
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun plainText(property: JsonElement?): String {
        val obj = property as? JsonObject ?: return ""
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return ""
        val arr = obj[type] as? kotlinx.serialization.json.JsonArray ?: return ""
        return arr.joinToString("") { el ->
            val o = el.jsonObject
            o["plain_text"]?.jsonPrimitive?.contentOrNull
                ?: o["text"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                ?: ""
        }
    }

    private fun normalize(property: JsonElement?): String {
        val obj = property as? JsonObject ?: return "∅"
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return "∅"
        val value = obj[type]
        return when (type) {
            "title", "rich_text" -> plainText(obj)
            "select", "status" -> (value as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull ?: "∅"
            "multi_select" -> (value as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }?.sorted()?.joinToString(",") ?: "∅"
            "url", "checkbox", "number" -> (value as? JsonPrimitive)?.contentOrNull ?: "∅"
            "date" -> (value as? JsonObject)?.let {
                (it["start"]?.jsonPrimitive?.contentOrNull ?: "") + "/" + (it["end"]?.jsonPrimitive?.contentOrNull ?: "")
            } ?: "∅"
            "files" -> value?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }?.joinToString(",") ?: "∅"
            else -> value.toString()
        }
    }
}
