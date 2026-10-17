package com.mhaefele.gridiron.spring

import com.mhaefele.gridiron.shared.DomainId
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.PlayerId
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter

@Configuration
class DomainIdConverters {
    @Bean
    fun matchIdConverter(): Converter<String, MatchId> =
        stringToDomainId(::MatchId)

    @Bean
    fun playerIdConverter(): Converter<String, PlayerId> =
        stringToDomainId(::PlayerId)

    @Bean
    fun LegalMoveIdConverter(): Converter<String, LegalActionId> =
        stringToDomainId(::LegalActionId)
}

private inline fun <T : DomainId> stringToDomainId(
    crossinline factory: (String) -> T,
): Converter<String, T> =
    Converter { source ->
        require(source.isNotBlank()) { "id must not be blank" }
        factory(source)
    }
