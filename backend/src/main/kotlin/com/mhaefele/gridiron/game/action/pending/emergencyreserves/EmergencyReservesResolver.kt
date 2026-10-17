package com.mhaefele.gridiron.game.action.pending.emergencyreserves

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.BoardMovement
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.EmergencyReserves
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver

/** takeAction owner for [EmergencyReserves] (PRE_TURN). */
@Component
class EmergencyReservesResolver(
    private val boardMovement: BoardMovement,
) : PendingDecisionResolver<EmergencyReserves>() {
    fun begin(): EmergencyReserves = EmergencyReserves()

    override fun mutate(
        session: MatchMutator,
        pending: EmergencyReserves,
        command: LegalAction,
    ): EmergencyReserves {
        val reserves = command as? LegalAction.Reserves ?: error("EmergencyReserves expects Reserves, was ${command::class.simpleName}")
        session.runStandalonePlayerMove(reserves.playerId, reserves.actionType) { boardMovement.applyReservesPlacement(session.board, reserves) }
        return EmergencyReserves(resolved = true)
    }
}
