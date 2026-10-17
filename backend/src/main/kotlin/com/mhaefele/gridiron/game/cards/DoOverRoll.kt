package com.mhaefele.gridiron.game.cards

import com.mhaefele.gridiron.game.dice.ArmourCheckOutcome
import com.mhaefele.gridiron.game.dice.ThrowCheckOutcome
import com.mhaefele.gridiron.game.dice.armourCheck
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.game.dice.dto.bestBlockFace
import com.mhaefele.gridiron.game.dice.throwCheck
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.catalog.ArmourStat
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.ThrowStat
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.ThrowRange

/** Whether Do-Over is still useful for a held after-dice roll. */
object DoOverRoll {
    fun mayImproveBlock(board: Board, blockerId: PlayerId, faces: List<BlockDieFace>): Boolean {
        val blocker = board.player(blockerId)
        if (blocker.hasAbility(PlayerAbility.Enforcer)) {
            return faces.any { it != BlockDieFace.SMASH }
        }
        return bestBlockFace(faces) != BlockDieFace.SMASH
    }

    fun mayImproveThrow(
        board: Board,
        throwerId: PlayerId,
        naturalRoll: Int,
        range: ThrowRange,
        obstructed: Boolean,
        hailMaryActive: Boolean,
        deadEyeActive: Boolean,
    ): Boolean {
        val thrower = board.player(throwerId)
        val outcome = when {
            hailMaryActive      -> throwCheck(naturalRoll, ThrowStat.Threshold(4), ThrowRange.SHORT, obstructed = false)
            deadEyeActive -> throwCheck(naturalRoll, ThrowStat.Threshold(2), ThrowRange.SHORT, obstructed = false)
            else                -> throwCheck(naturalRoll, thrower.profile.stats.throwStat, range, obstructed)
        }
        return outcome != ThrowCheckOutcome.Success
    }

    fun mayImproveArmour(naturalRoll: Int, armour: ArmourStat, flatten: Boolean): Boolean =
        when (armourCheck(naturalRoll, armour, flatten)) {
            ArmourCheckOutcome.Success                              -> true
            ArmourCheckOutcome.NoArmour, is ArmourCheckOutcome.Fail -> false
        }
}
