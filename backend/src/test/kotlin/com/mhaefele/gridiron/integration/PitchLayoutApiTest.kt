package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.model.catalog.PitchCatalog
import com.mhaefele.gridiron.model.catalog.PitchDefinitionId
import com.mhaefele.gridiron.integration.support.BaseApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class PitchLayoutApiTest : BaseApiTest() {

    @ParameterizedTest
    @CsvSource("default", "barricade")
    fun `pitch layout matches catalog definition`(pitchId: String) {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A
        if (pitchId == PitchCatalog.BARRICADE_ID.value) dice.queueD6(1) // trapdoor pick
        val match = api.createAndStartMatch(
            api.defaultCreateMatchRequest().copy(pitchDefinitionId = PitchDefinitionId(pitchId)),
        )

        val layout = api.pitch(match)
        val definition = PitchCatalog.definition(pitchId)

        assertThat(layout.pitchDefinitionId).isEqualTo(PitchDefinitionId(pitchId))
        assertThat(layout.playWidth).isEqualTo(definition.playWidth)
        assertThat(layout.playHeight).isEqualTo(definition.playHeight)
        assertThat(layout.homeEndZoneRow).isEqualTo(1)
        assertThat(layout.awayEndZoneRow).isEqualTo(definition.playHeight)
        assertThat(layout.trapdoors).containsExactlyInAnyOrderElementsOf(definition.trapdoors)
        assertThat(layout.blockedSquares).containsExactlyInAnyOrderElementsOf(definition.blockedSquares)
    }

    @Test
    fun `default pitch exposes center trapdoor and pillar obstacles`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A
        val match = api.createAndStartMatch(api.defaultCreateMatchRequest())

        val layout = api.pitch(match)

        assertThat(layout.pitchDefinitionId).isEqualTo(PitchCatalog.DEFAULT_ID)
        assertThat(layout.trapdoors).containsExactly(Coordinate(6, 8))
        assertThat(layout.blockedSquares).contains(Coordinate(3, 4), Coordinate(9, 12))
    }

    @Test
    fun `barricade pitch exposes two trapdoors and barricade squares`() {
        dice.queueD6(4, 1) // HOME Coach A; trapdoor pick unused until placement
        val match = api.createAndStartMatch(
            api.defaultCreateMatchRequest().copy(
                pitchDefinitionId = PitchCatalog.BARRICADE_ID,
            ),
        )

        val layout = api.pitch(match)

        assertThat(layout.trapdoors).containsExactlyInAnyOrder(
            Coordinate(3, 8),
            Coordinate(9, 8),
        )
        assertThat(layout.blockedSquares).contains(Coordinate(6, 8), Coordinate(6, 3), Coordinate(4, 5))
    }
}
