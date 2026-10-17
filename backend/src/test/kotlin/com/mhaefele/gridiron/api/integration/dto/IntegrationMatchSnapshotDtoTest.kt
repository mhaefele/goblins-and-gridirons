package com.mhaefele.gridiron.api.integration.dto

import com.mhaefele.gridiron.api.mapping.toBonusHandDto
import com.mhaefele.gridiron.api.mapping.toMatchSnapshotDto
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.matchState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class IntegrationMatchSnapshotDtoTest {
    private val json = JsonMapper.builder().addModule(kotlinModule()).build()

    @Test
    fun `JSON flattens snapshot fields alongside bonusHand`() {
        val state = matchState()
        val dto = IntegrationMatchSnapshotDto(
            snapshot = toMatchSnapshotDto(state),
            bonusHand = toBonusHandDto(state.cards, TeamSide.HOME),
        )

        val tree = json.readTree(json.writeValueAsString(dto))

        assertThat(tree.has("snapshot")).isFalse()
        assertThat(tree.has("lastEventId")).isTrue()
        assertThat(tree.has("gameState")).isTrue()
        assertThat(tree.has("pieces")).isTrue()
        assertThat(tree.has("bonusHand")).isTrue()
    }
}
