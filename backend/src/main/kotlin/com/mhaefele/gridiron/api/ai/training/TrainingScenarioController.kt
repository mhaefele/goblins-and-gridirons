package com.mhaefele.gridiron.api.ai.training

import com.mhaefele.gridiron.api.MatchGame
import com.mhaefele.gridiron.api.dto.response.MatchSnapshotDto
import com.mhaefele.gridiron.api.mapping.toMatchSnapshotDto
import com.mhaefele.gridiron.game.opponent.ComputerCoachRegistry
import com.mhaefele.gridiron.model.TrainingScenarioEnum
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.ComputerOpponentKind
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TrainingEpisodeMode
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Creates training scenario matches already opened on the first trainer decision.
 *
 * Request is slim: scenario knobs + AWAY opponent. HOME is always TRAINER; teams/pitch are server-chosen
 * (trainer team via the scenario's [AbstractTrainingScenario.trainerTeamFilter]).
 */
@RestController
@RequestMapping("/api/ai/training/matches")
class TrainingScenarioController(
    private val matchGame: MatchGame,
    private val trainingScenarios: TrainingScenarios,
    private val computerCoachRegistry: ComputerCoachRegistry,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createScenarioMatch(@Valid @RequestBody request: CreateScenarioMatchRequest): ScenarioMatchResponse {
        require(request.awayControlledBy == CoachControl.COMPUTER || request.awayControlledBy == CoachControl.HUMAN) { "awayControlledBy must be COMPUTER or HUMAN(HOME is always TRAINER)" }
        require(request.difficulty in 0..5) { "difficulty must be 0–5, was ${request.difficulty}" }

        val state = matchGame.createScenarioMatch(
            scenario = request.scenario,
            difficulty = request.difficulty,
            episodeMode = request.episodeMode,
            awayControlledBy = request.awayControlledBy,
        )
        computerCoachRegistry.spawnForMatch(state.id, request.computerOpponent)

        return ScenarioMatchResponse(
            matchId = state.id,
            scenario = request.scenario,
            snapshot = toMatchSnapshotDto(state),
        )
    }
}

/**
 * Slim create body. No Kotlin defaults — callers (Python / tests) always pass every field.
 * HOME is always TRAINER; teams and pitch are chosen on the server.
 */
data class CreateScenarioMatchRequest(
    @field:NotNull
    val scenario: TrainingScenarioEnum,
    val difficulty: Int,
    @field:NotNull
    val episodeMode: TrainingEpisodeMode,
    @field:NotNull
    val awayControlledBy: CoachControl,
    @field:NotNull
    val computerOpponent: ComputerOpponentKind,
)

data class ScenarioMatchResponse(
    val matchId: MatchId,
    val scenario: TrainingScenarioEnum,
    val snapshot: MatchSnapshotDto,
)

@RestController
@RequestMapping("/api/ai/training/scenarios")
class TrainingScenariosController(
    private val trainingScenarios: TrainingScenarios,
) {

    @GetMapping
    fun listScenarios(): List<TrainingScenarioCatalogEntry> = trainingScenarios.catalogEntries()
}
