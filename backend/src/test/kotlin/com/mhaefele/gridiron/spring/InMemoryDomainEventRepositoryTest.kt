package com.mhaefele.gridiron.spring

import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.DomainEventId
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.shared.PlayerId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InMemoryDomainEventRepositoryTest {
    private val repository = InMemoryDomainEventRepository()
    private val matchA = MatchId("match-a")
    private val matchB = MatchId("match-b")

    @Test
    fun `append assigns monotonic domainId per match starting at 1`() {
        val batch = listOf(
            DomainEvent.ActionStarted(PlayerId("h0"), Coordinate(1, 1), ActionType.RUN),
            DomainEvent.ActionComplete(PlayerId("h0"), Coordinate(2, 2), ActionType.RUN),
        )

        val stored = repository.append(matchA, batch)

        assertThat(stored.map { it.id }).containsExactly(
            DomainEventId(matchA, 1),
            DomainEventId(matchA, 2),
        )
        assertThat(stored.map { it.event }).containsExactlyElementsOf(batch)
    }

    @Test
    fun `domainId sequences are independent per match`() {
        repository.append(matchA, listOf(event()))
        repository.append(matchB, listOf(event()))
        val secondA = repository.append(matchA, listOf(event())).single()

        assertThat(secondA.id).isEqualTo(DomainEventId(matchA, 2))
        assertThat(repository.findByMatchId(matchB).single().id).isEqualTo(DomainEventId(matchB, 1))
    }

    @Test
    fun `find after domainId returns only later events`() {
        repository.append(matchA, listOf(event(), event(), event()))

        val afterSecond = repository.findByMatchIdAfter(matchA, afterDomainId = 2)

        assertThat(afterSecond.map { it.id.domainId }).containsExactly(3)
    }

    @Test
    fun `empty append returns empty list without advancing counter`() {
        assertThat(repository.append(matchA, emptyList())).isEmpty()

        val first = repository.append(matchA, listOf(event())).single()
        assertThat(first.id.domainId).isEqualTo(1)
    }

    @Test
    fun `unknown match returns empty list`() {
        assertThat(repository.findByMatchId(MatchId("missing"))).isEmpty()
    }

    private fun event(): DomainEvent =
        DomainEvent.PlayerMoved(PlayerId("h0"), Coordinate(1, 1), Coordinate(2, 2))
}
