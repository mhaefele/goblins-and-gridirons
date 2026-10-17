package com.mhaefele.gridiron.model.catalog

/** Resolves [TeamCatalogId] to team card roster data. */
object TeamCatalog {
    val DWARVES_ID = TeamCatalogId("dwarves")
    val ORCS_ID = TeamCatalogId("orcs")

    fun team(id: TeamCatalogId): TeamCard = GridironTeamCatalog.team(id)

    fun team(id: String): TeamCard = team(TeamCatalogId(id))

    fun allTeams(): List<TeamCard> = GridironTeamCatalog.allTeams()

    fun dwarves(): TeamCard = GridironTeamCatalog.dwarves()

    fun orcs(): TeamCard = GridironTeamCatalog.orcs()
}
