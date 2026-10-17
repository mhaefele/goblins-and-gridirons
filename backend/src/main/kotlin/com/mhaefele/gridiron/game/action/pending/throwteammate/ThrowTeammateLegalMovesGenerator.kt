package com.mhaefele.gridiron.game.action.pending.throwteammate

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.armourcheck.ArmourCheckLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.knockdown.KnockDownLegalMovesGenerator
import com.mhaefele.gridiron.game.legalmoves.throwTeammateLandingMoves
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.pending.ThrowTeammateContinuation
import org.springframework.stereotype.Component

/** Legal moves for [ThrowTeammateContinuation] (landing square / cancel / nested KnockDown / ArmourCheck). */
@Component
class ThrowTeammateLegalMovesGenerator(
    private val knockDownLegalMovesGenerator: KnockDownLegalMovesGenerator,
    private val armourCheckLegalMovesGenerator: ArmourCheckLegalMovesGenerator,
) : PendingDecisionLegalMovesGenerator<ThrowTeammateContinuation>() {
    override fun generate(
        state: MatchState,
        pending: ThrowTeammateContinuation,
    ): List<LegalAction> {
        val ongoing = pending.ongoingThrowTeammate
        return when (ongoing.stage) {
            OngoingThrowTeammate.ThrowTeammateStage.AWAITING_LANDING             -> throwTeammateLandingMoves(state.board.player(ongoing.throwerId), ongoing.teammateId, state.board)
            OngoingThrowTeammate.ThrowTeammateStage.DELEGATE_KNOCK_DOWN_THROWN,
            OngoingThrowTeammate.ThrowTeammateStage.DELEGATE_KNOCK_DOWN_OCCUPANT -> {
                val nested = checkNotNull(ongoing.nestedKnockDown) { "${ongoing.stage} missing nestedKnockDown" }
                knockDownLegalMovesGenerator.generate(state, nested)
            }
            OngoingThrowTeammate.ThrowTeammateStage.DELEGATE_ARMOUR_CHECK        -> {
                val nested = checkNotNull(ongoing.nestedArmourCheck) { "DELEGATE_ARMOUR_CHECK missing nestedArmourCheck" }
                armourCheckLegalMovesGenerator.generate(state, nested)
            }
            else                                                                 -> error("ThrowTeammateContinuation legal moves unexpected stage: ${ongoing.stage}")
        }
    }
}
