package com.mhaefele.gridiron.api.integration

import com.mhaefele.gridiron.api.dto.response.MatchRosterDto
import com.mhaefele.gridiron.api.dto.response.PitchLayoutDto
import com.mhaefele.gridiron.api.integration.dto.IntegrationCreateMatchResponse
import com.mhaefele.gridiron.api.integration.dto.IntegrationMatchStateDto
import com.mhaefele.gridiron.api.mapping.toMatchRosterDto
import com.mhaefele.gridiron.api.mapping.toPitchLayoutDto
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.spring.InMemoryMatchRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Integration", description = "External JSON agents — standard GUI DTOs plus seat-private bonus hand (`IntegrationMatchSnapshotDto`, `LegalAction`). Not the packed `/api/ai` trainer.")
@RestController
@RequestMapping("/api/integration/matches")
class IntegrationMatchController(
    private val integration: IntegrationMatchService,
    private val repository: InMemoryMatchRepository,
) {
    @Operation(summary = "Create and start a match", description = "No body. Defaults: random distinct teams, agent seat HOME = TRAINER, AWAY = LOOKAHEAD computer. Returns when it is your seat's turn.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createMatch(): IntegrationCreateMatchResponse = integration.createMatch()

    @Operation(summary = "Read match state", description = "Does not apply a move. Waits until `agentSeat` must act (or the match ends). `events` is always empty — use `play` for the resolution log.")
    @GetMapping("/{matchId}/state")
    fun state(
        @PathVariable matchId: MatchId,
        @Parameter(description = "Your seat — blocks until this seat must act.") @RequestParam(defaultValue = "HOME") agentSeat: TeamSide,
    ): IntegrationMatchStateDto = integration.matchState(matchId, waitForSeat = agentSeat, viewerSeat = agentSeat)

    @Operation(
        summary = "Play one legal move",
        description = "POST a `legalActionId` from `legalActions`. Waits out the opponent, then returns the next frame. `chosenLegalActionId` and `events` (since before your pick) are set.",
    )
    @PostMapping("/{matchId}/play")
    fun play(
        @PathVariable matchId: MatchId,
        @RequestBody legalActionId: LegalActionId,
        @Parameter(description = "Your seat — blocks until this seat must act again.") @RequestParam(defaultValue = "HOME") agentSeat: TeamSide,
    ): IntegrationMatchStateDto = integration.play(matchId, legalActionId, waitForSeat = agentSeat, viewerSeat = agentSeat)

    @Operation(summary = "Roster and player cards")
    @GetMapping("/{matchId}/roster")
    fun roster(@PathVariable matchId: MatchId): MatchRosterDto = toMatchRosterDto(repository.findById(matchId))

    @Operation(summary = "Pitch terrain")
    @GetMapping("/{matchId}/pitch")
    fun pitch(@PathVariable matchId: MatchId): PitchLayoutDto = toPitchLayoutDto(repository.findById(matchId))
}
