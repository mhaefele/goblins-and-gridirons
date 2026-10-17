package com.mhaefele.gridiron.api.ai.training.claim

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.SEND_THEM_OFF
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.DROP_THEM
import com.mhaefele.gridiron.support.defaultMatchConfig
import com.mhaefele.gridiron.support.matchState
import org.assertj.core.api.Assertions.assertThat
import com.mhaefele.gridiron.integration.support.BaseApiTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ClaimFillerSelectorTest : BaseApiTest() {

    @Autowired
    private lateinit var legalMovesGenerator: LegalMovesGenerator

    @ParameterizedTest
    @EnumSource(
        ChallengeClaimCondition::class,
        names = ["KEEP_RUNNING", "ALL_HANDS", "STAR_TURN"],
    )
    fun `multi-action claims are never opening-claimable`(condition: ChallengeClaimCondition) {
        val state = matchState(config = defaultMatchConfig())
        val openingMoves = legalMovesGenerator.forActingCoach(state)
        assertThat(ClaimFillerSelector.openingClaimableConditions(openingMoves, state)).doesNotContain(condition)
    }

    @Test
    fun `allowed filler conditions include focus and exclude other opening-claimable conditions`() {
        val state = matchState(config = defaultMatchConfig())
        val focus = ChallengeClaimCondition.GRAB_IT
        val openingMoves = legalMovesGenerator.forActingCoach(state)
        val claimable = ClaimFillerSelector.openingClaimableConditions(openingMoves, state)
        val allowed = ClaimFillerSelector.allowedFillerConditions(openingMoves, state, focus)

        assertThat(allowed).contains(focus)
        assertThat(allowed - focus).doesNotContainAnyElementsOf(claimable)
        assertThat(allowed).doesNotContainAnyElementsOf(ClaimFillerSelector.MULTI_MOVE)
    }

    @Test
    fun `mark excluded fillers drop block cards when any opening Mark exists`() {
        val state = matchState(config = defaultMatchConfig())
        val openingMoves = legalMovesGenerator.forActingCoach(state)
        val marks = openingMoves.filterIsInstance<LegalAction.Mark>()
        if (marks.isEmpty()) return

        val excluded = ClaimFillerSelector.markExcludedFillers(openingMoves, state.board)
        assertThat(excluded).contains(DROP_THEM, SEND_THEM_OFF)
    }

    @Test
    fun `allowed filler conditions exclude block cards when opening Mark is legal`() {
        val state = matchState(config = defaultMatchConfig())
        val focus = ChallengeClaimCondition.GRAB_IT
        val openingMoves = legalMovesGenerator.forActingCoach(state)
        if (openingMoves.none { it is LegalAction.Mark }) return

        val allowed = ClaimFillerSelector.allowedFillerConditions(openingMoves, state, focus)
        assertThat(allowed).doesNotContain(DROP_THEM, SEND_THEM_OFF)
    }
}
