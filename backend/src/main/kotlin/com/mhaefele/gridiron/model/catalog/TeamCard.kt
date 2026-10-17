package com.mhaefele.gridiron.model.catalog

/** One miniature's position and stats from a team card. [ability] is typed for resolution. */
data class PositionProfile(
    val name: String,
    val stats: StatLine,
    /** Null when this position has no special ability on the team card. */
    val ability: PlayerAbility? = null,
    /** True for Ogre / Troll / Oakwalker — may take the Throw Team-mate action (digest § Throw Team-mate). */
    val canThrowTeammate: Boolean = false,
)

/** Team card roster for match setup — one [roster] entry per on-pitch miniature. */
data class TeamCard(
    val id: TeamCatalogId,
    val name: String,
    val emergencyReserves: Int,
    val roster: List<PositionProfile>,
) {
    init {
        require(roster.size in 6..10) { "Roster must have 6–10 players, was ${roster.size}" }
        require(emergencyReserves > 0) { "Emergency reserves must be positive" }
    }
}
