package com.mhaefele.gridiron.game.action.pending.throwball

import com.mhaefele.gridiron.game.action.BoardMovement
import com.mhaefele.gridiron.game.action.Bounce
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.ThrowTarget
import com.mhaefele.gridiron.game.action.ability.FreeFollowUpAbilityHook
import com.mhaefele.gridiron.game.action.dto.throwDiceRolled
import com.mhaefele.gridiron.game.action.utils.throwTarget
import com.mhaefele.gridiron.game.board.dto.BoardMutator
import com.mhaefele.gridiron.game.cards.BonusOffers
import com.mhaefele.gridiron.game.cards.DoOverRoll
import com.mhaefele.gridiron.game.dice.DiceRoller
import com.mhaefele.gridiron.game.dice.ThrowCheckOutcome
import com.mhaefele.gridiron.game.dice.throwCheck
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.ThrowContinuation
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.ThrowStat
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.ThrowRange
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver
import com.mhaefele.gridiron.game.action.pending.run.RunResolver

/**
 * Throw geometry and resolution — owns the [DiceRoller] port for throw checks.
 * Public surface: [beginThrow], [beginNested], [beginQuickReleaseThrow], [mutate].
 *
 * Beat order (bonus pauses stay in this class):
 * 1. Hand-off (no check) or throw target chosen
 * 2. Dead Eye / Hail Mary ([ThrowTargetChosen])
 * 3. Roll throw check → [ThrowDiceReroll] for Do-Over and/or Pinpoint Passer
 * 4. Resolve catch / bounce
 * 5. Ability follow-ups (Catch and Go free Run; Rally free Stand Up)
 */
@Component
class ThrowResolver(
    private val dice: DiceRoller,
    private val bounce: Bounce,
    private val boardMovement: BoardMovement,
    private val freeFollowUpAbilityHook: FreeFollowUpAbilityHook,
    @Lazy private val runResolver: RunResolver,
) : PendingDecisionResolver<ThrowContinuation>() {
    /** Start a throw FSM for coach input / park. */
    fun beginThrow(session: MatchMutator, action: LegalAction.Throw): ThrowContinuation =
        beginNested(session, action)

    /** Nest under a parent FSM — crank, return continuation (no pending write). */
    fun beginNested(session: MatchMutator, action: LegalAction.Throw): ThrowContinuation {
        val mutator = session.board
        val thrower = mutator.getPlayer(action.playerId)
        check(thrower.holdsBall()) { "Throw requires ball carrier, ${action.playerId.value} holds no ball" }
        standingTeammateAt(mutator, thrower, action.target.destination)

        val ongoingThrow = OngoingThrowMutator(OngoingThrow(action.playerId, action.target, OngoingThrow.ThrowStage.INITIALIZE))
        when (action.requiredBonusCard?.bonusPlay) {
            BonusPlay.`Hail Mary Pass` -> {
                // Card already consumed via requiredBonusCard auto-play; effect is process-scoped here.
                ongoingThrow.hailMary = true
                val reResolveTarget = reResolveThrowTarget(session, ongoingThrow)
                //  TODO:  Checking this, but why would we need to re-resolve the target if we already computed it for the legal action using the hail mary??
                check(ongoingThrow.target == reResolveTarget) { "Unexpected re-resolve target: ${ongoingThrow.target}, expected: ${reResolveTarget}" }
            }
            else                       -> Unit
        }
        return ThrowContinuation(advance(session, ongoingThrow))
    }

    override fun mutate(session: MatchMutator, pendingDecision: ThrowContinuation, command: LegalAction): ThrowContinuation {
        val ongoingThrow = OngoingThrowMutator(pendingDecision.ongoingThrow)
        handleLegalAction(session, ongoingThrow, command)
        return ThrowContinuation(advance(session, ongoingThrow))
    }

    /** Consume one POST at an awaiting-input stage. Does not crank. */
    private fun handleLegalAction(session: MatchMutator, ongoingThrow: OngoingThrowMutator, action: LegalAction) {
        check(ongoingThrow.awaitsInput) { "handleLegalAction expects awaitsInput, was ${ongoingThrow.stage}" }
        when (ongoingThrow.stage) {
            OngoingThrow.ThrowStage.HANDLE_BONUS_CARD_OFFER      -> {
                handleThrowTargetChosen(session, ongoingThrow, action)
                ongoingThrow.stage = OngoingThrow.ThrowStage.INITIAL_DICE_ROLL
            }
            OngoingThrow.ThrowStage.APPLY_POST_ROLL_ABILITIES    -> {
                if (handleHeldThrowCheck(session, ongoingThrow, action)) {
                    ongoingThrow.stage = OngoingThrow.ThrowStage.QUEUE_POST_ROLL_ABILITIES
                } else {
                    ongoingThrow.stage = OngoingThrow.ThrowStage.APPLY_FINAL_RESULT
                }
            }
            OngoingThrow.ThrowStage.HANDLE_ABILITY_FOLLOW_UP     -> {
                if (action is LegalAction.Skip) {
                    val ability = checkNotNull(ongoingThrow.offeredAbility) { "HANDLE_ABILITY_FOLLOW_UP Skip missing ability" }
                    val playerId = checkNotNull(ongoingThrow.offeredAbilityPlayerId) { "HANDLE_ABILITY_FOLLOW_UP Skip missing player" }
                    ongoingThrow.resolvedFollowUpOffers = ongoingThrow.resolvedFollowUpOffers + (ability to playerId)
                    ongoingThrow.offeredAbility = null
                    ongoingThrow.offeredAbilityPlayerId = null
                    ongoingThrow.stage = OngoingThrow.ThrowStage.OFFER_ABILITY_FOLLOW_UPS
                } else when (val ability = ongoingThrow.offeredAbility) {
                    PlayerAbility.`Catch and Go` -> {
                        val run = action as? LegalAction.Run ?: error("Catch and Go expects Run")
                        ongoingThrow.nestedRun = runResolver.beginNested(session, run)
                        ongoingThrow.stage = OngoingThrow.ThrowStage.DELEGATE_ABILITY_FREE_ACTION
                    }
                    PlayerAbility.Rally -> {
                        val standUp = action as? LegalAction.StandUp ?: error("Rally expects StandUp")
                        boardMovement.applyStandUp(session.board, standUp)
                        session.recordTurnAction(standUp.playerId, ActionType.STAND_UP, isFree = true)
                        ongoingThrow.resolvedFollowUpOffers = ongoingThrow.resolvedFollowUpOffers + (ability to standUp.playerId)
                        ongoingThrow.offeredAbility = null
                        ongoingThrow.offeredAbilityPlayerId = null
                        ongoingThrow.stage = OngoingThrow.ThrowStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                    else                       -> error("No Throw free-action handling for $ability")
                }
            }
            OngoingThrow.ThrowStage.DELEGATE_ABILITY_FREE_ACTION -> {
                ongoingThrow.nestedRun = runResolver.mutate(session, ongoingThrow.nestedRun!!, action)
            }
            else                                                 -> error("handleLegalAction unexpected ${ongoingThrow.stage}")
        }
    }

    /** Auto-crank until DONE or an awaiting-input park. Never takes a [LegalAction]. */
    private fun advance(session: MatchMutator, ongoingThrow: OngoingThrowMutator): OngoingThrow {
        val mutator = session.board
        while (ongoingThrow.stage != OngoingThrow.ThrowStage.DONE && !ongoingThrow.awaitsInput) {
            when (ongoingThrow.stage) {
                OngoingThrow.ThrowStage.INITIALIZE                   -> {
                    if (ongoingThrow.target.range == ThrowRange.HAND_OFF) {
                        mutator.ballThrown(ongoingThrow.throwerId, mutator.square(ongoingThrow.target.destination).playerId!!)
                        ongoingThrow.stage = OngoingThrow.ThrowStage.DONE
                    } else {
                        ongoingThrow.stage = OngoingThrow.ThrowStage.OFFER_BONUS_CARDS
                    }
                }
                OngoingThrow.ThrowStage.OFFER_BONUS_CARDS            -> {
                    check(ongoingThrow.deadEye == false)
                    // Attached Accurate/Hail Mary already set modifiers via requiredBonusCard auto-play.
                    if (!ongoingThrow.hailMary && BonusOffers.shouldOfferBeforeThrowCheck(session)) {
                        ongoingThrow.stage = OngoingThrow.ThrowStage.HANDLE_BONUS_CARD_OFFER
                    } else {
                        ongoingThrow.stage = OngoingThrow.ThrowStage.INITIAL_DICE_ROLL
                    }
                }
                OngoingThrow.ThrowStage.INITIAL_DICE_ROLL            -> {
                    rollAndRecordOutcome(session, ongoingThrow)
                    ongoingThrow.stage = OngoingThrow.ThrowStage.QUEUE_POST_ROLL_ABILITIES
                }
                OngoingThrow.ThrowStage.QUEUE_POST_ROLL_ABILITIES    -> {
                    if (shouldOfferPostRollAbilities(session, ongoingThrow)) {
                        ongoingThrow.stage = OngoingThrow.ThrowStage.APPLY_POST_ROLL_ABILITIES
                    } else {
                        ongoingThrow.stage = OngoingThrow.ThrowStage.APPLY_FINAL_RESULT
                    }
                }
                OngoingThrow.ThrowStage.APPLY_FINAL_RESULT           -> {
                    resolveThrowResult(session, ongoingThrow)
                    ongoingThrow.stage = OngoingThrow.ThrowStage.OFFER_ABILITY_FOLLOW_UPS
                }
                OngoingThrow.ThrowStage.OFFER_ABILITY_FOLLOW_UPS     -> {
                    val grant = freeFollowUpAbilityHook.offersAfterResolved(ongoingThrow.throwerId, ActionType.THROW, session.toMatchState())
                        .firstOrNull { (it.ability to it.playerId) !in ongoingThrow.resolvedFollowUpOffers }
                    if (grant == null) {
                        ongoingThrow.stage = OngoingThrow.ThrowStage.DONE
                    } else {
                        ongoingThrow.offeredAbility = grant.ability
                        ongoingThrow.offeredAbilityPlayerId = grant.playerId
                        ongoingThrow.stage = OngoingThrow.ThrowStage.HANDLE_ABILITY_FOLLOW_UP
                    }
                }
                OngoingThrow.ThrowStage.DELEGATE_ABILITY_FREE_ACTION -> {
                    val nested = ongoingThrow.nestedRun!!
                    if (nested.isFinished()) {
                        session.recordTurnAction(nested.ongoingRun.playerId, ActionType.RUN, isFree = true)
                        val ability = checkNotNull(ongoingThrow.offeredAbility) { "DELEGATE_ABILITY_FREE_ACTION missing offeredAbility" }
                        val playerId = checkNotNull(ongoingThrow.offeredAbilityPlayerId) { "DELEGATE_ABILITY_FREE_ACTION missing offeredAbilityPlayerId" }
                        ongoingThrow.resolvedFollowUpOffers = ongoingThrow.resolvedFollowUpOffers + (ability to playerId)
                        ongoingThrow.nestedRun = null
                        ongoingThrow.offeredAbility = null
                        ongoingThrow.offeredAbilityPlayerId = null
                        ongoingThrow.stage = OngoingThrow.ThrowStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                }
                OngoingThrow.ThrowStage.HANDLE_BONUS_CARD_OFFER,
                OngoingThrow.ThrowStage.APPLY_POST_ROLL_ABILITIES,
                OngoingThrow.ThrowStage.HANDLE_ABILITY_FOLLOW_UP,
                OngoingThrow.ThrowStage.DONE,
                                                                     -> error("auto-crank unexpected ${ongoingThrow.stage}")
            }
        }
        return ongoingThrow.ongoingThrow
    }

    private fun resolveThrowResult(session: MatchMutator, ongoingThrow: OngoingThrowMutator) {
        val thrower = session.board.getPlayer(ongoingThrow.throwerId)
        val catcher = standingTeammateAt(session.board, thrower, ongoingThrow.target.destination)
        val mutator = session.board
        val from = mutator.playerCoordinate(ongoingThrow.throwerId)
        when (throwCheckOutcome(session, thrower, ongoingThrow.naturalRoll, ongoingThrow.target.range, ongoingThrow.target.obstructed, ongoingThrow)) {
            ThrowCheckOutcome.Success     -> mutator.ballThrown(ongoingThrow.throwerId, catcher.id)
            ThrowCheckOutcome.Fumble      -> {
                mutator.ballDroppedByPlayer(ongoingThrow.throwerId)
                bounce.bounce(mutator, from)
            }
            is ThrowCheckOutcome.Fail     -> {
                mutator.ballThrowMissed(ongoingThrow.throwerId, from, ongoingThrow.target.destination)
                bounce.bounce(mutator, ongoingThrow.target.destination)
            }
            ThrowCheckOutcome.Unthrowable -> error("Ball carrier ${ongoingThrow.throwerId.value} cannot throw")
        }
    }

    private fun handleThrowTargetChosen(session: MatchMutator, ongoingThrow: OngoingThrowMutator, command: LegalAction) {
        when (command) {
            is LegalAction.DeclineBonus      -> Unit
            is LegalAction.PlayDeadEye -> {
                //  TODO:  Can we handle this as a required card on the legal action?
                session.playBonusCard(session.activeCoach, command.bonusPlay)
                ongoingThrow.deadEye = true
            }
            is LegalAction.PlayHailMary      -> {
                session.playBonusCard(session.activeCoach, command.bonusPlay)
                ongoingThrow.hailMary = true
                ongoingThrow.target = reResolveThrowTarget(session, ongoingThrow)
            }
            else                             -> error("ThrowTargetChosen expects PlayDeadEye, PlayHailMary, or DeclineBonus, was ${command::class.simpleName}")
        }
    }

    private fun reResolveThrowTarget(session: MatchMutator, ongoingThrow: OngoingThrowMutator): ThrowTarget {
        val player = session.board.getPlayer(ongoingThrow.throwerId)
        val catcher = session.board.getPlayerAt(ongoingThrow.target.destination)
        return if (ongoingThrow.hailMary) {
            ThrowTarget(ongoingThrow.target.destination, ThrowRange.LONG, obstructed = false)
        } else {
            //  Can we just use the throw target from the ongoing throw/legal action here instead of re-calculating it?
            throwTarget(player, catcher, session.board.board)
        }
    }

    //  returns true if we need to check abilities again, false if not
    private fun handleHeldThrowCheck(session: MatchMutator, ongoingThrow: OngoingThrowMutator, command: LegalAction): Boolean {
        when (command) {
            //  Decline everything offered
            is LegalAction.DeclineBonus -> {
                ongoingThrow.doOverThrowDeclined = true
                return false
            }
            //  Adjust by do-over, maybe then offer pinpoint passer
            is LegalAction.AdjustThrowByDoOver -> {
                session.playBonusCard(session.activeCoach, BonusPlay.`Do-Over`)
                rollAndRecordOutcome(session, ongoingThrow, BonusPlay.`Do-Over`)
                return true
            }
            //  Adjust by pinpoint passer, mark do-over as not used.
            //  TODO:  Why don't we do pinpoint passer first since it's free then do-over if chosen?
            //  TODO:  Why don't we use pinpoint passer automatically?  wouldn't we always use it on a failed throw?
            is LegalAction.ChooseThrowReroll -> {
                ongoingThrow.doOverThrowDeclined = true
                if (command.reroll) {
                    rollAndRecordOutcome(session, ongoingThrow, playerAbility = PlayerAbility.`Pinpoint Passer`)
                    ongoingThrow.pinpointPasserUsed = true
                }
                return false
            }
            else                             -> error("ThrowDiceReroll unexpected command ${command::class.simpleName}")
        }
    }

    private fun shouldOfferPostRollAbilities(session: MatchMutator, ongoingThrow: OngoingThrowMutator): Boolean {
        val thrower = session.board.getPlayer(ongoingThrow.throwerId)
        val needsEp = thrower.hasAbility(PlayerAbility.`Pinpoint Passer`) && !ongoingThrow.pinpointPasserUsed && ongoingThrow.outcome != ThrowCheckOutcome.Success
        val doOver = !ongoingThrow.doOverThrowDeclined && BonusOffers.hasPlayable(session, session.activeCoach, BonusPlay.`Do-Over`) && DoOverRoll.mayImproveThrow(session.board.board, ongoingThrow.throwerId, ongoingThrow.naturalRoll, ongoingThrow.target.range, ongoingThrow.target.obstructed, ongoingThrow.hailMary, ongoingThrow.deadEye)
        return needsEp || doOver
    }

    private fun rollAndRecordOutcome(session: MatchMutator, ongoingThrow: OngoingThrowMutator, bonusPlay: BonusPlay? = null, playerAbility: PlayerAbility? = null) {
        val thrower = session.board.getPlayer(ongoingThrow.throwerId)
        val roll = rollAndRecordThrow(session.board, thrower, ongoingThrow.target.range, ongoingThrow.target.obstructed, bonusPlay = bonusPlay, playerAbility = playerAbility)
        val outcome = throwCheckOutcome(session, thrower, roll, ongoingThrow.target.range, ongoingThrow.target.obstructed, ongoingThrow)
        ongoingThrow.naturalRoll = roll
        ongoingThrow.outcome = outcome
    }

    /** One-shot Quick Release from Block — applies catch/bounce immediately; no continuation parked. */
    fun beginQuickReleaseThrow(session: MatchMutator, action: LegalAction.QuickReleaseThrow) {
        val mutator = session.board
        val throwerId = action.playerId
        val destination = action.target.destination
        val thrower = mutator.getPlayer(throwerId)
        check(thrower.holdsBall()) { "Throw requires ball carrier, ${throwerId.value} holds no ball" }
        val catcher = standingTeammateAt(mutator, thrower, destination)
        val target = throwTarget(thrower, catcher, mutator.board)
        // Create a basic ongoing throw.  will not even be persisted since this always resolves.
        val ongoingThrow = OngoingThrowMutator(OngoingThrow(throwerId, target, OngoingThrow.ThrowStage.DONE))
        when (target.range) {
            ThrowRange.HAND_OFF -> mutator.ballThrown(throwerId, catcher.id)
            else                -> {
                rollAndRecordOutcome(session, ongoingThrow)
                resolveThrowResult(session, ongoingThrow)
            }
        }
    }

    private fun throwCheckOutcome(session: MatchMutator, thrower: Player, naturalRoll: Int, range: ThrowRange, obstructed: Boolean, ongoingThrow: OngoingThrowMutator): ThrowCheckOutcome {
        return when {
            ongoingThrow.hailMary      -> throwCheck(naturalRoll, ThrowStat.Threshold(4), ThrowRange.SHORT, obstructed = false)
            ongoingThrow.deadEye -> throwCheck(naturalRoll, ThrowStat.Threshold(2), ThrowRange.SHORT, obstructed = false)
            else                       -> throwCheck(naturalRoll, thrower.profile.stats.throwStat, range, obstructed)
        }
    }

    private fun rollAndRecordThrow(mutator: BoardMutator, thrower: Player, range: ThrowRange, obstructed: Boolean, bonusPlay: BonusPlay? = null, playerAbility: PlayerAbility? = null): Int {
        val roll = dice.d6()
        mutator.record(throwDiceRolled(roll, thrower.profile.stats.throwStat, range, obstructed, bonusPlay = bonusPlay, ability = playerAbility))
        return roll
    }

    private fun standingTeammateAt(mutator: BoardMutator, thrower: Player, destination: Coordinate): Player =
        mutator.board.players.values.singleOrNull { player ->
            player.team == thrower.team && player.status == PlayerStatus.STANDING && mutator.playerCoordinate(player) == destination
        } ?: error("No standing teammate at throw destination $destination")
}
