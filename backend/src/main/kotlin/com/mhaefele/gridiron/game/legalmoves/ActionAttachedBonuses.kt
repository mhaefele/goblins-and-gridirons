package com.mhaefele.gridiron.game.legalmoves

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.RequiredBonusCard
import com.mhaefele.gridiron.game.action.ability.lobberBlock
import com.mhaefele.gridiron.game.action.utils.isAssistedBlock
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.TurnBonusModifiers
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange

/**
 * Geometry / availability bonuses that expand the legal-action list when in hand.
 * Diff against the base set; only actions that exist solely because of the card are emitted,
 * each tagged with [RequiredBonusCard]. Sprint / Press / Nerves diff by path geometry;
 * Weave diffs by destination (a clear path to the same square must not get a Weave twin).
 *
 * Optional offers (Back on Your Feet, Dead Eye, …) are annotated via [annotateAvailableBonuses].
 */
internal object ActionAttachedBonuses {
    fun hasHandCard(
        cards: CardState,
        coach: TeamSide,
        bonus: BonusPlay,
        used: Set<BonusPlay>,
    ): Boolean {
        if (bonus in used) return false
        return cards.hasBonusInHand(coach, bonus)
    }

    fun withRequiredBonus(action: LegalAction, card: RequiredBonusCard): LegalAction =
        when (action) {
            is LegalAction.Run                 -> action.copy(requiredBonusCard = card)
            is LegalAction.Mark                -> action.copy(requiredBonusCard = card)
            is LegalAction.Sidestep            -> action.copy(requiredBonusCard = card)
            is LegalAction.Throw               -> action.copy(requiredBonusCard = card)
            is LegalAction.ThrowTeammateSelect -> action.copy(requiredBonusCard = card)
            is LegalAction.ThrowTeammate       -> action.copy(requiredBonusCard = card)
            is LegalAction.Block               -> action.copy(requiredBonusCard = card)
            is LegalAction.Foul                -> action.copy(requiredBonusCard = card)
            is LegalAction.DeclareRun          -> action.copy(requiredBonusCard = card)
            is LegalAction.StandUp             -> action.copy(requiredBonusCard = card)
            is LegalAction.Reserves            -> action.copy(requiredBonusCard = card)
            else                               -> action
        }

    fun withAvailableBonuses(action: LegalAction, cards: List<RequiredBonusCard>): LegalAction {
        if (cards.isEmpty()) return action
        return when (action) {
            is LegalAction.Run        -> action.copy(availableBonusCards = cards)
            is LegalAction.Mark       -> action.copy(availableBonusCards = cards)
            is LegalAction.Sidestep   -> action.copy(availableBonusCards = cards)
            is LegalAction.Throw      -> action.copy(availableBonusCards = cards)
            is LegalAction.Block      -> action.copy(availableBonusCards = cards)
            is LegalAction.Foul       -> action.copy(availableBonusCards = cards)
            is LegalAction.DeclareRun -> action.copy(availableBonusCards = cards)
            is LegalAction.StandUp    -> action.copy(availableBonusCards = cards)
            is LegalAction.Reserves   -> action.copy(availableBonusCards = cards)
            else                      -> action
        }
    }

    /** Geometry key ignoring bonus suffix — used to diff base vs expanded sets. */
    fun geometryKey(action: LegalAction): String =
        when (action) {
            is LegalAction.PathMovement -> action.actionType.name + "_" + action.playerId.value + "_" + action.path.squares.joinToString("_") { "${it.x}_${it.y}" }
            is LegalAction.Throw        -> "THROW_${action.playerId.value}_${action.target.destination.x}_${action.target.destination.y}"
            is LegalAction.Block        -> "BLOCK_${action.playerId.value}_${action.destination.x}_${action.destination.y}"
            is LegalAction.Foul         -> "FOUL_${action.playerId.value}_${action.destination.x}_${action.destination.y}"
            is LegalAction.DeclareRun   -> "DECLARE_RUN_${action.playerId.value}"
            else                        -> action.id.value
        }

    /** Player + action type + destination — used so Weave is not offered when a clear path already reaches the square. */
    fun destinationKey(action: LegalAction.BoardTargetedLegalAction): String =
        action.actionType.name + "_" + action.playerId.value + "_" + "${action.targetCoordinate.x}_${action.targetCoordinate.y}"

    /**
     * Actions in [expanded] whose geometry is absent from [base], tagged with [card].
     */
    fun bonusOnlyDiff(
        base: List<LegalAction>,
        expanded: List<LegalAction>,
        card: RequiredBonusCard,
    ): List<LegalAction> {
        val baseKeys = base.map { geometryKey(it) }.toHashSet()
        return expanded
            .filter { geometryKey(it) !in baseKeys }
            .map { withRequiredBonus(it, card) }
    }

    /**
     * Like [bonusOnlyDiff], but also drops actions whose destination is already reachable in [base]
     * (same player + action type). Weave can change the "best" path to a square without unlocking it.
     */
    fun bonusOnlyNewDestinations(
        base: List<LegalAction>,
        expanded: List<LegalAction>,
        card: RequiredBonusCard,
    ): List<LegalAction> {
        val baseDestinations = base.filterIsInstance<LegalAction.BoardTargetedLegalAction>()
            .map { destinationKey(it) }.toHashSet()
        return bonusOnlyDiff(base, expanded, card)
            .filterIsInstance<LegalAction.BoardTargetedLegalAction>()
            .filter { destinationKey(it) !in baseDestinations }
    }

    fun expandFromHand(
        base: List<LegalAction>,
        generate: (PathBonusFlags, Boolean, Boolean) -> List<LegalAction>,
        turnBonuses: TurnBonusModifiers,
        cards: CardState,
        coach: TeamSide,
        used: Set<BonusPlay>,
    ): List<LegalAction> {
        val extras = mutableListOf<LegalAction>()
        val pathBase = turnBonuses.toPathFlags()

        if (hasHandCard(cards, coach, BonusPlay.Sprint, used)) {
            val card = RequiredBonusCard(BonusPlay.Sprint)
            val expanded = generate(pathBase.copy(sprint = true), false, false)
            extras += bonusOnlyDiff(base, expanded, card)
            // RandomD6 Sprint is a mid-check after plain DeclareRun (HANDLE_SPRINT), not a twin row.
        }
        if (hasHandCard(cards, coach, BonusPlay.Weave, used)) {
            val card = RequiredBonusCard(BonusPlay.Weave)
            val expanded = generate(pathBase.copy(weave = true), false, false)
            extras += bonusOnlyNewDestinations(base, expanded, card)
        }
        if (hasHandCard(cards, coach, BonusPlay.Press, used)) {
            val card = RequiredBonusCard(BonusPlay.Press)
            val expanded = generate(pathBase.copy(press = true), false, false)
            extras += bonusOnlyDiff(base, expanded, card)
        }
        if (hasHandCard(cards, coach, BonusPlay.`Ice Cold`, used)) {
            val card = RequiredBonusCard(BonusPlay.`Ice Cold`)
            val expanded = generate(pathBase, false, true)
            extras += bonusOnlyDiff(base, expanded, card)
        }
        if (hasHandCard(cards, coach, BonusPlay.`Hail Mary Pass`, used)) {
            val card = RequiredBonusCard(BonusPlay.`Hail Mary Pass`)
            val expanded = generate(pathBase, true, false)
            extras += bonusOnlyDiff(base, expanded, card)
        }

        return base + extras
    }

    /**
     * Tag Stand Up / Reserves / Throw / Block with optional [availableBonusCards] for UI + post-submit windows.
     * Geometry-required actions keep an empty available list.
     */
    fun annotateAvailableBonuses(
        actions: List<LegalAction>,
        board: Board?,
        cards: CardState,
        coach: TeamSide,
        used: Set<BonusPlay>,
        turnBonuses: TurnBonusModifiers = TurnBonusModifiers(),
    ): List<LegalAction> {
        val jump = if (hasHandCard(cards, coach, BonusPlay.`Back on Your Feet`, used)) RequiredBonusCard(BonusPlay.`Back on Your Feet`) else null
        val reserves = if (hasHandCard(cards, coach, BonusPlay.`Fresh Legs`, used)) RequiredBonusCard(BonusPlay.`Fresh Legs`) else null
        val accurate = if (hasHandCard(cards, coach, BonusPlay.`Dead Eye`, used)) RequiredBonusCard(BonusPlay.`Dead Eye`) else null
        val hailMary = if (hasHandCard(cards, coach, BonusPlay.`Hail Mary Pass`, used)) RequiredBonusCard(BonusPlay.`Hail Mary Pass`) else null
        val underdog = if (hasHandCard(cards, coach, BonusPlay.Underdog, used)) RequiredBonusCard(BonusPlay.Underdog) else null

        return actions.map { action ->
            if (action.requiredBonusCard != null) return@map action
            val offers = mutableListOf<RequiredBonusCard>()
            when (action) {
                is LegalAction.StandUp  -> if (jump != null) offers += jump
                is LegalAction.Reserves -> if (reserves != null) offers += reserves
                is LegalAction.Throw    -> {
                    if (action.target.range != ThrowRange.HAND_OFF) {
                        if (accurate != null) offers += accurate
                        if (hailMary != null) offers += hailMary
                    }
                }
                is LegalAction.Block    -> {
                    if (underdog != null && board != null && isUnassistedBlock(action, board, turnBonuses)) {
                        offers += underdog
                    }
                }
                else                    -> Unit
            }
            withAvailableBonuses(action, offers)
        }
    }

    private fun isUnassistedBlock(
        action: LegalAction.Block,
        board: Board,
        turnBonuses: TurnBonusModifiers,
    ): Boolean {
        if (turnBonuses.gangUp) return false
        val blocker = board.player(action.playerId)
        val targetId = board.squareAt(action.destination).playerId ?: return true
        val target = board.player(targetId)
        if (lobberBlock(blocker)) return true
        return !isAssistedBlock(blocker, target, board)
    }
}
