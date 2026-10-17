package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.game.dice.ScriptedDiceRoller
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.shared.Direction

/** Deterministic dice for scenario placement (crank + [AbstractTrainingScenario.actionToPrime]). */
object TrainingScenarioPlacementDice {
    /** Failed throw check — Pinpoint Passer / Do-Over held reroll window. */
    val FAILED_SHORT_THROW: ScriptedDiceRoller.() -> Unit = {
        queueD6(List(48) { 1 })
        queueBounce(*Array(16) { Direction.S })
        queueBlockDice(List(8) { BlockDieFace.MISS })
    }

    /** OS block pool without Smash — keep vs re-roll is unambiguous (re-roll). */
    val SECOND_SWING_BLOCK: ScriptedDiceRoller.() -> Unit = {
        queueD6(List(24) { 6 })
        queueBlockDice(List(16) { BlockDieFace.MISS })
        queueBounce(*Array(16) { Direction.S })
    }
}
