package com.mhaefele.gridiron.support

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import org.assertj.core.api.Assertions.assertThat

/**
 * Single-purpose legal-action asserts and finders.
 *
 * Prefer these over `filterIsInstance<…>().first { … }` / `isNotEmpty()` — each call asks one
 * question (available / unavailable / any / none) so failures stay readable.
 */

fun List<LegalAction>.matching(
    type: ActionType,
    playerId: PlayerId? = null,
    target: Coordinate? = null,
): List<LegalAction> =
    filter {
        it.actionType == type &&
            (playerId == null || (it is LegalAction.PlayerLegalAction && it.playerId == playerId)) &&
            (target == null || (it is LegalAction.BoardTargetedLegalAction && it.targetCoordinate == target))
    }

fun List<LegalAction>.matching(
    type: ActionType,
    player: PlacedPlayer,
    target: Coordinate? = null,
): List<LegalAction> = matching(type, player.player.id, target)

fun List<LegalAction>.matching(
    type: ActionType,
    player: Player,
    target: Coordinate? = null,
): List<LegalAction> = matching(type, player.id, target)

/** Find the single matching action — for [com.mhaefele.gridiron.integration.support.GridironApiClient.play]. */
fun List<LegalAction>.action(
    player: PlacedPlayer,
    type: ActionType,
    target: Coordinate,
): LegalAction =
    matching(type, player, target).singleOrNull() ?: error("Expected one $type for ${player.player.id} → $target; " + "found ${matching(type, player, target).size}. All legal actions:\n" + if (isEmpty()) "  (none)" else joinToString("\n") { "  $it" })

fun List<LegalAction>.action(
    player: PlacedPlayer,
    type: ActionType,
    x: Int,
    y: Int,
): LegalAction = action(player, type, Coordinate(x, y))

fun List<LegalAction>.action(
    player: Player,
    type: ActionType,
    target: Coordinate,
): LegalAction =
    matching(type, player, target).singleOrNull() ?: error("Expected one $type for ${player.id} → $target; " + "found ${matching(type, player, target).size}. All legal actions:\n" + if (isEmpty()) "  (none)" else joinToString("\n") { "  $it" })

fun List<LegalAction>.onlyAction(type: ActionType, player: PlacedPlayer? = null): LegalAction {
    val hits = matching(type, player?.player?.id)
    return hits.singleOrNull() ?: error("Expected exactly one $type${player?.let { " for ${it.player.id}" } ?: ""}; " + "found ${hits.size}. All legal actions:\n" + if (isEmpty()) "  (none)" else joinToString("\n") { "  $it" })
}

fun List<LegalAction>.assertAvailable(
    player: PlacedPlayer,
    type: ActionType,
    target: Coordinate,
) {
    assertThat(matching(type, player, target))
        .withFailMessage("Expected $type available for ${player.player.id} → $target")
        .isNotEmpty()
}

fun List<LegalAction>.assertAvailable(
    player: PlacedPlayer,
    type: ActionType,
    x: Int,
    y: Int,
) = assertAvailable(player, type, Coordinate(x, y))

fun List<LegalAction>.assertUnavailable(
    player: PlacedPlayer,
    type: ActionType,
    target: Coordinate,
) {
    assertThat(matching(type, player, target))
        .withFailMessage("Expected no $type for ${player.player.id} → $target")
        .isEmpty()
}

fun List<LegalAction>.assertUnavailable(
    player: PlacedPlayer,
    type: ActionType,
    x: Int,
    y: Int,
) = assertUnavailable(player, type, Coordinate(x, y))

fun List<LegalAction>.assertAny(type: ActionType, player: PlacedPlayer? = null) {
    assertThat(matching(type, player?.player?.id))
        .withFailMessage("Expected some $type${player?.let { " for ${it.player.id}" } ?: ""}")
        .isNotEmpty()
}

fun List<LegalAction>.assertNone(type: ActionType, player: PlacedPlayer? = null) {
    assertThat(matching(type, player?.player?.id))
        .withFailMessage("Expected no $type${player?.let { " for ${it.player.id}" } ?: ""}")
        .isEmpty()
}

/**
 * Assert a path movement of [type] reaches [target] and passes through [via] as an intermediate square.
 */
fun List<LegalAction>.assertAvailableVia(
    player: PlacedPlayer,
    type: ActionType,
    target: Coordinate,
    via: Coordinate,
) {
    val hits = matching(type, player, target)
        .filterIsInstance<LegalAction.PathMovement>()
        .filter { via in it.path.squares.dropLast(1) }
    assertThat(hits)
        .withFailMessage("Expected $type for ${player.player.id} → $target via $via")
        .isNotEmpty()
}

fun List<LegalAction>.assertAvailableVia(
    player: PlacedPlayer,
    type: ActionType,
    targetX: Int,
    targetY: Int,
    viaX: Int,
    viaY: Int,
) = assertAvailableVia(player, type, Coordinate(targetX, targetY), Coordinate(viaX, viaY))

/** No path of [type] for [player] uses [via] as an intermediate square. */
fun List<LegalAction>.assertUnavailableVia(
    player: PlacedPlayer,
    type: ActionType,
    via: Coordinate,
) {
    val hits = matching(type, player)
        .filterIsInstance<LegalAction.PathMovement>()
        .filter { via in it.path.squares.dropLast(1) }
    assertThat(hits)
        .withFailMessage("Expected no $type for ${player.player.id} via intermediate $via")
        .isEmpty()
}

fun List<LegalAction>.assertUnavailableVia(
    player: PlacedPlayer,
    type: ActionType,
    viaX: Int,
    viaY: Int,
) = assertUnavailableVia(player, type, Coordinate(viaX, viaY))
