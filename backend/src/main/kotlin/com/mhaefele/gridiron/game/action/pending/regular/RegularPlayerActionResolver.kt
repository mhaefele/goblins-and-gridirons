package com.mhaefele.gridiron.game.action.pending.regular

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.turn.MatchMutator
import com.mhaefele.gridiron.model.OpenPlayerAction
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.model.pending.ActionContinuation
import com.mhaefele.gridiron.model.pending.OfferExtraEffort
import com.mhaefele.gridiron.model.pending.RegularPlayerAction
import com.mhaefele.gridiron.model.pending.BlockContinuation
import com.mhaefele.gridiron.model.pending.FoulContinuation
import com.mhaefele.gridiron.model.pending.MarkContinuation
import com.mhaefele.gridiron.model.pending.ReservesContinuation
import com.mhaefele.gridiron.model.pending.RunContinuation
import com.mhaefele.gridiron.model.pending.ArmourCheckContinuation
import com.mhaefele.gridiron.model.pending.KnockDownContinuation
import com.mhaefele.gridiron.model.pending.BallGuardContinuation
import com.mhaefele.gridiron.model.pending.SidestepContinuation
import com.mhaefele.gridiron.model.pending.StandUpContinuation
import com.mhaefele.gridiron.model.pending.ThrowContinuation
import com.mhaefele.gridiron.model.pending.ThrowTeammateContinuation
import org.springframework.stereotype.Component
import com.mhaefele.gridiron.game.action.pending.PendingDecisionResolver
import com.mhaefele.gridiron.game.action.pending.block.BlockResolver
import com.mhaefele.gridiron.game.action.pending.run.RunResolver
import com.mhaefele.gridiron.game.action.pending.mark.MarkResolver
import com.mhaefele.gridiron.game.action.pending.sidestep.SidestepResolver
import com.mhaefele.gridiron.game.action.pending.foul.FoulResolver
import com.mhaefele.gridiron.game.action.pending.standup.StandUpResolver
import com.mhaefele.gridiron.game.action.pending.reserves.ReservesResolver
import com.mhaefele.gridiron.game.action.pending.throwball.ThrowResolver
import com.mhaefele.gridiron.game.action.pending.throwteammate.ThrowTeammateResolver

/**
 * takeAction owner for [RegularPlayerAction].
 * Bare RPA begins an open action and returns RPA with the *Continuation on [RegularPlayerAction.delegate].
 * With a delegate, follows into the matching *Resolver and returns the updated RPA.
 * Caller parks via [MatchMutator.updatePendingDecision] (root may be RPA or [OfferExtraEffort]).
 */
@Component
class RegularPlayerActionResolver(
    private val blockResolver: BlockResolver,
    private val foulResolver: FoulResolver,
    private val runResolver: RunResolver,
    private val markResolver: MarkResolver,
    private val sidestepResolver: SidestepResolver,
    private val throwResolver: ThrowResolver,
    private val throwTeammateResolver: ThrowTeammateResolver,
    private val standUpResolver: StandUpResolver,
    private val reservesResolver: ReservesResolver,
) : PendingDecisionResolver<RegularPlayerAction>() {
    fun begin(@Suppress("UNUSED_PARAMETER") session: MatchMutator): RegularPlayerAction =
        RegularPlayerAction()

    override fun mutate(
        session: MatchMutator,
        pending: RegularPlayerAction,
        command: LegalAction,
    ): RegularPlayerAction =
        when (val child = pending.delegate) {
            null -> beginAndDelegate(session, pending, command)
            else -> continueDelegate(session, pending, child, command)
        }

    private fun beginAndDelegate(
        session: MatchMutator,
        decision: RegularPlayerAction,
        command: LegalAction,
    ): RegularPlayerAction {
        command.requiredBonusCard?.let { required ->
            session.playBonusCard(session.activeCoach, required.bonusPlay)
        }
        val playerAction = command as? LegalAction.PlayerLegalAction ?: error("RegularPlayerAction expects PlayerLegalAction, was ${command::class.simpleName}")
        session.beginPlayerAction(playerAction.playerId, playerAction.actionType, OpenPlayerAction.Origin.BUDGETED)
        maybeConvertDanceStepSidestepToFree(session, command)
        return decision.copy(delegate = beginSelected(session, command))
    }

    /**
     * Dance Step: "This Player can make a free Sidestep Action."
     * A budgeted Sidestep by a Glade Dancer (root RPA only — not Extra Effort's fourth slot) converts in place.
     */
    private fun maybeConvertDanceStepSidestepToFree(session: MatchMutator, command: LegalAction) {
        if (command !is LegalAction.Sidestep) return
        if (session.requirePendingDecision() is OfferExtraEffort) return
        val player = session.board.getPlayer(command.playerId)
        if (!player.hasAbility(PlayerAbility.`Dance Step`)) return
        session.returnRegularActionSlot()
        session.markCurrentPlayerActionFree()
    }

    private fun continueDelegate(
        session: MatchMutator,
        decision: RegularPlayerAction,
        child: ActionContinuation,
        command: LegalAction,
    ): RegularPlayerAction {
        val next: ActionContinuation = when (child) {
            is ThrowContinuation         -> throwResolver.mutate(session, child, command)
            is ThrowTeammateContinuation -> throwTeammateResolver.mutate(session, child, command)
            is BlockContinuation         -> blockResolver.mutate(session, child, command)
            is RunContinuation           -> runResolver.mutate(session, child, command)
            is MarkContinuation          -> markResolver.mutate(session, child, command)
            is SidestepContinuation      -> sidestepResolver.mutate(session, child, command)
            is StandUpContinuation       -> standUpResolver.mutate(session, child, command)
            is ReservesContinuation      -> reservesResolver.mutate(session, child, command)
            is FoulContinuation          -> foulResolver.mutate(session, child, command)
            is BallGuardContinuation     -> error("BallGuardContinuation nests under KnockDown, not RegularPlayerAction.delegate")
            is KnockDownContinuation     -> error("KnockDownContinuation nests under Block / ThrowTeammate, not RegularPlayerAction.delegate")
            is ArmourCheckContinuation   -> error("ArmourCheckContinuation nests under KnockDown / Block / ThrowTeammate, not RegularPlayerAction.delegate")
        }
        // Cancel Throw Team-mate → bare selecting RPA again (DONE + cancelled; never park for COMPLETE).
        return if (next is ThrowTeammateContinuation && next.ongoingThrowTeammate.cancelled) {
            decision.copy(delegate = null)
        } else {
            decision.copy(delegate = next)
        }
    }

    private fun beginSelected(session: MatchMutator, command: LegalAction): ActionContinuation =
        when (command) {
            is LegalAction.DeclareRun          -> runResolver.beginDeclareRun(session, command)
            is LegalAction.Run                 -> runResolver.beginRun(session, command)
            is LegalAction.Mark                -> markResolver.beginMark(session, command)
            is LegalAction.Sidestep            -> sidestepResolver.beginSidestep(session, command)
            is LegalAction.Throw               -> throwResolver.beginThrow(session, command)
            is LegalAction.ThrowTeammateSelect -> throwTeammateResolver.beginThrowTeammate(session, command)
            is LegalAction.Block               -> blockResolver.beginBlock(session, command)
            is LegalAction.Foul                -> foulResolver.beginFoul(session, command)
            is LegalAction.StandUp             -> standUpResolver.beginStandUp(session, command)
            is LegalAction.Reserves            -> reservesResolver.beginReserves(session, command)
            else                               -> error(
                "RegularPlayerAction cannot begin ${command::class.simpleName} — " + "mid-action / offer / claim commands belong on their continuation resolvers",)
        }
}
