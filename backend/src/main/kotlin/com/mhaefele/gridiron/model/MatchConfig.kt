package com.mhaefele.gridiron.model

import com.mhaefele.gridiron.model.catalog.PitchDefinitionId
import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.TeamSide

/** Fixed match parameters — set at create and not changed by turn play. */
data class MatchConfig(
    /** Team that set up first — sudden-death tiebreak and first-turn pre-turn skip. */
    val coachA: TeamSide,
    /** Catalog id for the home coach's team — resolved at match create. */
    val homeTeamId: TeamCatalogId = TeamCatalogId("dwarves"),
    /** Catalog id for the away coach's team — resolved at match create. */
    val awayTeamId: TeamCatalogId = TeamCatalogId("orcs"),
    /** Frozen from home roster at setup — pre-turn emergency reserves check. */
    val homeEmergencyReserves: Int = 4,
    /** Frozen from away roster at setup — pre-turn emergency reserves check. */
    val awayEmergencyReserves: Int = 4,
    /** Catalog id for pitch layout — resolved at match create. */
    val pitchDefinitionId: PitchDefinitionId = PitchDefinitionId("default"),
    /** Who submits actions for HOME — human clients vs in-process computer coach. */
    val homeControlledBy: CoachControl = CoachControl.HUMAN,
    /** Who submits actions for AWAY — human clients vs in-process computer coach. */
    val awayControlledBy: CoachControl = CoachControl.HUMAN,
) {
    init {
        require(homeEmergencyReserves > 0) { "homeEmergencyReserves must be positive" }
        require(awayEmergencyReserves > 0) { "awayEmergencyReserves must be positive" }
    }

    fun controlledBy(team: TeamSide): CoachControl =
        when (team) {
            TeamSide.HOME -> homeControlledBy
            TeamSide.AWAY -> awayControlledBy
        }
}
