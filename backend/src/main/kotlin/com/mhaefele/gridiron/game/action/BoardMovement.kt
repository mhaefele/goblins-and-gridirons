package com.mhaefele.gridiron.game.action

import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.board.dto.BoardMutator
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.canHoldBall
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import org.springframework.stereotype.Component

@Component
class BoardMovement(
    private val bounce: Bounce,
) {
    /**
     * Walk [path] one square at a time. Entering a square with a loose ball ends the move there.
     * Pickup vs bounce uses [ballEncounter] when set (from the legal action); otherwise derives from
     * [actionType] + [PlayerAbility.`Sure Scoop`] + holdability.
     *
     * Vault / Ethereal may include opponent- or teammate-occupied squares mid-path; those are skipped without
     * occupying (the next empty square emits a PlayerMoved that hops over them). Ethereal may also skip
     * obstruction squares mid-path.
     */
    fun applyPathMovement(
        mutator: BoardMutator,
        action: LegalAction.PathMovement,
    ) {
        val start = action.path.squares.first()
        require(mutator.playerCoordinate(action.playerId) == start) { "Path must start on ${action.playerId.value}'s square, got $start" }
        val end = action.path.squares.last()
        for (to in action.path.squares.drop(1)) {
            val landing = mutator.square(to)
            val from = mutator.playerCoordinate(action.playerId)

            // skip squares that are blocked since will break our model (but some abilities can move through them so they may be on the path)
            if (landing.isBlocked() || landing.playerId != null) {
                require(to != end) { "Square $to is invalid end square" }
                val mover = mutator.getPlayer(action.playerId)
                if (landing.isBlocked()) {
                    require(mover.hasAbility(PlayerAbility.Ethereal)) { "Square $to is blocked" }
                } else {
                    require(mover.hasAbility(PlayerAbility.Ethereal) || mover.hasAbility(PlayerAbility.Vault)) { "Square $to is occupied" }
                }
            } else {
                mutator.playerMovedOneStep(action.playerId, from, to)
            }
        }

        when(action.ballEncounter) {
            BallEncounter.PICK_UP -> {
                val player = mutator.getPlayer(action.playerId)
                val ability =
                    if (action.actionType != ActionType.RUN && player.hasAbility(PlayerAbility.`Sure Scoop`)) {
                        PlayerAbility.`Sure Scoop`
                    } else {
                        null
                    }
                mutator.ballPickedUpByPlayer(action.playerId, at = end, ability = ability)
            }
            BallEncounter.BOUNCE  -> { bounce.bounce(mutator, end) }
            null                  -> Unit
        }
    }

    /**
     * Knock down [playerId]. If they held the ball and have Ball Guard, throws —
     * callers that nest Ball Guard must use [applyKnockDownDeferringBallGuard].
     */
    fun applyKnockDown(
        session: MatchMutator,
        mutator: BoardMutator,
        playerId: PlayerId,
    ): Coordinate? {
        val player = mutator.getPlayer(playerId)
        mutator.playerKnockedDown(playerId)
        if (!player.holdsBall()) return null
        val at = mutator.playerCoordinate(playerId)
        mutator.ballDroppedByPlayer(playerId)
        check(!player.hasAbility(PlayerAbility.`Ball Guard`)) { "Ball Guard requires applyKnockDownDeferringBallGuard so the caller's Ongoing* can park the bounce" }
        bounce.bounce(mutator, at)
        return null
    }

    /**
     * Knock down [playerId]. If Ball Guard applies, drops the ball and returns true so the caller
     * can nest Ball Guard on the player's square; otherwise bounces and returns false.
     */
    fun applyKnockDownDeferringBallGuard(
        session: MatchMutator,
        mutator: BoardMutator,
        playerId: PlayerId,
    ): Boolean {
        val player = mutator.getPlayer(playerId)
        mutator.playerKnockedDown(playerId)
        if (!player.holdsBall()) return false
        val at = mutator.playerCoordinate(playerId)
        mutator.ballDroppedByPlayer(playerId)
        if (player.hasAbility(PlayerAbility.`Ball Guard`)) return true
        bounce.bounce(mutator, at)
        return false
    }

    fun applyStandUp(mutator: BoardMutator, action: LegalAction.StandUp) {
        mutator.playerStoodUp(action.playerId)
    }

    fun applyReservesPlacement(
        mutator: BoardMutator,
        action: LegalAction.Reserves
    ) {
        val landing = mutator.square(action.placement)
        mutator.playerDeployedFromReserves(action.playerId, action.placement)
        if (landing.looseBallId != null) {
            check(action.ballEncounter == BallEncounter.BOUNCE) { "Reserves onto a loose ball always bounce, was ${action.ballEncounter}" }
            bounce.bounce(mutator, action.placement)
        }
    }
}

/**
 * Run always attempts pickup. Mark/Sidestep bounce unless the mover has Sure Scoop
 * (card: pick up whenever moved into a ball square, as though making a Run).
 */
private fun picksUpLooseBallOnEntry(player: Player, actionType: ActionType): Boolean =
    when (actionType) {
        ActionType.RUN                       -> true
        ActionType.MARK, ActionType.SIDESTEP -> player.hasAbility(PlayerAbility.`Sure Scoop`)
        else                                 -> false
    }

/**
 * Run always attempts pickup. Mark/Sidestep bounce unless the mover has Sure Scoop
 * (card: pick up whenever moved into a ball square, as though making a Run).
 * Players who cannot hold still bounce.
 */
fun ballEncounterOnLooseBallEntry(player: Player, actionType: ActionType): BallEncounter =
    if (picksUpLooseBallOnEntry(player, actionType) && playerCanPickUpLooseBall(player)) {
        BallEncounter.PICK_UP
    } else {
        BallEncounter.BOUNCE
    }


fun playerCanPickUpLooseBall(player: Player): Boolean =
    !player.holdsBall() && player.profile.stats.throwStat.canHoldBall()