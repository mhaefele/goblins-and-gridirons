package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.catalog.PitchCatalog
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.model.catalog.TeamComplexityLabel
import com.mhaefele.gridiron.model.catalog.TeamSummaryCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CatalogApiTest : BaseApiTest() {

    @Test
    fun `lists catalog teams and pitches`() {
        val teams = api.catalogTeams()
        val pitches = api.catalogPitches()

        assertThat(teams.teams.map { it.id })
            .containsExactlyInAnyOrderElementsOf(
                TeamCatalog.allTeams().map { it.id },
            )
        assertThat(pitches.pitches.map { it.id })
            .containsExactlyInAnyOrder(PitchCatalog.DEFAULT_ID, PitchCatalog.BARRICADE_ID)
    }

    @Test
    fun `team catalog entries include full roster cards with abilities`() {
        val dwarves = api.catalogTeams().teams.first { it.id == TeamCatalog.DWARVES_ID }
        val expected = TeamCatalog.dwarves()

        assertThat(dwarves.roster).hasSize(expected.roster.size)
        assertThat(dwarves.roster.map { it.positionLabel })
            .containsExactlyElementsOf(expected.roster.map { it.name })

        val doomSeeker = dwarves.roster.first { it.positionLabel == "Doom Seeker" }
        assertThat(doomSeeker.ability).isNotNull
        assertThat(doomSeeker.ability!!.key).isEqualTo("Relentless After Mark")
    }

    @Test
    fun `team catalog entries include playstyle summaries`() {
        val teams = api.catalogTeams().teams
        val dwarves = teams.first { it.id == TeamCatalog.DWARVES_ID }
        val expected = TeamSummaryCatalog.summary(TeamCatalog.DWARVES_ID)

        assertThat(dwarves.summary).isEqualTo(expected)
        assertThat(dwarves.summary.complexityLabel).isEqualTo(TeamComplexityLabel.Beginner)
        assertThat(dwarves.summary.ratings.bash).isEqualTo(5)

        assertThat(teams.map { it.id })
            .containsExactlyInAnyOrderElementsOf(TeamSummaryCatalog.allSummaries().map { it.id })
        assertThat(teams.first { it.id.value == "bloodsworn" }.summary.bashVsDashLabel)
            .isEqualTo("Heavy Bash")
        assertThat(teams.first { it.id.value == "ratfolk" }.summary.bashVsDash).isEqualTo(4.8)
        assertThat(teams.first { it.id.value == "gilded-crown" }.summary.complexityLabel)
            .isEqualTo(TeamComplexityLabel.Intermediate)
    }
}
