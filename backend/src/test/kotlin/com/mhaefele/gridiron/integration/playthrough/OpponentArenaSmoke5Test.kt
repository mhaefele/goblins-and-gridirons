package com.mhaefele.gridiron.integration.playthrough

import com.mhaefele.gridiron.integration.support.BaseApiTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class OpponentArenaSmoke5Test:BaseApiTest() {
    @Autowired
    lateinit var opponentArena: OpponentArena

    @BeforeEach
    fun setup() {
        dice.usePassThrough()
    }

    @Test
    fun `six paired games`() {
        val series = opponentArena.playSeries(games = 6, seed = 42L)
        println(series)
        check(series.details.size == 6)
    }
}
