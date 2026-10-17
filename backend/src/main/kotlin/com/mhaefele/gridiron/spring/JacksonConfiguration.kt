package com.mhaefele.gridiron.spring

import com.mhaefele.gridiron.shared.DomainId
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.model.catalog.ChallengeCardId
import com.mhaefele.gridiron.model.catalog.PitchDefinitionId
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.ser.std.StdSerializer
import tools.jackson.databind.deser.std.StdDeserializer

/**
 * Jackson module for cross-cutting serializers that annotations cannot express cleanly
 * (e.g. [DomainId] value types). Type-specific wire shape belongs on the type itself —
 * see `.cursor/rules/api-design.mdc`.
 */
@Configuration
class JacksonConfiguration {

    @Bean
    fun gridironFDomainModule(): SimpleModule =
        SimpleModule("gridiron-domain")
            .registerDomainId(PlayerId::class.java, ::PlayerId)
            .registerDomainId(MatchId::class.java, ::MatchId)
            .registerDomainId(TeamCatalogId::class.java, ::TeamCatalogId)
            .registerDomainId(PitchDefinitionId::class.java, ::PitchDefinitionId)
            .registerDomainId(ChallengeCardId::class.java, ::ChallengeCardId)
            .registerDomainId(LegalActionId::class.java, ::LegalActionId)
}

private fun <T : DomainId> SimpleModule.registerDomainId(
    type: Class<T>,
    factory: (String) -> T,
): SimpleModule =
    addSerializer(type, DomainIdValueSerializer(type))
        .addDeserializer(type, DomainIdValueDeserializer(type, factory))

private class DomainIdValueSerializer<T : DomainId>(type: Class<T>) : StdSerializer<T>(type) {
    override fun serialize(value: T, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeString(value.value)
    }
}

private class DomainIdValueDeserializer<T : DomainId>(
    type: Class<T>,
    private val factory: (String) -> T,
) : StdDeserializer<T>(type) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): T {
        val text = p.valueAsString
        if (text.isBlank()) {
            throw ctxt.weirdStringException(text, handledType(), "id must not be blank")
        }
        return factory(text)
    }
}
