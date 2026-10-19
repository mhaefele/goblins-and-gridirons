package com.mhaefele.gridiron.scenario

import com.mhaefele.gridiron.game.action.dto.StoredDomainEvent
import com.mhaefele.gridiron.support.bareEvents
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.model.catalog.PitchDefinitionId
import com.mhaefele.gridiron.shared.TurnPhase
import com.mhaefele.gridiron.api.dto.response.LegalAction

import com.mhaefele.gridiron.game.board.renderBoardForDisplay
import com.mhaefele.gridiron.model.catalog.PitchCatalog
import com.mhaefele.gridiron.game.opponent.scripted.ScriptedOpponentStrategy
import com.mhaefele.gridiron.integration.support.GridironApiClient
import com.mhaefele.gridiron.shared.MatchId

/**
 * Living game script over the HTTP API — poll loop (legal-actions → play → reload).
 *
 * Prefer create-match with [com.mhaefele.gridiron.shared.CoachControl.COMPUTER] seats
 * (see [com.mhaefele.gridiron.integration.playthrough.LivingGamePlaythroughTest]). This harness remains for single-threaded stepping / debugging.
 */
class LivingGamePlaythrough(
    private val api: GridironApiClient,
    private val loadMatch: (MatchId) -> MatchState,
    private val moveChooser: ScriptedOpponentStrategy = ScriptedOpponentStrategy(),
) {
    lateinit var match: MatchState
        private set

    private lateinit var matchId: MatchId

    fun startMatch(pitchDefinitionId: PitchDefinitionId = PitchCatalog.DEFAULT_ID) {
        val createResponse = api.createAndStartMatch(api.defaultCreateMatchRequest().copy(pitchDefinitionId = pitchDefinitionId))
        matchId = createResponse.matchId
        match = loadMatch(matchId)
        log("New match via createAndStartMatch (pitch=${pitchDefinitionId.value})")
        showState()
    }

    fun startBarricadeMatch() = startMatch(PitchCatalog.BARRICADE_ID)

    fun legalActions(): List<LegalAction> = api.legalActions(match)

    fun play(action: LegalAction) {
        log(describe(action))
        val response = api.play(match, action)
        match = loadMatch(matchId)
        showEvents(response.events)
        showState()
    }

    fun playCurrentActivation() {
        val activationStart = System.nanoTime()
        var actionsThisActivation = 0
        while (
            match.progress.result == null &&
            match.currentTurnState.phase == TurnPhase.PLAYER_ACTION &&
            match.currentTurnState.actionsRemaining > 0
        ) {
            actionsThisActivation++

            val legal = timed("GET legal-actions", suffix = { " (${it.size} legal moves)" }) { legalActions() }
            if (legal.isEmpty()) {
                log("No legal actions — stopping activation early")
                break
            }

            val chosen = timed("choose") { moveChooser.choose(match, legal) }
            log(describe(chosen))

            val response = timed("POST play") { api.play(match, chosen) }
            match = loadMatch(matchId)
            showEvents(response.events)
            showState()
        }
        val activationMs = (System.nanoTime() - activationStart) / 1_000_000.0
        println("  ⏱ activation total: ${"%.0f".format(activationMs)}ms ($actionsThisActivation actions)")
    }

    fun playCoachActivations(count: Int) {
        repeat(count) { activation ->
            if (match.progress.result != null) return
            log("Coach activation ${activation + 1} — ${match.currentTurnState.activeCoach}")
            playCurrentActivation()
        }
    }

    fun log(step: String) {
        println()
        println("=== $step ===")
    }

    private inline fun <T> timed(label: String, suffix: (T) -> String = { "" }, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - start) / 1_000_000.0
        println("  ⏱ $label: ${"%.0f".format(ms)}ms${suffix(result)}")
        return result
    }

    private fun showState() {
        val turn = match.currentTurnState
        println(
            "Turn ${match.progress.turnNumber} | ${turn.activeCoach} | " + "phase=${turn.phase} actions=${turn.actionsRemaining} | " + "score HOME ${match.progress.homeScore.points} — AWAY ${match.progress.awayScore.points}",)
        println(renderBoardForDisplay(match.board))
    }

    private fun showEvents(events: List<StoredDomainEvent>) {
        if (events.isEmpty()) return
        println("Events:")
        events.bareEvents().forEach { println(formatDomainEvent(it)) }
    }

    private fun describe(action: LegalAction): String =
        when (action) {
            is LegalAction.Run                  -> "RUN ${action.playerId.value} (${action.path.squares.size} squares)"
            is LegalAction.DeclareRun           -> "DECLARE RUN ${action.playerId.value} (roll Move)"
            is LegalAction.Mark                 -> "MARK ${action.playerId.value} (${action.path.squares.size} squares)"
            is LegalAction.Sidestep             -> "SIDESTEP ${action.playerId.value}"
            is LegalAction.Throw                -> "THROW ${action.playerId.value} → ${action.target.destination.display()}"
            is LegalAction.ThrowTeammateSelect  -> "THROW TEAMMATE SELECT ${action.playerId.value} → ${action.teammateId.value}"
            is LegalAction.ThrowTeammate        -> "THROW TEAMMATE ${action.playerId.value} ${action.teammateId.value} → ${action.target.destination.display()}"
            is LegalAction.CancelThrowTeammate  -> "CANCEL THROW TEAMMATE ${action.playerId.value}"
            is LegalAction.Block                -> "BLOCK ${action.playerId.value} → ${action.destination.display()}"
            is LegalAction.Foul                 -> "FOUL ${action.playerId.value} → ${action.destination.display()}"
            is LegalAction.StandUp              -> "STAND UP ${action.playerId.value}"
            is LegalAction.Reserves             -> "RESERVES ${action.playerId.value} → ${action.placement.display()}"
            is LegalAction.Skip                 -> "SKIP ${action.playerId.value}"
            is LegalAction.DeclineBonus         -> "DECLINE BONUS"
            is LegalAction.PlaceLooseBall       -> "PLACE BALL ${action.playerId.value} ${action.from.display()} → ${action.to.display()}"
            is LegalAction.ChooseBlockReroll    -> "BLOCK REROLL ${action.playerId.value} reroll=${action.reroll}"
            is LegalAction.ChooseBlockFaceOrder -> "BLOCK FACE ORDER ${action.playerId.value} ${action.orderedFaces.joinToString("-")}"
            is LegalAction.ChooseThrowReroll    -> "THROW REROLL ${action.playerId.value} reroll=${action.reroll}"
            is LegalAction.ContinueBlock        -> "CONTINUE BLOCK ${action.playerId.value} → ${action.destination.display()}"
            is LegalAction.QuickReleaseThrow    -> "QUICK RELEASE THROW ${action.playerId.value} → ${action.target.destination.display()}"
            is LegalAction.PlayBonusCard        -> "PLAY ${action.bonusPlay})"
            is LegalAction.AdjustThrowByDoOver  -> "ADJUST THROW BY DO-OVER ${action.playerId.value}"
            is LegalAction.AdjustBlockByDoOver  -> "ADJUST BLOCK BY DO-OVER ${action.playerId.value}"
            is LegalAction.AdjustArmourByDoOver -> "ADJUST ARMOUR BY DO-OVER ${action.targetId.value}"
            is LegalAction.ClaimChallenge       -> "CLAIM CHALLENGE slot ${action.slotIndex} ${action.challengeTitle}(+${action.points})"
            is LegalAction.ReplaceChallenge     -> "REPLACE CHALLENGE slot ${action.slotIndex} ${action.challengeTitle}"
        }
    }
