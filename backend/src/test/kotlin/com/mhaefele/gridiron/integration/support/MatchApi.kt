package com.mhaefele.gridiron.integration.support

import com.mhaefele.gridiron.api.dto.response.BonusHandDto
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.MatchActionResponse
import com.mhaefele.gridiron.api.dto.response.MatchGameStateDto
import com.mhaefele.gridiron.api.dto.response.MatchSnapshotDto
import com.mhaefele.gridiron.api.dto.response.PieceSnapshotDto
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.LegalActionId
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.single
import kotlin.collections.single

/**
 * Match-scoped HTTP surface for API tests — no [MatchId] plumbing at the call site.
 *
 * [gameState] / [pieces] / [snapshot] are the public views.
 * [bonusHand] is the seat-private hand endpoint (same path the client uses).
 *
 * Prefer [singleLegalAction] / [play] reified helpers when exactly one action of a type is expected —
 * failures dump the full legal-action list for debugging.
 */
class MatchApi(
    @PublishedApi internal val client: GridironApiClient,
    val matchId: MatchId,
) {
    fun legalActions(): List<LegalAction> = client.legalActions(matchId)

    fun legalActions(actionType: ActionType): List<LegalAction> =
        client.legalActions(matchId, actionType)

    /** Typed legal actions — e.g. `legalActions<LegalAction.ReplaceChallenge>()`. */
    @JvmName("legalActionsTyped")
    inline fun <reified T : LegalAction> legalActions(): List<T> {
        val legalActions = client.legalActions(matchId)
        val instances = legalActions.filterIsInstance<T>()
        return instances
    }

    fun singleLegalAction(actionType: ActionType): LegalAction {
        val all = legalActions()
        val ofType = all.filter { it.actionType == actionType }
        check(ofType.size == 1) { "Expected exactly one $actionType; found ${ofType.size}. All legal actions:\n${all.formatForDebug()}" }
        return ofType.single()
    }

    inline fun <reified T : LegalAction> singleLegalAction(predicate: (T) -> Boolean = { true }): T {
        val all = legalActions()
        val ofType = all.filterIsInstance<T>().filter(predicate)
        check(ofType.size == 1) { "Expected exactly one ${T::class.simpleName} matching predicate; found ${ofType.size}. All legal actions:\n${all.formatForDebug()}" }
        return ofType.single()
    }

    inline fun <reified T : LegalAction> anyLegalAction(predicate: (T) -> Boolean = { true }): T {
        val all = legalActions()
        val ofType = all.filterIsInstance<T>().filter(predicate)
        check(ofType.isNotEmpty()) { "Expected at least one ${T::class.simpleName} matching predicate; found 0. All legal actions:\n${all.formatForDebug()}" }
        return ofType.first()
    }

    @JvmName("legalActionsTypedSingle")
    inline fun <reified T : LegalAction.PlayerLegalAction> singleLegalAction(player: PlacedPlayer, coordinate: Coordinate? = null)
        = legalActions().single<T>(player, coordinate)

    @JvmName("legalActionsTypedSingleAlt1")
    inline fun <reified T : LegalAction.PlayerLegalAction> singleLegalAction(player: PlacedPlayer, x: Int, y: Int)
        = singleLegalAction<T>(player, Coordinate(x, y))

    fun play(actionType: ActionType): MatchActionResponse = play(singleLegalAction(actionType))

    /** Assert exactly one [T] and play it. */
    inline fun <reified T : LegalAction> play(noinline predicate: (T) -> Boolean = { true }): MatchActionResponse =
        play(singleLegalAction(predicate))

    inline fun <reified T : LegalAction> playAny(noinline predicate: (T) -> Boolean = { true }): MatchActionResponse =
        play(anyLegalAction(predicate))

    /** Assert exactly one [T] for [player] (optional target) and play it. */
    inline fun <reified T : LegalAction.PlayerLegalAction> play(player: PlacedPlayer, coordinate: Coordinate? = null): MatchActionResponse =
        play(singleLegalAction<T>(player, coordinate))

    inline fun <reified T : LegalAction.PlayerLegalAction> play(player: PlacedPlayer, x: Int, y: Int): MatchActionResponse =
        play<T>(player, Coordinate(x, y))

    fun legalActionsForPlayer(player: PlacedPlayer, actionType: ActionType? = null): List<LegalAction> =
        client.legalActionsForPlayer(matchId, player, actionType)

    fun play(action: LegalAction): MatchActionResponse = client.play(matchId, action)

    fun play(legalActionId: LegalActionId): MatchActionResponse = client.play(matchId, legalActionId)

    fun play(player: PlacedPlayer, type: ActionType, target: Coordinate): MatchActionResponse =
        client.play(matchId, player, type, target)

    fun play(player: PlacedPlayer, type: ActionType, x: Int, y: Int): MatchActionResponse =
        client.play(matchId, player, type, x, y)

    fun snapshot(): MatchSnapshotDto = client.snapshot(matchId)

    fun pieces(): PieceSnapshotDto = client.pieces(matchId)

    /** Public match HUD — scores, challenge tableau, turn clock (no seat-private hands). */
    fun gameState(): MatchGameStateDto = client.gameState(matchId)

    /** Seat-private bonus hand via `GET .../bonus-hand?seat=`. */
    fun bonusHand(seat: TeamSide = TeamSide.HOME): BonusHandDto = client.bonusHand(matchId, seat)
}

@PublishedApi
internal fun List<LegalAction>.formatForDebug(): String =
    if (isEmpty()) "  (none)" else joinToString("\n") { "  $it" }
