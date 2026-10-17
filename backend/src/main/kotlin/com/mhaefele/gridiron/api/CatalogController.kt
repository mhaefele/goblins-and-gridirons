package com.mhaefele.gridiron.api

import com.mhaefele.gridiron.api.dto.response.PitchCatalogEntry
import com.mhaefele.gridiron.api.dto.response.PitchCatalogDto
import com.mhaefele.gridiron.api.dto.response.TeamCatalogEntry
import com.mhaefele.gridiron.api.dto.response.TeamCatalogDto
import com.mhaefele.gridiron.api.mapping.toPlayerCardDto
import com.mhaefele.gridiron.model.catalog.PitchCatalog
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.model.catalog.TeamSummaryCatalog
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/catalog")
class CatalogController {
    @GetMapping("/teams")
    fun teams(): TeamCatalogDto =
        TeamCatalogDto(
            teams = TeamCatalog.allTeams().map { team ->
                TeamCatalogEntry(
                    id = team.id,
                    name = team.name,
                    roster = team.roster.map { profile -> toPlayerCardDto(profile, team.id) },
                    summary = TeamSummaryCatalog.summary(team.id),
                )
            },
        )

    @GetMapping("/pitches")
    fun pitches(): PitchCatalogDto =
        PitchCatalogDto(
            pitches = PitchCatalog.allDefinitions().map { (id, name) ->
                PitchCatalogEntry(id = id, name = name)
            },
        )
}
