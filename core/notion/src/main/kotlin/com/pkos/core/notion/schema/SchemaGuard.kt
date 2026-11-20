package com.pkos.core.notion.schema

/** Every Notion property PKOS may write. Anything not listed here is never touched. */
enum class FieldKey {
    TITLE, CAPTURE_ID, LINK_URL, DATE_ADDED, TYPE, STATUS, PRIORITY, PINNED, TAGS, CATEGORY, PLATFORM, FILES,
}

data class FieldSpec(
    val key: FieldKey,
    val propertyName: String,
    val expectedType: String,
    val required: Boolean,
)

/**
 * Approved mapping for "My knowledge Hub → Master Database" (decision 2026-11-20).
 * Names are exact, including the trailing spaces in "category " and "platform ".
 * Notes, Parent item and Sub-item are intentionally absent: PKOS never writes them.
 */
object MasterDatabaseFields {
    val specs: List<FieldSpec> = listOf(
        FieldSpec(FieldKey.TITLE, "Topic name", "title", required = true),
        FieldSpec(FieldKey.CAPTURE_ID, "Capture ID", "rich_text", required = true),
        FieldSpec(FieldKey.LINK_URL, "Link/URL", "url", required = false),
        FieldSpec(FieldKey.DATE_ADDED, "Date Added", "date", required = false),
        FieldSpec(FieldKey.TYPE, "Type", "select", required = false),
        FieldSpec(FieldKey.STATUS, "Status", "select", required = false),
        FieldSpec(FieldKey.PRIORITY, "Priority", "select", required = false),
        FieldSpec(FieldKey.PINNED, "Pinned", "checkbox", required = false),
        FieldSpec(FieldKey.TAGS, "Tags", "multi_select", required = false),
        FieldSpec(FieldKey.CATEGORY, "category ", "multi_select", required = false),
        FieldSpec(FieldKey.PLATFORM, "platform ", "select", required = false),
        FieldSpec(FieldKey.FILES, "Files & media", "files", required = false),
    )
}

data class SchemaProblem(
    val field: FieldKey,
    val propertyName: String,
    val kind: Kind,
    val expectedType: String,
    val actualType: String?,
    val blocking: Boolean,
) {
    enum class Kind { MISSING, WRONG_TYPE }
}

data class SchemaCheck(
    val schema: DataSourceSchema,
    val usable: Map<FieldKey, NotionPropertyDef>,
    val problems: List<SchemaProblem>,
) {
    val blocked: Boolean get() = problems.any { it.blocking }
}

object SchemaGuard {
    fun check(schema: DataSourceSchema, specs: List<FieldSpec> = MasterDatabaseFields.specs): SchemaCheck {
        val usable = mutableMapOf<FieldKey, NotionPropertyDef>()
        val problems = mutableListOf<SchemaProblem>()
        for (spec in specs) {
            val def = schema.property(spec.propertyName)
            when {
                def == null -> problems += SchemaProblem(
                    spec.key, spec.propertyName, SchemaProblem.Kind.MISSING, spec.expectedType, null, spec.required,
                )
                def.type != spec.expectedType -> problems += SchemaProblem(
                    spec.key, spec.propertyName, SchemaProblem.Kind.WRONG_TYPE, spec.expectedType, def.type, spec.required,
                )
                else -> usable[spec.key] = def
            }
        }
        return SchemaCheck(schema, usable, problems)
    }
}
