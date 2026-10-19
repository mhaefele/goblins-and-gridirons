package com.mhaefele.gridiron.game.action.pending.sidestep

import com.mhaefele.gridiron.game.action.BoardMovement
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.utils.playerMarksSquare
import com.mhaefele.gridiron.game.cards.BonusOffers
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.pending.SidestepContinuation
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.BonusPlay
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver

/**
 * Sidestep resolution.
 * Public surface: [beginSidestep], [beginNested], [mutate].
 */
@Component
class SidestepResolver(
    private val boardMovement: BoardMovement,
) : PendingDecisionResolver<SidestepContinuation>() {
    fun beginSidestep(session: MatchMutator, action: LegalAction.Sidestep): SidestepContinuation {
        val ongoing = applyDeclared(session, action)
        val next = advance(session, ongoing)
        return SidestepContinuation(next)
    }

    /** Nest under a parent FSM — apply path, crank, return continuation (no pending write). */
    fun beginNested(session: MatchMutator, action: LegalAction.Sidestep): SidestepContinuation {
        val ongoing = applyDeclared(session, action)
        return SidestepContinuation(advance(session, ongoing))
    }

    private fun applyDeclared(session: MatchMutator, action: LegalAction.Sidestep): OngoingSidestepMutator {
        boardMovement.applyPathMovement(session.board, action)
        val vacated = action.path.squares.first()
        val sidestepper = session.board.getPlayer(action.playerId)
        val eligible = session.board.board.players.values.filter { marker ->
            marker.team != sidestepper.team && marker.status == PlayerStatus.STANDING && playerMarksSquare(marker, vacated, session.board.board)
        }.map { it.id }
        return OngoingSidestepMutator(
            action.playerId,
            OngoingSidestep.SidestepStage.OFFER_STICK_CLOSE,
            vacatedSquare = vacated,
            eligibleMarkerIds = eligible,
        )
    }

    override fun mutate(session: MatchMutator, pending: SidestepContinuation, command: LegalAction): SidestepContinuation {
        val ongoing = OngoingSidestepMutator(pending.ongoingSidestep)
        handleLegalAction(session, ongoing, command)
        return SidestepContinuation(advance(session, ongoing))
    }

    /** Consume one POST at an awaiting-input stage. Does not crank. */
    private fun handleLegalAction(session: MatchMutator, ongoing: OngoingSidestepMutator, action: LegalAction) {
        check(ongoing.stage.awaitsInput) { "handleLegalAction expects awaitsInput, was ${ongoing.stage}" }
        when (ongoing.stage) {
            OngoingSidestep.SidestepStage.HANDLE_STICK_CLOSE -> {
                val opposition = checkNotNull(ongoing.respondingCoach) { "HANDLE_STICK_CLOSE missing respondingCoach" }
                when (action) {
                    is LegalAction.DeclineBonus  -> Unit
                    is LegalAction.PlayBonusCard -> {
                        check(action.bonusPlay == BonusPlay.`Stick Close`) { "HANDLE_STICK_CLOSE expects STICK_CLOSE, was ${action.bonusPlay}" }
                        session.playBonusCard(opposition, action.bonusPlay)
                        val landing = session.board.square(ongoing.vacatedSquare)
                        if (landing.playerId == null && !landing.isBlocked()) {
                            val marker = ongoing.eligibleMarkerIds.asSequence()
                                .mapNotNull { session.board.board.players[it] }
                                .firstOrNull { it.status == PlayerStatus.STANDING }
                            if (marker != null) {
                                val from = session.board.playerCoordinate(marker.id)
                                session.board.playerMoved(marker.id, from, ongoing.vacatedSquare, bonusPlay = BonusPlay.`Stick Close`)
                            }
                        }
                    }
                    else                         -> error("HANDLE_STICK_CLOSE expects PlayBonusCard or DeclineBonus, was ${action::class.simpleName}")
                }
                ongoing.respondingCoach = null
                ongoing.stage = OngoingSidestep.SidestepStage.DONE
            }
            else                                             -> error("handleLegalAction unexpected ${ongoing.stage}")
        }
    }

    /** Auto-crank until park or DONE. Never takes a [LegalAction]. */
    private fun advance(session: MatchMutator, ongoing: OngoingSidestepMutator): OngoingSidestep {
        while (ongoing.stage != OngoingSidestep.SidestepStage.DONE && !ongoing.stage.awaitsInput) {
            when (ongoing.stage) {
                OngoingSidestep.SidestepStage.OFFER_STICK_CLOSE -> {
                    // Marker coach — opposite the sidestepper (not activeCoach: Slip Away Sidesteps on the opponent's turn).
                    val opposition = session.board.getPlayer(ongoing.playerId).team.opposite()
                    if (BonusOffers.hasPlayable(session, opposition, BonusPlay.`Stick Close`) && ongoing.eligibleMarkerIds.isNotEmpty()) {
                        ongoing.respondingCoach = opposition
                        ongoing.stage = OngoingSidestep.SidestepStage.HANDLE_STICK_CLOSE
                    } else {
                        ongoing.stage = OngoingSidestep.SidestepStage.DONE
                    }
                }
                else                                            -> error("auto-crank unexpected ${ongoing.stage}")
            }
        }
        return ongoing.ongoingSidestep
    }
}
