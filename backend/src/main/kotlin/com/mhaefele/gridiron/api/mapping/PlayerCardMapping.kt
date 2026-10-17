package com.mhaefele.gridiron.api.mapping

import com.mhaefele.gridiron.api.dto.response.CardAbilityDto
import com.mhaefele.gridiron.api.dto.response.PlayerCardDto
import com.mhaefele.gridiron.model.catalog.ArmourStat
import com.mhaefele.gridiron.model.catalog.MoveValue
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.PositionProfile
import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import com.mhaefele.gridiron.model.catalog.ThrowStat

fun toPlayerCardDto(profile: PositionProfile, teamId: TeamCatalogId): PlayerCardDto =
    PlayerCardDto(
        teamId = teamId,
        positionLabel = profile.name,
        move = toMoveSquares(profile.stats.move),
        blockDiceCount = profile.stats.blockDiceCount,
        throwMinimumRoll = toThrowMinimumRoll(profile.stats.throwStat),
        armourMinimumRoll = toArmourMinimumRoll(profile.stats.armour),
        ability = toCardAbilityDto(profile.ability),
        canThrowTeammate = profile.canThrowTeammate,
    )

private fun toMoveSquares(move: MoveValue): Int? =
    when (move) {
        is MoveValue.Fixed -> move.squares
        MoveValue.RandomD6 -> null
    }

private fun toThrowMinimumRoll(throwStat: ThrowStat): Int? =
    when (throwStat) {
        is ThrowStat.Threshold -> throwStat.minimumRoll
        ThrowStat.Unthrowable  -> null
    }

private fun toArmourMinimumRoll(armour: ArmourStat): Int? =
    when (armour) {
        is ArmourStat.Threshold -> armour.minimumRoll
        ArmourStat.NoArmour     -> null
    }

private fun toCardAbilityDto(ability: PlayerAbility?): CardAbilityDto? {
    if (ability == null) return null
    return CardAbilityDto(key = ability.name, description = ability.description)
}
