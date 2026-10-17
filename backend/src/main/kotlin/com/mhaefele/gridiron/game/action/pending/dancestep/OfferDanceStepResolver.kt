package com.mhaefele.gridiron.game.action.pending.dancestep

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver
import com.mhaefele.gridiron.game.action.pending.sidestep.SidestepResolver
import com.mhaefele.gridiron.game.action.utils.standingStatus
import com.mhaefele.gridiron.game.legalmoves.sidestepMoves
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.OpenPlayerAction
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.model.pending.OfferDanceStep
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.StandingStatus
import org.springframework.stereotype.Component

/**
 * takeAction owner for [OfferDanceStep] (PLAYER_ACTION / AFTER_THIRD → OFFER_DANCE_STEP).
 * Accept begins a FREE Sidestep turn-slot on [OfferDanceStep.delegate]; Skip advances the queue or resolves.
 */
@Component
class OfferDanceStepResolver(
    private val sidestepResolver: SidestepResolver,
) : PendingDecisionResolver<OfferDanceStep>() {
    fun begin(session: MatchMutator): OfferDanceStep {
        val eligible = eligiblePlayerIds(session.toMatchState())
        check(eligible.isNotEmpty()) { "OfferDanceStep.begin requires at least one eligible Glade Dancer" }
        return OfferDanceStep(
            localRespondingCoach = session.activeCoach,
            playerId = eligible.first(),
            remainingPlayerIds = eligible.drop(1),
        )
    }

    override fun mutate(
        session: MatchMutator,
        pending: OfferDanceStep,
        command: LegalAction,
    ): OfferDanceStep =
        when (val child = pending.delegate) {
            null -> mutateOffer(session, pending, command)
            else -> pending.copy(delegate = sidestepResolver.mutate(session, child, command))
        }

    private fun mutateOffer(
        session: MatchMutator,
        offer: OfferDanceStep,
        command: LegalAction,
    ): OfferDanceStep =
        when (command) {
            is LegalAction.Skip     -> advanceOrResolve(session, offer)
            is LegalAction.Sidestep -> {
                check(command.playerId == offer.playerId) { "OfferDanceStep Sidestep for ${offer.playerId}, was ${command.playerId}" }
                session.beginPlayerAction(command.playerId, ActionType.SIDESTEP, OpenPlayerAction.Origin.FREE)
                offer.copy(delegate = sidestepResolver.beginSidestep(session, command))
            }
            else                    -> error("OfferDanceStep expects Sidestep or Skip, was ${command::class.simpleName}")
        }

    private fun advanceOrResolve(session: MatchMutator, offer: OfferDanceStep): OfferDanceStep {
        val stillEligible = offer.remainingPlayerIds.filter { playerId ->
            isEligible(session.toMatchState(), playerId)
        }
        return if (stillEligible.isEmpty()) {
            offer.copy(resolved = true)
        } else {
            OfferDanceStep(
                localRespondingCoach = session.activeCoach,
                playerId = stillEligible.first(),
                remainingPlayerIds = stillEligible.drop(1),
            )
        }
    }

    companion object {
        fun canOffer(session: MatchMutator): Boolean =
            eligiblePlayerIds(session.toMatchState()).isNotEmpty()

        fun eligiblePlayerIds(state: MatchState): List<PlayerId> =
            state.board.players.values
                .filter { isEligible(state, it.id) }
                .map { it.id }

        fun isEligible(state: MatchState, playerId: PlayerId): Boolean {
            val player = state.board.players[playerId] ?: return false
            if (player.team != state.currentTurnState.activeCoach) return false
            if (!player.hasAbility(PlayerAbility.`Dance Step`)) return false
            if (player.status != PlayerStatus.STANDING) return false
            if (playerId in state.currentTurnState.turnEndedPlayers) return false
            if (ActionType.SIDESTEP in (state.currentTurnState.actionsUsedThisTurn[playerId] ?: emptySet())) return false
            if (standingStatus(player, state.board) != StandingStatus.MARKED) return false
            return sidestepMoves(player, state.board).isNotEmpty()
        }
    }
}
