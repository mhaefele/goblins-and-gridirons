package com.mhaefele.gridiron.game.action

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.claim.ClaimChallengeResolver
import com.mhaefele.gridiron.game.action.pending.emergencyreserves.EmergencyReservesResolver
import com.mhaefele.gridiron.game.action.pending.initialplacement.InitialPlayerPlacementResolver
import com.mhaefele.gridiron.game.action.pending.gangup.OfferGangUpResolver
import com.mhaefele.gridiron.game.action.pending.holdtheline.OfferHoldTheLineResolver
import com.mhaefele.gridiron.game.action.pending.extraeffort.OfferExtraEffortResolver
import com.mhaefele.gridiron.game.action.pending.undying.OfferUndyingResolver
import com.mhaefele.gridiron.game.action.pending.regular.RegularPlayerActionResolver
import com.mhaefele.gridiron.game.action.pending.replace.ReplaceChallengeResolver
import com.mhaefele.gridiron.game.action.pending.dancestep.OfferDanceStepResolver
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.PendingDecisionSlot
import com.mhaefele.gridiron.model.pending.PlayerSpecificAction
import com.mhaefele.gridiron.model.pending.ReplaceChallenge
import com.mhaefele.gridiron.model.pending.ClaimChallenge
import com.mhaefele.gridiron.model.pending.OfferExtraEffort
import com.mhaefele.gridiron.model.pending.OfferDanceStep
import com.mhaefele.gridiron.model.pending.OfferHoldTheLine
import com.mhaefele.gridiron.model.pending.OfferGangUp
import com.mhaefele.gridiron.model.pending.ActionContinuation
import com.mhaefele.gridiron.model.pending.OfferUndying
import com.mhaefele.gridiron.model.pending.EmergencyReserves
import com.mhaefele.gridiron.model.pending.InitialPlayerPlacement
import com.mhaefele.gridiron.model.pending.RegularPlayerAction
import com.mhaefele.gridiron.scenario.formatDomainEvents
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Resolve one coach-submitted legal action onto a caller-owned [MatchMutator].
 *
 * Peek the parked pending, handle it, then [MatchMutator.completePendingDecision] when the
 * handler left a finished one-step pending. Open-action *Continuations at DONE stay until
 * TurnEngine COMPLETE.
 *
 * ## Pending / open-action invariants (see also [PendingDecision])
 * 1. Dispatch on the **root** pending — must be [PendingDecisionSlot.TopLevel]. One resolver per type;
 *    parents follow `delegate` into nested resolvers.
 * 2. Each pending type declares exactly one [com.mhaefele.gridiron.shared.TurnPhase] and ideally
 *    one turn step.
 * 3. Flat: `updatePendingDecision(resolver.mutate(…))` — resolvers never write the slot.
 * 4. Finished one-shots clear here; finished open-action *Continuations stay for COMPLETE.
 * 5. No post-hoc “finish open action outside PLAYER_ACTION” peeks — that bracket is PA-only.
 */
@Component
class PlayerActionExecutor(
    private val regularPlayerActionResolver: RegularPlayerActionResolver,
    private val offerUndyingResolver: OfferUndyingResolver,
    private val initialPlayerPlacementResolver: InitialPlayerPlacementResolver,
    private val emergencyReservesResolver: EmergencyReservesResolver,
    private val offerGangUpResolver: OfferGangUpResolver,
    private val offerHoldTheLineResolver: OfferHoldTheLineResolver,
    private val offerExtraEffortResolver: OfferExtraEffortResolver,
    private val offerDanceStepResolver: OfferDanceStepResolver,
    private val claimChallengeResolver: ClaimChallengeResolver,
    private val replaceChallengeResolver: ReplaceChallengeResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun takeAction(session: MatchMutator, command: LegalAction) =
        try {
            _takeAction(session, command)
        } catch (e: Exception) {
            log.error("Error applying player action. Action: $command.\nEvents:\n  ${formatDomainEvents(session.events).joinToString("\n  ")}", e)
            throw e
        }

    private fun _takeAction(session: MatchMutator, command: LegalAction) {
        require(!session.matchOver) { "Match already over" }
        require(session.needsPlayerInput()) { "No input needed, why did they send us an action?" }

        val decision = session.requirePendingDecision()
        val next = when (decision) {
            is RegularPlayerAction    -> regularPlayerActionResolver.mutate(session, decision, command)
            is OfferExtraEffort       -> offerExtraEffortResolver.mutate(session, decision, command)
            is OfferDanceStep          -> offerDanceStepResolver.mutate(session, decision, command)
            is OfferGangUp      -> offerGangUpResolver.mutate(session, decision, command)
            is OfferHoldTheLine     -> offerHoldTheLineResolver.mutate(session, decision, command)
            is ClaimChallenge         -> claimChallengeResolver.mutate(session, decision, command)
            is ReplaceChallenge       -> replaceChallengeResolver.mutate(session, decision, command)
            is OfferUndying        -> offerUndyingResolver.mutate(session, decision, command)
            is InitialPlayerPlacement -> initialPlayerPlacementResolver.mutate(session, decision, command)
            is EmergencyReserves      -> emergencyReservesResolver.mutate(session, decision, command)
            is ActionContinuation     -> error("${decision::class.simpleName} is NestedOnly — park under RegularPlayerAction.delegate")
            is PlayerSpecificAction   -> error("Unexpected PlayerSpecificAction root ${decision::class.simpleName}")
        }
        check(next is PendingDecisionSlot.TopLevel) { "takeAction mutate must return TopLevel, was ${next::class.simpleName}" }
        session.updatePendingDecision(next)

        // One-step / non-open finished pendings clear here; open-action DONE stays for COMPLETE.
        val pending = session.requirePendingDecision()
        if (pending.isFinished() && !pending.awaitsOpenActionComplete()) {
            session.completePendingDecision()
        }
    }
}