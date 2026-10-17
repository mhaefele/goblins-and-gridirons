package com.mhaefele.gridiron.spring

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.mhaefele.gridiron.api.ai.dto.AiFactor
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import io.swagger.v3.oas.models.media.Discriminator
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Springdoc infers [discriminator.propertyName] from Jackson `@JsonTypeInfo` but does not emit
 * `oneOf` + `discriminator.mapping` for sealed parents — and subtype `allOf` `$ref`s back to the
 * parent, which becomes a cycle once the parent is a `oneOf`. Rewrite both sealed wire types from
 * their `@JsonSubTypes` so OpenAPI matches the Jackson `kind` contract for TS codegen.
 *
 * Also rewrites [DomainId] value types to `string` (Jackson serializes them as plain JSON strings —
 * see [JacksonConfiguration]) and stamps `type: object` on sealed leaves so `openapi-typescript`
 * accepts the discriminator mapping.
 */
@Configuration
class OpenApiSealedTypesConfiguration {

    @Bean
    fun sealedTypesOpenApiCustomizer(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            val schemas = openApi.components?.schemas ?: return@OpenApiCustomizer
            rewriteDomainIdSchemas(schemas)
            rewriteSealedParent(schemas, DomainEvent::class.java)
            rewriteSealedParent(schemas, LegalAction::class.java)
            rewriteSealedParent(schemas, AiFactor::class.java)
            // Nest event under DomainEvent so list/SSE payloads share the same union.
            schemas["StoredDomainEvent"]?.properties?.get("event")?.let { event ->
                event.oneOf = null
                event.`$ref` = "#/components/schemas/DomainEvent"
            }
        }
}

/** Wire shape for [DomainId] subtypes — plain JSON string, not `{ "value": "…" }`. */
private fun rewriteDomainIdSchemas(schemas: MutableMap<String, Schema<*>>) {
    for (name in listOf("MatchId", "PlayerId", "TeamCatalogId", "PitchDefinitionId", "ChallengeCardId", "LegalActionId")) {
        schemas[name] = StringSchema()
    }
}

private fun rewriteSealedParent(schemas: MutableMap<String, Schema<*>>, type: Class<*>) {
    val subTypes = type.getAnnotation(JsonSubTypes::class.java) ?: error("${type.simpleName} must declare @JsonSubTypes for OpenAPI")
    val typeInfo = type.getAnnotation(JsonTypeInfo::class.java) ?: error("${type.simpleName} must declare @JsonTypeInfo for OpenAPI")
    val parentName = type.simpleName
    val discriminatorProperty = typeInfo.property

    val mapping = linkedMapOf<String, String>()
    val oneOf = mutableListOf<Schema<*>>()
    for (sub in subTypes.value) {
        val leafClass = sub.value.java
        val schemaName = leafClass.simpleName
        val kind = sub.name
        val subtypeSchema = schemas[schemaName] ?: error("Missing OpenAPI schema for $schemaName (from ${type.simpleName}.$kind)")
        flattenAwayParentRef(subtypeSchema, parentName, schemas)
        for (intermediate in sealedIntermediates(leafClass, type)) {
            schemas[intermediate]?.let { mergeConcreteParts(subtypeSchema, it, parentName, schemas) }
        }
        ensureKindProperty(subtypeSchema, kind)
        mapping[kind] = "#/components/schemas/$schemaName"
        oneOf += Schema<Any>().apply { `$ref` = "#/components/schemas/$schemaName" }
    }

    schemas[parentName] = Schema<Any>().apply {
        this.oneOf = oneOf
        discriminator = Discriminator().apply {
            propertyName = discriminatorProperty
            setMapping(mapping)
        }
    }
}

/**
 * Drop `$ref` to [parentName] from `allOf` (avoids parent↔child cycles once the parent becomes
 * `oneOf`). Merge remaining composed parts into a flat object schema.
 */
private fun flattenAwayParentRef(
    schema: Schema<*>,
    parentName: String,
    schemas: Map<String, Schema<*>>,
) {
    val parts = schema.allOf?.toList().orEmpty()
    if (parts.isEmpty()) return

    val kept = parts.filterNot { it.`$ref`?.endsWith("/$parentName") == true }
    schema.allOf = null
    schema.type = "object"
    schema.addType("object")
    for (part in kept) {
        mergePart(schema, part, parentName, schemas)
    }
}

/** Copy non-parent properties from an intermediate sealed type (e.g. PathMovement → Run). */
private fun mergeConcreteParts(
    target: Schema<*>,
    intermediate: Schema<*>,
    parentName: String,
    schemas: Map<String, Schema<*>>,
) {
    val parts = intermediate.allOf?.toList().orEmpty()
    if (parts.isEmpty()) {
        intermediate.properties?.forEach { (key, value) -> target.addProperty(key, value) }
        intermediate.required?.forEach { target.addRequiredItem(it) }
        return
    }
    for (part in parts.filterNot { it.`$ref`?.endsWith("/$parentName") == true }) {
        mergePart(target, part, parentName, schemas)
    }
}

private fun mergePart(
    target: Schema<*>,
    part: Schema<*>,
    parentName: String,
    schemas: Map<String, Schema<*>>,
) {
    val ref = part.`$ref`
    if (ref != null) {
        val name = ref.substringAfterLast('/')
        if (name == parentName) return
        val resolved = schemas[name] ?: return
        mergeConcreteParts(target, resolved, parentName, schemas)
        return
    }
    part.properties?.forEach { (key, value) -> target.addProperty(key, value) }
    part.required?.forEach { target.addRequiredItem(it) }
}

/** Interface / sealed names between a Jackson leaf and the annotated parent (exclusive). */
private fun sealedIntermediates(leaf: Class<*>, root: Class<*>): List<String> {
    val names = linkedSetOf<String>()
    fun visit(type: Class<*>) {
        if (type == root || !root.isAssignableFrom(type)) return
        if (type != leaf) names += type.simpleName
        type.interfaces.forEach(::visit)
    }
    visit(leaf)
    return names.toList()
}

private fun ensureKindProperty(schema: Schema<*>, kind: String) {
    schema.type = "object"
    schema.addType("object")
    schema.addProperty(
        "kind",
        StringSchema().apply {
            setEnum(listOf(kind))
        },
    )
    schema.addRequiredItem("kind")
}
