package com.mhaefele.gridiron.support

import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.PositionProfile
import com.mhaefele.gridiron.model.catalog.TeamCard
import com.mhaefele.gridiron.model.catalog.TeamCatalog

/**
 * Catalog lookups for tests that care about a capability / ability, not a roster name.
 *
 * Prefer these over `TeamCatalog.x().roster.first { it.name == "…" }` so the test states
 * what rule it is exercising. Migrate remaining name hardcodes when next touched — see BACKLOG 7.13.
 */
object TeamCatalogTestHelper {
    /** First catalog profile that can make a Throw Team-mate Action (`canThrowTeammate`). */
    fun throwTeammateProfile(): PositionProfile =
        firstProfile { it.canThrowTeammate } ?: error("No catalog profile with canThrowTeammate")

    /**
     * Same-team throwable teammate of [thrower] (different position name).
     * When [ability] is set, prefers a catalog mate with that ability; otherwise grafts it onto the first mate.
     */
    fun throwableTeammateOf(thrower: PositionProfile, ability: PlayerAbility? = null): PositionProfile {
        require(thrower.canThrowTeammate) { "throwableTeammateOf expects canThrowTeammate thrower, was ${thrower.name}" }
        val mates = teamOf(thrower).roster.filter { it.name != thrower.name }
        check(mates.isNotEmpty()) { "No throwable teammate on ${thrower.name}'s team" }
        if (ability == null) return mates.first()
        return mates.firstOrNull { it.ability == ability } ?: mates.first().copy(ability = ability)
    }

    /** First catalog profile with [ability]. */
    fun profileWith(ability: PlayerAbility): PositionProfile =
        firstProfile { it.ability == ability } ?: error("No catalog profile with ability $ability")

    private fun firstProfile(predicate: (PositionProfile) -> Boolean): PositionProfile? =
        TeamCatalog.allTeams().asSequence().flatMap { it.roster.asSequence() }.firstOrNull(predicate)

    private fun teamOf(profile: PositionProfile): TeamCard =
        TeamCatalog.allTeams().firstOrNull { team -> team.roster.any { it == profile } }
            ?: TeamCatalog.allTeams().firstOrNull { team ->
                team.roster.any { it.name == profile.name && it.canThrowTeammate == profile.canThrowTeammate && it.stats == profile.stats }
            } ?: error("No team contains profile ${profile.name}")
}
