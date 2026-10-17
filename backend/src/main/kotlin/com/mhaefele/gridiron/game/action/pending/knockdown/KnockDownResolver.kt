package com.mhaefele.gridiron.game.action.pending.knockdown

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.BoardMovement
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver
import com.mhaefele.gridiron.game.action.pending.armourcheck.ArmourCheckResolver
import com.mhaefele.gridiron.game.action.pending.ballguard.BallGuardResolver
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.pending.KnockDownContinuation
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Standing knock-down: Prone → optional Ball Guard → optional nested Armour Check.
 * Public surface: [beginStanding], [mutate].
 *
 * Nest under Block / Throw Team-mate — NestedOnly; never [MatchMutator.setPendingDecision] as a root.
 * Already-prone Armour Checks nest [ArmourCheckResolver] directly on the parent.
 */
@Component
class KnockDownResolver(
    private val boardMovement: BoardMovement,
    private val ballGuardResolver: BallGuardResolver,
    private val armourCheckResolver: ArmourCheckResolver,
) : PendingDecisionResolver<KnockDownContinuation>() {
    /**
     * Knock down a standing player, then run configured aftercare
     * ([withArmour] / [offerBladed] / Ball Guard).
     */
    fun beginStanding(session: MatchMutator, playerId: PlayerId, withArmour: Boolean, armourMinusOne: Boolean = false, offerBladed: Boolean = false): KnockDownContinuation {
        val player = session.board.getPlayer(playerId)
        check(player.status == PlayerStatus.STANDING) { "beginStanding expects STANDING, was ${player.status}" }
        val at = session.board.playerCoordinate(playerId)
        val ongoing = OngoingKnockDownMutator(playerId, at, OngoingKnockDown.KnockDownStage.APPLY_KNOCK_DOWN, withArmour, armourMinusOne, offerBladed)
        return KnockDownContinuation(advance(session, ongoing))
    }

    override fun mutate(session: MatchMutator, pending: KnockDownContinuation, command: LegalAction): KnockDownContinuation {
        val ongoing = OngoingKnockDownMutator(pending.ongoingKnockDown)
        handleLegalAction(session, ongoing, command)
        return KnockDownContinuation(advance(session, ongoing))
    }

    private fun handleLegalAction(session: MatchMutator, ongoing: OngoingKnockDownMutator, action: LegalAction) {
        check(ongoing.awaitsInput) { "handleLegalAction expects awaitsInput, was ${ongoing.stage}" }
        when (ongoing.stage) {
            OngoingKnockDown.KnockDownStage.DELEGATE_BALL_GUARD   -> { ongoing.nestedBallGuard = ballGuardResolver.mutate(session, ongoing.nestedBallGuard!!, action) }
            OngoingKnockDown.KnockDownStage.DELEGATE_ARMOUR_CHECK -> { ongoing.nestedArmourCheck = armourCheckResolver.mutate(session, ongoing.nestedArmourCheck!!, action) }
            else                                                  -> error("handleLegalAction unexpected ${ongoing.stage}")
        }
    }

    private fun advance(session: MatchMutator, ongoing: OngoingKnockDownMutator): OngoingKnockDown {
        while (ongoing.stage != OngoingKnockDown.KnockDownStage.DONE && !ongoing.awaitsInput) {
            when (ongoing.stage) {
                OngoingKnockDown.KnockDownStage.APPLY_KNOCK_DOWN      -> {
                    if (boardMovement.applyKnockDownDeferringBallGuard(session, session.board, ongoing.playerId)) {
                        val respondingCoach = ballGuardRespondingCoach(session, ongoing.playerId)
                        ongoing.nestedBallGuard = ballGuardResolver.beginNested(ongoing.playerId, ongoing.at, respondingCoach)
                        ongoing.stage = OngoingKnockDown.KnockDownStage.DELEGATE_BALL_GUARD
                    } else if (ongoing.withArmour) {
                        ongoing.nestedArmourCheck = armourCheckResolver.begin(
                            session, ongoing.playerId, armourMinusOne = ongoing.armourMinusOne, offerBladed = ongoing.offerBladed,
                        )
                        ongoing.stage = OngoingKnockDown.KnockDownStage.DELEGATE_ARMOUR_CHECK
                    } else {
                        ongoing.stage = OngoingKnockDown.KnockDownStage.DONE
                    }
                }
                OngoingKnockDown.KnockDownStage.DELEGATE_BALL_GUARD   -> {
                    val nested = checkNotNull(ongoing.nestedBallGuard) { "DELEGATE_BALL_GUARD missing nestedBallGuard" }
                    if (nested.isFinished()) {
                        ongoing.nestedBallGuard = null
                        if (ongoing.withArmour) {
                            ongoing.nestedArmourCheck = armourCheckResolver.begin(
                                session, ongoing.playerId, armourMinusOne = ongoing.armourMinusOne, offerBladed = ongoing.offerBladed,
                            )
                            ongoing.stage = OngoingKnockDown.KnockDownStage.DELEGATE_ARMOUR_CHECK
                        } else {
                            ongoing.stage = OngoingKnockDown.KnockDownStage.DONE
                        }
                    }
                }
                OngoingKnockDown.KnockDownStage.DELEGATE_ARMOUR_CHECK -> {
                    val nested = checkNotNull(ongoing.nestedArmourCheck) { "DELEGATE_ARMOUR_CHECK missing nestedArmourCheck" }
                    if (nested.isFinished()) {
                        ongoing.nestedArmourCheck = null
                        ongoing.stage = OngoingKnockDown.KnockDownStage.DONE
                    }
                }
                else                                                  -> error("KnockDown auto-crank unexpected ${ongoing.stage}")
            }
        }
        return ongoing.ongoingKnockDown
    }

    private fun ballGuardRespondingCoach(session: MatchMutator, playerId: PlayerId): TeamSide? {
        val playerTeam = session.board.getPlayer(playerId).team
        return if (playerTeam != session.activeCoach) playerTeam else null
    }
}
