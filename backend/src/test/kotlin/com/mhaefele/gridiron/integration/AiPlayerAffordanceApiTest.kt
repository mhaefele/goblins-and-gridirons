package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.ai.dto.AiFactor
import com.mhaefele.gridiron.api.ai.dto.AiLegalKind
import com.mhaefele.gridiron.api.ai.dto.AiObsLayout
import com.mhaefele.gridiron.api.dto.response.ClaimCertainty
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.pending.RegularPlayerAction
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Per-player affordance channels on AI cell obs (schema 13). */
class AiPlayerAffordanceApiTest : BaseApiTest() {

    @Test
    fun `TD_RUN carrier cell canScoreTouchdown and has reach bbox`() {
        dice.queueD6(List(24) { 6 })
        val response = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.TD_RUN))
        val step = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
        val passing = step.harness.legalRows.first { it.passingLegal }
        val actor = (passing.factors.first { it is AiFactor.Actor } as AiFactor.Actor).playerIndex
        val cell = step.policy.obs.cells.first { it.player?.playerIndex == actor }
        val player = cell.player!!

        assertThat(player.affordance.canScoreTouchdown).isTrue()
        assertThat(player.affordance.reachMinX).isNotEqualTo(AiObsLayout.SENTINEL)
        assertThat(player.affordance.reachMaxX).isGreaterThanOrEqualTo(player.affordance.reachMinX)
        assertThat(player.affordance.reachMinY).isNotEqualTo(AiObsLayout.SENTINEL)
        assertThat(player.affordance.reachMaxY).isGreaterThanOrEqualTo(player.affordance.reachMinY)
        assertThat(player.affordance.canPickupBall).isFalse()
        assertThat(player.affordance.canReachBallSquare).isFalse()
    }

    @Test
    fun `LOOSE_BALL_RUN pickup player canReachBall and canPickup`() {
        dice.queueD6(List(24) { 6 })
        val response = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.LOOSE_BALL_RUN))
        val step = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
        val passing = step.harness.legalRows.first { it.passingLegal }
        val actor = (passing.factors.first { it is AiFactor.Actor } as AiFactor.Actor).playerIndex
        val player = step.policy.obs.cells.first { it.player?.playerIndex == actor }.player!!

        assertThat(player.affordance.canReachBallSquare).isTrue()
        assertThat(player.affordance.canPickupBall).isTrue()
        assertThat(player.affordance.canScoreTouchdown).isFalse()
        assertThat(player.affordance.reachMinX).isNotEqualTo(AiObsLayout.SENTINEL)
    }

    @Test
    fun `CLAIM_GRAB_IT marks mightClaim on pickup actor`() {
        dice.queueD6(List(24) { 6 })
        val response = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.CLAIM_GRAB_IT))
        playThroughForcedReplaceToPlayerAction(response.matchId)
        val step = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
        val claimRows = step.harness.legalRows.filter { choice ->
            choice.potentialChallengeClaims.any { it.certainty == ClaimCertainty.POTENTIAL }
        }
        assertThat(claimRows).isNotEmpty()
        val actor = (claimRows.first().factors.first { it is AiFactor.Actor } as AiFactor.Actor).playerIndex
        val player = step.policy.obs.cells.first { it.player?.playerIndex == actor }.player!!

        assertThat(player.affordance.mightClaimChallenge).isTrue()
        assertThat(player.affordance.canPickupBall).isTrue()
    }

    @Test
    fun `CLAIM_HUDDLE_UP marks certainClaim on actor`() {
        dice.queueD6(List(24) { 6 })
        val response = api.createScenarioMatch(scenarioMatchRequest(TrainingScenarioEnum.CLAIM_HUDDLE_UP))
        playThroughForcedReplaceToPlayerAction(response.matchId)
        val step = api.aiStep(response.matchId, forSeat = TeamSide.HOME)
        val claimRows = step.harness.legalRows.filter { choice ->
            choice.potentialChallengeClaims.any { it.certainty == ClaimCertainty.CERTAIN }
        }
        assertThat(claimRows).isNotEmpty()
        val actor = (claimRows.first().factors.first { it is AiFactor.Actor } as AiFactor.Actor).playerIndex
        val player = step.policy.obs.cells.first { it.player?.playerIndex == actor }.player!!

        assertThat(player.affordance.certainClaimChallenge).isTrue()
        assertThat(player.affordance.mightClaimChallenge).isFalse()
    }

    /** Claim drills open on bait ReplaceChallenge — claim annotations appear after that discard. */
    private fun playThroughForcedReplaceToPlayerAction(matchId: MatchId) {
        val step = api.aiStep(matchId, forSeat = TeamSide.HOME)
        val bait = step.harness.legalRows.firstOrNull { it.kind == AiLegalKind.replaceChallenge && it.softMaskLegal } ?: error("claim replace: no soft-mask bait")
        api.play(matchId, bait.legalActionId)
        val state = loadMatch(matchId)
        val pending = state.currentTurnState.pendingDecision
        check(state.currentTurnState.phase == TurnPhase.PLAYER_ACTION && pending is RegularPlayerAction && pending.delegate == null) { "claim replace: expected PLAYER_ACTION select after bait discard, was phase=${state.currentTurnState.phase} pending=${pending?.javaClass?.simpleName}" }
    }
}
