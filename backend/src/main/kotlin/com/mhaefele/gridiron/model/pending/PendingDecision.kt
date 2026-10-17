package com.mhaefele.gridiron.model.pending

import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.TurnPhase
import org.apache.commons.lang3.builder.ReflectionToStringBuilder
import org.apache.commons.lang3.builder.ToStringStyle

/**
 * Front-of-queue coach choice. [com.mhaefele.gridiron.game.action.PlayerActionExecutor] always
 * pops one of these first, then dispatches by ongoing action funnel — never mixes command-first branches.
 *
 * ## Pending / open-action invariants
 * 1. **One slot** — null ⇒ `setPendingDecision`; same Kotlin class ⇒ `updatePendingDecision`.
 * 2. **Stage owns the pending** — park the type the current phase/step needs.
 * 3. **Clear takeAction owner per TopLevel root** — NestedOnly continuations are never roots.
 * 4. **One [phase] (ideally one step)** per pending type.
 * 5. **`currentPlayerAction` only in `PLAYER_ACTION`**.
 * 6. **Finish = [isFinished]** — one clear path to clear.
 * 7. **Ongoing* / stage decides the next beat**.
 * 8. **Nest via [delegate]** — parents (RPA, Extra Effort, *Continuations with nested children)
 *    expose the child. Prompts / respondingCoach / awaitsCoachInput follow the child when
 *    present (null from the child is kept — do not fall through); otherwise [localRespondingCoach]
 *    / [localAwaitsCoachInput] / [localPromptAbility] / [localPromptPlayerId] / [localPromptBonusPlay].
 */
sealed class PendingDecision {
    abstract val playDecisionType: PlayerDecisionType

    /** Turn phase this pending type may be parked in — fixed per concrete type, not per instance. */
    abstract val phase: TurnPhase

    /**
     * Nested child pending when this root parks another FSM (RPA → *Continuation, Extra Effort → RPA,
     * Block → KnockDown → Ball Guard / ArmourCheck / free follow-up, …). Default none — override on parents that nest.
     */
    open val delegate: PendingDecision? get() = null

    /** When set, this coach responds — not necessarily activeCoach (e.g. Quick Release). Follows [delegate]. */
    val respondingCoach: TeamSide?
        get() {
            val child = delegate
            return if (child != null) child.respondingCoach else localRespondingCoach
        }

    /** This pending's responding coach when [delegate] is null. */
    protected open val localRespondingCoach: TeamSide? get() = null

    /**
     * When true, this play is appended to actionsThisTurn. **Default false**.
     * Budget [RegularPlayerAction]s override to true.
     */
    open val countsAsPlayerAction: Boolean get() = false

    fun awaitsCoachInput(): Boolean {
        val child = delegate
        return if (child != null) child.awaitsCoachInput() else localAwaitsCoachInput
    }

    /** This pending awaits coach input when [delegate] is null. Default true. */
    protected open val localAwaitsCoachInput: Boolean get() = true

    /**
     * Finished pending — clear via MatchMutator.completePendingDecision.
     * Default: not awaiting coach. [ActionContinuation] overrides for Ongoing* auto-crank.
     */
    open fun isFinished(): Boolean = !awaitsCoachInput()

    open fun awaitsOpenActionComplete(): Boolean {
        val child = delegate
        return if (child != null) child.awaitsOpenActionComplete() else false
    }

    /** HUD / InputRequired ability cue — follows [delegate]; else [localPromptAbility]. */
    fun promptAbility(): PlayerAbility? {
        val child = delegate
        return if (child != null) child.promptAbility() else localPromptAbility
    }

    /** This pending's ability cue when [delegate] is null. */
    protected open val localPromptAbility: PlayerAbility? get() = null

    /** HUD / InputRequired player cue — follows [delegate]; else [localPromptPlayerId]. */
    fun promptPlayerId(): PlayerId? {
        val child = delegate
        return if (child != null) child.promptPlayerId() else localPromptPlayerId
    }

    /** This pending's player cue when [delegate] is null. */
    protected open val localPromptPlayerId: PlayerId? get() = null

    /** HUD / InputRequired bonus cue — follows [delegate]; else [localPromptBonusPlay]. */
    fun promptBonusPlay(): BonusPlay? {
        val child = delegate
        return if (child != null) child.promptBonusPlay() else localPromptBonusPlay
    }

    /** This pending's bonus cue when [delegate] is null. */
    protected open val localPromptBonusPlay: BonusPlay? get() = null

    override fun toString(): String =
        ReflectionToStringBuilder.toString(this, ToStringStyle.SHORT_PREFIX_STYLE)
}
