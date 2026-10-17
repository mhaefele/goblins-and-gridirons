package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.ClaimCertainty
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.KEEP_RUNNING
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.GRAB_IT
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.PUSH_FORWARD
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.PLAY_TO_THE_STANDS
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.DROP_THEM
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.TestProfiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private val awayEndZone = Coordinate(5, 15)

/** D1.F.4 — legal-actions payload annotates potential challenge claims. */
class ClaimAnnotationApiTest : BaseApiTest() {

    @Test
    fun `Keep Running annotates Runs as CERTAIN on the third Run of the turn`() {
        val match = match {
            challengeCards(KEEP_RUNNING, GRAB_IT, PLAY_TO_THE_STANDS)
            actionsRemaining(1)
            actionsUsed(
                mapOf(
                    PlayerId("prior-1") to setOf(ActionType.RUN),
                    PlayerId("prior-2") to setOf(ActionType.RUN),
                ),
            )
            addPlayer(4, 6, profile = TestProfiles.withMove(4))
        }
        val keepRunningId = match.gameState().challenges.activeSlots[0]!!.challengeCondition

        val runs = match.legalActions<LegalAction.Run>()
        assertThat(runs).isNotEmpty
        assertThat(runs).allMatch { run ->
            run.potentialChallengeClaims.any { it.claimCondition == keepRunningId && it.challengeTitle == "Keep Running!" && it.points == 2 && it.certainty == ClaimCertainty.CERTAIN }
        }
    }

    @Test
    fun `Drop Them annotates Blocks as POTENTIAL on later turns`() {
        val match = match {
            challengeCards(GRAB_IT, DROP_THEM, PLAY_TO_THE_STANDS)
            actionsRemaining(2)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY)
        }
        val takeDownId = match.gameState().challenges.activeSlots[1]!!.challengeCondition

        val blocks = match.legalActions<LegalAction.Block>()
        assertThat(blocks).isNotEmpty
        assertThat(blocks).allMatch { block ->
            block.potentialChallengeClaims.any { it.claimCondition == takeDownId && it.challengeTitle == "Drop Them!" && it.points == 2 && it.certainty == ClaimCertainty.POTENTIAL }
        }
    }

    @Test
    fun `Coach A turn 1 does not annotate claimable Blocks`() {
        val match = match {
            turnNumber(1)
            challengeCards(DROP_THEM, GRAB_IT, PLAY_TO_THE_STANDS)
            actionsRemaining(1)
            addPlayer(5, 8, profile = TestProfiles.withMove(2))
            addPlayer(6, 8, team = TeamSide.AWAY)
        }

        val blocks = match.legalActions<LegalAction.Block>()
        assertThat(blocks).isNotEmpty
        assertThat(blocks).allMatch { it.potentialChallengeClaims.isEmpty() }
    }

    @Test
    fun `Push Forward does not annotate a Run that only picks up on the last square`() {
        val match = match {
            challengeCards(PUSH_FORWARD, PLAY_TO_THE_STANDS, GRAB_IT)
            actionsRemaining(1)
            addPlayer(5, 6, profile = TestProfiles.withMove(6))
            addLooseBall(5, 10)
        }
        val pushForwardId = match.gameState().challenges.activeSlots[0]!!.challengeCondition

        val pickupRun = match.legalActions<LegalAction.Run>()
            .first { it.path.squares.last() == Coordinate(5, 10) }

        assertThat(pickupRun.potentialChallengeClaims.map { it.claimCondition })
            .doesNotContain(pushForwardId)
    }

    @Test
    fun `Push Forward does not annotate a scoring Run into the opponent End Zone`() {
        val match = match {
            challengeCards(PUSH_FORWARD, PLAY_TO_THE_STANDS, GRAB_IT)
            actionsRemaining(1)
            // Far enough from own End Zone that a TD Run would otherwise meet the 4+ distance clause.
            holdingBall(addPlayer(5, 10, profile = TestProfiles.withMove(6)))
        }
        val pushForwardId = match.gameState().challenges.activeSlots[0]!!.challengeCondition
        val playToTheStandsId = match.gameState().challenges.activeSlots[1]!!.challengeCondition

        val scoringRun = match.legalActions<LegalAction.Run>()
            .first { it.path.squares.last() == awayEndZone }

        assertThat(scoringRun.potentialChallengeClaims.map { it.claimCondition })
            .doesNotContain(pushForwardId)
            .contains(playToTheStandsId)
    }

    @Test
    fun `Play to the Stands does not annotate a Mark into the opponent End Zone`() {
        val match = match {
            challengeCards(PLAY_TO_THE_STANDS, GRAB_IT, PUSH_FORWARD)
            actionsRemaining(1)
            // Start off the marker so Mark is legal; land in EZ still Marking → not Open → no TD.
            holdingBall(addPlayer(4, 14, profile = TestProfiles.withMove(2)))
            addPlayer(6, 15, team = TeamSide.AWAY)
        }
        val playToTheStandsId = match.gameState().challenges.activeSlots[0]!!.challengeCondition

        val markIntoEz = match.legalActions<LegalAction.Mark>()
            .first { it.path.squares.last() == awayEndZone }

        assertThat(markIntoEz.potentialChallengeClaims.map { it.claimCondition })
            .doesNotContain(playToTheStandsId)
    }
}
