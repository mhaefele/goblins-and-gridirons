package com.mhaefele.gridiron.support

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.api.dto.response.MatchActionResponse
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.action.dto.StoredDomainEvent
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.MatchId
import org.assertj.core.api.Assertions.assertThat

/** Unwrap stored events for assertions that only care about resolution payloads. */
fun List<StoredDomainEvent>.bareEvents(): List<DomainEvent> = map { it.event }

fun assertEventsExactly(events: List<StoredDomainEvent>, vararg expected: DomainEvent) {
    assertThat(events.bareEvents()).containsExactly(*expected)
}

fun assertEventsExactly(response: MatchActionResponse, vararg expected: DomainEvent) {
    assertEventsExactly(response.events, *expected)
}

fun assertEventsContain(events: List<StoredDomainEvent>, vararg expected: DomainEvent) {
    assertThat(events.bareEvents()).contains(*expected)
}

fun assertEventsContain(response: MatchActionResponse, vararg expected: DomainEvent) {
    assertEventsContain(response.events, *expected)
}

/** Assert per-match monotonic ids when the test cares about persistence, not just payloads. */
fun assertEventIds(events: List<StoredDomainEvent>, matchId: MatchId, vararg domainIds: Int) {
    assertThat(events.map { it.id.matchId }).containsOnly(matchId)
    assertThat(events.map { it.id.domainId }).containsExactlyElementsOf(domainIds.toList())
}

fun assertEventIds(response: MatchActionResponse, matchId: MatchId, vararg domainIds: Int) {
    assertEventIds(response.events, matchId, *domainIds)
}

inline fun <reified T : LegalAction.PlayerLegalAction> List<LegalAction>.single(player: PlacedPlayer, coordinate: Coordinate? = null): T {
    var foundActions = this
        .filterIsInstance(T::class.java)
        .filter { it.playerId == player.id }

    if (coordinate != null) {
        foundActions = foundActions.filter { it is LegalAction.BoardTargetedLegalAction && it.targetCoordinate == coordinate }
    }

    val target = coordinate?.let { " at $it" }.orEmpty()
    check(foundActions.size == 1) { "Expected exactly one ${T::class.simpleName} for ${player.player.id}$target; found ${foundActions.size}. All legal actions:\n${if (isEmpty()) "  (none)" else joinToString("\n") { "  $it" }}" }
    return foundActions.single()
}
