package com.mhaefele.gridiron.support

import com.mhaefele.gridiron.model.catalog.MoveValue
import com.mhaefele.gridiron.model.catalog.PositionProfile
import com.mhaefele.gridiron.model.catalog.TeamCatalog

/** Sensible default roster entries when a test does not care about stats. */
object TestProfiles {
    // Prefer [TeamCatalogTestHelper] for ability / canThrowTeammate lookups.
    // TODO(7.13): migrate remaining name hardcodes ("Doom Seeker", "Glade Dancer", …) via that helper.

    fun lineman(): PositionProfile =
        TeamCatalog.dwarves().roster.first { it.name == "Lineman" }

    fun withMove(squares: Int): PositionProfile {
        val base = lineman()
        return base.copy(stats = base.stats.copy(move = MoveValue.Fixed(squares)))
    }

    fun orcBruiser(): PositionProfile =
        TeamCatalog.orcs().roster.first { it.name == "Bruiser" }

    fun dwarfLineman(): PositionProfile =
        TeamCatalog.dwarves().roster.first { it.name == "Lineman" }

    fun orcLineman(): PositionProfile =
        TeamCatalog.orcs().roster.first { it.name == "Lineman" }

    fun wraith(): PositionProfile =
        TeamCatalog.team("graveborn").roster.first { it.name == "Wraith" }
}
