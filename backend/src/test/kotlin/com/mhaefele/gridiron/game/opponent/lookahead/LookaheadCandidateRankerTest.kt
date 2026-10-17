package com.mhaefele.gridiron.game.opponent.lookahead

import com.mhaefele.gridiron.api.dto.response.ClaimCertainty
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.PotentialChallengeClaim
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.Path
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.matchState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class LookaheadCandidateRankerTest : BaseApiTest() {

    @Autowired
    private lateinit var legalMovesGenerator: LegalMovesGenerator

    @Test
    fun `keeps ball pickup in top slice when many aimless runs exist`() {
        val scenario = GameScenario.board()
        scenario.addPlayer(Coordinate(5, 6), profile = TestProfiles.withMove(5))
        scenario.addPlayer(Coordinate(3, 3), profile = TestProfiles.withMove(5))
        scenario.addLooseBall(Coordinate(5, 7))
        val state = matchState(board = scenario.board, activeCoach = TeamSide.HOME)
        val legal = legalMovesGenerator.forActingCoach(state).filter { it is LegalAction.Run || it is LegalAction.Mark }
        assertThat(legal.size).isGreaterThan(12)

        val top = LookaheadCandidateRanker.top(state, legal, limit = 12)
        assertThat(top).hasSize(12)
        assertThat(top.filterIsInstance<LegalAction.Run>().any { it.path.squares.last() == Coordinate(5, 7) })
            .isTrue()
    }

    @Test
    fun `1pt claim annotation outranks marked-carrier sidestep unlock bump`() {
        val state = matchState(activeCoach = TeamSide.HOME)
        val claim = PotentialChallengeClaim(
            claimCondition = ChallengeClaimCondition.GRAB_IT,
            challengeTitle = "Grab It!",
            points = 1,
            certainty = ClaimCertainty.CERTAIN,
        )
        val claimRun = LegalAction.Run(PlayerId("h0"), Path(listOf(Coordinate(5, 5), Coordinate(5, 6))), potentialChallengeClaims = listOf(claim))
        val unlockSidestep = LegalAction.Sidestep(PlayerId("h1"), Path(listOf(Coordinate(4, 4), Coordinate(4, 5))))
        // Unlock prior is +90 when marked+carrier; even that alone must lose to 1pt claim (*280).
        val ctx = LookaheadCandidateRanker.Context.from(state, TeamSide.HOME)
        assertThat(LookaheadCandidateRanker.weight(claimRun, ctx))
            .isGreaterThan(LookaheadCandidateRanker.weight(unlockSidestep, ctx) + 90.0)
        val top = LookaheadCandidateRanker.top(state, listOf(unlockSidestep, claimRun), limit = 1)
        assertThat(top.single()).isEqualTo(claimRun)
    }
}
