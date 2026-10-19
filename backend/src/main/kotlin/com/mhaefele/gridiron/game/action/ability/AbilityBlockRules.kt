package com.mhaefele.gridiron.game.action.ability

import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.hasAbility

enum class BlockRole { BLOCKER, TARGET }

/** Substitute block-die faces per card before choosing the best result. */
fun substituteBlockFace(
    face: BlockDieFace,
    player: Player,
    role: BlockRole,
): BlockDieFace {
    if (role == BlockRole.BLOCKER) {
        when {
            player.hasAbility(PlayerAbility.Colossus) && face in setOf(BlockDieFace.TACKLE, BlockDieFace.SMASH) -> return BlockDieFace.FLATTEN
            player.hasAbility(PlayerAbility.`Heavy Hitter`) && face == BlockDieFace.SMASH                       -> return BlockDieFace.FLATTEN
            player.hasAbility(PlayerAbility.Underfoot) && face == BlockDieFace.TACKLE                           -> return BlockDieFace.MISS
            player.hasAbility(PlayerAbility.`Beneath Notice`) && face == BlockDieFace.TACKLE                    -> return BlockDieFace.MISS
            player.hasAbility(PlayerAbility.Lobber) && face == BlockDieFace.SHOVE                               -> return BlockDieFace.MISS
        }
    }
    if (role == BlockRole.TARGET) {
        when {
            player.hasAbility(PlayerAbility.Anchored) && face == BlockDieFace.SHOVE -> return BlockDieFace.MISS
            player.hasAbility(PlayerAbility.Colossus) && face == BlockDieFace.SHOVE -> return BlockDieFace.MISS
        }
    }
    return face
}

fun applyBlockerFaceSubstitutions(faces: List<BlockDieFace>, blocker: Player): List<BlockDieFace> =
    faces.map { substituteBlockFace(it, blocker, BlockRole.BLOCKER) }

fun lobberBlock(blocker: Player): Boolean = blocker.hasAbility(PlayerAbility.Lobber)

/** Violent: roll a D6 with the Block Dice; on 6 the target is auto-Injured. */
fun violentAutoInjureCheck(blocker: Player): Boolean =
    blocker.hasAbility(PlayerAbility.Violent)
