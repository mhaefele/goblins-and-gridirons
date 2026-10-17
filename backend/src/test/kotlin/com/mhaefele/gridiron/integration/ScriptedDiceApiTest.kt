package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.integration.support.BaseApiTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestTemplate

/** HTTP queue endpoints for [com.mhaefele.gridiron.api.testonly.ScriptedDiceController]. */
class ScriptedDiceApiTest : BaseApiTest() {

    @Autowired
    private lateinit var restTemplate: RestTemplate

    @Test
    fun `posts queue overrides that are consumed in order`() {
        restTemplate.exchange(
            "/api/test/dice/d6",
            HttpMethod.POST,
            HttpEntity(mapOf("values" to listOf(6, 2))),
            Void::class.java,
        )
        restTemplate.exchange(
            "/api/test/dice/d8",
            HttpMethod.POST,
            HttpEntity(mapOf("values" to listOf(7))),
            Void::class.java,
        )
        restTemplate.exchange(
            "/api/test/dice/block",
            HttpMethod.POST,
            HttpEntity(mapOf("values" to listOf("MISS"))),
            Void::class.java,
        )

        assertThat(dice.d6()).isEqualTo(6)
        assertThat(dice.d6()).isEqualTo(2)
        assertThat(dice.d8()).isEqualTo(7)
        assertThat(dice.blockDice(1)).containsExactly(BlockDieFace.MISS)
    }

    @Test
    fun `rejects empty queue bodies`() {
        assertThatThrownBy { restTemplate.exchange("/api/test/dice/d6", HttpMethod.POST, HttpEntity(mapOf("values" to emptyList<Int>())), Void::class.java,) }
            .isInstanceOf(HttpStatusCodeException::class.java)
            .extracting { (it as HttpStatusCodeException).statusCode }
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `rejects out-of-range d6 values`() {
        assertThatThrownBy { restTemplate.exchange("/api/test/dice/d6", HttpMethod.POST, HttpEntity(mapOf("values" to listOf(9))), Void::class.java,) }
            .isInstanceOf(HttpStatusCodeException::class.java)
            .extracting { (it as HttpStatusCodeException).statusCode }
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
