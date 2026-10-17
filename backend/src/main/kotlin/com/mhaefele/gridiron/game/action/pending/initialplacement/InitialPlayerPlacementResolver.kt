package com.mhaefele.gridiron.game.action.pending.initialplacement

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.BoardMovement
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.InitialPlayerPlacement
import com.mhaefele.gridiron.shared.PlayerId
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver

/** takeAction owner for [InitialPlayerPlacement] (PIECE_PLACEMENT). */
@Component
class InitialPlayerPlacementResolver(
    private val boardMovement: BoardMovement,
) : PendingDecisionResolver<InitialPlayerPlacement>() {
    fun begin(playerId: PlayerId): InitialPlayerPlacement =
        InitialPlayerPlacement(playerId)

    override fun mutate(
        session: MatchMutator,
        pending: InitialPlayerPlacement,
        command: LegalAction,
    ): InitialPlayerPlacement {
        val reserves = command as? LegalAction.Reserves ?: error("InitialPlayerPlacement expects Reserves, was ${command::class.simpleName}")
        session.runStandalonePlayerMove(reserves.playerId, reserves.actionType) { boardMovement.applyReservesPlacement(session.board, reserves) }
        return InitialPlayerPlacement(pending.playerId, resolved = true)
    }
}
