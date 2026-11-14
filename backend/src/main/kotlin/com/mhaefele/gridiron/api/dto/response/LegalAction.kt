package com.mhaefele.gridiron.api.dto.response

import com.mhaefele.gridiron.model.pending.ThrowTeammateContinuation
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.ChallengeClaimCondition
import com.mhaefele.gridiron.shared.ThrowRange
import com.mhaefele.gridiron.shared.*
import io.swagger.v3.oas.annotations.media.Schema

/**
 * One choosable move.
 *
 * [PlayerLegalAction] — scoped to a roster player ([playerId]).
 * [BoardTargetedLegalAction] — also has a pitch square the coach may click ([targetCoordinate]).
 * [BasicPlayerAction] — regular turn-budget (or free-grant) player actions: run, mark, block, etc.
 * [FollowUpAction] — mid-resolution or optional-prompt choices: re-rolls, Ball Guard placement,
 * quick-release continue, Skip / DeclineBonus. Pending decisions only filter which of these are offered.
 *
 * Coach card choices ([PlayBonusCard], [ClaimChallenge], [ReplaceChallenge], [DeclineBonus]) are
 * follow-ups with no player or board target.
 *
 * Each variant is self-describing for resolution — [com.mhaefele.gridiron.game.action.PlayerActionExecutor]
 * dispatches on the parked [com.mhaefele.gridiron.model.PendingDecision]; selecting
 * [com.mhaefele.gridiron.model.RegularPlayerAction] enters begin* via
 * [com.mhaefele.gridiron.game.action.pending.regular.RegularPlayerActionResolver]. Mid-run budget lives on
 * [com.mhaefele.gridiron.model.RunContinuation] /
 * [com.mhaefele.gridiron.game.action.pending.run.RunResolver].
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = LegalAction.Run::class, name = "run"),
    JsonSubTypes.Type(value = LegalAction.Mark::class, name = "mark"),
    JsonSubTypes.Type(value = LegalAction.Sidestep::class, name = "sidestep"),
    JsonSubTypes.Type(value = LegalAction.Throw::class, name = "throw"),
    JsonSubTypes.Type(value = LegalAction.ThrowTeammateSelect::class, name = "throwTeammateSelect"),
    JsonSubTypes.Type(value = LegalAction.ThrowTeammate::class, name = "throwTeammate"),
    JsonSubTypes.Type(value = LegalAction.CancelThrowTeammate::class, name = "cancelThrowTeammate"),
    JsonSubTypes.Type(value = LegalAction.Block::class, name = "block"),
    JsonSubTypes.Type(value = LegalAction.Foul::class, name = "foul"),
    JsonSubTypes.Type(value = LegalAction.StandUp::class, name = "standUp"),
    JsonSubTypes.Type(value = LegalAction.Reserves::class, name = "reserves"),
    JsonSubTypes.Type(value = LegalAction.Skip::class, name = "skip"),
    JsonSubTypes.Type(value = LegalAction.DeclineBonus::class, name = "declineBonus"),
    JsonSubTypes.Type(value = LegalAction.PlaceLooseBall::class, name = "placeLooseBall"),
    JsonSubTypes.Type(value = LegalAction.ChooseBlockReroll::class, name = "chooseBlockReroll"),
    JsonSubTypes.Type(value = LegalAction.ChooseBlockFaceOrder::class, name = "chooseBlockFaceOrder"),
    JsonSubTypes.Type(value = LegalAction.ChooseThrowReroll::class, name = "chooseThrowReroll"),
    JsonSubTypes.Type(value = LegalAction.AdjustThrowByDoOver::class, name = "adjustThrowByDoOver"),
    JsonSubTypes.Type(value = LegalAction.AdjustBlockByDoOver::class, name = "adjustBlockByDoOver"),
    JsonSubTypes.Type(value = LegalAction.AdjustArmourByDoOver::class, name = "adjustArmourByDoOver"),
    JsonSubTypes.Type(value = LegalAction.ContinueBlock::class, name = "continueBlock"),
    JsonSubTypes.Type(value = LegalAction.QuickReleaseThrow::class, name = "quickReleaseThrow"),
    JsonSubTypes.Type(value = LegalAction.DeclareRun::class, name = "declareRun"),
    JsonSubTypes.Type(value = LegalAction.PlayGangUp::class, name = "playGangUp"),
    JsonSubTypes.Type(value = LegalAction.PlayHoldTheLine::class, name = "playHoldTheLine"),
    JsonSubTypes.Type(value = LegalAction.PlayExtraEffort::class, name = "playExtraEffort"),
    JsonSubTypes.Type(value = LegalAction.PlayMomentum::class, name = "playMomentum"),
    JsonSubTypes.Type(value = LegalAction.PlayAllInTackle::class, name = "playAllInTackle"),
    JsonSubTypes.Type(value = LegalAction.PlaySlipAway::class, name = "playSlipAway"),
    JsonSubTypes.Type(value = LegalAction.PlayCutOff::class, name = "playCutOff"),
    JsonSubTypes.Type(value = LegalAction.PlayStickClose::class, name = "playStickClose"),
    JsonSubTypes.Type(value = LegalAction.PlayBackOnYourFeet::class, name = "playBackOnYourFeet"),
    JsonSubTypes.Type(value = LegalAction.PlayFreshLegs::class, name = "playFreshLegs"),
    JsonSubTypes.Type(value = LegalAction.PlayBrace::class, name = "playBrace"),
    JsonSubTypes.Type(value = LegalAction.PlayUnderdog::class, name = "playUnderdog"),
    JsonSubTypes.Type(value = LegalAction.PlaySpikedGauntlets::class, name = "playSpikedGauntlets"),
    JsonSubTypes.Type(value = LegalAction.PlayDeadEye::class, name = "playDeadEye"),
    JsonSubTypes.Type(value = LegalAction.PlayHailMary::class, name = "playHailMary"),
    JsonSubTypes.Type(value = LegalAction.PlaySprint::class, name = "playSprint"),
    JsonSubTypes.Type(value = LegalAction.ClaimChallenge::class, name = "claimChallenge"),
    JsonSubTypes.Type(value = LegalAction.ReplaceChallenge::class, name = "replaceChallenge"),
)
@Schema(description = "One choosable move — discriminated by `kind`. POST the row's `id` to `…/play`.")
sealed interface LegalAction {
    val actionType: ActionType
    /**
     * Bonus card this choice spends, if any.
     *
     * - **Geometry** (Run/Mark/Throw/…): Sprint/Weave/Press/Ice Cold/Hail Mary tagged onto the
     *   path/throw; encoded into [id] as `_BONUS_…`;
     *   [com.mhaefele.gridiron.game.action.pending.regular.RegularPlayerActionResolver] auto-plays
     *   it for the active coach when beginning the open action.
     * - **[BonusCardChoice]** (Play* pause / Do-Over adjust): same field as [BonusCardChoice.bonusPlay]
     *   so callers / AI pack read one place; [id] stays the ActionType token alone (subtype already
     *   unique). Per-play kinds stay distinct; the pack bonus column carries this card (hand codes).
     *   Spending coach may not be the active one (Brace, Slip Away, …) — resolvers call
     *   [com.mhaefele.gridiron.game.turn.MatchMutator.playBonusCard] with the right seat.
     */
    val requiredBonusCard: BonusPlay?
        get() = null
    /**
     * Optional bonus cards the coach may play if they take this action (Back on Your Feet, Dead Eye, …).
     * Not encoded in [id]; submit stays a bare id, then a Play/Decline window may open.
     */
    val availableBonusCards: List<BonusPlay>
        get() = emptyList()
    /**
     * Active challenges this action could earn if resolved as expected (D1.F.4).
     * Distinct from [availableBonusCards] (play-from-hand). Empty on follow-ups.
     */
    val potentialChallengeClaims: List<PotentialChallengeClaim>
        get() = emptyList()
    val id: LegalActionId
        get() = LegalActionId(this.getLegalActionIdString())

    /** Actions that belong to a specific roster player. */
    sealed interface PlayerLegalAction : LegalAction {
        val playerId: PlayerId
    }

    /**
     * Player actions whose primary affordance is a pitch square (path end, block target, stand-up
     * here, etc.). Modal-only follow-ups (re-rolls, Skip) are not board-targeted.
     */
    sealed interface BoardTargetedLegalAction : PlayerLegalAction {
        val targetCoordinate: Coordinate
    }

    /** Regular player actions (also reused as free-ability grant payloads). */
    sealed interface BasicPlayerAction : BoardTargetedLegalAction

    /**
     * Run, Mark, and Sidestep share a [path]. Resolution can treat them uniformly; branch on
     * [actionType] only when rules diverge (e.g. ball on path).
     */
    sealed interface PathMovement : BasicPlayerAction {
        val path: Path
        /**
         * Non-null when the path ends on a loose ball — pickup vs bounce (annotated at legal-set gen).
         */
        val ballEncounter: BallEncounter?
            get() = null
        override val targetCoordinate: Coordinate
            get() = path.squares.last()
    }

    /** Mid-resolution or optional-prompt choices — not a fresh regular action pick. */
    sealed interface FollowUpAction : LegalAction

    /** Follow-ups scoped to a player (re-rolls, Ball Guard, Skip, …). */
    sealed interface PlayerFollowUpAction : FollowUpAction, PlayerLegalAction

    /** Player follow-ups the coach may take by clicking a pitch square. */
    sealed interface BoardTargetedFollowUpAction : PlayerFollowUpAction, BoardTargetedLegalAction

    data class Run(
        override val playerId: PlayerId,
        override val path: Path,
        override val requiredBonusCard: BonusPlay? = null,
        override val availableBonusCards: List<BonusPlay> = emptyList(),
        override val potentialChallengeClaims: List<PotentialChallengeClaim> = emptyList(),
        override val ballEncounter: BallEncounter? = null,
    ) : PathMovement {
        override val actionType: ActionType = ActionType.RUN
    }

    /**
     * Start a [MoveValue.RandomD6] Run: always plain. If Sprint is in hand, [RunResolver]
     * parks [HANDLE_SPRINT] before the Move roll; else rolls immediately and parks paths.
     * [actionType] stays [ActionType.RUN] (not a separate action kind).
     */
    data class DeclareRun(
        override val playerId: PlayerId,
        override val targetCoordinate: Coordinate,
        override val requiredBonusCard: BonusPlay? = null,
        override val availableBonusCards: List<BonusPlay> = emptyList(),
        override val potentialChallengeClaims: List<PotentialChallengeClaim> = emptyList(),
    ) : BasicPlayerAction {
        override val actionType: ActionType = ActionType.RUN
    }

    data class Mark(
        override val playerId: PlayerId,
        override val path: Path,
        override val requiredBonusCard: BonusPlay? = null,
        override val availableBonusCards: List<BonusPlay> = emptyList(),
        override val potentialChallengeClaims: List<PotentialChallengeClaim> = emptyList(),
        override val ballEncounter: BallEncounter? = null,
    ) : PathMovement {
        override val actionType: ActionType = ActionType.MARK
    }

    data class Sidestep(
        override val playerId: PlayerId,
        override val path: Path,
        override val requiredBonusCard: BonusPlay? = null,
        override val availableBonusCards: List<BonusPlay> = emptyList(),
        override val potentialChallengeClaims: List<PotentialChallengeClaim> = emptyList(),
        override val ballEncounter: BallEncounter? = null,
    ) : PathMovement {
        override val actionType: ActionType = ActionType.SIDESTEP
    }

    data class Throw(
        override val playerId: PlayerId,
        val target: ThrowTarget,
        override val requiredBonusCard: BonusPlay? = null,
        override val availableBonusCards: List<BonusPlay> = emptyList(),
        override val potentialChallengeClaims: List<PotentialChallengeClaim> = emptyList(),
    ) : BasicPlayerAction {
        override val actionType: ActionType = ActionType.THROW
        override val targetCoordinate: Coordinate get() = target.destination
    }

    /**
     * Start Throw Team-mate: choose which adjacent team-mate to throw.
     * Opens [ThrowTeammateContinuation] without consuming the action until a landing is picked.
     */
    data class ThrowTeammateSelect(
        override val playerId: PlayerId,
        val teammateId: PlayerId,
        override val targetCoordinate: Coordinate,
        override val requiredBonusCard: BonusPlay? = null,
        override val availableBonusCards: List<BonusPlay> = emptyList(),
        override val potentialChallengeClaims: List<PotentialChallengeClaim> = emptyList(),
    ) : BasicPlayerAction {
        override val actionType: ActionType = ActionType.THROW_TEAMMATE
    }

    /** Complete Throw Team-mate: land [teammateId] on [target] (or injure on fumble / occupied). */
    data class ThrowTeammate(
        override val playerId: PlayerId,
        val teammateId: PlayerId,
        val target: ThrowTarget,
        override val requiredBonusCard: BonusPlay? = null,
        override val availableBonusCards: List<BonusPlay> = emptyList(),
        override val potentialChallengeClaims: List<PotentialChallengeClaim> = emptyList(),
    ) : BasicPlayerAction {
        override val actionType: ActionType = ActionType.THROW_TEAMMATE
        override val targetCoordinate: Coordinate get() = target.destination
    }

    /** Unwind Throw Team-mate after select — restores a regular player-action pick. */
    data class CancelThrowTeammate(
        override val playerId: PlayerId,
    ) : PlayerFollowUpAction {
        override val actionType: ActionType = ActionType.CANCEL_THROW_TEAMMATE
    }

    data class Block(
        override val playerId: PlayerId,
        val destination: Coordinate,
        override val requiredBonusCard: BonusPlay? = null,
        override val availableBonusCards: List<BonusPlay> = emptyList(),
        override val potentialChallengeClaims: List<PotentialChallengeClaim> = emptyList(),
    ) : BasicPlayerAction {
        override val actionType: ActionType = ActionType.BLOCK
        override val targetCoordinate: Coordinate get() = destination
    }

    /** Open player fouls an adjacent Prone opposition player (digest § Foul). */
    data class Foul(
        override val playerId: PlayerId,
        val destination: Coordinate,
        override val requiredBonusCard: BonusPlay? = null,
        override val availableBonusCards: List<BonusPlay> = emptyList(),
        override val potentialChallengeClaims: List<PotentialChallengeClaim> = emptyList(),
    ) : BasicPlayerAction {
        override val actionType: ActionType = ActionType.FOUL
        override val targetCoordinate: Coordinate get() = destination
    }

    data class StandUp(
        override val playerId: PlayerId,
        override val targetCoordinate: Coordinate,
        override val requiredBonusCard: BonusPlay? = null,
        override val availableBonusCards: List<BonusPlay> = emptyList(),
        override val potentialChallengeClaims: List<PotentialChallengeClaim> = emptyList(),
    ) : BasicPlayerAction {
        override val actionType: ActionType = ActionType.STAND_UP
    }

    data class Reserves(
        override val playerId: PlayerId,
        val placement: Coordinate,
        override val requiredBonusCard: BonusPlay? = null,
        override val availableBonusCards: List<BonusPlay> = emptyList(),
        override val potentialChallengeClaims: List<PotentialChallengeClaim> = emptyList(),
        /** Non-null [BallEncounter.BOUNCE] when placement lands on a loose ball. */
        val ballEncounter: BallEncounter? = null,
    ) : BasicPlayerAction {
        override val actionType: ActionType = ActionType.RESERVES
        override val targetCoordinate: Coordinate get() = placement
    }

    /** Decline an optional free grant or shove follow-up — no board work. */
    data class Skip(
        override val playerId: PlayerId,
    ) : PlayerFollowUpAction {
        override val actionType: ActionType = ActionType.SKIP
    }

    /** Decline playing a bonus card during a bonus offer pending. */
    data object DeclineBonus : FollowUpAction {
        override val actionType: ActionType = ActionType.SKIP
    }

    /** Ball Guard: move the loose ball on [from] onto [to]. */
    data class PlaceLooseBall(
        override val playerId: PlayerId,
        val from: Coordinate,
        val to: Coordinate,
    ) : BoardTargetedFollowUpAction {
        override val actionType: ActionType = ActionType.PLACE_LOOSE_BALL
        override val targetCoordinate: Coordinate get() = to
    }

    /**
     * Second Swing: re-roll block dice ([reroll]=true) or keep [faces] ([reroll]=false).
     *
     * TODO(AI2.19): boolean doubles as identity — AI maps to `reroll` / `keepOffer`. Prefer two
     * subtypes (or drop the flag) when promoting a shared wire kind onto these DTOs.
     */
    data class ChooseBlockReroll(
        override val playerId: PlayerId,
        val destination: Coordinate,
        val faces: List<BlockDieFace>,
        val assisted: Boolean,
        val reroll: Boolean,
    ) : PlayerFollowUpAction {
        override val actionType: ActionType = ActionType.CHOOSE_BLOCK_REROLL
    }

    /** Enforcer: resolve block faces in this order (not best-face). */
    data class ChooseBlockFaceOrder(
        override val playerId: PlayerId,
        val destination: Coordinate,
        val orderedFaces: List<BlockDieFace>,
    ) : PlayerFollowUpAction {
        override val actionType: ActionType = ActionType.CHOOSE_BLOCK_FACE_ORDER
    }

    /**
     * Pinpoint Passer: re-roll throw check ([reroll]=true) or keep [naturalRoll] ([reroll]=false).
     *
     * TODO(AI2.19): same boolean-as-kind smell as [ChooseBlockReroll].
     */
    data class ChooseThrowReroll(
        override val playerId: PlayerId,
        val destination: Coordinate,
        val naturalRoll: Int,
        val range: ThrowRange,
        val obstructed: Boolean,
        val reroll: Boolean,
    ) : PlayerFollowUpAction {
        override val actionType: ActionType = ActionType.CHOOSE_THROW_REROLL
    }

    /** Do-Over: re-roll the held throw check. */
    data class AdjustThrowByDoOver(
        override val playerId: PlayerId,
        val destination: Coordinate,
        val naturalRoll: Int,
        val range: ThrowRange,
        val obstructed: Boolean,
    ) : PlayerFollowUpAction, BonusCardChoice {
        override val actionType: ActionType = ActionType.ADJUST_THROW_BY_DO_OVER
        override val bonusPlay: BonusPlay = BonusPlay.`Do-Over`
    }

    /** Do-Over: re-roll the held block dice. */
    data class AdjustBlockByDoOver(
        override val playerId: PlayerId,
        val destination: Coordinate,
        val faces: List<BlockDieFace>,
        val assisted: Boolean,
    ) : PlayerFollowUpAction, BonusCardChoice {
        override val actionType: ActionType = ActionType.ADJUST_BLOCK_BY_DO_OVER
        override val bonusPlay: BonusPlay = BonusPlay.`Do-Over`
    }

    /** Do-Over: re-roll the held armour check (coach-level — no roster player scope). */
    data class AdjustArmourByDoOver(
        val targetId: PlayerId,
        val naturalRoll: Int,
    ) : BonusCardChoice {
        override val actionType: ActionType = ActionType.ADJUST_ARMOUR_BY_DO_OVER
        override val bonusPlay: BonusPlay = BonusPlay.`Do-Over`
    }

    /** Quick Release declined — resume the deferred block without offering quick-release again. */
    data class ContinueBlock(
        override val playerId: PlayerId,
        val destination: Coordinate,
    ) : BoardTargetedFollowUpAction {
        override val actionType: ActionType = ActionType.CONTINUE_BLOCK
        override val targetCoordinate: Coordinate get() = destination
    }

    /** Quick Release free throw/hand-off (may be marked). */
    data class QuickReleaseThrow(
        override val playerId: PlayerId,
        val target: ThrowTarget,
    ) : BoardTargetedFollowUpAction {
        override val actionType: ActionType = ActionType.QUICK_RELEASE_THROW
        override val targetCoordinate: Coordinate get() = target.destination
    }

    /**
     * Play a bonus card from hand during a bonus-offer pause (phase / interrupt / before-throw /
     * mid-block — not Do-Over held-dice). Each card that opens a pause has its own subtype; the
     * server picks a matching hand card. The throw/mark/block (etc.) was already chosen — this is
     * the second ask to spend or [DeclineBonus]. [requiredBonusCard] / [bonusPlay] name the card;
     * resolvers still branch on the Play* subtype (or [bonusPlay]) for effect.
     */
    sealed interface PlayBonusCard : BonusCardChoice

    /**
     * Coach spends a bonus card from hand ([PlayBonusCard] or a Do-Over adjust) — [bonusPlay] /
     * [requiredBonusCard] carry the card for prompts, harness rows, and the AI bonus pack column.
     */
    sealed interface BonusCardChoice : FollowUpAction {
        val bonusPlay: BonusPlay
        override val requiredBonusCard: BonusPlay get() = bonusPlay
        val description: String get() = bonusPlay.description
    }

    data object PlayGangUp : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_GANG_UP
        override val bonusPlay: BonusPlay = BonusPlay.`Gang Up`
    }

    data object PlayHoldTheLine : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_HOLD_THE_LINE
        override val bonusPlay: BonusPlay = BonusPlay.`Hold the Line`
    }

    data object PlayExtraEffort : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_EXTRA_EFFORT
        override val bonusPlay: BonusPlay = BonusPlay.`Extra Effort`
    }

    data object PlayMomentum : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_MOMENTUM
        override val bonusPlay: BonusPlay = BonusPlay.Momentum
    }

    data object PlayAllInTackle : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_ALL_IN_TACKLE
        override val bonusPlay: BonusPlay = BonusPlay.`All-In Tackle`
    }

    data object PlaySlipAway : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_SLIP_AWAY
        override val bonusPlay: BonusPlay = BonusPlay.`Slip Away`
    }

    data object PlayCutOff : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_CUT_OFF
        override val bonusPlay: BonusPlay = BonusPlay.`Cut Off`
    }

    data object PlayStickClose : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_STICK_CLOSE
        override val bonusPlay: BonusPlay = BonusPlay.`Stick Close`
    }

    data object PlayBackOnYourFeet : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_BACK_ON_YOUR_FEET
        override val bonusPlay: BonusPlay = BonusPlay.`Back on Your Feet`
    }

    data object PlayFreshLegs : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_FRESH_LEGS
        override val bonusPlay: BonusPlay = BonusPlay.`Fresh Legs`
    }

    data object PlayBrace : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_BRACE
        override val bonusPlay: BonusPlay = BonusPlay.Brace
    }

    data object PlayUnderdog : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_UNDERDOG
        override val bonusPlay: BonusPlay = BonusPlay.Underdog
    }

    data object PlaySpikedGauntlets : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_SPIKED_GAUNTLETS
        override val bonusPlay: BonusPlay = BonusPlay.`Spiked Gauntlets`
    }

    data object PlayDeadEye : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_DEAD_EYE
        override val bonusPlay: BonusPlay = BonusPlay.`Dead Eye`
    }

    data object PlayHailMary : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_HAIL_MARY
        override val bonusPlay: BonusPlay = BonusPlay.`Hail Mary Pass`
    }

    data object PlaySprint : PlayBonusCard {
        override val actionType: ActionType = ActionType.PLAY_SPRINT
        override val bonusPlay: BonusPlay = BonusPlay.Sprint
    }

    /** Claim one eligible active challenge (D1.B.choice). */
    data class ClaimChallenge(
        val slotIndex: Int,
        val claimCondition: ChallengeClaimCondition,
        val challengeTitle: String,
        val points: Int,
    ) : FollowUpAction {
        override val actionType: ActionType = ActionType.CLAIM_CHALLENGE
    }

    /** Forced Pre-Turn replace: discard this active slot, then draw. */
    data class ReplaceChallenge(
        val slotIndex: Int,
        val claimCondition: ChallengeClaimCondition,
        val challengeTitle: String,
    ) : FollowUpAction {
        override val actionType: ActionType = ActionType.REPLACE_CHALLENGE
        val description: String get() = claimCondition.description
    }
}

private fun LegalAction.getLegalActionIdString(): String {
    val parts = buildList {
        add(token(actionType.name))
        when (val action = this@getLegalActionIdString) {
            is LegalAction.PlayerLegalAction -> add(token(action.playerId.value))
            is LegalAction.DeclineBonus,
            is LegalAction.PlayBonusCard,
            is LegalAction.AdjustArmourByDoOver,
            is LegalAction.ClaimChallenge,
            is LegalAction.ReplaceChallenge -> Unit
        }
        when (val action = this@getLegalActionIdString) {
            is LegalAction.PathMovement             -> add(token(action.path))
            is LegalAction.ThrowTeammateSelect      -> add(token(action.teammateId.value))
            is LegalAction.ThrowTeammate            -> {
                add(token(action.teammateId.value))
                add(token(action.target.destination))
            }
            is LegalAction.CancelThrowTeammate      -> add("cancel")
            is LegalAction.Skip                     -> Unit
            is LegalAction.DeclineBonus             -> Unit
            is LegalAction.ChooseBlockReroll        -> {
                add(token(action.destination))
                add(action.reroll.toString())
            }
            is LegalAction.ChooseBlockFaceOrder     -> {
                add(token(action.destination))
                add(action.orderedFaces.joinToString("-") { token(it.name) })
            }
            is LegalAction.ChooseThrowReroll        -> {
                add(token(action.destination))
                add(action.reroll.toString())
            }
            is LegalAction.AdjustThrowByDoOver      -> { add(token(action.destination)) }
            is LegalAction.AdjustBlockByDoOver      -> { add(token(action.destination)) }
            is LegalAction.AdjustArmourByDoOver     -> { add(token(action.targetId.value)) }
            is LegalAction.PlaceLooseBall           -> {
                add(token(action.from))
                add(token(action.to))
            }
            is LegalAction.PlayBonusCard            -> Unit  // Bonus play is encoded as part of the action subtype
            is LegalAction.ClaimChallenge           -> add("slot${action.slotIndex}")
            is LegalAction.ReplaceChallenge         -> add("slot${action.slotIndex}")
            is LegalAction.BoardTargetedLegalAction -> add(token(action.targetCoordinate))
        }
    }
    val geometry = parts.joinToString("_")
    // Geometry twins only — BonusCardChoice ids are already unique by ActionType / subtype.
    val bonus = requiredBonusCard ?: return geometry
    if (this is LegalAction.BonusCardChoice) return geometry
    return geometry + "_BONUS_" + token(bonus.name)
}

/** Leading action type from a [LegalAction.id] (`RUN_playerId_…`, `THROW_…_BONUS_Sprint`). */
fun LegalActionId.getActionType(): ActionType {
    val head = value.substringBefore("_BONUS_").split("_").firstOrNull()
    require(!head.isNullOrEmpty()) { "No action type in legal action id $value" }
    val match = ActionType.entries.firstOrNull { it.name.replace("_", "") == head }
    require(match != null) { "Unknown action type '$head' in legal action id $value" }
    return match
}

/** Player id field from a [LegalAction.id] (`ACTIONTYPE_playerId_…`). Tokens never contain `_`. */
fun LegalActionId.getPlayerId(knownPlayers: Collection<PlayerId>): PlayerId? {
    val parts = value.split("_")
    val actionToken = parts.getOrNull(0) ?: return null
    // Coach-level ids have no player token (PLAY*, CLAIMCHALLENGE_slot0, bare SKIP).
    when {
        actionToken.startsWith("PLAY")                                                                                -> return null
        actionToken == "CLAIMCHALLENGE" || actionToken == "REPLACECHALLENGE" || actionToken == "ADJUSTARMOURBYDOOVER" -> return null
        actionToken == "SKIP" && parts.size == 1                                                                      -> return null // DeclineBonus
    }
    val playerId = parts.getOrNull(1) ?: return null
    val matches = knownPlayers.filter { it.value == playerId }
    require(matches.size <= 1) { "Ambiguous player id '$playerId' in $value" }
    return matches.firstOrNull()
}

/** Strip `_` so `_` can be the sole field delimiter in [LegalAction.id]. */
private fun token(raw: String): String = raw.replace("_", "")

private fun LegalAction.token(coordinate: Coordinate): String = "${coordinate.x}x${coordinate.y}"

private fun LegalAction.token(path: Path): String = path.squares.joinToString("-") { token(it) }

/** Coach-level Play* legal action for a pause window (not Sprint/Weave/etc. geometry tags). */
fun playBonusLegalAction(bonus: BonusPlay): LegalAction.PlayBonusCard =
    when (bonus) {
        BonusPlay.`Gang Up`                                                         -> LegalAction.PlayGangUp
        BonusPlay.`Hold the Line`                                                   -> LegalAction.PlayHoldTheLine
        BonusPlay.`Extra Effort`                                                    -> LegalAction.PlayExtraEffort
        BonusPlay.Momentum                                                          -> LegalAction.PlayMomentum
        BonusPlay.`All-In Tackle`                                                   -> LegalAction.PlayAllInTackle
        BonusPlay.`Slip Away`                                                       -> LegalAction.PlaySlipAway
        BonusPlay.`Cut Off`                                                         -> LegalAction.PlayCutOff
        BonusPlay.`Stick Close`                                                     -> LegalAction.PlayStickClose
        BonusPlay.`Back on Your Feet`                                               -> LegalAction.PlayBackOnYourFeet
        BonusPlay.`Fresh Legs`                                                      -> LegalAction.PlayFreshLegs
        BonusPlay.Brace                                                             -> LegalAction.PlayBrace
        BonusPlay.Underdog                                                          -> LegalAction.PlayUnderdog
        BonusPlay.`Spiked Gauntlets`                                                -> LegalAction.PlaySpikedGauntlets
        BonusPlay.`Dead Eye`                                                        -> LegalAction.PlayDeadEye
        BonusPlay.`Hail Mary Pass`                                                  -> LegalAction.PlayHailMary
        BonusPlay.Sprint                                                            -> LegalAction.PlaySprint
        BonusPlay.Weave, BonusPlay.Press, BonusPlay.`Ice Cold`, BonusPlay.`Do-Over` -> error("$bonus uses attached geometry or Adjust*ByDoOver, not a Play* pause")
    }

/**
 * Query-only throw geometry plus effective check target.
 *
 * [throwCheckNeeded] — `0` = hand-off; `2..6` = minimum natural (same codes as cell `throwStat`).
 * Long / obstruction / Hail Mary / Dead Eye are already baked in. P(success) is derived at pack /
 * rank via [com.mhaefele.gridiron.game.dice.throwSuccessChanceFromNeeded] — not a second wire field.
 */
data class ThrowTarget(
    val destination: Coordinate,
    val range: ThrowRange,
    val obstructed: Boolean,
    val throwCheckNeeded: Int,
) {
    init {
        require(throwCheckNeeded == 0 || throwCheckNeeded in 2..6) {
            "throwCheckNeeded must be 0 (hand-off) or 2..6, was $throwCheckNeeded"
        }
    }
}
