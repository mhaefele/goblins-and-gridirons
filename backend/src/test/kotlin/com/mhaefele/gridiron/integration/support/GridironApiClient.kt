package com.mhaefele.gridiron.integration.support

import com.mhaefele.gridiron.api.ai.training.CreateScenarioMatchRequest
import com.mhaefele.gridiron.api.ai.training.ScenarioMatchResponse
import com.mhaefele.gridiron.api.ai.training.TrainingScenarioCatalogEntry
import com.mhaefele.gridiron.api.ai.dto.nested.AiNestedStepDto
import com.mhaefele.gridiron.api.ai.dto.nested.AiNestedSubmitResultDto
import com.mhaefele.gridiron.api.ai.dto.packed.AiPackedStepDto
import com.mhaefele.gridiron.api.ai.dto.packed.AiPackedSubmitResultDto
import com.mhaefele.gridiron.api.integration.dto.IntegrationCreateMatchResponse
import com.mhaefele.gridiron.api.integration.dto.IntegrationMatchStateDto
import com.mhaefele.gridiron.api.dto.request.CreateMatchRequest
import com.mhaefele.gridiron.api.dto.response.BonusHandDto
import com.mhaefele.gridiron.api.dto.response.CreateMatchResponse
import com.mhaefele.gridiron.api.dto.response.MatchGameStateDto
import com.mhaefele.gridiron.api.dto.response.MatchActionResponse
import com.mhaefele.gridiron.api.dto.response.MatchRosterDto
import com.mhaefele.gridiron.api.dto.response.MatchSnapshotDto
import com.mhaefele.gridiron.api.dto.response.PieceSnapshotDto
import com.mhaefele.gridiron.api.dto.response.PitchLayoutDto
import com.mhaefele.gridiron.api.dto.response.PitchCatalogDto
import com.mhaefele.gridiron.api.dto.response.TeamCatalogDto
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.action
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestTemplate
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper

/**
 * Typed HTTP client for API tests — REST is transport; tests work with Kotlin objects.
 *
 * Uses plain [RestTemplate] (throws on 4xx/5xx), not fault-tolerant [TestRestTemplate].
 */
class GridironApiClient(
    private val restTemplate: RestTemplate,
    private val jsonMapper: JsonMapper,
) {
    fun catalogTeams(): TeamCatalogDto = get("/api/catalog/teams")

    fun catalogPitches(): PitchCatalogDto = get("/api/catalog/pitches")

    fun defaultCreateMatchRequest(): CreateMatchRequest {
        val teams = catalogTeams().teams
        require(teams.size >= 2) { "Catalog must list at least two teams" }
        return CreateMatchRequest(
            homeTeamId = teams[0].id,
            awayTeamId = teams[1].id,
        )
    }

    /** Create only — match stays in [com.mhaefele.gridiron.shared.TurnPhase.GAME_START] until [startMatch]. */
    fun createMatch(request: CreateMatchRequest): CreateMatchResponse = post("/api/matches", request)

    fun startMatch(matchId: MatchId): MatchActionResponse =
        post("/api/matches/${matchId.value}/start")

    fun startMatch(match: CreateMatchResponse): MatchActionResponse = startMatch(match.matchId)

    fun deleteMatch(matchId: MatchId) {
        restTemplate.exchange(
            "/api/matches/${matchId.value}",
            HttpMethod.DELETE,
            null,
            Void::class.java,
        )
    }

    fun deleteMatch(match: CreateMatchResponse) = deleteMatch(match.matchId)

    /**
     * Create then start — lands in piece placement.
     * Production clients call [createMatch] + SSE subscribe + [startMatch] separately.
     */
    fun createAndStartMatch(request: CreateMatchRequest): CreateMatchResponse {
        val created = createMatch(request)
        val started = startMatch(created.matchId)
        return CreateMatchResponse(created.matchId, started.snapshot)
    }

    fun listTrainingScenarios(): List<TrainingScenarioCatalogEntry> = get("/api/ai/training/scenarios")

    fun createScenarioMatch(request: CreateScenarioMatchRequest): ScenarioMatchResponse =
        post("/api/ai/training/matches", request)

    fun snapshot(matchId: MatchId): MatchSnapshotDto = get("/api/matches/${matchId.value}/snapshot")

    fun snapshot(match: MatchState): MatchSnapshotDto = snapshot(match.id)

    fun snapshot(match: CreateMatchResponse): MatchSnapshotDto = snapshot(match.matchId)

    fun pieces(matchId: MatchId): PieceSnapshotDto = snapshot(matchId).pieces

    fun pieces(match: MatchState): PieceSnapshotDto = pieces(match.id)

    fun pieces(match: CreateMatchResponse): PieceSnapshotDto = pieces(match.matchId)

    fun pitch(matchId: MatchId): PitchLayoutDto = get("/api/matches/${matchId.value}/pitch")

    fun pitch(match: MatchState): PitchLayoutDto = pitch(match.id)

    fun pitch(match: CreateMatchResponse): PitchLayoutDto = pitch(match.matchId)

    fun roster(matchId: MatchId): MatchRosterDto = get("/api/matches/${matchId.value}/roster")

    fun roster(match: MatchState): MatchRosterDto = roster(match.id)

    fun roster(match: CreateMatchResponse): MatchRosterDto = roster(match.matchId)

    fun gameState(matchId: MatchId): MatchGameStateDto = snapshot(matchId).gameState

    fun gameState(match: MatchState): MatchGameStateDto = gameState(match.id)

    fun gameState(match: CreateMatchResponse): MatchGameStateDto = gameState(match.matchId)

    /** Seat-private bonus hand — never on the shared snapshot. */
    fun bonusHand(matchId: MatchId, seat: TeamSide): BonusHandDto =
        get("/api/matches/${matchId.value}/bonus-hand?seat=${seat.name}")

    fun legalActions(matchId: MatchId): List<LegalAction> = fetchLegalActions(matchId)

    fun legalActions(matchId: MatchId, actionType: ActionType): List<LegalAction> =
        fetchLegalActions(matchId).filter { it.actionType == actionType }

    /** Typed legal actions — e.g. `legalActions<LegalAction.Run>(match)`. */
    @JvmName("legalActionsTyped")
    inline fun <reified T : LegalAction> legalActions(matchId: MatchId): List<T> =
        fetchLegalActions(matchId).filterIsInstance<T>()

    fun legalActions(match: MatchState): List<LegalAction> = fetchLegalActions(match.id)

    fun legalActions(match: MatchState, actionType: ActionType): List<LegalAction> =
        legalActions(match.id, actionType)

    @JvmName("legalActionsTypedMatch")
    inline fun <reified T : LegalAction> legalActions(match: MatchState): List<T> =
        fetchLegalActions(match.id).filterIsInstance<T>()

    fun legalActions(match: CreateMatchResponse): List<LegalAction> = fetchLegalActions(match.matchId)

    fun legalActions(match: CreateMatchResponse, actionType: ActionType): List<LegalAction> =
        legalActions(match.matchId, actionType)

    @JvmName("legalActionsTypedCreated")
    inline fun <reified T : LegalAction> legalActions(match: CreateMatchResponse): List<T> =
        fetchLegalActions(match.matchId).filterIsInstance<T>()

    @PublishedApi
    internal fun fetchLegalActions(matchId: MatchId): List<LegalAction> =
        get("/api/matches/${matchId.value}/legal-actions")

    fun legalActionsForPlayer(matchId: MatchId, player: PlacedPlayer, actionType: ActionType? = null) = legalActionsForPlayer(matchId, player.player, actionType)

    fun legalActionsForPlayer(matchId: MatchId, player: Player, actionType: ActionType? = null)
    = legalActions(matchId)
        .filterIsInstance<LegalAction.PlayerLegalAction>()
        .filter { it.playerId == player.id }
        .filter { actionType == null || it.actionType == actionType }

    fun play(matchId: MatchId, legalActionId: LegalActionId): MatchActionResponse =
        post("/api/matches/${matchId.value}/legal-actions/submit", legalActionId)

    fun play(matchId: MatchId, action: LegalAction): MatchActionResponse = play(matchId, action.id)

    fun play(
        matchId: MatchId,
        player: PlacedPlayer,
        type: ActionType,
        target: Coordinate,
    ): MatchActionResponse = play(matchId, legalActions(matchId).action(player, type, target))

    fun play(
        matchId: MatchId,
        player: PlacedPlayer,
        type: ActionType,
        x: Int,
        y: Int,
    ): MatchActionResponse = play(matchId, player, type, Coordinate(x, y))

    fun play(match: MatchState, action: LegalAction): MatchActionResponse = play(match.id, action)

    fun play(match: CreateMatchResponse, action: LegalAction): MatchActionResponse = play(match.matchId, action)

    /** AI trainer step — obs + factored legals. */
    fun aiStep(
        matchId: MatchId,
        perspectiveSeat: TeamSide? = null,
        forSeat: TeamSide = TeamSide.HOME,
    ): AiNestedStepDto {
        val qs = aiQuery(perspectiveSeat = perspectiveSeat, forSeat = forSeat)
        return get("/api/ai/matches/${matchId.value}/step$qs")
    }

    fun aiStep(match: CreateMatchResponse, perspectiveSeat: TeamSide? = null, forSeat: TeamSide = TeamSide.HOME) =
        aiStep(match.matchId, perspectiveSeat, forSeat)

    fun aiStep(match: MatchApi, perspectiveSeat: TeamSide? = null, forSeat: TeamSide = TeamSide.HOME) =
        aiStep(match.matchId, perspectiveSeat, forSeat)

    /** Sanity / parity only — training harness uses this path; functional tests stay on [aiStep]. */
    fun aiStepPacked(
        matchId: MatchId,
        perspectiveSeat: TeamSide? = null,
        forSeat: TeamSide = TeamSide.HOME,
    ): AiPackedStepDto {
        val qs = aiQuery(perspectiveSeat = perspectiveSeat, forSeat = forSeat)
        return get("/api/ai/matches/${matchId.value}/step/packed$qs")
    }

    fun aiSubmit(matchId: MatchId, legalActionId: LegalActionId, forSeat: TeamSide = TeamSide.HOME): AiNestedSubmitResultDto {
        val qs = aiQuery(forSeat = forSeat)
        return post("/api/ai/matches/${matchId.value}/submit$qs", legalActionId)
    }

    fun aiSubmit(match: CreateMatchResponse, legalActionId: LegalActionId, forSeat: TeamSide = TeamSide.HOME) =
        aiSubmit(match.matchId, legalActionId, forSeat)

    fun aiSubmit(match: MatchApi, legalActionId: LegalActionId, forSeat: TeamSide = TeamSide.HOME) =
        aiSubmit(match.matchId, legalActionId, forSeat)

    /** Sanity / parity only — training harness uses this path; functional tests stay on [aiSubmit]. */
    fun aiSubmitPacked(matchId: MatchId, legalActionId: LegalActionId, forSeat: TeamSide = TeamSide.HOME): AiPackedSubmitResultDto {
        val qs = aiQuery(forSeat = forSeat)
        return post("/api/ai/matches/${matchId.value}/submit/packed$qs", legalActionId)
    }

    fun integrationCreateMatch(): IntegrationCreateMatchResponse =
        post("/api/integration/matches")

    fun integrationState(
        matchId: MatchId,
        agentSeat: TeamSide = TeamSide.HOME,
    ): IntegrationMatchStateDto = get("/api/integration/matches/${matchId.value}/state?agentSeat=${agentSeat.name}")

    fun integrationState(match: CreateMatchResponse, agentSeat: TeamSide = TeamSide.HOME) =
        integrationState(match.matchId, agentSeat)

    fun integrationState(match: MatchApi, agentSeat: TeamSide = TeamSide.HOME) =
        integrationState(match.matchId, agentSeat)

    fun integrationPlay(
        matchId: MatchId,
        legalActionId: LegalActionId,
        agentSeat: TeamSide = TeamSide.HOME,
    ): IntegrationMatchStateDto =
        post("/api/integration/matches/${matchId.value}/play?agentSeat=${agentSeat.name}", legalActionId)

    fun integrationPlay(match: CreateMatchResponse, legalActionId: LegalActionId, agentSeat: TeamSide = TeamSide.HOME) =
        integrationPlay(match.matchId, legalActionId, agentSeat)

    fun integrationPlay(match: MatchApi, legalActionId: LegalActionId, agentSeat: TeamSide = TeamSide.HOME) =
        integrationPlay(match.matchId, legalActionId, agentSeat)

    private fun aiQuery(
        perspectiveSeat: TeamSide? = null,
        forSeat: TeamSide? = null,
    ): String {
        val parts = buildList {
            if (perspectiveSeat != null) add("perspectiveSeat=${perspectiveSeat.name}")
            if (forSeat != null) add("forSeat=${forSeat.name}")
        }
        return if (parts.isEmpty()) "" else "?${parts.joinToString("&")}"
    }

    internal inline fun <reified T : Any> errorBody(ex: HttpStatusCodeException): T? =
        ex.responseBodyAsString.takeIf { it.isNotBlank() }?.let { jsonMapper.readValue(it, jacksonTypeRef<T>()) }

    private fun playerActionPath(matchId: MatchId, playerId: PlayerId, action: String): String =
        "/api/matches/${matchId.value}/players/${playerId.value}/$action"

    private inline fun <reified T : Any> get(path: String): T =
        restTemplate.exchange(path, HttpMethod.GET, null, typeRef<T>()).body ?: error("Expected response body for GET $path")

    private inline fun <reified T : Any> post(path: String, body: Any? = null): T =
        restTemplate.exchange(
            path,
            HttpMethod.POST,
            body?.let { HttpEntity(it) },
            typeRef<T>(),
        ).body ?: error("Expected response body for POST $path")

    private inline fun <reified T> typeRef(): ParameterizedTypeReference<T> =
        object : ParameterizedTypeReference<T>() {}

    private inline fun <reified T> jacksonTypeRef(): TypeReference<T> =
        object : TypeReference<T>() {}
}
