package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.MatchActionResponse
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.MatchApi
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.model.pending.PlayerDecisionType
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.PositionProfile
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.ActionType.RUN
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TeamSide.AWAY
import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.assertEventsContain
import com.mhaefele.gridiron.support.assertNone
import com.mhaefele.gridiron.support.bareEvents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private val awayEndZone = Coordinate(5, 15)

/**
 * Touchdowns score once per turn-slot beat after free follow-ups / after-own
 * ([PlayerActionStep.TOUCHDOWN]). Scorer then goes to [PlayerStatus.RESERVES] — decline any
 * pre-TD grants (Pass on the Run, Catch and Go) before asserting the dugout.
 */
class TouchdownApiTest : BaseApiTest() {

    private fun profile(teamId: String, name: String): PositionProfile =
        TeamCatalog.team(teamId).roster.first { it.name == name }

    /** Skip free-ability / decline after-own windows until the turn-slot claim or next budgeted action. */
    private fun drainAfterActionOffers(match: MatchApi) {
        repeat(8) {
            val skip = match.legalActions<LegalAction.Skip>().firstOrNull()
            if (skip != null) {
                match.play(skip)
                return@repeat
            }
            val decline = match.legalActions<LegalAction.DeclineBonus>().firstOrNull()
            if (decline != null) {
                match.play(decline)
                return@repeat
            }
            return
        }
    }

    @Test
    fun `run into opponent end zone scores touchdown and turn continues`() {
        lateinit var runner: PlacedPlayer
        val match = match {
            actionsRemaining(2)
            runner = holdingBall(addPlayer(5, 14, profile = TestProfiles.withMove(2)))
        }

        val run = match.singleLegalAction<LegalAction.Run> { it.path.squares.last() == awayEndZone }

        val response = match.play(run)
        val pieces = match.pieces()

        assertThat(response.snapshot.gameState.homeScore).isEqualTo(4)
        assertThat(pieces.looseBallLocations).isEmpty()
        assertThat(pieces.players.none { it.holdsBall }).isTrue()
        assertThat(player(pieces, runner.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertThat(response.snapshot.gameState.actionsRemaining).isEqualTo(1)
        val bare = response.events.map { it.event }
        val scoredAt = bare.indexOfFirst { it is DomainEvent.TouchdownScored }
        val ballOutAt = bare.indexOfFirst { it is DomainEvent.BallRemovedFromPlay }
        val dugoutAt = bare.indexOfFirst { it is DomainEvent.PlayerRemovedAfterTouchdown }
        assertThat(scoredAt).isGreaterThanOrEqualTo(0)
        assertThat(scoredAt).isLessThan(ballOutAt)
        assertThat(ballOutAt).isLessThan(dugoutAt)
        assertEventsContain(
            response,
            DomainEvent.TouchdownScored(runner.player.id, TeamSide.HOME, awayEndZone, 4, 0),
        )
    }

    @Test
    fun `catch and go may be offered before touchdown then skip scores`() {
        lateinit var thrower: PlacedPlayer
        lateinit var catcher: PlacedPlayer
        val match = match {
            actionsRemaining(2)
            thrower = addPlayer(5, 13, profile = profile("human", "Thrower"))
            holdingBall(thrower)
            catcher = addPlayer(5, 15, team = thrower.team, profile = profile("human", "Catcher"))
            addPlayer(3, 8, team = thrower.team)
        }

        val throwAction = match.singleLegalAction<LegalAction.Throw> { it.target.destination == awayEndZone }
        dice.queueD6(6)
        match.play(throwAction)

        assertThat(match.gameState().homeScore).isEqualTo(0)
        assertThat(match.legalActions<LegalAction.Run>().any { it.playerId == catcher.player.id }).isTrue()
        drainAfterActionOffers(match)

        assertThat(match.gameState().homeScore).isEqualTo(4)
        assertThat(player(match.pieces(), catcher.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertNoOnPitchActionsFor(match, catcher)
        assertThat(match.legalActions<LegalAction.Run>().any { it.playerId != catcher.player.id }).isTrue()
    }

    @Test
    fun `hand-off into end zone scores touchdown after after-action drain`() {
        lateinit var thrower: PlacedPlayer
        lateinit var catcher: PlacedPlayer
        val match = match {
            actionsRemaining(2)
            thrower = addPlayer(5, 14, profile = profile("human", "Thrower"))
            holdingBall(thrower)
            catcher = addPlayer(5, 15, team = thrower.team, profile = profile("human", "Catcher"))
            addPlayer(3, 8, team = thrower.team)
        }

        val handOff = match.legalActions<LegalAction.Throw>()
            .first { it.target.destination == awayEndZone }
        match.play(handOff)
        drainAfterActionOffers(match)

        assertThat(match.gameState().homeScore).isEqualTo(4)
        assertThat(player(match.pieces(), catcher.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertNoOnPitchActionsFor(match, catcher)
    }

    @Test
    fun `pass on the run may be offered before touchdown then skip scores`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            actionsRemaining(2)
            thrower = addPlayer(5, 14, profile = profile("gilded-crown", "Thrower"))
            holdingBall(thrower)
            addPlayer(3, 8, team = thrower.team)
        }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.playerId == thrower.player.id && it.path.squares.last() == awayEndZone }
        match.play(run)

        assertThat(match.gameState().homeScore).isEqualTo(0)
        assertThat(match.legalActions<LegalAction.Skip>()).isNotEmpty()
        drainAfterActionOffers(match)

        assertThat(match.gameState().homeScore).isEqualTo(4)
        assertThat(player(match.pieces(), thrower.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertNoOnPitchActionsFor(match, thrower)
        match.legalActions().assertNone(ActionType.THROW)
    }

    @Test
    fun `pass on the run may be offered after end-zone pickup then skip scores`() {
        lateinit var thrower: PlacedPlayer
        val match = match {
            actionsRemaining(2)
            thrower = addPlayer(5, 14, profile = profile("gilded-crown", "Thrower"))
            addLooseBall(5, 15)
            addPlayer(3, 8, team = thrower.team)
        }

        val run = match.legalActions<LegalAction.Run>()
            .first { it.playerId == thrower.player.id && it.path.squares.last() == awayEndZone }
        match.play(run)

        // Mid-path pickup can pause on RunContinuation; finish into / on the EZ then assert.
        if (match.legalActions().any { it.actionType == ActionType.SKIP }) {
            val cont = match.legalActions<LegalAction.Run>()
                .firstOrNull { it.path.squares.last() == awayEndZone }
            if (cont != null) {
                match.play(cont)
            } else {
                match.play(match.legalActions<LegalAction.Skip>().single())
            }
        }

        drainAfterActionOffers(match)

        assertThat(player(match.pieces(), thrower.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertThat(match.gameState().homeScore).isEqualTo(4)
        match.legalActions().assertNone(ActionType.SKIP)
        assertNoOnPitchActionsFor(match, thrower)
        match.legalActions().assertNone(ActionType.THROW)
    }

    @Test
    fun `dugout touchdown scorer may only redeploy via reserves not free run or skip`() {
        lateinit var runner: PlacedPlayer
        lateinit var teammate: PlacedPlayer
        val match = match {
            actionsRemaining(2)
            runner = holdingBall(addPlayer(5, 14, profile = TestProfiles.withMove(2)))
            teammate = addPlayer(3, 8)
        }

        match.play(
            match.legalActions<LegalAction.Run>()
                .first { it.playerId == runner.player.id && it.path.squares.last() == awayEndZone },
        )
        drainAfterActionOffers(match)

        assertThat(player(match.pieces(), runner.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertNoOnPitchActionsFor(match, runner)
        assertThat(match.legalActions<LegalAction.Skip>().none { it.playerId == runner.player.id }).isTrue()
        assertThat(
            match.legalActions<LegalAction.Run>().any { it.playerId == teammate.player.id },
        ).isTrue()
        assertThat(
            match.legalActions<LegalAction.Reserves>().any { it.playerId == runner.player.id },
        ).isTrue()
    }

    @Test
    fun `Entering the endzone on opponents turn does not trigger a touchdown, but immediately triggers it at the beginning of your turn`() {
        lateinit var homePlayer: PlacedPlayer
        lateinit var awayPlayer: PlacedPlayer
        val match = match {
            awayBonus(BonusPlay.`Slip Away`)
            actionsRemaining(2)
            homePlayer = addPlayer(6, 2)
            awayPlayer = holdingBall(addPlayer(4, 2, team = AWAY))
        }

        match.play(homePlayer, ActionType.MARK, 5,2)

        playBonusCard(match, BonusPlay.`Slip Away`)

        match.play(awayPlayer, ActionType.SIDESTEP, 3,1)
        assertThat(match.gameState().awayScore).isEqualTo(0) // no touchdown yet

        val (_, events) = match.play(homePlayer, RUN, 6, 2)

        assertThat(events.bareEvents()).containsExactly(
            DomainEvent.ActionStarted(homePlayer.id, playerAt = Coordinate(x = 5, y = 2), actionType = RUN),
            DomainEvent.PlayerMoved(homePlayer.id, from = Coordinate(x = 5, y = 2), to = Coordinate(x = 6, y = 2)),
            DomainEvent.ActionComplete(homePlayer.id, playerAt = Coordinate(x = 6, y = 2), actionType = RUN),
            DomainEvent.TeamActionConsumed(actionsRemaining = 0),
            DomainEvent.PhaseStarted(phase = TurnPhase.TURN_END, turnNumber = 3, activeCoach = TeamSide.HOME),
            DomainEvent.PhaseStarted(phase = TurnPhase.TURN_START, turnNumber = 4, activeCoach = AWAY),
            DomainEvent.PhaseStarted(phase = TurnPhase.PRE_TURN, turnNumber = 4, activeCoach = AWAY),
            DomainEvent.TouchdownScored(awayPlayer.id, team = AWAY, at = Coordinate(x = 3, y = 1), homeScore = 0, awayScore = 4),
            DomainEvent.BallRemovedFromPlay(awayPlayer.id),
            DomainEvent.PlayerRemovedAfterTouchdown(awayPlayer.id, from = Coordinate(x = 3, y = 1)),
            DomainEvent.InputRequired.PlayerAction(team = AWAY, decisionType = PlayerDecisionType.REPLACE_CHALLENGE, )
        )
        assertThat(match.gameState().awayScore).isEqualTo(4)

    }

    private fun assertNoFreeAbilityGrant(response: MatchActionResponse, ability: PlayerAbility) {
        assertThat(
            response.events.bareEvents()
                .filterIsInstance<DomainEvent.InputRequired.PlayerAction>()
                .none { it.ability == ability },
        ).isTrue()
    }

    /** Free on-pitch follow-ups (Run / Throw / Block / Sidestep / Skip) for a dugout scorer. */
    private fun assertNoOnPitchActionsFor(match: MatchApi, scorer: PlacedPlayer) {
        val scorerId = scorer.player.id
        assertThat(match.legalActions<LegalAction.Run>().none { it.playerId == scorerId }).isTrue()
        assertThat(match.legalActions<LegalAction.Throw>().none { it.playerId == scorerId }).isTrue()
        assertThat(match.legalActions<LegalAction.Block>().none { it.playerId == scorerId }).isTrue()
        assertThat(match.legalActions<LegalAction.Sidestep>().none { it.playerId == scorerId }).isTrue()
        assertThat(match.legalActions<LegalAction.Skip>().none { it.playerId == scorerId }).isTrue()
        assertThat(match.legalActions<LegalAction.Mark>().none { it.playerId == scorerId }).isTrue()
    }
}
