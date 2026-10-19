package com.mhaefele.gridiron.game.action.pending.armourcheck

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.armourDiceRolled
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver
import com.mhaefele.gridiron.game.board.dto.BoardMutator
import com.mhaefele.gridiron.game.cards.BonusOffers
import com.mhaefele.gridiron.game.cards.DoOverRoll
import com.mhaefele.gridiron.game.dice.ArmourCheckOutcome
import com.mhaefele.gridiron.game.dice.DiceRoller
import com.mhaefele.gridiron.game.dice.armourCheck
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.catalog.ArmourStat
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.pending.ArmourCheckContinuation
import com.mhaefele.gridiron.shared.PlayerId
import org.springframework.stereotype.Component

/**
 * Armour Check: optional Bladed → roll → optional Do-Over → injure on fail.
 * Public surface: [begin], [mutate].
 *
 * Nest under KnockDown / Block / Throw Team-mate — NestedOnly; never [MatchMutator.setPendingDecision] as a root.
 */
@Component
class ArmourCheckResolver(
    private val dice: DiceRoller,
) : PendingDecisionResolver<ArmourCheckContinuation>() {
    fun begin(session: MatchMutator, playerId: PlayerId, armourMinusOne: Boolean = false, offerBladed: Boolean = false): ArmourCheckContinuation {
        val stage = if (offerBladed) OngoingArmourCheck.ArmourCheckStage.OFFER_BLADED else OngoingArmourCheck.ArmourCheckStage.ROLL_ARMOUR
        val ongoing = OngoingArmourCheckMutator(playerId, stage, armourMinusOne, offerBladed)
        return ArmourCheckContinuation(advance(session, ongoing))
    }

    override fun mutate(session: MatchMutator, pending: ArmourCheckContinuation, command: LegalAction): ArmourCheckContinuation {
        val ongoing = OngoingArmourCheckMutator(pending.ongoingArmourCheck)
        handleLegalAction(session, ongoing, command)
        return ArmourCheckContinuation(advance(session, ongoing))
    }

    private fun handleLegalAction(session: MatchMutator, ongoing: OngoingArmourCheckMutator, action: LegalAction) {
        check(ongoing.awaitsInput) { "handleLegalAction expects awaitsInput, was ${ongoing.stage}" }
        when (ongoing.stage) {
            OngoingArmourCheck.ArmourCheckStage.HANDLE_BLADED         -> {
                when (action) {
                    is LegalAction.DeclineBonus        -> Unit
                    is LegalAction.PlaySpikedGauntlets -> {
                        session.playBonusCard(session.activeCoach, action.bonusPlay)
                        ongoing.spikedGauntlets = true
                    }
                    else                               -> error("HANDLE_BLADED expects PlaySpikedGauntlets or DeclineBonus, was ${action::class.simpleName}")
                }
                ongoing.stage = OngoingArmourCheck.ArmourCheckStage.ROLL_ARMOUR
            }
            OngoingArmourCheck.ArmourCheckStage.HANDLE_ARMOUR_DO_OVER -> {
                val armour = effectiveArmour(session, ongoing)
                when (action) {
                    is LegalAction.DeclineBonus         -> { applyArmourOutcome(session.board, ongoing.playerId, armour, ongoing.armourMinusOne, ongoing.naturalRoll!!) }
                    is LegalAction.AdjustArmourByDoOver -> {
                        session.playBonusCard(session.activeCoach, BonusPlay.`Do-Over`)
                        val armourStat = armour as? ArmourStat.Threshold ?: error("Do-Over armour re-roll expects Threshold armour, was $armour")
                        val roll = dice.d6()
                        session.board.record(armourDiceRolled(roll, armourStat, ongoing.armourMinusOne, bonusPlay = BonusPlay.`Do-Over`))
                        applyArmourOutcome(session.board, ongoing.playerId, armourStat, ongoing.armourMinusOne, roll)
                    }
                    else                                -> error("HANDLE_ARMOUR_DO_OVER expects AdjustArmourByDoOver or DeclineBonus, was ${action::class.simpleName}")
                }
                ongoing.stage = OngoingArmourCheck.ArmourCheckStage.DONE
            }
            else                                                      -> error("handleLegalAction unexpected ${ongoing.stage}")
        }
    }

    private fun advance(session: MatchMutator, ongoing: OngoingArmourCheckMutator): OngoingArmourCheck {
        while (ongoing.stage != OngoingArmourCheck.ArmourCheckStage.DONE && !ongoing.awaitsInput) {
            when (ongoing.stage) {
                OngoingArmourCheck.ArmourCheckStage.OFFER_BLADED -> {
                    if (ongoing.offerBladed && BonusOffers.shouldOfferSpikedGauntlets(session)) {
                        ongoing.stage = OngoingArmourCheck.ArmourCheckStage.HANDLE_BLADED
                    } else {
                        ongoing.stage = OngoingArmourCheck.ArmourCheckStage.ROLL_ARMOUR
                    }
                }
                OngoingArmourCheck.ArmourCheckStage.ROLL_ARMOUR  -> {
                    val board = session.board
                    val armour = effectiveArmour(session, ongoing)
                    when (armour) {
                        is ArmourStat.NoArmour  -> {
                            board.playerInjured(ongoing.playerId)
                            ongoing.stage = OngoingArmourCheck.ArmourCheckStage.DONE
                        }
                        is ArmourStat.Threshold -> {
                            val roll = dice.d6()
                            board.record(armourDiceRolled(roll, armour, ongoing.armourMinusOne))
                            val mayRt = BonusOffers.hasPlayable(session, session.activeCoach, BonusPlay.`Do-Over`) && DoOverRoll.mayImproveArmour(roll, armour, ongoing.armourMinusOne)
                            if (mayRt) {
                                ongoing.naturalRoll = roll
                                ongoing.stage = OngoingArmourCheck.ArmourCheckStage.HANDLE_ARMOUR_DO_OVER
                            } else {
                                applyArmourOutcome(board, ongoing.playerId, armour, ongoing.armourMinusOne, roll)
                                ongoing.stage = OngoingArmourCheck.ArmourCheckStage.DONE
                            }
                        }
                    }
                }
                else                                             -> error("ArmourCheck auto-crank unexpected ${ongoing.stage}")
            }
        }
        return ongoing.ongoingArmourCheck
    }

    private fun effectiveArmour(session: MatchMutator, ongoing: OngoingArmourCheckMutator): ArmourStat {
        val armour = session.board.getPlayer(ongoing.playerId).profile.stats.armour
        return if (ongoing.spikedGauntlets) ArmourStat.Threshold(6) else armour
    }

    private fun applyArmourOutcome(
        board: BoardMutator,
        playerId: PlayerId,
        armour: ArmourStat,
        armourMinusOne: Boolean,
        naturalRoll: Int,
    ) {
        when (armour) {
            is ArmourStat.NoArmour  -> board.playerInjured(playerId)
            is ArmourStat.Threshold -> when (armourCheck(naturalRoll, armour, armourMinusOne)) {
                ArmourCheckOutcome.Success                              -> Unit
                ArmourCheckOutcome.NoArmour, is ArmourCheckOutcome.Fail -> board.playerInjured(playerId)
            }
        }
    }
}
