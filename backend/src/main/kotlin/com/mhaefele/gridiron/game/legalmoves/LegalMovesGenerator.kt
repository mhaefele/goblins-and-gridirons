package com.mhaefele.gridiron.game.legalmoves

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.gangup.OfferGangUpLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.claim.ClaimChallengeLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.holdtheline.OfferHoldTheLineLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.emergencyreserves.EmergencyReservesLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.initialplacement.InitialPlayerPlacementLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.extraeffort.OfferExtraEffortLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.undying.OfferUndyingLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.regular.RegularPlayerActionLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.replace.ReplaceChallengeLegalMovesGenerator
import com.mhaefele.gridiron.game.action.pending.dancestep.OfferDanceStepLegalMovesGenerator
import com.mhaefele.gridiron.game.cards.ActionAttachedClaims
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.pending.ActionContinuation
import com.mhaefele.gridiron.model.pending.ClaimChallenge
import com.mhaefele.gridiron.model.pending.EmergencyReserves
import com.mhaefele.gridiron.model.pending.InitialPlayerPlacement
import com.mhaefele.gridiron.model.pending.OfferGangUp
import com.mhaefele.gridiron.model.pending.OfferHoldTheLine
import com.mhaefele.gridiron.model.pending.OfferExtraEffort
import com.mhaefele.gridiron.model.pending.OfferDanceStep
import com.mhaefele.gridiron.model.pending.OfferUndying
import com.mhaefele.gridiron.model.pending.PendingDecision
import com.mhaefele.gridiron.model.pending.PlayerSpecificAction
import com.mhaefele.gridiron.model.pending.RegularPlayerAction
import com.mhaefele.gridiron.model.pending.ReplaceChallenge
import org.springframework.stereotype.Component

/**
 * Match-level legal-move entry — mirrors [com.mhaefele.gridiron.game.action.PlayerActionExecutor].
 * Dispatches the root pending to its [com.mhaefele.gridiron.game.action.pending.PendingDecisionLegalMovesGenerator];
 * parents follow nested delegates themselves.
 */
@Component
class LegalMovesGenerator(
    private val regularPlayerActionLegalMovesGenerator: RegularPlayerActionLegalMovesGenerator,
    private val offerExtraEffortLegalMovesGenerator: OfferExtraEffortLegalMovesGenerator,
    private val offerDanceStepLegalMovesGenerator: OfferDanceStepLegalMovesGenerator,
    private val offerGangUpLegalMovesGenerator: OfferGangUpLegalMovesGenerator,
    private val offerHoldTheLineLegalMovesGenerator: OfferHoldTheLineLegalMovesGenerator,
    private val claimChallengeLegalMovesGenerator: ClaimChallengeLegalMovesGenerator,
    private val replaceChallengeLegalMovesGenerator: ReplaceChallengeLegalMovesGenerator,
    private val offerUndyingLegalMovesGenerator: OfferUndyingLegalMovesGenerator,
    private val initialPlayerPlacementLegalMovesGenerator: InitialPlayerPlacementLegalMovesGenerator,
    private val emergencyReservesLegalMovesGenerator: EmergencyReservesLegalMovesGenerator,
) {
    /**
     * Legal moves for the acting seat ([MatchState.actingSeat]).
     *
     * **Never empty** for that coach — a parked pending that needs input always exposes ≥1 choice.
     * An empty menu is a broken invariant and throws (not a soft “pass” / skip signal).
     * Soft-empty for a *viewer* seat lives on [com.mhaefele.gridiron.api.MatchGame], not here.
     *
     * **Cost:** full enum + ball encounter + claim annotate. Prefer one call per distinct pending /
     * board snapshot — mutate geometry (e.g. place a ball) *before* probing, and reuse the list
     * when the pending has not changed. Use [forSettle] when claim hints are not needed.
     */
    fun forActingCoach(
        state: MatchState,
        timer: LegalMovesPhaseTimer = LegalMovesPhaseTimer.NOOP,
    ): List<LegalAction> {
        val acting = state.actingSeat()
        val raw = timer.enumMoves { enumerate(state) }
        val withBall = timer.ballAnnotate { ActionAttachedBallEncounters.annotate(actions = raw, board = state.board) }
        val annotated = timer.claimsAnnotate { ActionAttachedClaims.annotatePotentialClaims(actions = withBall, state = state) }
        check(annotated.isNotEmpty()) { "Acting coach $acting has no legal moves(pending=${state.currentTurnState.pendingDecision?.let { it::class.simpleName }})" }
        return annotated
    }

    /**
     * Pending-scoped moves for lookahead settle / dry-run apply: enum + ball encounter only.
     * Skips claim annotate — settle only needs an applyable action, not UI claim hints.
     */
    fun forSettle(state: MatchState): List<LegalAction> {
        val raw = enumerate(state)
        val annotated = ActionAttachedBallEncounters.annotate(actions = raw, board = state.board)
        check(annotated.isNotEmpty()) { "Settle has no legal moves(pending=${state.currentTurnState.pendingDecision?.let { it::class.simpleName }})" }
        return annotated
    }

    /** Full menu for the parked root pending. */
    private fun enumerate(state: MatchState): List<LegalAction> {
        val next = state.currentTurnState.pendingDecision ?: error("No pending decision — cannot enumerate legal moves for ${state.actingSeat()}")
        return generateForRoot(state, next)
    }

    private fun generateForRoot(state: MatchState, next: PendingDecision): List<LegalAction> =
        when (next) {
            is RegularPlayerAction    -> regularPlayerActionLegalMovesGenerator.generate(state, next)
            is OfferExtraEffort       -> offerExtraEffortLegalMovesGenerator.generate(state, next)
            is OfferDanceStep          -> offerDanceStepLegalMovesGenerator.generate(state, next)
            is OfferGangUp      -> offerGangUpLegalMovesGenerator.generate(state, next)
            is OfferHoldTheLine     -> offerHoldTheLineLegalMovesGenerator.generate(state, next)
            is ClaimChallenge         -> claimChallengeLegalMovesGenerator.generate(state, next)
            is ReplaceChallenge       -> replaceChallengeLegalMovesGenerator.generate(state, next)
            is OfferUndying        -> offerUndyingLegalMovesGenerator.generate(state, next)
            is InitialPlayerPlacement -> initialPlayerPlacementLegalMovesGenerator.generate(state, next)
            is EmergencyReserves      -> emergencyReservesLegalMovesGenerator.generate(state, next)
            is ActionContinuation     -> error("${next::class.simpleName} is NestedOnly — park under RegularPlayerAction.delegate")
            is PlayerSpecificAction   -> error("Unexpected PlayerSpecificAction root ${next::class.simpleName}")
        }
}
