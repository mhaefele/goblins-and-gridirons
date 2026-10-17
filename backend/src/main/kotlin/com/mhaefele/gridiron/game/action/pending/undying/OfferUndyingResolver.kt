package com.mhaefele.gridiron.game.action.pending.undying

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.BoardMovement
import com.mhaefele.gridiron.game.action.ability.FreeFollowUpAbilityHook
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.OfferUndying
import org.springframework.stereotype.Component

/**
 * takeAction owner for [OfferUndying] (PRE_TURN / PLAYER_ABILITIES).
 * Stand Up applies board work under [MatchMutator.runStandalonePlayerMove] — no currentPlayerAction.
 */
@Component
class OfferUndyingResolver(
    private val boardMovement: BoardMovement,
    private val freeFollowUpAbilityHook: FreeFollowUpAbilityHook,
) : PendingDecisionResolver<OfferUndying>() {
    override fun mutate(
        session: MatchMutator,
        pending: OfferUndying,
        command: LegalAction,
    ): OfferUndying {
        when (command) {
            is LegalAction.Skip    -> Unit
            is LegalAction.StandUp -> {
                session.runStandalonePlayerMove(command.playerId, command.actionType) { boardMovement.applyStandUp(session.board, command) }
            }
            else                   -> error("OfferUndying expects StandUp or Skip, was ${command::class.simpleName}")
        }
        return advanceOrResolve(session, pending)
    }

    private fun advanceOrResolve(session: MatchMutator, decision: OfferUndying): OfferUndying {
        val next = freeFollowUpAbilityHook.nextPreTurnUndying(session.toMatchState(), decision)
        return next ?: OfferUndying(
            playerId = decision.playerId,
            remainingPlayerIds = emptyList(),
            resolved = true,
        )
    }
}
