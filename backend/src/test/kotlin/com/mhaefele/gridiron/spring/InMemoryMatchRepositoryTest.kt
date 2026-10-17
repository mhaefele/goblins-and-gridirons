package com.mhaefele.gridiron.spring

import com.mhaefele.gridiron.api.MatchNotFoundException
import com.mhaefele.gridiron.shared.MatchId
import com.mhaefele.gridiron.support.matchState
import com.mhaefele.gridiron.support.withActionsRemaining
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class InMemoryMatchRepositoryTest {
    private val repository = InMemoryMatchRepository()

    @Test
    fun `save then find returns equal state`() {
        val state = matchState(id = MatchId("repo-match"))

        repository.save(state)

        assertThat(repository.findById(MatchId("repo-match"))).isEqualTo(state)
    }

    @Test
    fun `missing id throws not found`() {
        assertThatThrownBy { repository.findById(MatchId("missing")) }
            .isInstanceOf(MatchNotFoundException::class.java)
    }

    @Test
    fun `save overwrites existing id`() {
        val original = matchState(id = MatchId("overwrite"))
        val updated = original.withActionsRemaining(1)

        repository.save(original)
        repository.save(updated)

        assertThat(repository.findById(MatchId("overwrite"))).isEqualTo(updated)
    }
}
