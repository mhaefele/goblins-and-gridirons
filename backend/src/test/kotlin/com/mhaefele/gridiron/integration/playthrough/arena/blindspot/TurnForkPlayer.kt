package com.mhaefele.gridiron.integration.playthrough.arena.blindspot

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.dice.DiceRoller
import com.mhaefele.gridiron.game.dice.DiceRollerContext
import com.mhaefele.gridiron.game.legalmoves.LegalMovesGenerator
import com.mhaefele.gridiron.game.opponent.OpponentStrategy
import com.mhaefele.gridiron.game.opponent.lookahead.LookaheadSearch
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

/**
 * Plays forked [MatchState] copies under a private dice overlay until a turn-number boundary.
 * Does not touch MatchGame / repositories.
 */
@Component
class TurnForkPlayer(
    private val search: LookaheadSearch,
    private val legalMovesGenerator: LegalMovesGenerator,
    private val diceRollerContext: DiceRollerContext,
) {
    /** One applied action with focal-perspective score deltas (0 when the board score was unchanged). */
    data class ScoredStep(
        val action: LegalAction,
        val deltaUs: Int,
        val deltaThem: Int,
        /** Scoring (`+4us Touchdown`) and beat notes (`pickup h4`) for [annotated]. */
        val notes: List<String>,
    ) {
        fun annotated(): String {
            if (notes.isEmpty()) return action.brief()
            // Claim already named in notes — avoid Claim("X")[+Nus "X"].
            val base = when (action) {
                is LegalAction.ClaimChallenge -> "Claim"
                else                          -> action.brief()
            }
            return "$base[${notes.joinToString("; ")}]"
        }
    }

    data class PathResult(
        /** State after the focal coach's turn closes (or game over during that turn). */
        val afterOwnTurn: MatchState,
        /** State after the opponent's following turn (or game over). */
        val afterOppTurn: MatchState,
        val ownTurnSteps: List<ScoredStep>,
        val throughOppSteps: List<ScoredStep>,
    ) {
        fun scoringSummaryThroughOpp(): String {
            val notes = (ownTurnSteps + throughOppSteps).flatMap { it.notes }.filter { it.isScoringNote() }
            return if (notes.isEmpty()) "(none)" else notes.joinToString(", ")
        }
    }

    /**
     * Apply [firstAction], finish the focal coach's turn with [ownStrategy], then play the
     * opponent's following turn with [oppStrategy]. Interrupt seats use the matching strategy.
     */
    fun playSplit(
        snapshot: MatchState,
        firstAction: LegalAction,
        focal: TeamSide,
        ownStrategy: OpponentStrategy,
        oppStrategy: OpponentStrategy,
        dice: DiceRoller,
        maxSteps: Int = 120,
    ): PathResult {
        val startTurn = snapshot.progress.turnNumber
        return diceRollerContext.withRoller(dice) {
            val ownSteps = ArrayList<ScoredStep>(16)
            val oppSteps = ArrayList<ScoredStep>(16)
            var state = snapshot
            val first = applyScored(state, firstAction, focal)
            ownSteps.add(first.scored)
            state = first.after
            var steps = 0

            while (state.progress.result == null && state.progress.turnNumber == startTurn && steps < maxSteps) {
                steps++
                val step = oneStep(state, focal, ownStrategy, oppStrategy) ?: break
                ownSteps.add(step.scored)
                state = step.after
            }
            val afterOwn = state

            while (state.progress.result == null && state.progress.turnNumber == startTurn + 1 && steps < maxSteps) {
                steps++
                val step = oneStep(state, focal, ownStrategy, oppStrategy) ?: break
                oppSteps.add(step.scored)
                state = step.after
            }

            PathResult(
                afterOwnTurn = afterOwn,
                afterOppTurn = state,
                ownTurnSteps = ownSteps,
                throughOppSteps = oppSteps,
            )
        }
    }

    private data class Applied(val scored: ScoredStep, val after: MatchState)

    private fun applyScored(before: MatchState, action: LegalAction, focal: TeamSide): Applied {
        val after = search.apply(before, action)
        val notes = possessionNotes(before, after, focal) + scoringNotes(before, after, focal)
        val deltaUs = pointsFor(after.progress, focal) - pointsFor(before.progress, focal)
        val deltaThem = pointsFor(after.progress, focal.opponent()) - pointsFor(before.progress, focal.opponent())
        return Applied(
            ScoredStep(action = action, deltaUs = deltaUs, deltaThem = deltaThem, notes = notes),
            after,
        )
    }

    private fun oneStep(
        state: MatchState,
        focal: TeamSide,
        ownStrategy: OpponentStrategy,
        oppStrategy: OpponentStrategy,
    ): Applied? {
        val legal = legalMovesGenerator.forActingCoach(state)
        if (legal.isEmpty()) return null
        val acting = state.actingSeat()
        val strategy = if (acting == focal) ownStrategy else oppStrategy
        val action = strategy.chooseAction(state, legal)
        return applyScored(state, action, focal)
    }
}
