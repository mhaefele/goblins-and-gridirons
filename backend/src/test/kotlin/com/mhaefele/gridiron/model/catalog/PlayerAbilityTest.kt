package com.mhaefele.gridiron.model.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PlayerAbilityTest {
    @Test
    fun `every ability has a description`() {
        PlayerAbility.entries.forEach { ability ->
            assertThat(ability.description).isNotBlank
        }
    }

    @Test
    fun `orc and dwarf specialists use expected ability keys`() {
        val orcs = TeamCatalog.orcs()
        val dwarves = TeamCatalog.dwarves()

        assertThat(orcs.roster.first { it.name == "Thrower" }.ability).isEqualTo(PlayerAbility.`Sure Scoop`)
        assertThat(orcs.roster.first { it.name == "Blitzer" }.ability).isEqualTo(PlayerAbility.`Second Swing`)
        assertThat(orcs.roster.first { it.name == "Bruiser" }.ability).isEqualTo(PlayerAbility.`Heavy Hitter`)

        assertThat(dwarves.roster.first { it.name == "Runner" }.ability).isEqualTo(PlayerAbility.`Ball Guard`)
        assertThat(dwarves.roster.first { it.name == "Blitzer" }.ability).isEqualTo(PlayerAbility.`Second Swing`)
        assertThat(dwarves.roster.first { it.name == "Doom Seeker" }.ability).isEqualTo(PlayerAbility.`Relentless After Mark`)
    }

    /**
     * Guardrail: each [PlayerAbility] must have at least one behavioral API/unit test.
     * Values name the covering test class (update when adding abilities).
     */
    @Test
    fun `every ability has a named behavioral test`() {
        val coveredBy = mapOf(
            PlayerAbility.`Sure Scoop` to "PlayerAbilityApiTest / PlayerAbilityAcceptanceApiTest",
            PlayerAbility.`Strike and Fade` to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.Disruptor to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.Elusive to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.Charge to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.`Quick Release` to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.`Second Swing` to "PlayerAbilityApiTest / PlayerAbilityAcceptanceApiTest",
            PlayerAbility.`Relentless After Block` to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.`Relentless After Mark` to "PlayerAbilityApiTest",
            PlayerAbility.`Ball Guard` to "PlayerAbilityApiTest / PlayerAbilityAcceptanceApiTest",
            PlayerAbility.`Pinpoint Passer` to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.`Catch and Go` to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.`Wild Ward` to "PlayerAbilityApiTest",
            PlayerAbility.`Nimble Beast` to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.Colossus to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.`Pass on the Run` to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.Anchored to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.Enforcer to "EnforcerAbilityApiTest",
            PlayerAbility.Ethereal to "PlayerAbilityApiTest",
            PlayerAbility.Violent to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.Rally to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.Underfoot to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.`Heavy Hitter` to "PlayerAbilityApiTest",
            PlayerAbility.Undying to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.`Beneath Notice` to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.Lobber to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.Vault to "PlayerAbilityApiTest",
            PlayerAbility.Juggernaut to "PlayerAbilityApiTest / ActionAvailabilityTest",
            PlayerAbility.`Dance Step` to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.Devoted to "PlayerAbilityAcceptanceApiTest",
            PlayerAbility.Thirst to "PlayerAbilityAcceptanceApiTest",
        )
        assertThat(coveredBy.keys).containsExactlyInAnyOrderElementsOf(PlayerAbility.entries)
    }
}
