package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.Path
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.assertAny
import com.mhaefele.gridiron.support.assertNone
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Single nullable pending: pre-turn grants one-at-a-time; mid-action follow-ups on *Continuation.
 * Asserts via legal actions / gameState — not domain pending peeks.
 */
class CompoundPendingQueueApiTest : BaseApiTest() {

    private fun profile(teamId: String, name: String) =
        TeamCatalog.team(teamId).roster.first { it.name == name }

    @Test
    fun `two prone Undying players offer one grant at a time`() {
        lateinit var first: PlacedPlayer
        lateinit var second: PlacedPlayer
        val match = match {
            activeCoach(TeamSide.AWAY)
            actionsRemaining(1)
            addPlayer(5, 6, team = TeamSide.AWAY)
            addPlayer(6, 6)
            addLooseBall(7, 8)
            first = addPlayer(8, 6, status = PlayerStatus.PRONE, profile = profile("restless-dead", "Skeleton"))
            second = addPlayer(9, 6, status = PlayerStatus.PRONE, profile = profile("restless-dead", "Skeleton"))
        }

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(ActionType.BLOCK)
        resolveForcedChallengeReplaceIfPresent(match.matchId)

        assertThat(match.gameState().phase).isEqualTo(TurnPhase.PRE_TURN)
        // One grant at a time: exactly one Stand Up + Skip, not both skeletons at once.
        val firstStand = match.legalActions<LegalAction.StandUp>().single()
        assertThat(firstStand.playerId).isIn(first.player.id, second.player.id)
        match.legalActions().assertAny(ActionType.SKIP)

        match.play(match.legalActions<LegalAction.Skip>().single())

        val secondStand = match.legalActions<LegalAction.StandUp>().single()
        assertThat(secondStand.playerId).isIn(first.player.id, second.player.id)
        assertThat(secondStand.playerId).isNotEqualTo(firstStand.playerId)

        match.play(match.legalActions<LegalAction.Skip>().single())
        match.legalActions().assertNone(ActionType.SKIP)
        assertThat(match.gameState().phase).isEqualTo(TurnPhase.PLAYER_ACTION)
    }

    @Test
    fun `Rally offers sequential Stand Ups on the Run continuation`() {
        lateinit var boar: PlacedPlayer
        lateinit var left: PlacedPlayer
        lateinit var right: PlacedPlayer
        val match = match {
            boar = addPlayer(5, 6, profile = profile("norse", "Ale Hog"))
            left = addPlayer(4, 6, status = PlayerStatus.PRONE)
            right = addPlayer(6, 6, status = PlayerStatus.PRONE)
        }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.playerId == boar.player.id && it.targetCoordinate == Coordinate(5, 5) }
        match.play(run)

        val firstStand = match.legalActions<LegalAction.StandUp>().single()
        assertThat(firstStand.playerId).isIn(left.player.id, right.player.id)

        match.play(firstStand)

        val secondStand = match.legalActions<LegalAction.StandUp>().single()
        assertThat(secondStand.playerId).isIn(left.player.id, right.player.id)
        assertThat(secondStand.playerId).isNotEqualTo(firstStand.playerId)

        match.play(secondStand)
        assertThat(player(match.pieces(), left.player.id).status).isEqualTo(PlayerStatus.STANDING)
        assertThat(player(match.pieces(), right.player.id).status).isEqualTo(PlayerStatus.STANDING)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `after-own Momentum then All-In Tackle offers one window at a time then one grant`() {
        lateinit var blitzer: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.Momentum, BonusPlay.`All-In Tackle`)
            actionsRemaining(3)
            blitzer = addPlayer(4, 6, profile = TestProfiles.withMove(4))
            addPlayer(7, 6, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
            addPlayer(7, 7, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(5, 6) },
        )
        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(6, 6) },
        )

        val firstWindow = match.legalActions<LegalAction.PlayBonusCard>()
        assertThat(firstWindow.map { it.bonusPlay }).contains(BonusPlay.Momentum, BonusPlay.`All-In Tackle`)
        match.play(firstWindow.first { it.bonusPlay == BonusPlay.Momentum })

        // Momentum grant only — free Block, no second bonus window stacked.
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(match.legalActions<LegalAction.Block>().filter { it.playerId == blitzer.player.id }).isNotEmpty()

        dice.queueBlockDice(BlockDieFace.MISS)
        match.play(match.legalActions<LegalAction.Block>().first { it.playerId == blitzer.player.id })

        assertThat(match.legalActions<LegalAction.PlayBonusCard>().map { it.bonusPlay })
            .containsExactly(BonusPlay.`All-In Tackle`)
        match.play(match.legalActions<LegalAction.PlayBonusCard>().single())

        assertThat(match.legalActions<LegalAction.Block>().filter { it.playerId == blitzer.player.id }).isNotEmpty()
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
    }

    @Test
    fun `All-In Tackle shove follow-up is sole pending when no leftover Momentum grant`() {
        lateinit var blitzer: PlacedPlayer
        val match = match {
            homeBonus(BonusPlay.`All-In Tackle`)
            actionsRemaining(2)
            blitzer = addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(7, 8, team = TeamSide.AWAY, profile = TestProfiles.dwarfLineman())
        }

        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.playerId == blitzer.player.id && it.targetCoordinate == Coordinate(6, 8) },
        )
        match.play(match.legalActions<LegalAction.PlayBonusCard>().single())

        dice.queueBlockDice(BlockDieFace.SHOVE)
        match.play(match.legalActions<LegalAction.Block>().first { it.playerId == blitzer.player.id })

        // Shove follow-up only — Mark into vacated square or Skip; no bonus window left.
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
        assertThat(match.legalActions()).containsExactlyInAnyOrder(
            LegalAction.Skip(blitzer.player.id),
            LegalAction.Mark(blitzer.player.id, Path(listOf(Coordinate(6, 8), Coordinate(7, 8)))),
        )
    }

    @Test
    fun `Slip Away interrupt does not compound pending — open Mark is the resume token`() {
        val match = match {
            awayBonus(BonusPlay.`Slip Away`)
            actionsRemaining(2)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(7, 8, team = TeamSide.AWAY, profile = TestProfiles.lineman())
        }

        match.play(
            match.legalActions<LegalAction.Mark>()
                .first { it.path.squares.last() == Coordinate(6, 8) },
        )
        assertThat(match.legalActions<LegalAction.PlayBonusCard>().map { it.bonusPlay })
            .containsExactly(BonusPlay.`Slip Away`)
        match.legalActions().assertNone(ActionType.SIDESTEP)

        match.play(match.legalActions<LegalAction.PlayBonusCard>().single())
        assertThat(match.legalActions<LegalAction.Sidestep>()).isNotEmpty()
        assertThat(match.legalActions<LegalAction.PlayBonusCard>()).isEmpty()
    }
}
