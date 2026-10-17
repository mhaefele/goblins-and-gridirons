package com.mhaefele.gridiron.game.action.pending.mark

import com.mhaefele.gridiron.game.action.BoardMovement
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.ability.FreeFollowUpAbilityHook
import com.mhaefele.gridiron.game.action.utils.playerMarksOpponent
import com.mhaefele.gridiron.game.cards.BonusOffers
import com.mhaefele.gridiron.game.legalmoves.hasMarkableBlockTarget
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.MarkContinuation
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver
import com.mhaefele.gridiron.game.action.pending.block.BlockResolver
import com.mhaefele.gridiron.game.action.pending.sidestep.SidestepResolver

/**
 * Mark resolution — one [MarkContinuation] for the whole turn-slot beat.
 *
 * Flow: path → Slip Away (opp bonus) → player-ability follow-ups → Mark bonus cards
 * (Momentum / All-In Tackle) → DONE. Non-Mark work nests as *Continuation on [OngoingMark].
 * Public surface: [beginMark], [beginNested], [mutate].
 */
@Component
class MarkResolver(
    private val boardMovement: BoardMovement,
    private val blockResolver: BlockResolver,
    private val sidestepResolver: SidestepResolver,
    private val freeFollowUpAbilityHook: FreeFollowUpAbilityHook,
) : PendingDecisionResolver<MarkContinuation>() {
    fun beginMark(session: MatchMutator, action: LegalAction.Mark): MarkContinuation {
        val ongoing = applyDeclared(session, action)
        val next = advance(session, ongoing)
        // Park even at DONE — TurnEngine COMPLETE pops the finished continuation and closes the open action.
        return MarkContinuation(next)
    }

    /** Nest under a parent FSM — apply path, crank, return continuation (no pending write). */
    fun beginNested(session: MatchMutator, action: LegalAction.Mark): MarkContinuation {
        val ongoing = applyDeclared(session, action)
        return MarkContinuation(advance(session, ongoing))
    }

    private fun applyDeclared(session: MatchMutator, action: LegalAction.Mark): OngoingMarkMutator {
        boardMovement.applyPathMovement(session.board, action)
        return OngoingMarkMutator(action.playerId, OngoingMark.MarkStage.OFFER_SLIP_AWAY)
    }

    /** Resume an existing [MarkContinuation] (pending front already that continuation). */
    override fun mutate(session: MatchMutator, pending: MarkContinuation, command: LegalAction): MarkContinuation {
        val ongoing = OngoingMarkMutator(pending.ongoingMark)
        handleLegalAction(session, ongoing, command)
        return MarkContinuation(advance(session, ongoing))
    }

    /** Consume one POST at an awaiting-input stage. Does not crank. */
    private fun handleLegalAction(session: MatchMutator, ongoing: OngoingMarkMutator, action: LegalAction) {
        check(ongoing.awaitsInput) { "handleLegalAction expects awaitsInput, was ${ongoing.stage}" }
        when (ongoing.stage) {
            OngoingMark.MarkStage.HANDLE_SLIP_AWAY -> {
                val opposition = checkNotNull(ongoing.respondingCoach) { "HANDLE_SLIP_AWAY missing respondingCoach" }
                when (action) {
                    is LegalAction.DeclineBonus  -> {
                        ongoing.respondingCoach = null
                        ongoing.stage = OngoingMark.MarkStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                    is LegalAction.PlayBonusCard -> {
                        check(action.bonusPlay == BonusPlay.`Slip Away`) { "HANDLE_SLIP_AWAY expects SLIP_AWAY, was ${action.bonusPlay}" }
                        val cardId = session.playBonusCard(opposition, action.bonusPlay)
                        val marker = session.board.getPlayer(ongoing.playerId)
                        val marked = markedAllyForSlipAway(session, marker, opposition) ?: error("Slip Away Play with no ally Marked by ${ongoing.playerId}")
                        // Hand off free Sidestep — not a MarkContinuation stage; nest under DELEGATE_INTERRUPT_SIDESTEP.
                        ongoing.interruptRefundCardId = cardId
                        ongoing.interruptSidestepPlayerId = marked.id
                        ongoing.respondingCoach = opposition
                        ongoing.stage = OngoingMark.MarkStage.CHOOSE_INTERRUPT_SIDESTEP
                    }
                    else                         -> error("HANDLE_SLIP_AWAY expects PlayBonusCard or DeclineBonus, was ${action::class.simpleName}")
                }
            }

            OngoingMark.MarkStage.CHOOSE_INTERRUPT_SIDESTEP -> {
                val playerId = checkNotNull(ongoing.interruptSidestepPlayerId) { "CHOOSE_INTERRUPT_SIDESTEP missing player" }
                when (action) {
                    is LegalAction.Skip     -> {
                        ongoing.interruptRefundCardId?.let { session.returnBonusCard(ongoing.respondingCoach!!, it) }
                        ongoing.interruptRefundCardId = null
                        ongoing.interruptSidestepPlayerId = null
                        ongoing.respondingCoach = null
                        ongoing.stage = OngoingMark.MarkStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                    is LegalAction.Sidestep -> {
                        check(action.playerId == playerId) { "Slip Away Sidestep for $playerId, was ${action.playerId}" }
                        ongoing.nestedSidestep = sidestepResolver.beginNested(session, action)
                        ongoing.stage = OngoingMark.MarkStage.DELEGATE_INTERRUPT_SIDESTEP
                    }
                    else                    -> error("CHOOSE_INTERRUPT_SIDESTEP expects Sidestep or Skip, was ${action::class.simpleName}")
                }
            }

            OngoingMark.MarkStage.DELEGATE_INTERRUPT_SIDESTEP -> { ongoing.nestedSidestep = sidestepResolver.mutate(session, ongoing.nestedSidestep!!, action) }

            OngoingMark.MarkStage.HANDLE_ABILITY_FOLLOW_UP -> {
                val ability = checkNotNull(ongoing.offeredAbility) { "HANDLE_ABILITY_FOLLOW_UP missing offeredAbility" }
                when {
                    action is LegalAction.Skip                                                             -> {
                        val playerId = checkNotNull(ongoing.offeredAbilityPlayerId) { "HANDLE_ABILITY_FOLLOW_UP Skip missing player" }
                        ongoing.resolvedFollowUpOffers = ongoing.resolvedFollowUpOffers + (ability to playerId)
                        ongoing.offeredAbility = null
                        ongoing.offeredAbilityPlayerId = null
                        ongoing.stage = OngoingMark.MarkStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                    ability == PlayerAbility.`Relentless After Mark` || ability == PlayerAbility.Charge -> {
                        val block = action as? LegalAction.Block ?: error("$ability expects Block, was ${action::class.simpleName}")
                        ongoing.nestedBlock = blockResolver.beginNested(session, block, allInTackleProneAfterBlock = false)
                        ongoing.stage = OngoingMark.MarkStage.DELEGATE_ABILITY_FREE_ACTION
                    }
                    ability == PlayerAbility.Rally                                                  -> {
                        val standUp = action as? LegalAction.StandUp ?: error("Rally expects StandUp, was ${action::class.simpleName}")
                        boardMovement.applyStandUp(session.board, standUp)
                        session.recordTurnAction(standUp.playerId, ActionType.STAND_UP, isFree = true)
                        ongoing.resolvedFollowUpOffers = ongoing.resolvedFollowUpOffers + (ability to standUp.playerId)
                        ongoing.offeredAbility = null
                        ongoing.offeredAbilityPlayerId = null
                        ongoing.stage = OngoingMark.MarkStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                    else                                                                                   -> error("No Mark free-action handling for $ability")
                }
            }

            OngoingMark.MarkStage.DELEGATE_ABILITY_FREE_ACTION -> { ongoing.nestedBlock = blockResolver.mutate(session, ongoing.nestedBlock!!, action) }

            OngoingMark.MarkStage.HANDLE_MARK_BONUS_CARDS -> {
                val coach = checkNotNull(ongoing.respondingCoach) { "HANDLE_MARK_BONUS_CARDS missing respondingCoach" }
                when (action) {
                    is LegalAction.DeclineBonus                               -> {
                        ongoing.respondingCoach = null
                        ongoing.stage = OngoingMark.MarkStage.DONE
                    }
                    is LegalAction.PlayMomentum, is LegalAction.PlayAllInTackle -> {
                        check(action.bonusPlay == BonusPlay.Momentum || action.bonusPlay == BonusPlay.`All-In Tackle`) { "HANDLE_MARK_BONUS_CARDS expects MOMENTUM or ALL_IN_TACKLE, was ${action.bonusPlay}" }
                        session.playBonusCard(coach, action.bonusPlay)
                        // Close Mark-bonus window and spawn free Block; re-offer via OFFER_MARK_BONUS_CARDS after the grant.
                        ongoing.markBonusCard = action.bonusPlay
                        ongoing.respondingCoach = coach
                        ongoing.stage = OngoingMark.MarkStage.CHOOSE_MARK_BONUS_FREE_BLOCK
                    }
                    else                                                      -> error("HANDLE_MARK_BONUS_CARDS expects PlayMomentum, PlayAllInTackle, or DeclineBonus, was ${action::class.simpleName}")
                }
            }

            OngoingMark.MarkStage.CHOOSE_MARK_BONUS_FREE_BLOCK -> {
                when (action) {
                    is LegalAction.Skip  -> {
                        ongoing.markBonusCard = null
                        ongoing.respondingCoach = null
                        ongoing.stage = OngoingMark.MarkStage.OFFER_MARK_BONUS_CARDS
                    }
                    is LegalAction.Block -> {
                        val diving = ongoing.markBonusCard == BonusPlay.`All-In Tackle`
                        ongoing.nestedBlock = blockResolver.beginNested(session, action, allInTackleProneAfterBlock = diving)
                        ongoing.stage = OngoingMark.MarkStage.DELEGATE_MARK_BONUS_FREE_BLOCK
                    }
                    else                 -> error("CHOOSE_MARK_BONUS_FREE_BLOCK expects Block or Skip, was ${action::class.simpleName}")
                }
            }

            OngoingMark.MarkStage.DELEGATE_MARK_BONUS_FREE_BLOCK -> { ongoing.nestedBlock = blockResolver.mutate(session, ongoing.nestedBlock!!, action) }

            else -> error("handleLegalAction unexpected ${ongoing.stage}")
        }
    }

    /** Auto-crank until park or DONE. Never takes a [LegalAction]. */
    private fun advance(session: MatchMutator, ongoing: OngoingMarkMutator): OngoingMark {
        while (ongoing.stage != OngoingMark.MarkStage.DONE && !ongoing.awaitsInput) {
            when (ongoing.stage) {
                OngoingMark.MarkStage.OFFER_SLIP_AWAY -> {
                    val opposition = session.activeCoach.opposite()
                    val marker = session.board.getPlayer(ongoing.playerId)
                    // Slip Away needs a standing opposition ally currently Marked by this Marker.
                    if (BonusOffers.hasPlayable(session, opposition, BonusPlay.`Slip Away`) && markedAllyForSlipAway(session, marker, opposition) != null) {
                        ongoing.respondingCoach = opposition
                        ongoing.stage = OngoingMark.MarkStage.HANDLE_SLIP_AWAY
                    } else {
                        ongoing.stage = OngoingMark.MarkStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                }

                OngoingMark.MarkStage.DELEGATE_INTERRUPT_SIDESTEP -> {
                    val nested = ongoing.nestedSidestep!!
                    if (nested.isFinished()) {
                        session.recordTurnAction(nested.ongoingSidestep.playerId, ActionType.SIDESTEP, isFree = true)
                        ongoing.nestedSidestep = null
                        ongoing.interruptRefundCardId = null
                        ongoing.interruptSidestepPlayerId = null
                        ongoing.respondingCoach = null
                        ongoing.stage = OngoingMark.MarkStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                }

                OngoingMark.MarkStage.OFFER_ABILITY_FOLLOW_UPS -> {
                    val grant = freeFollowUpAbilityHook.offersAfterResolved(ongoing.playerId, ActionType.MARK, session.toMatchState())
                        .firstOrNull { (it.ability to it.playerId) !in ongoing.resolvedFollowUpOffers }
                    if (grant == null) {
                        ongoing.offeredAbility = null
                        ongoing.offeredAbilityPlayerId = null
                        ongoing.stage = OngoingMark.MarkStage.OFFER_MARK_BONUS_CARDS
                    } else {
                        ongoing.offeredAbility = grant.ability
                        ongoing.offeredAbilityPlayerId = grant.playerId
                        ongoing.stage = OngoingMark.MarkStage.HANDLE_ABILITY_FOLLOW_UP
                    }
                }

                OngoingMark.MarkStage.DELEGATE_ABILITY_FREE_ACTION -> {
                    val nested = ongoing.nestedBlock!!
                    if (nested.isFinished()) {
                        session.recordTurnAction(nested.ongoingBlock.blockerId, ActionType.BLOCK, isFree = true)
                        val ability = checkNotNull(ongoing.offeredAbility) { "DELEGATE_ABILITY_FREE_ACTION missing offeredAbility" }
                        val playerId = checkNotNull(ongoing.offeredAbilityPlayerId) { "DELEGATE_ABILITY_FREE_ACTION missing offeredAbilityPlayerId" }
                        ongoing.resolvedFollowUpOffers = ongoing.resolvedFollowUpOffers + (ability to playerId)
                        ongoing.nestedBlock = null
                        ongoing.offeredAbility = null
                        ongoing.offeredAbilityPlayerId = null
                        ongoing.stage = OngoingMark.MarkStage.OFFER_ABILITY_FOLLOW_UPS
                    }
                }

                OngoingMark.MarkStage.OFFER_MARK_BONUS_CARDS -> {
                    if (markBonusMomentumEligible(session, ongoing.playerId) || markBonusAllInTackleEligible(session, ongoing.playerId)) {
                        ongoing.respondingCoach = session.activeCoach
                        ongoing.stage = OngoingMark.MarkStage.HANDLE_MARK_BONUS_CARDS
                    } else {
                        ongoing.stage = OngoingMark.MarkStage.DONE
                    }
                }

                OngoingMark.MarkStage.DELEGATE_MARK_BONUS_FREE_BLOCK -> {
                    val nested = ongoing.nestedBlock!!
                    if (nested.isFinished()) {
                        session.recordTurnAction(nested.ongoingBlock.blockerId, ActionType.BLOCK, isFree = true)
                        ongoing.nestedBlock = null
                        ongoing.markBonusCard = null
                        ongoing.respondingCoach = null
                        ongoing.stage = OngoingMark.MarkStage.OFFER_MARK_BONUS_CARDS
                    }
                }

                else -> error("auto-crank unexpected stage ${ongoing.stage}")
            }
        }
        return ongoing.ongoingMark
    }

    /** Standing opposition ally Marked by [marker] — Slip Away's free Sidestep target. */
    private fun markedAllyForSlipAway(session: MatchMutator, marker: Player, opposition: TeamSide): Player? =
        session.board.board.players.values.firstOrNull { ally ->
            ally.team == opposition && ally.status == PlayerStatus.STANDING && playerMarksOpponent(marker, ally, session.board.board)
        }

    companion object {
        private fun markBonusMomentumEligible(session: MatchMutator, markerId: PlayerId): Boolean {
            if (!BonusOffers.hasPlayable(session, session.activeCoach, BonusPlay.Momentum)) return false
            val ran = session.actionsThisTurn.any { it.playerId == markerId && it.actionType == ActionType.RUN }
            if (!ran) return false
            val marker = session.board.getPlayer(markerId)
            return marker.status == PlayerStatus.STANDING && hasMarkableBlockTarget(marker, session.board.board)
        }

        private fun markBonusAllInTackleEligible(session: MatchMutator, markerId: PlayerId): Boolean {
            if (!BonusOffers.hasPlayable(session, session.activeCoach, BonusPlay.`All-In Tackle`)) return false
            val marker = session.board.getPlayer(markerId)
            return hasMarkableBlockTarget(marker, session.board.board)
        }
    }
}
