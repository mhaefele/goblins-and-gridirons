package com.mhaefele.gridiron.model

import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TrainingEpisodeMode

/** Named training board setup for RL curriculum episodes. */
enum class TrainingScenarioEnum {
    /** Carrier has the ball within run range of the end zone. Score it. */
    TD_RUN,

    /** Carrier has the ball, marked from behind (farther from the scoring end zone). Sidestep open, then score. */
    MARKED_RUN,

    /** Thrower holds the ball out of run range; a teammate is in the end zone. Throw and score. */
    THROW_TO_ENDZONE,

    /**
     * Carrier within short throw range of the scoring end zone with Run already spent; hand-off/short
     * and long receivers both in the end zone (difficulty scatter stays out of end zones). Pass by
     * throwing to the closer end-zone receiver. Throws stay unflagged.
     */
    THROW_CLOSER_RANGE,

    /**
     * Carrier within short throw range of the scoring end zone with Run already spent; two Short
     * receivers in the end zone, one marked (difficulty scatter stays out of end zones). Pass by
     * throwing to the unmarked end-zone receiver. Throws unflagged.
     */
    THROW_OPEN_RECEIVER,

    /** A loose ball sits uncontested within reach of the trainer. Pick it up and run it in. */
    LOOSE_BALL_RUN,

    /** Loose ball with an adjacent opponent (bounce risk). Block the guard or score. */
    LOOSE_BALL_BOUNCE,

    /** An opponent near their end zone holds the ball; a trainer player is adjacent marking them. Strip and score. */
    STRIP_AND_SCORE,

    /**
     * Opponent carrier near **their** scoring end zone (trainer's end zone) with a trainer
     * adjacent. Pass by Blocking the carrier — deny the imminent TD (defense / left-td-open).
     */
    DENY_TD,

    /**
     * Opponent marker can uniquely Mark two trainers next turn (Lock Them Down! on the tableau).
     * Soft-mask Block that marker — exposure-only (lookahead Tries to claim Lock Them Down itself).
     */
    DENY_LOCK_THEM_DOWN,

    /**
     * Loose ball with an opposition runner adjacent (Grab It! on the tableau).
     * Soft-mask Block that runner — exposure-only until lookahead is d0–d5 reliable.
     */
    DENY_PICKUP,

    /**
     * Trainer carrier marked by an adjacent opposition Blocker (Strip It! on the tableau).
     * Pass by Sidestepping off or Blocking the threat.
     */
    DENY_STRIP_IT,

    /**
     * Trainer marked by an adjacent opposition Blocker (Drop Them! on the tableau).
     * Pass by Sidestepping off or Blocking the threat.
     */
    DENY_DROP_THEM,

    /**
     * Pre-Turn forced replace with a full tableau (random three challenge titles).
     * Exposure only — claim drills carry the graded keep-set lesson ([ai/BLINDSPOTS.md] `tableau-replace`).
     */
    CHALLENGE_REPLACE,

    /**
     * One blocker can Block two targets — one Assisted (teammate also marking), one not.
     * Pass by taking the Assisted Block.
     */
    ASSISTED_BLOCK,

    /**
     * Opens mid-Block on Second Swing keep vs re-roll. Pass by keeping a Smash pool
     * or re-rolling when the pool can still improve.
     */
    HELD_SS_REROLL,

    /**
     * Opens mid-Throw on Pinpoint Passer keep vs re-roll (failed check). Pass by re-rolling.
     */
    HELD_PP_REROLL,

    /**
     * Opens mid-Throw with Do-Over in hand on a failed check. Pass by adjusting (not declining).
     */
    HELD_DO_OVER,

    // --- Challenge claim drills (tableau pinned; sparse board; claims enabled) ------------

    /** One Run picks up a ball the team did not hold at turn start. */
    CLAIM_GRAB_IT,

    /** One Run / Mark / Sidestep ends adjacent to two teammates. */
    CLAIM_HUDDLE_UP,

    /** One Run / Throw moves team possession ≥ 4 squares further from own End Zone. */
    CLAIM_PUSH_FORWARD,

    /** One Run / Sidestep leaves no teammates adjacent. */
    CLAIM_FAN_OUT,

    /** One Action scores a Touchdown. */
    CLAIM_PLAY_TO_THE_STANDS,

    /** One successful Throw (not a Hand-off). */
    CLAIM_CONNECT,

    /** One Action strips an opposition ball carrier. */
    CLAIM_STRIP_IT,

    /** Third Run of the turn. */
    CLAIM_KEEP_RUNNING,

    /** One Action ends on a Trapdoor while opposition holds a ball. */
    CLAIM_HOLD_THE_PIT,

    /** One successful Throw with a negative throw-check modifier. */
    CLAIM_THREAD_THE_NEEDLE,

    /** Three different Players make three different Actions. */
    CLAIM_ALL_HANDS,

    /** One Player finishes a third distinct budgeted Action. */
    CLAIM_STAR_TURN,

    /** One Block knocks the target Down. */
    CLAIM_DROP_THEM,

    /** One Mark uniquely marks two unmarked opposition Players. */
    CLAIM_LOCK_THEM_DOWN,

    /** One Block / Foul injures the target. */
    CLAIM_SEND_THEM_OFF,

    /** Send Them Off via Foul — only the prone opposition player is breakable. */
    CLAIM_SEND_THEM_OFF_FOUL,

    /** Claim three active challenges in one turn (Clean Sweep +2). */
    CLAIM_CLEAN_SWEEP,
}

/** Frozen curriculum metadata for a training scenario episode — null on regular matches. */
data class TrainingEpisodeConfig(
    val scenario: TrainingScenarioEnum,
    /** Extra reserves scattered after scripted pieces (0–5). */
    val difficulty: Int,
    /** Side the trainer controls — used for scenario validation. */
    val trainerSide: TeamSide,
    /** Reserved — currently unused (full engine legals always). */
    val episodeMode: TrainingEpisodeMode = TrainingEpisodeMode.TRAIN,
    /**
     * [MatchProgress.turnNumber] when the episode opens. Claim drills bump past Coach A's
     * turn-1 claim gate while still treating this turn as the opening decision.
     */
    val openingTurnNumber: Int = 1,
    /**
     * Sticky drill grade. Scenarios are only asked while [TrainingDrillStatus.STILL_IN_PROGRESS];
     * [TrainingDrillStatus.PASS] and [TrainingDrillStatus.DISTRACTED] stick for the rest of the episode.
     */
    val drillStatus: TrainingDrillStatus = TrainingDrillStatus.STILL_IN_PROGRESS,
    /** Trainer score when the episode opens — always zero on a fresh scenario board. */
    val openingTurnStartScoreUs: Int = 0,
    /** Trainer score when [openingTurnNumber] ends; set when [MatchProgress.turnNumber] advances past it. */
    val openingTurnEndScoreUs: Int? = null,
    /**
     * Player who must finish three distinct budgeted actions for [TrainingScenarioEnum.CLAIM_STAR_TURN].
     * Set at placement time so validation and grading know which reserve was cast as the star.
     */
    val starTurnStarId: PlayerId? = null,
) {
    init {
        require(difficulty in 0..5) { "difficulty must be 0–5, was $difficulty" }
        require(openingTurnNumber >= 1) { "openingTurnNumber must be ≥ 1, was $openingTurnNumber" }
    }
}
