package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TrainingEpisodeMode
import kotlin.random.Random
import org.springframework.stereotype.Component

/**
 * Soft-mask and PASS diverge on the **first** opening select (no ball yet), **TRAIN only**:
 *
 * - **PASS / expected** (`passingLegal`): pickup only — Python opening stats / expected rank use this.
 * - **Soft-mask** (`softMaskLegal`, TRAIN): pickup always, plus each empty-handed scoring-EZ run with
 *   probability **1/4** (`Random.nextInt(4) == 0` per ask). EVAL: soft-mask == PASS (no empty-EZ).
 *
 * Why: shared policy + TD_RUN make empty-EZ hotter than pickup. Soft-masking **every** empty-EZ with
 * the same +2.5 left relative order unchanged → train ~6% US. Thinning empty-EZ soft-mask keeps some
 * fail exposure without drowning pickup→+4 trajectories. Soft-mask is sampling only — empty-EZ stays
 * off `passingLegal`; [drillStatus] PASSes only on a Run touchdown.
 *
 * After pickup: soft-mask == PASS == carrier EZ run.
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

    override fun isSoftMaskScenarioMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (isLessonRun(action, state, trainerSide)) return true
        // Empty-EZ distractors: TRAIN rollouts only (eval/gates keep soft-mask == lesson).
        if (state.trainingEpisode?.episodeMode != TrainingEpisodeMode.TRAIN) return false
        // First budgeted select only — occasional empty-EZ samples (not every dash).
        if (state.currentTurnState.actionsThisTurn.isNotEmpty()) return false
        if (!isEmptyHandedEndZoneRun(action, state, trainerSide)) return false
        return Random.nextInt(4) == 0
    }

    /** Expected / winrate path — never empty-EZ soft-mask distractors. */
    override fun isPassingScenarioMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isLessonRun(action, state, trainerSide)

    /** Run TD only — empty-EZ soft-mask trials and pickup itself do not PASS. */
    override fun drillStatus(before: MatchState, after: MatchState, action: LegalAction): TrainingDrillStatus =
        drillStatusForTouchdownByRun(before, after, action) ?: TrainingDrillStatus.STILL_IN_PROGRESS

    private fun isLessonRun(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (action !is LegalAction.Run || state.board.players[action.playerId]?.team != trainerSide) return false
        val carrier = trainerBallCarrierOrNull(state, trainerSide)
        return if (carrier == null) {
            action.ballEncounter == BallEncounter.PICK_UP
        } else {
            action.playerId == carrier.id && state.board.squareAt(action.path.squares.last()).isEndZone(trainerSide.opposite())
        }
    }

    /** Trainer run into scoring EZ that is not a pickup (empty-handed dash). */
    private fun isEmptyHandedEndZoneRun(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean {
        if (action !is LegalAction.Run || state.board.players[action.playerId]?.team != trainerSide) return false
        if (action.ballEncounter == BallEncounter.PICK_UP) return false
        return state.board.squareAt(action.path.squares.last()).isEndZone(trainerSide.opposite())
    }
}
