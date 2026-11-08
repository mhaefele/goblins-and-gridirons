package com.mhaefele.gridiron.api.ai

import com.mhaefele.gridiron.api.MatchGame
import com.mhaefele.gridiron.api.ai.dto.AiScoringDeltaDto
import com.mhaefele.gridiron.api.ai.dto.nested.AiNestedStepDto
import com.mhaefele.gridiron.api.ai.dto.nested.AiNestedSubmitResultDto
import com.mhaefele.gridiron.api.ai.dto.packed.AiPackedStepDto
import com.mhaefele.gridiron.api.ai.dto.packed.AiPackedSubmitResultDto
import com.mhaefele.gridiron.api.ai.mapping.AiGameStateMapper
import com.mhaefele.gridiron.api.ai.mapping.AiPackedMapper
import com.mhaefele.gridiron.api.ai.mapping.AiStepMapper
import com.mhaefele.gridiron.api.ai.training.TrainingEpisodeEnd
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.MatchProgress
import com.mhaefele.gridiron.model.MatchState
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.spring.InMemoryMatchRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Trainer-facing views over a live match — nested vs packed step/submit types;
 * `/packed` uses [AiPackedObsDto] on [policy.obs].
 *
 * Create / start stay on `/api/matches/...`. GUI submit stays on `POST .../legal-actions/submit`.
 * Harness never opens `/events`. Steady-state loop is `POST /api/ai/matches/{id}/submit/packed`.
 *
 * [step] / [submit] require `forSeat` — the harness plays one seat (vs computer or browser human).
 */
@RestController
@RequestMapping("/api/ai/matches")
class AiMatchController(
    private val repository: InMemoryMatchRepository,
    private val matchGame: MatchGame,
    private val turnWait: AiTurnWait,
    private val submitTiming: AiSubmitTiming,
    private val gameState: AiGameStateMapper,
    private val steps: AiStepMapper,
    private val packed: AiPackedMapper,
) {
    /**
     * One gym decision: harness metadata + packable obs + legal rows for the seat that must act.
     *
     * @param perspectiveSeat “us” for relative obs; defaults to [forSeat].
     */
    @GetMapping("/{matchId}/step")
    fun step(
        @PathVariable matchId: MatchId,
        @RequestParam(required = false) perspectiveSeat: TeamSide?,
        @RequestParam forSeat: TeamSide,
    ): AiNestedStepDto {
        val waited = turnWait.untilSeatOrTerminal(matchId, forSeat)
        val perspective = perspectiveSeat ?: forSeat
        val (state, legals) = if (waited.harnessEpisodeOver()) {
            waited to emptyList()
        } else {
            val acting = waited.actingSeat()
            check(acting == forSeat) { "forSeat=$forSeat but acting=$acting after untilSeatOrTerminal" }
            val menu = matchGame.legalActions(waited)
            matchGame.ensureOpeningPassingIdsFrozen(waited, menu) to menu
        }
        return steps.toAiStepDto(state, legals, perspectiveSeat = perspective)
    }

    /** Same decision as [step], lean packed body for the Python harness. */
    @GetMapping("/{matchId}/step/packed")
    fun stepPacked(
        @PathVariable matchId: MatchId,
        @RequestParam(required = false) perspectiveSeat: TeamSide?,
        @RequestParam forSeat: TeamSide,
    ): AiPackedStepDto = packed.pack(step(matchId, perspectiveSeat, forSeat))

    /**
     * Submit one legal action and return the reward frame plus the next gym decision.
     *
     * @param forSeat seat this caller owns; blocks until that seat must act after a handoff.
     */
    @PostMapping("/{matchId}/submit")
    fun submit(
        @PathVariable matchId: MatchId,
        @RequestBody legalActionId: LegalActionId,
        @RequestParam forSeat: TeamSide,
    ): AiNestedSubmitResultDto {
        val beforeState = repository.findById(matchId)
        return applyHarnessSubmit(beforeState, legalActionId, forSeat) { it }
    }

    /** Same envelope as [submit], lean packed body for the Python harness. */
    @PostMapping("/{matchId}/submit/packed")
    fun submitPacked(
        @PathVariable matchId: MatchId,
        @RequestBody legalActionId: LegalActionId,
        @RequestParam forSeat: TeamSide,
    ): AiPackedSubmitResultDto {
        val beforeState = repository.findById(matchId)
        return applyHarnessSubmit(beforeState, legalActionId, forSeat) { packed.pack(it) }
    }

    /**
     * One-seat harness submit — waits on handoff, then builds SAME_FRAME or NEXT_FRAME envelope.
     *
     * @param encodeSubmit identity for nested routes, or [AiPackedMapper.pack] for `/packed`.
     */
    private fun <T> applyHarnessSubmit(
        beforeState: MatchState,
        legalActionId: LegalActionId,
        seat: TeamSide,
        encodeSubmit: (AiNestedSubmitResultDto) -> T,
    ): T {
        require(!beforeState.harnessEpisodeOver()) { "Cannot submit after the training episode ended" }
        val acting = beforeState.actingSeat()
        require(acting == seat) { "forSeat=$seat but acting=$acting — call GET step?forSeat= first" }
        val matchId = beforeState.id
        val timing = submitTiming.startTiming(matchId)
        val afterState = timing.engine { matchGame.submitActionOutcome(matchId, legalActionId).newState }
        val scoring = scoringDelta(beforeState.progress, afterState.progress, seat)
        // # DEVIATION: use trainingEpisodeEnded (window + LOOSE d0–d1 first-step), not window alone —
        // otherwise early-end still packs a live menu and fails "terminal step must have empty legals".
        val handedOff = !afterState.harnessEpisodeOver() && afterState.actingSeat() != seat
        val afterWait = timing.turnWait { if (handedOff) turnWait.untilSeatOrTerminal(matchId, seat) else null }
        val latest = afterWait ?: afterState
        val (stepState, nextLegals) = if (latest.harnessEpisodeOver()) {
            latest to emptyList()
        } else {
            val nextActing = latest.actingSeat()
            check(nextActing == seat) { "forSeat=$seat but acting=$nextActing after handoff wait" }
            val menu = timing.nextLegals { timer -> matchGame.legalActions(latest, timer = timer) }
            matchGame.ensureOpeningPassingIdsFrozen(latest, menu) to menu
        }
        val dto = timing.packDto { createHarnessSubmitResult(afterState = afterState, seat = seat, handedOff = afterWait != null, scoring = scoring, chosenLegalActionId = legalActionId, nextLegals = nextLegals, nextState = stepState,) }
        val result = timing.packFlat { encodeSubmit(dto) }
        timing.submit(handedOff)
        return result
    }

    /**
     * One-seat harness envelope after [applyHarnessSubmit].
     *
     * [afterSubmitRewards] = harness rewards from [afterState] when a handoff wait ran; else `null` (same frame as [rewards]).
     */
    private fun createHarnessSubmitResult(
        afterState: MatchState,
        seat: TeamSide,
        handedOff: Boolean,
        scoring: AiScoringDeltaDto,
        chosenLegalActionId: LegalActionId,
        nextLegals: List<LegalAction>,
        nextState: MatchState,
    ): AiNestedSubmitResultDto {
        val afterSubmitRewards = if (handedOff) gameState.toHarnessRewardSnapshot(afterState, seat) else null
        return AiNestedSubmitResultDto(
            chosenLegalActionId = chosenLegalActionId,
            scoring = scoring,
            afterSubmitRewards = afterSubmitRewards,
            next = steps.toAiStepDto(nextState, nextLegals, perspectiveSeat = seat),
        )
    }
}

/** Perspective-relative TD / challenge-card deltas for one submit ([seat] = “us”). */
internal fun scoringDelta(before: MatchProgress, after: MatchProgress, seat: TeamSide): AiScoringDeltaDto {
    val (tdUsBefore, tdThemBefore) = touchdownsRelative(before, seat)
    val (tdUsAfter, tdThemAfter) = touchdownsRelative(after, seat)
    val (cardsUsBefore, cardsThemBefore) = challengeCardsRelative(before, seat)
    val (cardsUsAfter, cardsThemAfter) = challengeCardsRelative(after, seat)
    return AiScoringDeltaDto(
        touchdownsUs = tdUsAfter - tdUsBefore,
        touchdownsThem = tdThemAfter - tdThemBefore,
        challengeCardsUs = cardsUsAfter - cardsUsBefore,
        challengeCardsThem = cardsThemAfter - cardsThemBefore,
    )
}

private fun touchdownsRelative(progress: MatchProgress, perspective: TeamSide): Pair<Int, Int> =
    when (perspective) {
        TeamSide.HOME -> progress.homeTouchdowns to progress.awayTouchdowns
        TeamSide.AWAY -> progress.awayTouchdowns to progress.homeTouchdowns
    }

private fun challengeCardsRelative(progress: MatchProgress, perspective: TeamSide): Pair<Int, Int> =
    when (perspective) {
        TeamSide.HOME -> progress.homeChallengeCards to progress.awayChallengeCards
        TeamSide.AWAY -> progress.awayChallengeCards to progress.homeChallengeCards
    }

/** Match over, coach-window over, or scenario early-end (e.g. LOOSE_BALL_RUN d0–d1 first step). */
private fun MatchState.harnessEpisodeOver(): Boolean =
    isGameEnded() || TrainingEpisodeEnd.trainingEpisodeEnded(this)
