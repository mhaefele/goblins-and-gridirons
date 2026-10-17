package com.mhaefele.gridiron.game.action.dto

import com.mhaefele.gridiron.game.dice.ArmourCheckOutcome
import com.mhaefele.gridiron.game.dice.ThrowCheckOutcome
import com.mhaefele.gridiron.game.dice.armourCheck
import com.mhaefele.gridiron.game.dice.clampModifier
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.game.dice.dto.FoulResult
import com.mhaefele.gridiron.game.dice.dto.bestBlockFace
import com.mhaefele.gridiron.game.dice.dto.foulResultFor
import com.mhaefele.gridiron.game.dice.throwCheck
import com.mhaefele.gridiron.game.dice.throwCheckModifier
import com.mhaefele.gridiron.model.catalog.ArmourStat
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.ThrowStat
import com.mhaefele.gridiron.shared.ThrowRange

fun throwDiceRolled(
    naturalRoll: Int,
    throwStat: ThrowStat,
    range: ThrowRange,
    obstructed: Boolean,
    ability: PlayerAbility? = null,
    bonusPlay: BonusPlay? = null,
): DomainEvent.DiceRolled {
    val outcome = throwCheck(naturalRoll, throwStat, range, obstructed)
    val modifier = throwCheckModifier(range, obstructed)
    val threshold = (throwStat as ThrowStat.Threshold).minimumRoll
    return DomainEvent.DiceRolled(
        purpose = DiceRollPurpose.THROW,
        d6 = naturalRoll,
        threshold = threshold,
        modifier = modifier,
        checkResult = outcome.toCheckResult(),
        ability = ability,
        bonusPlay = bonusPlay,
    )
}

fun armourDiceRolled(
    naturalRoll: Int,
    armour: ArmourStat.Threshold,
    flatten: Boolean,
    ability: PlayerAbility? = null,
    bonusPlay: BonusPlay? = null,
): DomainEvent.DiceRolled {
    val modifier = if (flatten) -1 else 0
    val outcome = armourCheck(naturalRoll, armour, flatten)
    return DomainEvent.DiceRolled(
        purpose = DiceRollPurpose.ARMOUR,
        d6 = naturalRoll,
        threshold = armour.minimumRoll,
        modifier = modifier,
        checkResult = outcome.toCheckResult(),
        ability = ability,
        bonusPlay = bonusPlay,
    )
}

fun blockDiceRolled(
    faces: List<BlockDieFace>,
    blockResult: BlockDieFace? = bestBlockFace(faces),
    ability: PlayerAbility? = null,
    bonusPlay: BonusPlay? = null,
): DomainEvent.DiceRolled =
    DomainEvent.DiceRolled(
        purpose = DiceRollPurpose.BLOCK,
        blockFaces = faces,
        blockResult = blockResult,
        ability = ability,
        bonusPlay = bonusPlay,
    )

fun foulDiceRolled(
    face: BlockDieFace,
    foulResult: FoulResult = foulResultFor(face),
    ability: PlayerAbility? = null,
    bonusPlay: BonusPlay? = null,
): DomainEvent.DiceRolled =
    DomainEvent.DiceRolled(
        purpose = DiceRollPurpose.FOUL,
        blockFaces = listOf(face),
        blockResult = face,
        foulResult = foulResult,
        ability = ability,
        bonusPlay = bonusPlay,
    )

private fun ThrowCheckOutcome.toCheckResult(): DiceCheckResult =
    when (this) {
        ThrowCheckOutcome.Success     -> DiceCheckResult.PASS
        ThrowCheckOutcome.Fumble      -> DiceCheckResult.FUMBLE
        is ThrowCheckOutcome.Fail     -> DiceCheckResult.FAIL
        ThrowCheckOutcome.Unthrowable -> error("Unthrowable players do not roll throw checks")
    }

private fun ArmourCheckOutcome.toCheckResult(): DiceCheckResult =
    when (this) {
        ArmourCheckOutcome.Success                              -> DiceCheckResult.PASS
        ArmourCheckOutcome.NoArmour, is ArmourCheckOutcome.Fail -> DiceCheckResult.FAIL
    }

/** Total after modifier for display; null when naturals 1/6 short-circuit the check. */
fun DomainEvent.DiceRolled.adjustedTotal(): Int? {
    val roll = d6 ?: return null
    val mod = modifier ?: return null
    if (roll == 1 || roll == 6) return null
    return roll + clampModifier(mod)
}
