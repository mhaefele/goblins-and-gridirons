package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.shared.TeamSide

/**
 * Board drill that plays one budgeted action during placement so the episode opens on a
 * held-check window (OS / EP / Do-Over / Enforcer). Soft-mask and PASS target that window.
 */
abstract class AbstractHeldCheckTrainingScenario : AbstractTrainingScenario() {
    /** Budgeted Block / Throw that parks on the held window. */
    abstract override fun actionToPrime(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>): LegalAction

    /** True for any legal on the held window (keep, re-roll, adjust, decline, face orders, …). */
    protected abstract fun isHeldCheckLegal(action: LegalAction): Boolean

    /** Unambiguous best choice among [isHeldCheckLegal] moves given the held faces / roll. */
    protected abstract fun isBestHeldCheckMove(action: LegalAction, state: MatchState): Boolean

    final override fun validateBoard(state: MatchState, trainerSide: TeamSide, legals: List<LegalAction>) {
        require(legals.any(::isHeldCheckLegal)) { "${scenario.name}: expected a held-check legal after prime" }
        require(legals.any { isBestHeldCheckMove(it, state) }) { "${scenario.name}: no unambiguously best held-check move" }
    }

    final override fun isSoftMaskScenarioMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isBestHeldCheckMove(action, state)

    final override fun isPassingScenarioMove(action: LegalAction, state: MatchState, trainerSide: TeamSide): Boolean =
        isBestHeldCheckMove(action, state)
}
