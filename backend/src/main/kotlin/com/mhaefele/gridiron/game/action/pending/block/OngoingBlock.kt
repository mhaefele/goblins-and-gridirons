package com.mhaefele.gridiron.game.action.pending.block

import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.pending.ArmourCheckContinuation
import com.mhaefele.gridiron.model.pending.BlockContinuation
import com.mhaefele.gridiron.model.pending.KnockDownContinuation
import com.mhaefele.gridiron.model.pending.RunContinuation
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide

/** Snapshot of a block in progress — persisted only inside [BlockContinuation]. */
data class OngoingBlock(
    val blockerId: PlayerId,
    val destination: Coordinate,
    val targetId: PlayerId,
    val targetTeam: TeamSide,
    val stage: BlockStage,
    /** Gang Up was active when this block began — Assisted for its duration. */
    val gangUpAssisted: Boolean = false,
    val braceNoAssist: Boolean = false,
    val braceShoveIsMiss: Boolean = false,
    val underdog: Boolean = false,
    val assisted: Boolean = false,
    val faces: List<BlockDieFace>? = null,
    val secondSwingUsed: Boolean = false,
    /** Declined Do-Over for this block's dice pool — survives HTTP via the continuation. */
    val doOverBlockDeclined: Boolean = false,
    /** Remaining Enforcer faces after an interrupt mid-chain; null when not applying Enforcer faces. */
    val remainingEnforcerFaces: List<BlockDieFace>? = null,
    /** Vacated square for optional shove Mark follow-up; null unless at HANDLE_SHOVE_FOLLOWUP. */
    val shoveVacatedSquare: Coordinate? = null,
    /**
     * Target's square when nesting target KnockDown / ArmourCheck — Enforcer follow-up after injury.
     * Block-owned; not read from the child continuation.
     */
    val enforcerFollowUpSquare: Coordinate? = null,
    /** Nested knock-down; non-null at DELEGATE_KNOCK_DOWN_TARGET / DELEGATE_KNOCK_DOWN_BLOCKER. */
    val nestedKnockDown: KnockDownContinuation? = null,
    /** Nested armour check; non-null at DELEGATE_ARMOUR_CHECK (already-prone Enforcer). */
    val nestedArmourCheck: ArmourCheckContinuation? = null,
    /** All-In Tackle — after this Block completes, place the blocker Prone (no armour). */
    val allInTackleProneAfterBlock: Boolean = false,
    val nestedRun: RunContinuation? = null,
    /** Nested free Block (Relentless After Block) under DELEGATE_FREE_ACTION. */
    val nestedBlock: BlockContinuation? = null,
    val offeredAbility: PlayerAbility? = null,
    val offeredAbilityPlayerId: PlayerId? = null,
    /** Abilities already skipped or resolved this Block — do not re-offer. */
    val resolvedFollowUpAbilities: Set<PlayerAbility> = emptySet(),
) {
    /** Coach must answer before the auto-crank continues. DELEGATE tracks nested child's park. */
    val awaitsInput: Boolean
        get() = when (stage) {
            BlockStage.DELEGATE_KNOCK_DOWN_TARGET,
            BlockStage.DELEGATE_KNOCK_DOWN_BLOCKER -> nestedKnockDown!!.awaitsCoachInput()
            BlockStage.DELEGATE_ARMOUR_CHECK       -> nestedArmourCheck!!.awaitsCoachInput()
            BlockStage.DELEGATE_FREE_ACTION        -> when {
                nestedRun != null   -> nestedRun.awaitsCoachInput()
                nestedBlock != null -> nestedBlock.awaitsCoachInput()
                else                -> error("DELEGATE_FREE_ACTION without nested child")
            }
            else                                   -> stage.awaitsInput
        }

    val isDone: Boolean get() = stage == BlockStage.DONE

    enum class BlockStage {
        INITIALIZE,
        OFFER_QUICK_RELEASE,
        HANDLE_QUICK_RELEASE,
        OFFER_BRACE,
        HANDLE_BRACE,
        OFFER_UNDERDOG,
        HANDLE_UNDERDOG,
        VIOLENT_CHECK,
        ROLL_POOL,
        QUEUE_POST_POOL,
        HANDLE_POST_POOL,
        RESOLVE_FACES,
        HANDLE_ENFORCER_ORDER,
        APPLY_ENFORCER_FACES,
        APPLY_BEST_FACE,
        /** Standing target knock-down → afterArmourResolved. */
        DELEGATE_KNOCK_DOWN_TARGET,
        /** All-In Tackle blocker prone (no armour) → OFFER_ABILITY_FOLLOW_UPS. */
        DELEGATE_KNOCK_DOWN_BLOCKER,
        /** Already-prone Enforcer armour → afterArmourResolved. */
        DELEGATE_ARMOUR_CHECK,
        HANDLE_SHOVE_FOLLOWUP,
        /** Core block board work finished — ability follow-ups next. */
        CORE_COMPLETE,
        OFFER_ABILITY_FOLLOW_UPS,
        HANDLE_ABILITY_FOLLOW_UP,
        DELEGATE_FREE_ACTION,
        DONE,
        ;

        /** Coach must answer before the auto-crank continues. DELEGATE uses [OngoingBlock.awaitsInput]. */
        val awaitsInput: Boolean
            get() = when (this) {
                HANDLE_QUICK_RELEASE, HANDLE_BRACE, HANDLE_UNDERDOG, HANDLE_POST_POOL,
                HANDLE_ENFORCER_ORDER, HANDLE_SHOVE_FOLLOWUP, HANDLE_ABILITY_FOLLOW_UP,
                     -> true
                else -> false
            }
    }
}

class OngoingBlockMutator(
    var ongoingBlock: OngoingBlock,
) {
    val blockerId: PlayerId get() = ongoingBlock.blockerId
    val destination: Coordinate get() = ongoingBlock.destination
    val targetId: PlayerId get() = ongoingBlock.targetId
    val targetTeam: TeamSide get() = ongoingBlock.targetTeam

    var stage: OngoingBlock.BlockStage
        set(value) { ongoingBlock = ongoingBlock.copy(stage = value) }
        get() = ongoingBlock.stage

    val awaitsInput: Boolean get() = ongoingBlock.awaitsInput

    val isDone: Boolean get() = ongoingBlock.isDone

    val gangUpAssisted: Boolean get() = ongoingBlock.gangUpAssisted

    var braceNoAssist: Boolean
        set(value) { ongoingBlock = ongoingBlock.copy(braceNoAssist = value) }
        get() = ongoingBlock.braceNoAssist

    var braceShoveIsMiss: Boolean
        set(value) { ongoingBlock = ongoingBlock.copy(braceShoveIsMiss = value) }
        get() = ongoingBlock.braceShoveIsMiss

    var underdog: Boolean
        set(value) { ongoingBlock = ongoingBlock.copy(underdog = value) }
        get() = ongoingBlock.underdog

    var assisted: Boolean
        set(value) { ongoingBlock = ongoingBlock.copy(assisted = value) }
        get() = ongoingBlock.assisted

    var faces: List<BlockDieFace>?
        set(value) { ongoingBlock = ongoingBlock.copy(faces = value) }
        get() = ongoingBlock.faces

    var secondSwingUsed: Boolean
        set(value) { ongoingBlock = ongoingBlock.copy(secondSwingUsed = value) }
        get() = ongoingBlock.secondSwingUsed

    var doOverBlockDeclined: Boolean
        set(value) { ongoingBlock = ongoingBlock.copy(doOverBlockDeclined = value) }
        get() = ongoingBlock.doOverBlockDeclined

    var remainingEnforcerFaces: List<BlockDieFace>?
        set(value) { ongoingBlock = ongoingBlock.copy(remainingEnforcerFaces = value) }
        get() = ongoingBlock.remainingEnforcerFaces

    var shoveVacatedSquare: Coordinate?
        set(value) { ongoingBlock = ongoingBlock.copy(shoveVacatedSquare = value) }
        get() = ongoingBlock.shoveVacatedSquare

    var enforcerFollowUpSquare: Coordinate?
        set(value) { ongoingBlock = ongoingBlock.copy(enforcerFollowUpSquare = value) }
        get() = ongoingBlock.enforcerFollowUpSquare

    var nestedKnockDown: KnockDownContinuation?
        set(value) { ongoingBlock = ongoingBlock.copy(nestedKnockDown = value) }
        get() = ongoingBlock.nestedKnockDown

    var nestedArmourCheck: ArmourCheckContinuation?
        set(value) { ongoingBlock = ongoingBlock.copy(nestedArmourCheck = value) }
        get() = ongoingBlock.nestedArmourCheck

    val allInTackleProneAfterBlock: Boolean get() = ongoingBlock.allInTackleProneAfterBlock

    var nestedRun: RunContinuation?
        set(value) { ongoingBlock = ongoingBlock.copy(nestedRun = value) }
        get() = ongoingBlock.nestedRun

    var nestedBlock: BlockContinuation?
        set(value) { ongoingBlock = ongoingBlock.copy(nestedBlock = value) }
        get() = ongoingBlock.nestedBlock

    var offeredAbility: PlayerAbility?
        set(value) { ongoingBlock = ongoingBlock.copy(offeredAbility = value) }
        get() = ongoingBlock.offeredAbility

    var offeredAbilityPlayerId: PlayerId?
        set(value) { ongoingBlock = ongoingBlock.copy(offeredAbilityPlayerId = value) }
        get() = ongoingBlock.offeredAbilityPlayerId

    var resolvedFollowUpAbilities: Set<PlayerAbility>
        set(value) { ongoingBlock = ongoingBlock.copy(resolvedFollowUpAbilities = value) }
        get() = ongoingBlock.resolvedFollowUpAbilities
}
