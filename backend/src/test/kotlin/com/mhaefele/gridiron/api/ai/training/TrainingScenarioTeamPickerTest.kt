package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TrainingScenarioTeamPickerTest {

    @Test
    fun `pinpoint passer filter matches silverleaf only`() {
        val filter: (com.mhaefele.gridiron.model.catalog.TeamCard) -> Boolean =
            { TrainingScenarioTeamPicker.rosterHasAbility(it, PlayerAbility.`Pinpoint Passer`) }
        val eligible = TrainingScenarioTeamPicker.eligibleTeams(filter).map { it.id }
        assertThat(eligible).containsExactly(TeamCatalogId("silverleaf"))
    }

    @Test
    fun `enforcer filter matches bloodsworn only`() {
        val filter: (com.mhaefele.gridiron.model.catalog.TeamCard) -> Boolean =
            { TrainingScenarioTeamPicker.rosterHasAbility(it, PlayerAbility.Enforcer) }
        val eligible = TrainingScenarioTeamPicker.eligibleTeams(filter).map { it.id }
        assertThat(eligible).containsExactly(TeamCatalogId("bloodsworn"))
    }

    @Test
    fun `second swing filter matches every blitzer team`() {
        val filter: (com.mhaefele.gridiron.model.catalog.TeamCard) -> Boolean =
            { TrainingScenarioTeamPicker.rosterHasAbility(it, PlayerAbility.`Second Swing`) }
        val eligible = TrainingScenarioTeamPicker.eligibleTeams(filter).map { it.id.value }.toSet()
        assertThat(eligible).containsExactlyInAnyOrder(
            "dark-elf",
            "dwarves",
            "silverleaf",
            "human",
            "gilded-crown",
            "norse",
            "orcs",
            "restless-dead",
            "ratfolk",
        )
    }

    @Test
    fun `default filter accepts all teams`() {
        assertThat(TrainingScenarioTeamPicker.eligibleTeams { true }).hasSize(TeamCatalog.allTeams().size)
    }

    @Test
    fun `pickTrainerTeamId returns an eligible team`() {
        val filter: (com.mhaefele.gridiron.model.catalog.TeamCard) -> Boolean =
            { TrainingScenarioTeamPicker.rosterHasAbility(it, PlayerAbility.`Pinpoint Passer`) }
        val id = TrainingScenarioTeamPicker.pickTrainerTeamId(filter)
        assertThat(id).isEqualTo(TeamCatalogId("silverleaf"))
    }
}
