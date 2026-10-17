package com.mhaefele.gridiron.spring

import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class IntegrationOpenApiConfiguration {
    @Bean
    fun integrationOpenApi(): GroupedOpenApi =
        GroupedOpenApi.builder().group("integration").pathsToMatch("/api/integration/**").build()

    /** Springdoc does not flatten Jackson [@JsonUnwrapped] — merge manually for codegen. */
    @Bean
    fun integrationSnapshotOpenApiCustomizer(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            val schemas = openApi.components?.schemas ?: return@OpenApiCustomizer
            flattenUnwrappedSchema(
                schemas = schemas,
                wrapper = "IntegrationMatchSnapshotDto",
                unwrappedRef = "MatchSnapshotDto",
                wrapperProperty = "snapshot",
            )
        }
}

private fun flattenUnwrappedSchema(
    schemas: MutableMap<String, Schema<*>>,
    wrapper: String,
    unwrappedRef: String,
    wrapperProperty: String,
) {
    val target = schemas[wrapper] ?: return
    val unwrapped = schemas[unwrappedRef] ?: return
    target.properties?.remove(wrapperProperty)
    unwrapped.properties?.forEach { (key, value) -> target.addProperty(key, value) }
    unwrapped.required?.forEach { target.addRequiredItem(it) }
    target.allOf = null
    target.type = "object"
    target.addType("object")
}
