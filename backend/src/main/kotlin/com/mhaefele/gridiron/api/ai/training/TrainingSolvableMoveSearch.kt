package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.ai.AiSubmitTiming
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.opponent.search.DeterministicActionSearch
import com.mhaefele.gridiron.game.opponent.search.SearchOutcome
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.TrainingDrillStatus
import com.mhaefele.gridiron.model.pending.ReplaceChallenge
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.TrainingEpisodeMode
import org.springframework.stereotype.Component

/**
 * Training soft-mask adapter — **not** the search.
 *
 * Owns only: when soft-mask may use reachability, what SUCCESS means ([TrainingDrillStatus.PASS]),
 * and timing. Tree walk lives in [DeterministicActionSearch] (horizon = end of trainer turn).
 *
 * Multi-step TRAIN claims: **opening** coach select keeps scenario heuristic soft-mask (cheap,
 * already lesson-shaped). Reachability search runs only on **follow-up** selects after the first
 * budgeted action — tighter mask where branching hurts most.
 *
 * Caller ([com.mhaefele.gridiron.api.ai.mapping.AiLegalChoiceMapper]) invokes once per legal menu
 * and reuses the id set for every row. See [softMaskIds] for null vs empty vs non-empty.
 */
@Component
class TrainingSolvableMoveSearch(
    private val search: DeterministicActionSearch,
    private val trainingScenarios: TrainingScenarios,
    private val submitTiming: AiSubmitTiming,
) {
    /**
     * Reachability soft-mask ids for this menu, or null when search does **not** own soft-mask here.
     *
     * - **null** — adapter declines (opening select, not TRAIN claim multi-step, mid-action,
     *   replace, …). Caller keeps scenario heuristic soft-mask; search was not run.
     * - **non-empty** — search ran; only these ids are soft-mask true.
     * - **empty** — search ran and found no root that still reaches PASS. Caller currently falls
     *   back to heuristic so soft-mask is not wiped blank (fuse / optimistic miss).
     */
    fun softMaskIds(state: MatchState, roots: List<LegalAction>): Set<LegalActionId>? {
        val episode = state.trainingEpisode ?: return null
        if (episode.episodeMode != TrainingEpisodeMode.TRAIN) return null
        if (episode.drillStatus != TrainingDrillStatus.STILL_IN_PROGRESS) return null
        if (!episode.scenario.name.startsWith("CLAIM_")) return null
        // Opening-turn gate lives here (training policy), not inside the game-tree search.
        if (!trainingScenarios.isTrainerOpeningTurn(state)) return null
        // Soft-mask reachability is for coach select menus; open-action follow-ups keep heuristic.
        if (state.currentTurnState.currentPlayerAction != null) return null
        // Forced replace keeps heuristic bait soft-mask — search starts on PLAYER_ACTION select.
        if (state.currentTurnState.pendingDecision is ReplaceChallenge) return null
        val script = trainingScenarios.implementation(episode.scenario)
        // One-move drills already soft-mask via scenario heuristic; search is for multi-step claims.
        if (script.expectedBudgetedActions <= 1) return null
        // First budgeted select: heuristic soft-mask is good enough — skip the tree walk.
        if (state.currentTurnState.actionsThisTurn.isEmpty()) return null

        // Do not OR-expand the full legal menu — only roots the scenario already treats as lesson
        // soft-mask (e.g. Show Them How star, Keep Running unused Runs). Huge fan-out cut.
        val lessonRoots = roots.filter { trainingScenarios.isSoftMaskScenarioMove(it, state) }
        if (lessonRoots.isEmpty()) return null

        val started = System.nanoTime()
        return try {
            search.reachableRootIds(
                state = state,
                roots = lessonRoots,
                actingCoach = episode.trainerSide,
                evaluate = passEvaluator(),
            )
        } finally {
            submitTiming.recordSoftmaskSearch(state.id, System.nanoTime() - started)
        }
    }

    /**
     * Grade the dry-run apply, then map to search outcome.
     * Any line that cannot still reach PASS fails immediately (no kid expand):
     * DISTRACTED episode grade, or a coach-select move off the lesson soft-mask line
     * (wrong Show Them How player, non-Run on Keep Running, …).
     */
    private fun passEvaluator(): DeterministicActionSearch.SearchEvaluator =
        DeterministicActionSearch.SearchEvaluator { before, after, action ->
            val graded = trainingScenarios.updatedTrainingEpisode(before, after, action)
            when {
                graded.drillStatus == TrainingDrillStatus.PASS -> SearchOutcome.SUCCESS
                // Can't succeed from here — prune (episode already non-PASS, or off-lesson select).
                graded.drillStatus == TrainingDrillStatus.DISTRACTED || isDeadLessonChoice(before, action) -> SearchOutcome.FAILURE
                else                                                                                       -> SearchOutcome.CONTINUE
            }
        }

    /**
     * Coach-select player move that is not on this scenario's soft-mask lesson line.
     * Mid-action / claim / decline are not judged here — soft-mask is false there by design.
     */
    private fun isDeadLessonChoice(before: MatchState, action: LegalAction): Boolean {
        if (before.currentTurnState.currentPlayerAction != null) return false
        return when (action) {
            is LegalAction.BasicPlayerAction,
            is LegalAction.DeclareRun,
                -> !trainingScenarios.isSoftMaskScenarioMove(action, before)
            else -> false
        }
    }
}
