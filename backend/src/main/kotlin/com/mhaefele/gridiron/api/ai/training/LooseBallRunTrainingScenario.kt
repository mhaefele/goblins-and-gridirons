package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TrainingEpisodeMode
import kotlin.random.Random
import org.springframework.stereotype.Component

/**
 * Soft-mask and PASS diverge on the **first** opening select (no ball yet), **TRAIN only**:
 *
 * - **PASS / expected** (`passingLegal`): pickup only — Python opening stats / expected rank use this.
 * - **Soft-mask** (`softMaskLegal`, TRAIN): every lesson run, plus **exactly
 *   [BAD_SOFT_MASK_COUNT]** random empty-handed scoring-EZ runs (or fewer if the menu is short).
 *   EVAL: soft-mask == lesson (no empty-EZ).
 *
 * Why: shared policy + TD_RUN make empty-EZ hotter than pickup. Soft-masking **every** empty-EZ with
 * the same +2.5 left relative order unchanged → train ~6% US. A fixed small distractor sample keeps
 * fail exposure without drowning pickup→+4 trajectories. Soft-mask is sampling only — empty-EZ stays
 * off `passingLegal`.
 *
 * **Grading**
 * - **d0–d1 (experiment):** first opening action only — PASS on pickup, else THEM; episode ends
 *   immediately ([scenarioEndsAfterFirstOpeningAction]). No continue-to-score lesson at these levels.
 * - **d2–d5:** [drillStatus] PASSes only on a Run touchdown (after pickup: soft-mask == PASS ==
 *   carrier EZ run).
 */
@Component
class LooseBallRunTrainingScenario : AbstractLooseBallTrainingScenario(contested = false) {
    override val scenario = TrainingScenarioEnum.LOOSE_BALL_RUN

    override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        require(
            legals.filterIsInstance<LegalAction.Run>().any { run ->
                run.ballEncounter == BallEncounter.PICK_UP && state.board.players[run.playerId]?.team == trainerSide
            },
        ) { "LOOSE_BALL_RUN: no legal trainer run picks up the loose ball" }
    }

    /**
     * Menu-level soft-mask so TRAIN openings can take a fixed-size random distractor sample.
     * Per-row Bernoulli cannot guarantee "exactly N".
     */
    override fun softMaskLegalIds(
        actions: List<LegalAction>,
        state: MatchState,
        trainerSide: TeamSide,
    ): Set<LegalActionId> {
        val lessonIds = actions.filter { isLessonRun(it, state, trainerSide) }.map { it.id }.toSet()
        if (state.trainingEpisode?.episodeMode != TrainingEpisodeMode.TRAIN) return lessonIds
        // First budgeted select only — follow-ups soft-mask the lesson line alone.
        if (state.currentTurnState.actionsThisTurn.isNotEmpty()) return lessonIds
        val bad = actions.filter { isEmptyHandedEndZoneRun(it, state, trainerSide) }
        val distractors = if (bad.size <= BAD_SOFT_MASK_COUNT) {
            bad
        } else {
            bad.shuffled(Random.Default).take(BAD_SOFT_MASK_COUNT)
        }
        return lessonIds + distractors.map { it.id }
    }

    /** Fallback / search roots — lesson only (distractors come from [softMaskLegalIds]). */
    override fun isSoftMaskScenarioMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isLessonRun(action, state, trainerSide)

    /** Expected / winrate path — never empty-EZ soft-mask distractors. */
    override fun isPassingScenarioMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isLessonRun(action, state, trainerSide)

    override fun drillStatus(before: MatchState, after: MatchState, action: LegalAction): TrainingDrillStatus {
        val difficulty = before.trainingEpisode?.difficulty ?: 0
        if (difficulty in 0..1) {
            // Pickup-only one-shot: PASS on a trainer PICK_UP run, miss stays in-progress → THEM at early end.
            return if (isTrainerPickupRun(action, before, trainerSide(before))) {
                TrainingDrillStatus.PASS
            } else {
                TrainingDrillStatus.STILL_IN_PROGRESS
            }
        }
        return drillStatusForTouchdownByRun(before, after, action) ?: TrainingDrillStatus.STILL_IN_PROGRESS
    }

    private fun isLessonRun(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (action !is LegalAction.Run || state.board.players[action.playerId]?.team != trainerSide) return false
        val carrier = trainerBallCarrierOrNull(state, trainerSide)
        return if (carrier == null) {
            action.ballEncounter == BallEncounter.PICK_UP
        } else {
            action.playerId == carrier.id && state.board.squareAt(action.path.squares.last()).isEndZone(trainerSide.opposite())
        }
    }

    /** Trainer run that attempts pickup (d0–d1 one-shot grade). */
    private fun isTrainerPickupRun(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (action !is LegalAction.Run || state.board.players[action.playerId]?.team != trainerSide) return false
        return action.ballEncounter == BallEncounter.PICK_UP
    }

    /** Trainer run into scoring EZ that is not a pickup (empty-handed dash). */
    private fun isEmptyHandedEndZoneRun(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (action !is LegalAction.Run || state.board.players[action.playerId]?.team != trainerSide) return false
        if (action.ballEncounter == BallEncounter.PICK_UP) return false
        return state.board.squareAt(action.path.squares.last()).isEndZone(trainerSide.opposite())
    }

    companion object {
        /** TRAIN opening: soft-mask this many non-lesson empty-EZ runs (when available). */
        const val BAD_SOFT_MASK_COUNT = 2
    }
}
