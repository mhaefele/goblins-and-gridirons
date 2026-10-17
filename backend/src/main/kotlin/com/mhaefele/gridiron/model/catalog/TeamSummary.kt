package com.mhaefele.gridiron.model.catalog

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/**
 * Coach-facing playstyle blurb for a team card — loaded from `catalog/team-summaries.json`.
 * Methodology: [`reference/team-summaries.md`](../../../../../../reference/team-summaries.md).
 *
 * [id] matches [TeamCard.id] / [TeamCatalogId] (e.g. `"ironhide"`, `"silverleaf"`).
 */
data class TeamSummary(
    /** Unique slug for the team roster — useful for image asset lookup or database matching. Display name is [TeamCard.name]. */
    val id: TeamCatalogId,
    /**
     * Continuous scale from 1 to 5 for the team's core physical vs. speed identity:
     * - 1: Pure Heavy Bash (high strength, low mobility)
     * - 3: Balanced Hybrid
     * - 5: Pure Heavy Dash (high speed, fragile)
     */
    @field:Min(1)
    @field:Max(5)
    val bashVsDash: Double,
    /** Human-readable playstyle archetype (e.g. `"Heavy Bash"`, `"Wildcard Tricksters"`). */
    @field:NotBlank
    val bashVsDashLabel: String,
    /** Normalized 1–5 ratings calculated from roster stats. */
    @field:Valid
    val ratings: TeamRatings,
    /** Team difficulty and tactical nuance. */
    val complexityLabel: TeamComplexityLabel,
    /** One-sentence macro overview of the team's playstyle identity. */
    @field:NotBlank
    val summary: String,
    /** One or two key tactical advantages or high-performing roster attributes. */
    @field:Size(min = 1, max = 2)
    val keyStrengths: List<@NotBlank String>,
    /** One or two major vulnerabilities or common traps to avoid. */
    @field:Size(min = 1, max = 2)
    val weaknesses: List<@NotBlank String>,
    /** Concise tactical advice for how to win with the team on the pitch. */
    @field:NotBlank
    val recommendedStrategy: String,
)

/** Normalized 1–5 numerical ratings calculated from roster stats. */
data class TeamRatings(
    /** Average Movement Allowance across the roster. */
    @field:Min(1)
    @field:Max(5)
    val speed: Int,
    /** Physical blocking capacity, strength, and offensive dice output. */
    @field:Min(1)
    @field:Max(5)
    val bash: Int,
    /** Passing, catching, and weave success probability. */
    @field:Min(1)
    @field:Max(5)
    val agility: Int,
    /** Overall roster durability and resistance to injuries/knockouts. */
    @field:Min(1)
    @field:Max(5)
    val armor: Int,
)

/** Team difficulty and tactical nuance. */
enum class TeamComplexityLabel {
    Beginner,
    Intermediate,
    Advanced,
}
