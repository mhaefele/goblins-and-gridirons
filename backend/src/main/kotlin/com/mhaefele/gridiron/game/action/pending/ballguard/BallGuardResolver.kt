package com.mhaefele.gridiron.game.action.pending.ballguard

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.Bounce
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.BallGuardContinuation
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver

/**
 * Ball Guard ball placement after a knock-down drop.
 * Public surface: [beginNested], [mutate].
 *
 * Nest under KnockDown (under Block / Throw Team-mate) — NestedOnly; never [MatchMutator.setPendingDecision] as a root.
 */
@Component
class BallGuardResolver(
    private val bounce: Bounce,
) : PendingDecisionResolver<BallGuardContinuation>() {
    fun beginNested(playerId: PlayerId, dropAt: Coordinate, respondingCoach: TeamSide? = null): BallGuardContinuation =
        BallGuardContinuation(
            OngoingBallGuard(playerId, dropAt, OngoingBallGuard.BallGuardStage.HANDLE_PLACE),
            localRespondingCoach = respondingCoach,
        )

    override fun mutate(session: MatchMutator, pending: BallGuardContinuation, command: LegalAction): BallGuardContinuation {
        val ongoing = OngoingBallGuardMutator(pending.ongoingBallGuard)
        handleLegalAction(session, ongoing, command)
        return BallGuardContinuation(advance(session, ongoing), localRespondingCoach = pending.respondingCoach)
    }

    private fun handleLegalAction(session: MatchMutator, ongoing: OngoingBallGuardMutator, action: LegalAction) {
        check(ongoing.stage.awaitsInput) { "handleLegalAction expects awaitsInput, was ${ongoing.stage}" }
        when (ongoing.stage) {
            OngoingBallGuard.BallGuardStage.HANDLE_PLACE -> {
                check(action is LegalAction.PlaceLooseBall) { "HANDLE_PLACE expects PlaceLooseBall, was ${action::class.simpleName}" }
                check(action.from == ongoing.dropAt) { "Ball Guard place from ${action.from} does not match drop ${ongoing.dropAt}" }
                check(action.playerId == ongoing.playerId) { "Ball Guard place player ${action.playerId} is not ${ongoing.playerId}" }
                bounce.placeLooseBall(session.board, action.from, action.to)
                ongoing.stage = OngoingBallGuard.BallGuardStage.DONE
            }
            else                                         -> error("handleLegalAction unexpected ${ongoing.stage}")
        }
    }

    private fun advance(@Suppress("UNUSED_PARAMETER") session: MatchMutator, ongoing: OngoingBallGuardMutator): OngoingBallGuard {
        check(ongoing.stage == OngoingBallGuard.BallGuardStage.DONE || ongoing.stage.awaitsInput) { "Ball Guard auto-crank unexpected ${ongoing.stage}" }
        return ongoing.ongoingBallGuard
    }
}
