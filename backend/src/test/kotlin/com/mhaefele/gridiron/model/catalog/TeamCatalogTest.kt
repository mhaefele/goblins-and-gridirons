package com.mhaefele.gridiron.model.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TeamCatalogTest {
    @Test
    fun `dwarves roster matches rulebook stats`() {
        val team = TeamCatalog.dwarves()

        assertThat(team.id).isEqualTo(TeamCatalog.DWARVES_ID)
        assertThat(team.emergencyReserves).isEqualTo(4)
        assertThat(team.roster).hasSize(6)
        assertThat(team.roster.count { it.name == "Lineman" }).isEqualTo(3)

        val runner = team.roster.first { it.name == "Runner" }
        assertThat(runner.stats.move).isEqualTo(MoveValue.Fixed(6))
        assertThat(runner.stats.throwStat).isEqualTo(ThrowStat.Threshold(4))

        val doomSeeker = team.roster.first { it.name == "Doom Seeker" }
        assertThat(doomSeeker.stats.throwStat).isEqualTo(ThrowStat.Threshold(6))
        assertThat(doomSeeker.stats.armour).isEqualTo(ArmourStat.Threshold(4))
    }

    @Test
    fun `orcs roster matches rulebook stats`() {
        val team = TeamCatalog.orcs()

        assertThat(team.id).isEqualTo(TeamCatalog.ORCS_ID)
        assertThat(team.emergencyReserves).isEqualTo(4)
        assertThat(team.roster).hasSize(6)

        val linemen = team.roster.filter { it.name == "Lineman" }
        assertThat(linemen).hasSize(3)
        assertThat(linemen).allMatch { it.stats.throwStat == ThrowStat.Threshold(4) }

        val bruiser = team.roster.first { it.name == "Bruiser" }
        assertThat(bruiser.stats.blockDiceCount).isEqualTo(2)
        assertThat(bruiser.stats.throwStat).isEqualTo(ThrowStat.Threshold(6))
        assertThat(bruiser.ability).isEqualTo(PlayerAbility.`Heavy Hitter`)
    }

    @Test
    fun `catalog lists all twenty three teams with valid rosters`() {
        val teams = TeamCatalog.allTeams()

        assertThat(teams).hasSize(23)
        assertThat(teams.map { it.id }).doesNotHaveDuplicates()
        assertThat(teams).allMatch { it.roster.size in 6..10 }
    }

    @Test
    fun `goblin and halfling roster sizes match official cards`() {
        val goblin = TeamCatalog.team("goblin")
        assertThat(goblin.roster).hasSize(7)
        assertThat(goblin.roster.count { it.name == "Goblin" }).isEqualTo(6)
        assertThat(goblin.roster.count { it.name == "Troll" }).isEqualTo(1)

        val halfling = TeamCatalog.team("halfling")
        assertThat(halfling.roster).hasSize(7)
        assertThat(halfling.roster.count { it.name == "Lineman" }).isEqualTo(4)
        assertThat(halfling.roster.first { it.name == "Hefty" }.ability).isEqualTo(PlayerAbility.Anchored)
    }

    @Test
    fun `emergency reserves match official cards`() {
        assertThat(TeamCatalog.team("scalekin").emergencyReserves).isEqualTo(5)
        assertThat(TeamCatalog.team("graveborn").emergencyReserves).isEqualTo(5)
        assertThat(TeamCatalog.team("restless-dead").emergencyReserves).isEqualTo(4)
    }

    @Test
    fun `wood elf glade dancer and dark elf bloodblade use official abilities`() {
        assertThat(TeamCatalog.team("wood-elf").roster.first { it.name == "Glade Dancer" }.ability)
            .isEqualTo(PlayerAbility.`Dance Step`)
        assertThat(TeamCatalog.team("dark-elf").roster.first { it.name == "Bloodblade" }.ability)
            .isEqualTo(PlayerAbility.`Relentless After Mark`)
        assertThat(TeamCatalog.team("dark-elf").roster.first { it.name == "Blitzer" }.stats.throwStat)
            .isEqualTo(ThrowStat.Threshold(4))
    }

    @Test
    fun `specialist positions record typed abilities`() {
        val orcs = TeamCatalog.orcs()

        assertThat(orcs.roster.first { it.name == "Thrower" }.ability).isEqualTo(PlayerAbility.`Sure Scoop`)
        assertThat(orcs.roster.first { it.name == "Bruiser" }.ability).isEqualTo(PlayerAbility.`Heavy Hitter`)
    }
}
