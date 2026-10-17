package com.mhaefele.gridiron.game.cards

import com.mhaefele.gridiron.model.pending.ClaimChallenge
import com.mhaefele.gridiron.game.action.Bounce
import com.mhaefele.gridiron.game.action.dto.DiceRollPurpose
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.action.utils.neighbors
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.OpenPlayerAction
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.PlayerId

/**
 * Vampire Thirst — after the Claim Challenge Card step for a Block, if any Miss
 * showed on the Block Dice, injure that player (ball bounces if held).
 *
 * Fires only after the claim step (sync auto-claim or coach [ClaimChallenge])
 * so claim always precedes injury.
 *
 * Devoted: when an adjacent teammate has Devoted, auto-redirect the injury to them
 * (coach choice parked — see docs/D1-open-questions.md).
 */
object Thirst {
    fun afterClaimStep(session: MatchMutator, completed: OpenPlayerAction, bounce: Bounce) {
        val player = session.board.getPlayer(completed.playerId)
        val events = session.actionBracketEvents()
        when (completed.actionType) {
            ActionType.BLOCK -> {
                if (!player.hasAbility(PlayerAbility.Thirst)) return
                if (!events.any { event ->
                        event is DomainEvent.DiceRolled &&
                            event.purpose == DiceRollPurpose.BLOCK &&
                            event.blockFaces.orEmpty().any { face -> face == BlockDieFace.MISS }
                    }
                ) {
                    return
                }
                // Touchdown (and other removals) run on the turn-slot beat before THIRST.
                if (player.status !in setOf(PlayerStatus.STANDING, PlayerStatus.PRONE)) return
                injureThirstVictim(session, completed.playerId, bounce)
            }
            else             -> Unit
        }
    }

    private fun injureThirstVictim(session: MatchMutator, playerId: PlayerId, bounce: Bounce) {
        val victim = session.board.getPlayer(playerId)
        val targetId = adjacentDevoted(session, victim)?.id ?: playerId
        val at = session.board.playerCoordinate(targetId)
        if (session.board.getPlayer(targetId).holdsBall()) {
            session.board.ballDroppedByPlayer(targetId)
            bounce.bounce(session.board, at, cannotPickUp = targetId)
        }
        session.board.playerInjured(targetId)
    }

    private fun adjacentDevoted(session: MatchMutator, victim: Player): Player? {
        val at = session.board.playerCoordinate(victim.id)
        return neighbors(session.board.board.squareAt(at), session.board.board)
            .mapNotNull { n -> n.playerId?.let { session.board.getPlayer(it) } }
            .firstOrNull { ally ->
                ally.team == victim.team && ally.id != victim.id && ally.hasAbility(PlayerAbility.Devoted)
            }
    }
}
