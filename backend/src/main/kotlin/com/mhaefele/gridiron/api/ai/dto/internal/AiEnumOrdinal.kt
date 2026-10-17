package com.mhaefele.gridiron.api.ai.dto.internal

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.ser.std.StdSerializer

/**
 * AI API wire: enums as **0-based ordinals** (not names). GUI DTOs stay name-based.
 *
 * Apply on AI DTO properties only — never put ordinal `@JsonValue` on shared domain enums.
 *
 * Packable obs still use [AiObsEncoding] in the harness: JSON `null` → vector `0`,
 * else `ordinal + 1`. Wire `null` stays JSON null (not `0`).
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@JacksonAnnotationsInside
@JsonSerialize(using = AiEnumOrdinalSerializer::class)
@JsonDeserialize(using = AiEnumOrdinalDeserializer::class)
annotation class AiEnumOrdinal

/**
 * Same as [AiEnumOrdinal] for collection **elements** (`List<BonusPlay?>`, …).
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@JacksonAnnotationsInside
@JsonSerialize(contentUsing = AiEnumOrdinalSerializer::class)
@JsonDeserialize(contentUsing = AiEnumOrdinalDeserializer::class)
annotation class AiEnumOrdinalContent

/** Writes `enum.ordinal` as a JSON number. */
class AiEnumOrdinalSerializer : StdSerializer<Enum<*>>(Enum::class.java) {
    override fun serialize(value: Enum<*>, gen: JsonGenerator, ctxt: SerializationContext) {
        gen.writeNumber(value.ordinal)
    }
}

/**
 * Reads a JSON number into the property’s concrete enum type via [createContextual].
 */
class AiEnumOrdinalDeserializer : StdDeserializer<Enum<*>>(Enum::class.java) {

    override fun createContextual(ctxt: DeserializationContext, property: BeanProperty?): ValueDeserializer<*> {
        if (property == null) return this
        val raw = property.type.rawClass
        @Suppress("UNCHECKED_CAST")
        if (raw.isEnum) {
            return ForEnum(raw as Class<out Enum<*>>)
        }
        // contentUsing: resolve the element type of List/array.
        val content = property.type.containedTypeOrUnknown(0)?.rawClass
        @Suppress("UNCHECKED_CAST")
        if (content != null && content.isEnum) {
            return ForEnum(content as Class<out Enum<*>>)
        }
        return this
    }

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Enum<*> {
        throw ctxt.weirdNumberException(
            p.numberValue,
            Enum::class.java,
            "AiEnumOrdinalDeserializer used without contextual enum type",
        )
    }

    private class ForEnum(
        private val type: Class<out Enum<*>>,
    ) : ValueDeserializer<Enum<*>>() {
        private val constants: Array<out Enum<*>> = type.enumConstants

        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Enum<*> {
            val ordinal = p.intValue
            if (ordinal !in constants.indices) {
                throw ctxt.weirdNumberException(
                    ordinal,
                    type,
                    "ordinal out of range 0..${constants.lastIndex}",
                )
            }
            return constants[ordinal]
        }
    }
}
