package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.TeamCard
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.model.catalog.TeamCatalogId

/** Catalog helpers for [AbstractTrainingScenario.trainerTeamFilter] and match creation. */
object TrainingScenarioTeamPicker {

    fun rosterHasAbility(team: TeamCard, ability: PlayerAbility): Boolean =
        team.roster.any { it.ability == ability }

    fun eligibleTeams(filter: (TeamCard) -> Boolean): List<TeamCard> =
        TeamCatalog.allTeams().filter(filter)

    fun pickTrainerTeamId(filter: (TeamCard) -> Boolean): TeamCatalogId {
        val eligible = eligibleTeams(filter)
        require(eligible.isNotEmpty()) { "No team roster satisfies scenario trainer filter" }
        return eligible.random().id
    }
}
