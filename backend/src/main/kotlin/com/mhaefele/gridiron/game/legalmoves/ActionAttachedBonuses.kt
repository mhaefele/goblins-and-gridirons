package com.mhaefele.gridiron.game.legalmoves

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.ThrowTarget
import com.mhaefele.gridiron.game.action.ability.lobberBlock
import com.mhaefele.gridiron.game.action.utils.isAssistedBlock
import com.mhaefele.gridiron.game.dice.throwCheckNeeded
import com.mhaefele.gridiron.model.CardState
import com.mhaefele.gridiron.model.TurnBonusModifiers
import com.mhaefele.gridiron.model.board.Board
import com.mhaefele.gridiron.model.catalog.BonusPlay
import com.mhaefele.gridiron.model.catalog.ThrowStat
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.shared.ThrowRange

/**
 * Geometry / availability bonuses that expand the legal-action list when in hand.
 * Diff against the base set; only actions that exist solely because of the card are emitted,
 * each tagged with [LegalAction.requiredBonusCard]. Sprint / Press / Nerves diff by path geometry;
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

    fun withRequiredBonus(action: LegalAction, bonus: BonusPlay): LegalAction =
        when (action) {
            is LegalAction.Run                 -> action.copy(requiredBonusCard = bonus)
            is LegalAction.Mark                -> action.copy(requiredBonusCard = bonus)
            is LegalAction.Sidestep            -> action.copy(requiredBonusCard = bonus)
            is LegalAction.Throw               -> action.copy(requiredBonusCard = bonus, target = action.target.withRequiredThrowBonus(bonus))
            is LegalAction.ThrowTeammateSelect -> action.copy(requiredBonusCard = bonus)
            is LegalAction.ThrowTeammate       -> action.copy(requiredBonusCard = bonus, target = action.target.withRequiredThrowBonus(bonus))
            is LegalAction.Block               -> action.copy(requiredBonusCard = bonus)
            is LegalAction.Foul                -> action.copy(requiredBonusCard = bonus)
            is LegalAction.DeclareRun          -> action.copy(requiredBonusCard = bonus)
            is LegalAction.StandUp             -> action.copy(requiredBonusCard = bonus)
            is LegalAction.Reserves            -> action.copy(requiredBonusCard = bonus)
            else                               -> action
        }

    /** Hail Mary / Dead Eye rewrite the check — keep [ThrowTarget.throwCheckNeeded] aligned with resolve. */
    private fun ThrowTarget.withRequiredThrowBonus(bonus: BonusPlay): ThrowTarget {
        val rewritten = when (bonus) {
            BonusPlay.`Hail Mary Pass` -> ThrowStat.Threshold(4)
            BonusPlay.`Dead Eye`       -> ThrowStat.Threshold(2)
            else                       -> return this
        }
        return copy(throwCheckNeeded = throwCheckNeeded(rewritten, ThrowRange.SHORT, obstructed = false))
    }

    fun withAvailableBonuses(action: LegalAction, bonuses: List<BonusPlay>): LegalAction {
        if (bonuses.isEmpty()) return action
        return when (action) {
            is LegalAction.Run        -> action.copy(availableBonusCards = bonuses)
            is LegalAction.Mark       -> action.copy(availableBonusCards = bonuses)
            is LegalAction.Sidestep   -> action.copy(availableBonusCards = bonuses)
            is LegalAction.Throw      -> action.copy(availableBonusCards = bonuses)
            is LegalAction.Block      -> action.copy(availableBonusCards = bonuses)
            is LegalAction.Foul       -> action.copy(availableBonusCards = bonuses)
            is LegalAction.DeclareRun -> action.copy(availableBonusCards = bonuses)
            is LegalAction.StandUp    -> action.copy(availableBonusCards = bonuses)
            is LegalAction.Reserves   -> action.copy(availableBonusCards = bonuses)
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
     * Actions in [expanded] whose geometry is absent from [base], tagged with [bonus].
     */
    fun bonusOnlyDiff(
        base: List<LegalAction>,
        expanded: List<LegalAction>,
        bonus: BonusPlay,
    ): List<LegalAction> {
        val baseKeys = base.map { geometryKey(it) }.toHashSet()
        return expanded
            .filter { geometryKey(it) !in baseKeys }
            .map { withRequiredBonus(it, bonus) }
    }

    /**
     * Like [bonusOnlyDiff], but also drops actions whose destination is already reachable in [base]
     * (same player + action type). Weave can change the "best" path to a square without unlocking it.
     */
    fun bonusOnlyNewDestinations(
        base: List<LegalAction>,
        expanded: List<LegalAction>,
        bonus: BonusPlay,
    ): List<LegalAction> {
        val baseDestinations = base.filterIsInstance<LegalAction.BoardTargetedLegalAction>()
            .map { destinationKey(it) }.toHashSet()
        return bonusOnlyDiff(base, expanded, bonus)
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
            val expanded = generate(pathBase.copy(sprint = true), false, false)
            extras += bonusOnlyDiff(base, expanded, BonusPlay.Sprint)
            // RandomD6 Sprint is a mid-check after plain DeclareRun (HANDLE_SPRINT), not a twin row.
        }
        if (hasHandCard(cards, coach, BonusPlay.Weave, used)) {
            val expanded = generate(pathBase.copy(weave = true), false, false)
            extras += bonusOnlyNewDestinations(base, expanded, BonusPlay.Weave)
        }
        if (hasHandCard(cards, coach, BonusPlay.Press, used)) {
            val expanded = generate(pathBase.copy(press = true), false, false)
            extras += bonusOnlyDiff(base, expanded, BonusPlay.Press)
        }
        if (hasHandCard(cards, coach, BonusPlay.`Ice Cold`, used)) {
            val expanded = generate(pathBase, false, true)
            extras += bonusOnlyDiff(base, expanded, BonusPlay.`Ice Cold`)
        }
        if (hasHandCard(cards, coach, BonusPlay.`Hail Mary Pass`, used)) {
            val expanded = generate(pathBase, true, false)
            extras += bonusOnlyDiff(base, expanded, BonusPlay.`Hail Mary Pass`)
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
        val jump = if (hasHandCard(cards, coach, BonusPlay.`Back on Your Feet`, used)) BonusPlay.`Back on Your Feet` else null
        val reserves = if (hasHandCard(cards, coach, BonusPlay.`Fresh Legs`, used)) BonusPlay.`Fresh Legs` else null
        val accurate = if (hasHandCard(cards, coach, BonusPlay.`Dead Eye`, used)) BonusPlay.`Dead Eye` else null
        val hailMary = if (hasHandCard(cards, coach, BonusPlay.`Hail Mary Pass`, used)) BonusPlay.`Hail Mary Pass` else null
        val underdog = if (hasHandCard(cards, coach, BonusPlay.Underdog, used)) BonusPlay.Underdog else null

        return actions.map { action ->
            if (action.requiredBonusCard != null) return@map action
            val offers = mutableListOf<BonusPlay>()
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
