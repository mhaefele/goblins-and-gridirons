package com.mhaefele.gridiron.game.action.dto

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.game.dice.dto.FoulResult
import com.mhaefele.gridiron.model.pending.PlayerDecisionType
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.shared.*
import io.swagger.v3.oas.annotations.media.Schema

/** How a match ended — wire mirror of [com.mhaefele.gridiron.model.MatchResult] kinds. */
enum class MatchEndKind {
    suddenDeath,
    pointsVictory,
}

/** Why a die was rolled — used by [DomainEvent.DiceRolled] for GUI labels and replay. */
enum class DiceRollPurpose {
    BOUNCE,
    THROW,
    BLOCK,
    /** Single Block Die reinterpreted as Foul results (digest § Foul). */
    FOUL,
    ARMOUR,
    /** Violent: D6 rolled with the Block Dice; 6 auto-injures without resolving block dice. */
    VIOLENT,
    /** Random Move Value: D6 rolled at the start of a Run (Rattle Cart, etc.). */
    MOVE,
}

/** Pass/fail for D6 threshold checks (throw, armour). */
enum class DiceCheckResult {
    PASS,
    FAIL,
    FUMBLE,
}

/**
 * Ordered audit log for one action resolution. Denormalized on purpose: a GUI can replay beats
 * in sequence, a board maintainer can apply each event without diffing, and undo can reverse
 * physical events using explicit from/to squares and [playerAt] where relevant.
 *
 * [turnPhase] / [turnStep] are stamped at [com.mhaefele.gridiron.game.turn.MatchMutator] /
 * [com.mhaefele.gridiron.game.board.dto.BoardMutator] record time — not set at construction.
 * They live on the sealed class body so data-class equals/hashCode ignore them (tests compare
 * payloads only). Named [turnPhase] to avoid clashing with [PhaseStarted.phase] / [MatchEnded.phase].
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = DomainEvent.ActionStarted::class, name = "actionStarted"),
    JsonSubTypes.Type(value = DomainEvent.ActionComplete::class, name = "actionComplete"),
    JsonSubTypes.Type(value = DomainEvent.DiceRolled::class, name = "diceRolled"),
    JsonSubTypes.Type(value = DomainEvent.PlayerMoved::class, name = "playerMoved"),
    JsonSubTypes.Type(value = DomainEvent.PlayerStatusChanged::class, name = "playerStatusChanged"),
    JsonSubTypes.Type(value = DomainEvent.PlayerDeployedFromReserves::class, name = "playerDeployedFromReserves"),
    JsonSubTypes.Type(value = DomainEvent.PlayerInjured::class, name = "playerInjured"),
    JsonSubTypes.Type(value = DomainEvent.PlayerRemovedAfterTouchdown::class, name = "playerRemovedAfterTouchdown"),
    JsonSubTypes.Type(value = DomainEvent.PlayerPlacedOnPitch::class, name = "playerPlacedOnPitch"),
    JsonSubTypes.Type(value = DomainEvent.BallPickedUp::class, name = "ballPickedUp"),
    JsonSubTypes.Type(value = DomainEvent.BallDropped::class, name = "ballDropped"),
    JsonSubTypes.Type(value = DomainEvent.BallThrown::class, name = "ballThrown"),
    JsonSubTypes.Type(value = DomainEvent.BallThrowMissed::class, name = "ballThrowMissed"),
    JsonSubTypes.Type(value = DomainEvent.BallBounced::class, name = "ballBounced"),
    JsonSubTypes.Type(value = DomainEvent.BallPlacedLoose::class, name = "ballPlacedLoose"),
    JsonSubTypes.Type(value = DomainEvent.BallRemovedFromPlay::class, name = "ballRemovedFromPlay"),
    JsonSubTypes.Type(value = DomainEvent.TouchdownScored::class, name = "touchdownScored"),
    JsonSubTypes.Type(value = DomainEvent.ChallengeTableauRefreshed::class, name = "challengeTableauRefreshed"),
    JsonSubTypes.Type(value = DomainEvent.ChallengeCardReplaced::class, name = "challengeCardReplaced"),
    JsonSubTypes.Type(value = DomainEvent.ChallengeCardClaimed::class, name = "challengeCardClaimed"),
    JsonSubTypes.Type(value = DomainEvent.CleanSweep::class, name = "cleanSweep"),
    JsonSubTypes.Type(value = DomainEvent.ChallengeHandTrimmed::class, name = "challengeHandTrimmed"),
    JsonSubTypes.Type(value = DomainEvent.BonusPlayed::class, name = "bonusPlayed"),
    JsonSubTypes.Type(value = DomainEvent.BonusReturned::class, name = "bonusReturned"),
    JsonSubTypes.Type(value = DomainEvent.PointsVictoryArmed::class, name = "pointsVictoryArmed"),
    JsonSubTypes.Type(value = DomainEvent.PointsVictoryCountdown::class, name = "pointsVictoryCountdown"),
    JsonSubTypes.Type(value = DomainEvent.PlayerTurnEnded::class, name = "playerTurnEnded"),
    JsonSubTypes.Type(value = DomainEvent.TeamActionConsumed::class, name = "teamActionConsumed"),
    JsonSubTypes.Type(value = DomainEvent.PhaseStarted::class, name = "phaseStarted"),
    JsonSubTypes.Type(value = DomainEvent.MatchEnded::class, name = "matchEnded"),
    JsonSubTypes.Type(value = DomainEvent.InputRequired.PlayerAction::class, name = "playerActionRequired"),
)
@Schema(description = "One recorded game event — discriminated by `kind` (moves, dice, scores, phase changes, …).")
sealed class DomainEvent {
    /** Turn phase when this event was recorded; null if recorded outside a [MatchMutator]. */
    var turnPhase: TurnPhase? = null

    /** Phase step when this event was recorded; null when the phase has no step or outside a [MatchMutator]. */
    var turnStep: PhaseStep? = null

    /** Opens a resolution bracket — all following events until [ActionComplete] belong to this action. */
    data class ActionStarted(
        val playerId: PlayerId,
        /** Null when the player was in the dugout before this action (e.g. reserves deploy). */
        val playerAt: Coordinate?,
        val actionType: ActionType,
    ) : DomainEvent()

    /** Closes the resolution bracket opened by [ActionStarted]. */
    data class ActionComplete(
        val playerId: PlayerId,
        /** Null when the player is not on the pitch after resolution (e.g. removed to dugout). */
        val playerAt: Coordinate?,
        val actionType: ActionType,
    ) : DomainEvent()

    data class DiceRolled(
        val purpose: DiceRollPurpose,
        /** Natural D6 result; null when this roll used another die type. */
        val d6: Int? = null,
        /** Natural D8 result; null when this roll used another die type. */
        val d8: Int? = null,
        /** Minimum roll to pass; null when this roll is not a threshold check. */
        val threshold: Int? = null,
        /** Applied modifier (clamped ±1); null when this roll is not a threshold check. */
        val modifier: Int? = null,
        /** Pass/fail/fumble; null when this roll is not a threshold check. */
        val checkResult: DiceCheckResult? = null,
        /** Block pool faces; null when this roll is not a block. */
        val blockFaces: List<BlockDieFace>? = null,
        /** Chosen block face; null when this roll is not a block or faces are not yet chosen. */
        val blockResult: BlockDieFace? = null,
        /** Foul chart result; null when this roll is not a foul. */
        val foulResult: FoulResult? = null,
        /** Ability that caused/modified this roll; null when untagged. */
        val ability: PlayerAbility? = null,
        /** Bonus play that caused/modified this roll; null when untagged. */
        val bonusPlay: BonusPlay? = null,
    ) : DomainEvent()

    data class PlayerMoved(
        val playerId: PlayerId,
        val from: Coordinate,
        val to: Coordinate,
        /** Ability/card that caused this move when tagged (e.g. Stick Close); null when untagged. */
        val ability: PlayerAbility? = null,
        /** Bonus play that caused this move when tagged; null when untagged. */
        val bonusPlay: BonusPlay? = null,
    ) : DomainEvent()

    data class PlayerStatusChanged(
        val playerId: PlayerId,
        val playerAt: Coordinate,
        val from: PlayerStatus,
        val to: PlayerStatus,
    ) : DomainEvent()

    data class PlayerDeployedFromReserves(
        val playerId: PlayerId,
        val to: Coordinate,
    ) : DomainEvent()

    /** Failed armour or trapdoor injury — sent to dugout. */
    data class PlayerInjured(
        val playerId: PlayerId,
        val from: Coordinate,
    ) : DomainEvent()

    /** Scorer returns to dugout after a touchdown. */
    data class PlayerRemovedAfterTouchdown(
        val playerId: PlayerId,
        val from: Coordinate,
    ) : DomainEvent()

    data class PlayerPlacedOnPitch(
        val playerId: PlayerId,
        val at: Coordinate,
    ) : DomainEvent()

    /**
     * Ball events follow two beats: **land** (ball becomes loose on a square) then **bounce**
     * (D8 hop square-to-square). Each beat is its own resolution method and [DomainEvent].
     * Successful throws skip the loose-square beat — [BallThrown] is a direct transfer.
     */

    data class BallPickedUp(
        val playerId: PlayerId,
        val playerAt: Coordinate,
        /** Ability tagged on pickup (e.g. Sure Scoop); null when untagged. */
        val ability: PlayerAbility? = null,
    ) : DomainEvent()

    /** Land — carrier drops the ball on their square; bounce may follow. */
    data class BallDropped(
        val playerId: PlayerId,
        val playerAt: Coordinate,
    ) : DomainEvent()

    /** Successful throw or hand-off — ball moves from thrower square to catcher square. */
    data class BallThrown(
        val from: Coordinate,
        val to: Coordinate,
    ) : DomainEvent()

    /** Land — throw missed; ball sits loose at [to]; bounce chain follows separately. */
    data class BallThrowMissed(
        val from: Coordinate,
        val to: Coordinate,
    ) : DomainEvent()

    /** Bounce — ball hops from [from] to [to] (loose throughout). Same coords = deflected, no move. */
    data class BallBounced(
        val from: Coordinate,
        val to: Coordinate,
    ) : DomainEvent()

    /** Ball appears loose without a prior square — match setup / trapdoor spawn (rulebook New Ball!). */
    data class BallPlacedLoose(
        val at: Coordinate,
    ) : DomainEvent()

    /** Ball removed from play — cleared from [playerId] when held, else loose at [at]. Exactly one of the two is set. */
    data class BallRemovedFromPlay(
        /** Set when removing a held ball from this carrier; null when removing a loose ball. */
        val playerId: PlayerId? = null,
        /** Set when removing a loose ball at this square; null when removing a held ball. */
        val at: Coordinate? = null,
    ) : DomainEvent() {
        init {
            require(playerId != null || at != null) { "BallRemovedFromPlay requires playerId (held) or at (loose)" }
        }
    }

    data class TouchdownScored(
        val playerId: PlayerId,
        val team: TeamSide,
        val at: Coordinate,
        /** Home points after this touchdown. */
        val homeScore: Int,
        /** Away points after this touchdown. */
        val awayScore: Int,
    ) : DomainEvent()

    /** Empty active slots filled from the deck during Pre-Turn Refresh. */
    data class ChallengeTableauRefreshed(
        val filledSlots: List<Int>,
        /** Cards drawn into [filledSlots] (same order). */
        val filledCards: List<ChallengeCardDto>,
        /** New visible deck top after the fill; null when the deck is empty. */
        val deckTop: ChallengeCardDto?,
        val deckRemaining: Int,
    ) : DomainEvent()

    /** Forced replace: one active discarded and replaced from the deck (auto-pick slot for D1.B). */
    data class ChallengeCardReplaced(
        val slotIndex: Int,
        val discardedClaimCondition: ChallengeClaimCondition,
        /** Replacement drawn into the slot; null when the deck was empty (slot left empty). */
        val drawn: ChallengeCardDto?,
        /** Visible deck top after the replace; null when the deck is empty. */
        val deckTop: ChallengeCardDto?,
        val deckRemaining: Int,
    ) : DomainEvent()

    /** Challenge claimed into the coach's bonus hand (secret). Slot left empty until Refresh. */
    data class ChallengeCardClaimed(
        val team: TeamSide,
        val slotIndex: Int,
        val claimCondition: ChallengeClaimCondition,
        val challengeTitle: String,
        val pointsAwarded: Int,
        val homeScore: Int,
        val awayScore: Int,
        val claimsThisTurn: Int,
    ) : DomainEvent()

    /** Claimed all three actives in one turn — +2 points. */
    data class CleanSweep(
        val team: TeamSide,
        val pointsAwarded: Int,
        val homeScore: Int,
        val awayScore: Int,
    ) : DomainEvent()

    /** End-of-turn hand limit — discarded down to 3 bonus plays (auto oldest-first for D1.B). */
    data class ChallengeHandTrimmed(
        val team: TeamSide,
        val discardedBonusPlays: List<BonusPlay>,
        val handSize: Int,
    ) : DomainEvent()

    /** Coach played a bonus card from hand into the discard. */
    data class BonusPlayed(
        val team: TeamSide,
        val bonusPlay: BonusPlay,
    ) : DomainEvent()

    /** Bonus free action was skipped — card returned to hand (e.g. Cut Off Mark declined). */
    data class BonusReturned(
        val team: TeamSide,
        val bonusPlay: BonusPlay,
    ) : DomainEvent()

    /**
     * Last challenge card dealt into an active slot — each team has [turnsRemaining] coach
     * activations left (including the turn whose Pre-Turn just dealt), then Points Victory.
     */
    data class PointsVictoryArmed(
        val turnsRemaining: Int,
    ) : DomainEvent()

    /** After a coach closes while Points Victory is armed; [turnsRemaining] is still > 0. */
    data class PointsVictoryCountdown(
        val turnsRemaining: Int,
    ) : DomainEvent()

    /** Block tackle or miss — blocker cannot take further actions this turn (digest § Block). */
    data class PlayerTurnEnded(
        val playerId: PlayerId,
    ) : DomainEvent()

    /** Coach spent one team action slot. */
    data class TeamActionConsumed(
        val actionsRemaining: Int,
    ) : DomainEvent()

    /**
     * Turn clock entered [phase]. Leaving a phase is implied by the next [PhaseStarted] (or [MatchEnded]).
     * [turnNumber] / [activeCoach] are the HUD snapshot at entry; [actionsRemaining] is set when
     * entering [TurnPhase.PLAYER_ACTION].
     */
    data class PhaseStarted(
        val phase: TurnPhase,
        val turnNumber: Int,
        val activeCoach: TeamSide,
        /** Budget remaining when entering PLAYER_ACTION; null for other phases. */
        val actionsRemaining: Int? = null,
    ) : DomainEvent()

    /** Match finished. */
    data class MatchEnded(
        val turnNumber: Int,
        val phase: TurnPhase,
        val activeCoach: TeamSide,
        val endKind: MatchEndKind,
        /** Null on a Points Victory score tie. */
        val winner: TeamSide?,
    ) : DomainEvent()

    /**
     * Protocol signal — server expects coach input from [team]. Does not patch the board;
     * client should `GET .../legal-actions` (or prompt APIs later) then POST.
     */
    sealed class InputRequired : DomainEvent() {
        abstract val team: TeamSide

        /**
         * Active coach should pick and submit a player action.
         * [ability] tags ability-driven prompts (free grant, Ball Guard, …); null for ordinary action prompts.
         */
        data class PlayerAction(
            override val team: TeamSide,
            val decisionType: PlayerDecisionType,
            /** Ability that opened this prompt; null for ordinary player-action prompts. */
            val ability: PlayerAbility? = null,
            //  Nonnull if this is an action for a specific player
            val playerId: PlayerId? = null,
            // Nonnull if this is a bonus play
            val bonusPlay: BonusPlay? = null,
        ) : InputRequired()
    }
}

private val domainEventJsonSubTypeNames: Map<Class<out DomainEvent>, String> =
    requireNotNull(DomainEvent::class.java.getAnnotation(JsonSubTypes::class.java)) { "DomainEvent must declare @JsonSubTypes" }.value.associate { it.value.java as Class<out DomainEvent> to it.name }

/** SSE `event` frame name — same string as Jackson `kind` on the wire. */
fun DomainEvent.sseEventName(): String =
    domainEventJsonSubTypeNames[javaClass] ?: error("Unregistered DomainEvent subtype: ${javaClass.simpleName}")

