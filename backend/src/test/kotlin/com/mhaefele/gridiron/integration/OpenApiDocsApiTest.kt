package com.mhaefele.gridiron.integration

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.mhaefele.gridiron.api.ai.dto.AiFactor
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.integration.support.BaseApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestTemplate

class OpenApiDocsApiTest : BaseApiTest() {

    @Autowired
    private lateinit var restTemplate: RestTemplate

    @Test
    fun `OpenAPI docs are available and list match endpoints`() {
        val response = restTemplate.exchange(
            "/v3/api-docs",
            HttpMethod.GET,
            null,
            object : ParameterizedTypeReference<Map<String, Any>>() {},
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body ?: error("Expected OpenAPI body")
        @Suppress("UNCHECKED_CAST")
        val paths = body["paths"] as Map<String, Any>
        assertThat(paths.keys).anyMatch { it.startsWith("/api/matches") }
        assertThat(paths.keys).anyMatch { it.startsWith("/api/catalog") }
        assertThat(paths.keys).anyMatch { it.startsWith("/api/ai/") }
        assertThat(paths.keys).anyMatch { it.startsWith("/api/integration/") }
    }

    @Test
    fun `integration OpenAPI group lists only integration paths`() {
        val response = restTemplate.exchange(
            "/v3/api-docs/integration",
            HttpMethod.GET,
            null,
            object : ParameterizedTypeReference<Map<String, Any>>() {},
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val paths = (response.body ?: error("Expected OpenAPI body"))["paths"] as Map<String, Any>
        assertThat(paths.keys).allMatch { it.startsWith("/api/integration/") }
        assertThat(paths.keys).anyMatch { it == "/api/integration/matches" }
    }

    @Test
    fun `integration OpenAPI flattens IntegrationMatchSnapshotDto like JsonUnwrapped JSON`() {
        val response = restTemplate.exchange(
            "/v3/api-docs/integration",
            HttpMethod.GET,
            null,
            object : ParameterizedTypeReference<Map<String, Any>>() {},
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val schemas = ((response.body ?: error("Expected OpenAPI body"))["components"] as Map<String, Any>)["schemas"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val schema = schemas["IntegrationMatchSnapshotDto"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val properties = schema["properties"] as Map<String, Any>

        assertThat(properties.keys).containsExactlyInAnyOrder("lastEventId", "gameState", "pieces", "bonusHand")
        assertThat(schema).doesNotContainKey("allOf")
    }

    @Test
    fun `DomainEvent and LegalAction schemas are oneOf discriminated by kind`() {
        val response = restTemplate.exchange(
            "/v3/api-docs",
            HttpMethod.GET,
            null,
            object : ParameterizedTypeReference<Map<String, Any>>() {},
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val schemas = (response.body!!["components"] as Map<String, Any>)["schemas"] as Map<String, Any>

        assertDiscriminatedSealedSchema(schemas, "DomainEvent", DomainEvent::class.java)
        assertDiscriminatedSealedSchema(schemas, "LegalAction", LegalAction::class.java)
        assertDiscriminatedSealedSchema(schemas, "AiFactor", AiFactor::class.java)
    }

    @Test
    fun `DomainId schemas are plain strings matching Jackson wire shape`() {
        val response = restTemplate.exchange(
            "/v3/api-docs",
            HttpMethod.GET,
            null,
            object : ParameterizedTypeReference<Map<String, Any>>() {},
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val schemas = (response.body!!["components"] as Map<String, Any>)["schemas"] as Map<String, Any>

        for (name in listOf("MatchId", "PlayerId", "TeamCatalogId", "PitchDefinitionId", "ChallengeCardId", "LegalActionId")) {
            @Suppress("UNCHECKED_CAST")
            val schema = schemas[name] as Map<String, Any>
            assertThat(schema["type"]).isEqualTo("string")
            assertThat(schema).doesNotContainKey("properties")
        }
    }

    private fun assertDiscriminatedSealedSchema(
        schemas: Map<String, Any>,
        schemaName: String,
        type: Class<*>,
    ) {
        @Suppress("UNCHECKED_CAST")
        val schema = schemas[schemaName] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val oneOf = schema["oneOf"] as List<Map<String, Any>>
        @Suppress("UNCHECKED_CAST")
        val discriminator = schema["discriminator"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val mapping = discriminator["mapping"] as Map<String, String>

        assertThat(discriminator["propertyName"]).isEqualTo("kind")

        val expectedKinds = requireNotNull(type.getAnnotation(JsonSubTypes::class.java)).value
            .map { it.name }
        assertThat(mapping.keys).containsExactlyInAnyOrderElementsOf(expectedKinds)

        for (sub in requireNotNull(type.getAnnotation(JsonSubTypes::class.java)).value) {
            val ref = "#/components/schemas/${sub.value.simpleName}"
            assertThat(mapping[sub.name]).isEqualTo(ref)
            assertThat(oneOf).anyMatch { it["\$ref"] == ref }
            @Suppress("UNCHECKED_CAST")
            val leaf = schemas[sub.value.simpleName] as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val properties = leaf["properties"] as Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val kindSchema = properties["kind"] as Map<String, Any>
            assertThat(kindSchema["enum"]).isEqualTo(listOf(sub.name))
        }
    }
}
