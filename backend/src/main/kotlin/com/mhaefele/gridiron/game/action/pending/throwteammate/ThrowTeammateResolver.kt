package com.mhaefele.gridiron.game.action.pending.throwteammate

import com.mhaefele.gridiron.game.action.Bounce
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.throwDiceRolled
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver
import com.mhaefele.gridiron.game.action.pending.armourcheck.ArmourCheckResolver
import com.mhaefele.gridiron.game.action.pending.knockdown.KnockDownResolver
import com.mhaefele.gridiron.game.action.utils.distance
import com.mhaefele.gridiron.game.action.utils.isThrowableTeammate
import com.mhaefele.gridiron.game.action.utils.throwTeammateLandingTarget
import com.mhaefele.gridiron.game.board.dto.BoardMutator
import com.mhaefele.gridiron.game.dice.DiceRoller
import com.mhaefele.gridiron.game.dice.ThrowCheckOutcome
import com.mhaefele.gridiron.game.dice.throwCheck
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.pending.ThrowTeammateContinuation
import com.mhaefele.gridiron.shared.PlayerId
import org.springframework.stereotype.Component

/**
 * Throw Team-mate resolution (digest § Throw Team-mate) — separate from ball [ThrowResolver].
 *
 * Stage story (throw facts on [OngoingThrowTeammate] after the check):
 * - Empty success: APPLY_EMPTY_LANDING → DONE
 * - Empty fail: APPLY_EMPTY_LANDING → KNOCK_DOWN_THROWN → (DELEGATE_KNOCK_DOWN_THROWN) → DONE
 * - Occupied standing: KNOCK_DOWN_OCCUPANT → (DELEGATE_KNOCK_DOWN_OCCUPANT) → FINISH_OCCUPIED → DONE
 * - Occupied already prone: KNOCK_DOWN_OCCUPANT → (DELEGATE_ARMOUR_CHECK) → FINISH_OCCUPIED → DONE
 * - Fumble: resolved in handle → DONE
 */
@Component
class ThrowTeammateResolver(
    private val dice: DiceRoller,
    private val bounce: Bounce,
    private val knockDownResolver: KnockDownResolver,
    private val armourCheckResolver: ArmourCheckResolver,
) : PendingDecisionResolver<ThrowTeammateContinuation>() {
    /** Select a throwable teammate — returns [ThrowTeammateContinuation] for the caller to delegate. */
    fun beginThrowTeammate(session: MatchMutator, action: LegalAction.ThrowTeammateSelect): ThrowTeammateContinuation {
        val thrower = session.board.getPlayer(action.playerId)
        val teammate = session.board.getPlayer(action.teammateId)
        require(isThrowableTeammate(thrower, teammate)) { "${action.teammateId.value} is not a throwable team-mate for ${action.playerId.value}" }
        val throwerAt = session.board.playerCoordinate(action.playerId)
        val teammateAt = session.board.playerCoordinate(action.teammateId)
        require(distance(session.board.board.squareAt(throwerAt), session.board.board.squareAt(teammateAt), session.board.board) == 1) { "Thrown team-mate must be adjacent" }
        check(session.currentPlayerAction != null) { "Throw Team-mate select expects a current player action" }
        return ThrowTeammateContinuation(
            OngoingThrowTeammate(action.playerId, action.teammateId, OngoingThrowTeammate.ThrowTeammateStage.AWAITING_LANDING),
        )
    }

    /**
     * Resume Throw Team-mate. Cancel returns DONE with [OngoingThrowTeammate.cancelled];
     * caller (RPA) clears its delegate.
     */
    override fun mutate(session: MatchMutator, pending: ThrowTeammateContinuation, command: LegalAction): ThrowTeammateContinuation {
        val ongoing = OngoingThrowTeammateMutator(pending.ongoingThrowTeammate)
        handleLegalAction(session, ongoing, command)
        return ThrowTeammateContinuation(advance(session, ongoing))
    }

    private fun handleLegalAction(session: MatchMutator, ongoing: OngoingThrowTeammateMutator, command: LegalAction) {
        check(ongoing.awaitsInput) { "handleLegalAction expects awaitsInput, was ${ongoing.stage}" }
        when (ongoing.stage) {
            OngoingThrowTeammate.ThrowTeammateStage.AWAITING_LANDING              -> when (command) {
                is LegalAction.CancelThrowTeammate -> {
                    session.abortCurrentPlayerAction()
                    session.returnRegularActionSlot()
                    ongoing.cancelled = true
                    ongoing.stage = OngoingThrowTeammate.ThrowTeammateStage.DONE
                }
                is LegalAction.ThrowTeammate       -> {
                    check(command.playerId == ongoing.throwerId && command.teammateId == ongoing.teammateId) { "Landing thrower/teammate drifted from ongoing ${ongoing.throwerId}/${ongoing.teammateId}" }
                    check(session.currentPlayerAction != null) { "Throw Team-mate landing expects a current player action" }
                    rollThrowCheck(session, ongoing, command)
                }
                else                               -> error("ThrowTeammateContinuation AWAITING_LANDING expects ThrowTeammate or CancelThrowTeammate, was ${command::class.simpleName}")
            }
            OngoingThrowTeammate.ThrowTeammateStage.DELEGATE_KNOCK_DOWN_THROWN,
            OngoingThrowTeammate.ThrowTeammateStage.DELEGATE_KNOCK_DOWN_OCCUPANT -> { ongoing.nestedKnockDown = knockDownResolver.mutate(session, ongoing.nestedKnockDown!!, command) }
            OngoingThrowTeammate.ThrowTeammateStage.DELEGATE_ARMOUR_CHECK        -> { ongoing.nestedArmourCheck = armourCheckResolver.mutate(session, ongoing.nestedArmourCheck!!, command) }
            else                                                                 -> error("handleLegalAction unexpected ${ongoing.stage}")
        }
    }

    private fun advance(session: MatchMutator, ongoing: OngoingThrowTeammateMutator): OngoingThrowTeammate {
        while (ongoing.stage != OngoingThrowTeammate.ThrowTeammateStage.DONE && !ongoing.awaitsInput) {
            when (ongoing.stage) {
                OngoingThrowTeammate.ThrowTeammateStage.APPLY_EMPTY_LANDING          -> {
                    applyEmptyLanding(session.board, ongoing)
                    ongoing.stage = if (ongoing.throwSucceeded == true) {
                        OngoingThrowTeammate.ThrowTeammateStage.DONE
                    } else {
                        OngoingThrowTeammate.ThrowTeammateStage.KNOCK_DOWN_THROWN
                    }
                }
                OngoingThrowTeammate.ThrowTeammateStage.KNOCK_DOWN_THROWN            -> {
                    ongoing.nestedKnockDown = knockDownResolver.beginStanding(session, ongoing.teammateId, withArmour = false)
                    ongoing.stage = OngoingThrowTeammate.ThrowTeammateStage.DELEGATE_KNOCK_DOWN_THROWN
                }
                OngoingThrowTeammate.ThrowTeammateStage.DELEGATE_KNOCK_DOWN_THROWN   -> {
                    val nested = checkNotNull(ongoing.nestedKnockDown) { "DELEGATE_KNOCK_DOWN_THROWN missing nestedKnockDown" }
                    if (nested.isFinished()) {
                        ongoing.nestedKnockDown = null
                        ongoing.stage = OngoingThrowTeammate.ThrowTeammateStage.DONE
                    }
                }
                OngoingThrowTeammate.ThrowTeammateStage.KNOCK_DOWN_OCCUPANT          -> {
                    val destination = checkNotNull(ongoing.destination) { "KNOCK_DOWN_OCCUPANT missing destination" }
                    val throwSucceeded = checkNotNull(ongoing.throwSucceeded) { "KNOCK_DOWN_OCCUPANT missing throwSucceeded" }
                    val occupantId = checkNotNull(session.board.square(destination).playerId) { "KNOCK_DOWN_OCCUPANT missing occupant at $destination" }
                    check(occupantId != ongoing.teammateId) { "KNOCK_DOWN_OCCUPANT occupant is the thrown player $occupantId" }
                    val occupant = session.board.getPlayer(occupantId)
                    if (occupant.status == PlayerStatus.STANDING) {
                        ongoing.nestedKnockDown = knockDownResolver.beginStanding(session, occupantId, withArmour = true, armourMinusOne = throwSucceeded)
                        ongoing.stage = OngoingThrowTeammate.ThrowTeammateStage.DELEGATE_KNOCK_DOWN_OCCUPANT
                    } else {
                        check(!occupant.holdsBall()) { "A prone player can't hold a ball." }
                        ongoing.nestedArmourCheck = armourCheckResolver.begin(session, occupantId, armourMinusOne = throwSucceeded)
                        ongoing.stage = OngoingThrowTeammate.ThrowTeammateStage.DELEGATE_ARMOUR_CHECK
                    }
                }
                OngoingThrowTeammate.ThrowTeammateStage.DELEGATE_KNOCK_DOWN_OCCUPANT -> {
                    val nested = checkNotNull(ongoing.nestedKnockDown) { "DELEGATE_KNOCK_DOWN_OCCUPANT missing nestedKnockDown" }
                    if (nested.isFinished()) {
                        ongoing.nestedKnockDown = null
                        ongoing.stage = OngoingThrowTeammate.ThrowTeammateStage.FINISH_OCCUPIED
                    }
                }
                OngoingThrowTeammate.ThrowTeammateStage.DELEGATE_ARMOUR_CHECK        -> {
                    val nested = checkNotNull(ongoing.nestedArmourCheck) { "DELEGATE_ARMOUR_CHECK missing nestedArmourCheck" }
                    if (nested.isFinished()) {
                        ongoing.nestedArmourCheck = null
                        ongoing.stage = OngoingThrowTeammate.ThrowTeammateStage.FINISH_OCCUPIED
                    }
                }
                OngoingThrowTeammate.ThrowTeammateStage.FINISH_OCCUPIED              -> {
                    finishOccupied(session.board, ongoing)
                    ongoing.stage = OngoingThrowTeammate.ThrowTeammateStage.DONE
                }
                else                                                                 -> error("Throw Team-mate auto-crank unexpected ${ongoing.stage}")
            }
        }
        return ongoing.ongoingThrowTeammate
    }

    /** Roll the Throw Check, stash throw facts, enter the empty or occupied path (Fumble → DONE). */
    private fun rollThrowCheck(session: MatchMutator, ongoing: OngoingThrowTeammateMutator, action: LegalAction.ThrowTeammate) {
        val mutator = session.board
        val thrower = mutator.getPlayer(action.playerId)
        val teammate = mutator.getPlayer(action.teammateId)
        require(isThrowableTeammate(thrower, teammate)) { "${action.teammateId.value} is not a throwable team-mate for ${action.playerId.value}" }
        val target = throwTeammateLandingTarget(thrower, action.target.destination, mutator.board)
        check(target.range == action.target.range && target.obstructed == action.target.obstructed) { "Landing target geometry drifted for ${action.target.destination}" }

        val roll = dice.d6()
        mutator.record(throwDiceRolled(roll, thrower.profile.stats.throwStat, target.range, target.obstructed))
        when (val outcome = throwCheck(roll, thrower.profile.stats.throwStat, target.range, target.obstructed)) {
            ThrowCheckOutcome.Unthrowable                        -> error("Throw Team-mate requires a throw value, ${action.playerId.value} is unthrowable")
            ThrowCheckOutcome.Fumble                             -> {
                resolveFumble(mutator, action.teammateId, action.playerId)
                ongoing.stage = OngoingThrowTeammate.ThrowTeammateStage.DONE
            }
            ThrowCheckOutcome.Success, is ThrowCheckOutcome.Fail -> {
                val occupied = mutator.square(target.destination).playerId != null
                ongoing.destination = target.destination
                ongoing.throwSucceeded = outcome == ThrowCheckOutcome.Success
                ongoing.stage = if (occupied) {
                    OngoingThrowTeammate.ThrowTeammateStage.KNOCK_DOWN_OCCUPANT
                } else {
                    OngoingThrowTeammate.ThrowTeammateStage.APPLY_EMPTY_LANDING
                }
            }
        }
    }

    private fun applyEmptyLanding(mutator: BoardMutator, ongoing: OngoingThrowTeammateMutator) {
        val destination = checkNotNull(ongoing.destination) { "APPLY_EMPTY_LANDING missing destination" }
        val from = mutator.playerCoordinate(ongoing.teammateId)
        val preExistingLoose = mutator.square(destination).looseBallId
        mutator.playerMoved(ongoing.teammateId, from, destination)
        if (preExistingLoose != null) {
            bounce.bounce(mutator, destination)
        }
    }

    private fun finishOccupied(mutator: BoardMutator, ongoing: OngoingThrowTeammateMutator) {
        val destination = checkNotNull(ongoing.destination) { "FINISH_OCCUPIED missing destination" }
        val thrown = mutator.getPlayer(ongoing.teammateId)
        if (thrown.holdsBall()) {
            val from = mutator.playerCoordinate(ongoing.teammateId)
            mutator.ballThrowMissed(ongoing.teammateId, from, destination)
            bounce.bounce(mutator, destination, cannotPickUp = ongoing.teammateId)
        }
        mutator.playerInjured(ongoing.teammateId)
    }

    private fun resolveFumble(
        mutator: BoardMutator,
        teammateId: PlayerId,
        throwerId: PlayerId,
    ) {
        val thrown = mutator.getPlayer(teammateId)
        if (thrown.holdsBall()) {
            val from = mutator.playerCoordinate(teammateId)
            val throwerAt = mutator.playerCoordinate(throwerId)
            mutator.ballThrowMissed(teammateId, from, throwerAt)
            bounce.bounce(mutator, throwerAt, cannotPickUp = teammateId)
        }
        mutator.playerInjured(teammateId)
    }
}
