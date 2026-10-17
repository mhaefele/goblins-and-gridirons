package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.request.CreateMatchRequest
import com.mhaefele.gridiron.api.dto.response.MatchRosterPlayerDto
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.integration.support.BaseApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MatchRosterApiTest : BaseApiTest() {

    @Test
    fun `roster lists one player card per deployed player`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A
        val match = api.createAndStartMatch(dwarvesVsOrcs())

        val roster = api.roster(match)
        val pieces = api.pieces(match)

        assertThat(roster.players).hasSize(12)
        assertThat(roster.players.map { it.playerId }).containsExactlyInAnyOrderElementsOf(
            pieces.players.map { it.id },
        )
    }

    @Test
    fun `dwarf and orc linemen expose structured player cards`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A
        val match = api.createAndStartMatch(dwarvesVsOrcs())

        val roster = api.roster(match)
        val pieces = api.pieces(match)
        val teamById = pieces.players.associate { it.id to it.team }

        val dwarfLinemen = roster.players.filterLinemen(teamById, TeamSide.HOME)
        val orcLinemen = roster.players.filterLinemen(teamById, TeamSide.AWAY)

        assertThat(dwarfLinemen).hasSize(3)
        assertThat(dwarfLinemen).allMatch { entry ->
            entry.playerCard.teamId == TeamCatalog.DWARVES_ID &&
                entry.playerCard.move == 4 &&
                entry.playerCard.blockDiceCount == 1 &&
                entry.playerCard.throwMinimumRoll == 5 &&
                entry.playerCard.armourMinimumRoll == 2 &&
                entry.playerCard.ability == null
        }

        assertThat(orcLinemen).hasSize(3)
        assertThat(orcLinemen).allMatch { entry ->
            entry.playerCard.teamId == TeamCatalog.ORCS_ID &&
                entry.playerCard.move == 5 &&
                entry.playerCard.blockDiceCount == 1 &&
                entry.playerCard.throwMinimumRoll == 4 &&
                entry.playerCard.armourMinimumRoll == 3 &&
                entry.playerCard.ability == null
        }
    }

    @Test
    fun `specialist player cards expose ability key and description`() {
        dice.queueD6(4) // AWAY wins toss → HOME is Coach A
        val match = api.createAndStartMatch(dwarvesVsOrcs())

        val bruiser = api.roster(match).players.single { it.playerCard.positionLabel == "Bruiser" }

        assertThat(bruiser.playerCard.ability).isNotNull
        assertThat(bruiser.playerCard.ability!!.key).isEqualTo("Heavy Hitter")
        assertThat(bruiser.playerCard.ability!!.description).contains("Smash")
        assertThat(bruiser.playerCard.blockDiceCount).isEqualTo(2)
    }

    private fun dwarvesVsOrcs() =
        CreateMatchRequest(
            homeTeamId = TeamCatalog.DWARVES_ID,
            awayTeamId = TeamCatalog.ORCS_ID,
        )

    private fun List<MatchRosterPlayerDto>.filterLinemen(
        teamById: Map<PlayerId, TeamSide>,
        team: TeamSide,
    ): List<MatchRosterPlayerDto> =
        filter { teamById[it.playerId] == team && it.playerCard.positionLabel == "Lineman" }
}
