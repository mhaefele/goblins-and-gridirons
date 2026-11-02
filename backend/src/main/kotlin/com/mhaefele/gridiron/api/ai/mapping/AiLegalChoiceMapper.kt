package com.mhaefele.gridiron.api.ai.mapping

import com.mhaefele.gridiron.api.ai.dto.AiLegalChoiceDto
import com.mhaefele.gridiron.api.ai.training.TrainingScenarios
import com.mhaefele.gridiron.api.ai.training.TrainingSolvableMoveSearch
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.shared.TeamSide
import org.springframework.stereotype.Component

@Component
class AiLegalChoiceMapper(
    private val trainingScenarios: TrainingScenarios,
    private val solvableSoftMask: TrainingSolvableMoveSearch,
    private val legalPacker: AiLegalPacker,
) {
    fun toLegalChoices(
        actions: List<LegalAction>,
        perspectiveSeat: TeamSide,
        state: MatchState,
    ): List<AiLegalChoiceDto> {
        // softMaskIds: null = search N/A (use heuristic); non-empty = those ids; empty = searched,
        // none solvable — still fall back to heuristic so soft-mask is not blanked.
        val solvableIds = solvableSoftMask.softMaskIds(state, actions)
        val choices = actions.map { action ->
            AiLegalChoiceDto(
                action = action,
                passingLegal = trainingScenarios.isPassingScenarioMove(action, state),
                softMaskLegal = when {
                    solvableIds == null      -> trainingScenarios.isSoftMaskScenarioMove(action, state)
                    solvableIds.isNotEmpty() -> action.id in solvableIds
                    else                     -> trainingScenarios.isSoftMaskScenarioMove(action, state)
                },
            )
        }
        checkDistinctPackedRows(choices, state, perspectiveSeat)
        // Shuffle so join-score / soft-mask cannot latch onto catalog order. Packed legalMoves,
        // harness legalRows, soft-mask flags, and the chosen row index all share this order —
        // resolve by index into the shuffled menu (ONNX + Python env).
        return choices.shuffled()
    }

    /** Packed legal-row ints must be unique within one legal set (submit identity via action.id). */
    private fun checkDistinctPackedRows(
        choices: List<AiLegalChoiceDto>,
        state: MatchState,
        perspectiveSeat: TeamSide,
    ) {
        val seen = mutableMapOf<List<Int>, AiLegalChoiceDto>()
        for (choice in choices) {
            val key = legalPacker.harnessFactorKey(choice.action, state, perspectiveSeat)
            val prior = seen.put(key, choice)
            check(prior == null) { "Duplicate legal-row pack $key for ${prior!!.action::class.simpleName}/${prior.action.id} and ${choice.action::class.simpleName}/${choice.action.id}" }
        }
    }
}
