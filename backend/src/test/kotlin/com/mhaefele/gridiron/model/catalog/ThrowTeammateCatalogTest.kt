package com.mhaefele.gridiron.model.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ThrowTeammateCatalogTest {
    @Test
    fun `ogre troll and oakwalker can throw teammate`() {
        val ogres = TeamCatalog.team("ogre").roster.filter { it.name == "Ogre" }
        val trolls = TeamCatalog.team("goblin").roster.filter { it.name == "Troll" }
        val halfTreemen = TeamCatalog.team("halfling").roster.filter { it.name == "Oakwalker" }
        val gnomeTreemen = TeamCatalog.team("gnome").roster.filter { it.name == "Oakwalker" }

        assertThat(ogres).isNotEmpty.allMatch { it.canThrowTeammate }
        assertThat(trolls).isNotEmpty.allMatch { it.canThrowTeammate }
        assertThat(halfTreemen).isNotEmpty.allMatch { it.canThrowTeammate }
        assertThat(gnomeTreemen).isNotEmpty.allMatch { it.canThrowTeammate }
    }

    @Test
    fun `other positions cannot throw teammate`() {
        assertThat(TeamCatalog.orcs().roster).noneMatch { it.canThrowTeammate }
        val grublings = TeamCatalog.team("ogre").roster.filter { it.name == "Grubling" }
        assertThat(grublings).isNotEmpty.noneMatch { it.canThrowTeammate }
    }
}
