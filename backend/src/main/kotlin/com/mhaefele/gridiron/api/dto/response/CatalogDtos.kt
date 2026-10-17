package com.mhaefele.gridiron.api.dto.response

import com.mhaefele.gridiron.api.dto.request.CreateMatchRequest

import com.mhaefele.gridiron.model.catalog.PitchDefinitionId
import com.mhaefele.gridiron.model.catalog.TeamCatalogId
import com.mhaefele.gridiron.model.catalog.TeamSummary

/** Available team cards for [CreateMatchRequest]. */
data class TeamCatalogEntry(
    val id: TeamCatalogId,
    val name: String,
    /** Full card roster including duplicate position slots (e.g. three linemen). */
    val roster: List<PlayerCardDto>,
    /** Coach-facing playstyle blurb from `team-summaries.json`. */
    val summary: TeamSummary,
)

data class TeamCatalogDto(
    val teams: List<TeamCatalogEntry>,
)

/** Available pitch layouts for [CreateMatchRequest]. */
data class PitchCatalogEntry(
    val id: PitchDefinitionId,
    val name: String,
)

data class PitchCatalogDto(
    val pitches: List<PitchCatalogEntry>,
)
