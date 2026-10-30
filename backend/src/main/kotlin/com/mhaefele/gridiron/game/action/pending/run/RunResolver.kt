package com.mhaefele.gridiron.game.action.pending.run

import com.mhaefele.gridiron.game.action.BoardMovement
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.ability.FreeFollowUpAbilityHook
import com.mhaefele.gridiron.game.action.dto.DiceRollPurpose
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.cards.BonusOffers
import com.mhaefele.gridiron.game.dice.DiceRoller
import com.mhaefele.gridiron.game.legalmoves.cutOffReachableOpenAlly
import com.mhaefele.gridiron.game.legalmoves.mayIntervene
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.RunContinuation
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.MoveValue
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.runMoveAllowance
import com.mhaefele.gridiron.shared.ActionType
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver
import com.mhaefele.gridiron.game.action.pending.mark.MarkResolver
import com.mhaefele.gridiron.game.action.pending.throwball.ThrowResolver

/**
 * Run resolution — one [RunContinuation] through path, Cut Off nest, and ability follow-ups.
 * Public surface: [beginRun], [beginDeclareRun], [beginNested], [mutate].
 */
@Component
class RunResolver(
    private val dice: DiceRoller,
    private val boardMovement: BoardMovement,
    private val freeFollowUpAbilityHook: FreeFollowUpAbilityHook,
    @Lazy private val markResolver: MarkResolver,
    @Lazy private val throwResolver: ThrowResolver,
) : PendingDecisionResolver<RunContinuation>() {
    fun beginRun(session: MatchMutator, action: LegalAction.Run): RunContinuation {
        val ongoing = applyDeclared(session, action)
        val next = advance(session, ongoing)
        // Park even at DONE — TurnEngine COMPLETE pops the finished continuation and closes the open action.
        return RunContinuation(next)
    }

    /** Nest under a parent FSM — apply path, crank, return continuation (no pending write). */
    fun beginNested(session: MatchMutator, action: LegalAction.Run): RunContinuation {
        val ongoing = applyDeclared(session, action)
        return RunContinuation(advance(session, ongoing))
    }

    private fun applyDeclared(session: MatchMutator, action: LegalAction.Run): OngoingRunMutator {
        val sprintBonus = if (action.requiredBonusCard == BonusPlay.Sprint) 2 else 0
        val budget = session.board.getPlayer(action.playerId).profile.stats.runMoveAllowance() + sprintBonus
        require(action.path.steps <= budget) { "Path of ${action.path.steps} steps exceeds remaining move budget $budget" }
        val endedOnBall = session.board.square(action.path.squares.last()).looseBallId != null
        boardMovement.applyPathMovement(session.board, action)
        val remaining = budget - action.path.steps
        val stage = if (endedOnBall && remaining > 0) {
            OngoingRun.RunStage.AWAITING_PATH
        } else {
            OngoingRun.RunStage.OFFER_CUT_OFF
        }
        return OngoingRunMutator(action.playerId, stage, remaining)
    }

    fun beginDeclareRun(session: MatchMutator, action: LegalAction.DeclareRun): RunContinuation {
        val move = session.board.getPlayer(action.playerId).profile.stats.move
        check(move is MoveValue.RandomD6) { "DeclareRun is only for RandomD6 Move, was $move" }
        check(action.requiredBonusCard == null) { "DeclareRun must be plain; Sprint is HANDLE_SPRINT" }
        return if (BonusOffers.hasPlayable(session, session.activeCoach, BonusPlay.Sprint)) {
            val ongoing = OngoingRunMutator(action.playerId, OngoingRun.RunStage.HANDLE_SPRINT, 0)
            RunContinuation(ongoing.ongoingRun)
        } else {
            val roll = dice.d6()
            session.board.record(DomainEvent.DiceRolled(purpose = DiceRollPurpose.MOVE, d6 = roll))
            RunContinuation(OngoingRunMutator(action.playerId, OngoingRun.RunStage.AWAITING_PATH, roll).ongoingRun)
        }
    }

    override fun mutate(session: MatchMutator, pending: RunContinuation, command: LegalAction): RunContinuation {
        val ongoing = OngoingRunMutator(pending.ongoingRun)
        handleLegalAction(session, ongoing, command)
        return RunContinuation(advance(session, ongoing))
    }

    /** Consume one POST at an awaiting-input stage. Does not crank. */
    private fun handleLegalAction(session: MatchMutator, ongoing: OngoingRunMutator, action: LegalAction) {
        check(ongoing.awaitsInput) { "handleLegalAction expects awaitsInput, was ${ongoing.stage}" }
        when (ongoing.stage) {
            OngoingRun.RunStage.AWAITING_PATH -> when (action) {
                is LegalAction.Skip -> ongoing.stage = OngoingRun.RunStage.OFFER_CUT_OFF
                is LegalAction.Run  -> applyPath(session, ongoing, action)
                else                -> error("AWAITING_PATH expects Run or Skip, was ${action::class.simpleName}")
            }

            OngoingRun.RunStage.HANDLE_SPRINT -> {
                val sprintBonus = when (action) {
                    is LegalAction.DeclineBonus -> 0
                    is LegalAction.PlaySprint   -> {
                        // TODO: can we make this require a card and follow that path instead of implicitly playing the card based on the action?
                        session.playBonusCard(session.activeCoach, BonusPlay.Sprint)
                        2
                    }
                    else                        -> error("HANDLE_SPRINT expects PlaySprint or DeclineBonus, was ${action::class.simpleName}")
                }
                val roll = dice.d6()
                session.board.record(DomainEvent.DiceRolled(purpose = DiceRollPurpose.MOVE, d6 = roll))
                ongoing.squaresRemaining = roll + sprintBonus
                ongoing.stage = OngoingRun.RunStage.AWAITING_PATH
            }

            OngoingRun.RunStage.HANDLE_CUT_OFF -> {
                val opposition = checkNotNull(ongoing.respondingCoach) { "HANDLE_CUT_OFF missing respondingCoach" }
                when (action) {
                    is LegalAction.DeclineBonus  -> {
                        ongoing.respondingCoach = null
                        ongoing.stage = OngoingRun.RunStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                    is LegalAction.PlayBonusCard -> {
                        check(action.bonusPlay == BonusPlay.`Cut Off`) { "HANDLE_CUT_OFF expects CUT_OFF, was ${action.bonusPlay}" }
                        // TODO: can we make this require a card and follow that path instead of implicitly playing the card based on the action?
                        val cardId = session.playBonusCard(opposition, action.bonusPlay)
                        val runnerAt = session.board.playerCoordinate(ongoing.playerId)
                        val open = cutOffReachableOpenAlly(session.board.board, opposition, runnerAt) ?: error("Cut Off Play with no reachable Open ally")
                        ongoing.interruptRefundCardId = cardId
                        ongoing.interruptMarkPlayerId = open.id
                        ongoing.stage = OngoingRun.RunStage.CHOOSE_INTERRUPT_MARK
                    }
                    else                         -> error("HANDLE_CUT_OFF expects PlayBonusCard or DeclineBonus, was ${action::class.simpleName}")
                }
            }

            OngoingRun.RunStage.CHOOSE_INTERRUPT_MARK -> {
                val playerId = checkNotNull(ongoing.interruptMarkPlayerId) { "CHOOSE_INTERRUPT_MARK missing player" }
                when (action) {
                    is LegalAction.Skip -> {
                        ongoing.interruptRefundCardId?.let { session.returnBonusCard(ongoing.respondingCoach!!, it) }
                        ongoing.interruptRefundCardId = null
                        ongoing.interruptMarkPlayerId = null
                        ongoing.respondingCoach = null
                        ongoing.stage = OngoingRun.RunStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                    is LegalAction.Mark -> {
                        check(action.playerId == playerId) { "Cut Off Mark for $playerId, was ${action.playerId}" }
                        ongoing.nestedMark = markResolver.beginNested(session, action)
                        ongoing.stage = OngoingRun.RunStage.DELEGATE_INTERRUPT_MARK
                    }
                    else                -> error("CHOOSE_INTERRUPT_MARK expects Mark or Skip, was ${action::class.simpleName}")
                }
            }

            OngoingRun.RunStage.DELEGATE_INTERRUPT_MARK -> { ongoing.nestedMark = markResolver.mutate(session, ongoing.nestedMark!!, action) }

            OngoingRun.RunStage.HANDLE_ABILITY_FOLLOW_UP -> {
                if (action is LegalAction.Skip) {
                    val ability = checkNotNull(ongoing.offeredAbility) { "HANDLE_ABILITY_FOLLOW_UP Skip missing ability" }
                    val playerId = checkNotNull(ongoing.offeredAbilityPlayerId) { "HANDLE_ABILITY_FOLLOW_UP Skip missing player" }
                    ongoing.resolvedFollowUpOffers = ongoing.resolvedFollowUpOffers + (ability to playerId)
                    ongoing.offeredAbility = null
                    ongoing.offeredAbilityPlayerId = null
                    ongoing.stage = OngoingRun.RunStage.OFFER_ABILITY_FOLLOW_UPS
                } else when (val ability = ongoing.offeredAbility) {
                    PlayerAbility.Rally             -> {
                        val standUp = action as? LegalAction.StandUp ?: error("Rally expects StandUp")
                        boardMovement.applyStandUp(session.board, standUp)
                        session.recordTurnAction(standUp.playerId, ActionType.STAND_UP, isFree = true)
                        ongoing.resolvedFollowUpOffers = ongoing.resolvedFollowUpOffers + (ability to standUp.playerId)
                        ongoing.offeredAbility = null
                        ongoing.offeredAbilityPlayerId = null
                        ongoing.stage = OngoingRun.RunStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                    PlayerAbility.`Pass on the Run` -> {
                        val throwAction = action as? LegalAction.Throw ?: error("Pass on the Run expects Throw")
                        ongoing.nestedThrow = throwResolver.beginNested(session, throwAction)
                        ongoing.stage = OngoingRun.RunStage.DELEGATE_ABILITY_FREE_ACTION
                    }
                    else                            -> error("No Run free-action handling for $ability")
                }
            }

            OngoingRun.RunStage.DELEGATE_ABILITY_FREE_ACTION -> { ongoing.nestedThrow = throwResolver.mutate(session, ongoing.nestedThrow!!, action) }

            else -> error("handleLegalAction unexpected ${ongoing.stage}")
        }
    }

    /** Auto-crank until park or DONE. Never takes a [LegalAction]. */
    private fun advance(session: MatchMutator, ongoing: OngoingRunMutator): OngoingRun {
        while (ongoing.stage != OngoingRun.RunStage.DONE && !ongoing.awaitsInput) {
            when (ongoing.stage) {
                OngoingRun.RunStage.OFFER_CUT_OFF -> {
                    val opposition = session.activeCoach.opposite()
                    if (BonusOffers.hasPlayable(session, opposition, BonusPlay.`Cut Off`) && mayIntervene(session.board.board, opposition, ongoing.playerId)) {
                        ongoing.respondingCoach = opposition
                        ongoing.stage = OngoingRun.RunStage.HANDLE_CUT_OFF
                    } else {
                        ongoing.stage = OngoingRun.RunStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                }

                OngoingRun.RunStage.DELEGATE_INTERRUPT_MARK -> {
                    val nested = ongoing.nestedMark!!
                    if (nested.isFinished()) {
                        session.recordTurnAction(nested.ongoingMark.playerId, ActionType.MARK, isFree = true)
                        ongoing.nestedMark = null
                        ongoing.interruptRefundCardId = null
                        ongoing.interruptMarkPlayerId = null
                        ongoing.respondingCoach = null
                        ongoing.stage = OngoingRun.RunStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                }

                OngoingRun.RunStage.DELEGATE_ABILITY_FREE_ACTION -> {
                    val nested = ongoing.nestedThrow!!
                    if (nested.isFinished()) {
                        session.recordTurnAction(nested.ongoingThrow.throwerId, ActionType.THROW, isFree = true)
                        val ability = checkNotNull(ongoing.offeredAbility) { "DELEGATE_ABILITY_FREE_ACTION missing offeredAbility" }
                        val playerId = checkNotNull(ongoing.offeredAbilityPlayerId) { "DELEGATE_ABILITY_FREE_ACTION missing offeredAbilityPlayerId" }
                        ongoing.resolvedFollowUpOffers = ongoing.resolvedFollowUpOffers + (ability to playerId)
                        ongoing.nestedThrow = null
                        ongoing.offeredAbility = null
                        ongoing.offeredAbilityPlayerId = null
                        ongoing.stage = OngoingRun.RunStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                }

                OngoingRun.RunStage.OFFER_ABILITY_FOLLOW_UPS -> {
                    val grant = freeFollowUpAbilityHook.offersAfterResolved(ongoing.playerId, ActionType.RUN, session.toMatchState())
                        .firstOrNull { (it.ability to it.playerId) !in ongoing.resolvedFollowUpOffers }
                    if (grant == null) {
                        ongoing.stage = OngoingRun.RunStage.DONE
                    } else {
                        ongoing.offeredAbility = grant.ability
                        ongoing.offeredAbilityPlayerId = grant.playerId
                        ongoing.stage = OngoingRun.RunStage.HANDLE_ABILITY_FOLLOW_UP
                    }
                }

                else -> error("auto-crank unexpected ${ongoing.stage}")
            }
        }
        return ongoing.ongoingRun
    }

    private fun applyPath(session: MatchMutator, ongoing: OngoingRunMutator, run: LegalAction.Run) {
        require(run.path.steps <= ongoing.squaresRemaining) { "Path of ${run.path.steps} steps exceeds remaining move budget ${ongoing.squaresRemaining}" }
        val endedOnBall = session.board.square(run.path.squares.last()).looseBallId != null
        boardMovement.applyPathMovement(session.board, run)
        ongoing.squaresRemaining -= run.path.steps
        ongoing.stage = if (endedOnBall && ongoing.squaresRemaining > 0) {
            OngoingRun.RunStage.AWAITING_PATH
        } else {
            OngoingRun.RunStage.OFFER_CUT_OFF
        }
    }
}
