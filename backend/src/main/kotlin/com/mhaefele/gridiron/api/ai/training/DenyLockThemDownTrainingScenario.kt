package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.ai.training.claim.ChallengeClaimTableau
import com.mhaefele.gridiron.api.ai.training.claim.ClaimBoardPhrases
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.distance
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.GRAB_IT
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.FAN_OUT
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition.LOCK_THEM_DOWN
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Opponent can Mark into a square uniquely adjacent to two trainers (Lock Them Down! active).
 * Opening lesson: Block that marker before their turn ([ai/BLINDSPOTS.md] `left-claim-open`).
 *
 * Fillers are titles a Block cannot bank so the soft-mask stays the deny, not a claim fight.
 *
 * Soft-mask trains the model; fat lookahead often Sidesteps / elsewhere first on a non-carrier
 * (same class as skipped DENY_PICKUP), so this is exposure-only until search values the deny.
 */
@Component
class DenyLockThemDownTrainingScenario : AbstractTrainingScenario() {
    override val scenario = TrainingScenarioEnum.DENY_LOCK_THEM_DOWN
    override val gatesProgress = false
    override val lookaheadSolveEval = false

    override fun buildBoard(setup: TrainingBoardBuilder, trainerSide: TeamSide, difficulty: Int) {
        ClaimBoardPhrases.placeDenyLockThemDownThreat(setup, trainerSide)
        val clearAround = setup.scatterAvoidZones()
        scatterDifficultyExtras(setup, trainerSide, difficulty, opponentAvoid = clearAround, trainerAvoid = clearAround)
    }

    override fun configureEpisode(state: MatchState, trainerSide: TeamSide): MatchState =
        state.copy(cards = ChallengeClaimTableau.withFocus(LOCK_THEM_DOWN, GRAB_IT, FAN_OUT))

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        require(state.cards.activeCards().any { it.challenge == LOCK_THEM_DOWN }) { "${scenario.name}: Lock Them Down! must be active" }
        require(legals.any { isSoftMaskOpeningMove(it, state, trainerSide) }) { "${scenario.name}: no legal Block on a Lock Them Down threat marker" }
    }

    override fun isSoftMaskOpeningMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (action !is LegalAction.Block) return false
        val targetId = state.board.squareAt(action.destination).playerId ?: return false
        val target = state.board.players[targetId] ?: return false
        return isLockThemDownThreatMarker(target, state.board, trainerSide)
    }

    companion object {
        /**
         * Standing opposition player who has an empty one-step Mark land adjacent to ≥2 standing
         * trainers — the Lock Them Down path we open by Blocking.
         */
        fun isLockThemDownThreatMarker(marker: Player, board: Board, trainerSide: TeamSide): Boolean {
            if (marker.team != trainerSide.opposite() || marker.status != PlayerStatus.STANDING) return false
            val trainers = board.players.values.filter { it.team == trainerSide && it.status == PlayerStatus.STANDING }
            return neighbors(board.findSquare(marker.id), board).any { land ->
                // neighbors() includes the OOB border; distance() refuses those (and Mark cannot land there).
                if (land.isBlocked() || land.playerId != null) return@any false
                if (land.isTrapdoor() || land.isEndZone(trainerSide) || land.isEndZone(trainerSide.opposite())) return@any false
                trainers.count { distance(land, board.findSquare(it.id), board) == 1 } >= 2
            }
        }
    }
}
