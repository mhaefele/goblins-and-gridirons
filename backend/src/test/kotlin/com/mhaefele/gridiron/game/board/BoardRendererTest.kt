package com.mhaefele.gridiron.game.board

import com.mhaefele.gridiron.model.board.Ball
import com.mhaefele.gridiron.model.board.BallId
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.model.catalog.PitchCatalog
import com.mhaefele.gridiron.support.GameScenario
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BoardRendererTest {

    private val definition = PitchCatalog.default

    @Test
    fun `empty default layout golden output`() {
        val rendered = renderBoard(definition.emptyBoard())

        assertThat(rendered).isEqualTo(
            """
            #############
            #===========#
            #...........#
            #...........#
            #..XX...XX..#
            #..XX...XX..#
            #...........#
            #...........#
            #.....+.....#
            #...........#
            #...........#
            #..XX...XX..#
            #..XX...XX..#
            #...........#
            #...........#
            #-----------#
            #############
            """.trimIndent(),
        )
    }

    @Test
    fun `empty barricade layout golden output`() {
        val rendered = renderBoard(PitchCatalog.barricade.emptyBoard())

        assertThat(rendered).isEqualTo(
            """
            #############
            #===========#
            #...........#
            #.....X.....#
            #...........#
            #..XX...XX..#
            #...........#
            #...........#
            #..+..X..+..#
            #...........#
            #...........#
            #..XX...XX..#
            #...........#
            #.....X.....#
            #...........#
            #-----------#
            #############
            """.trimIndent(),
        )
    }

    @Test
    fun `display render includes legend beside board`() {
        val rendered = renderBoardForDisplay(definition.emptyBoard())

        assertThat(rendered).contains("Hh HOME")
        assertThat(rendered.lines()).hasSize(17)
    }

    @Test
    fun `render shows standing and prone players loose ball and carrier`() {
        val looseCoord = Coordinate(6, 8)
        val heldBall = Ball(BallId("ball-held"))
        val looseBall = Ball(BallId("ball-loose"))
        val scenario = GameScenario.board(definition.emptyBoard())
        scenario.addPlayer(Coordinate(2, 1))
        scenario.addPlayer(Coordinate(4, 1), status = PlayerStatus.PRONE)
        scenario.addPlayer(Coordinate(8, 15), team = TeamSide.AWAY)
        scenario.holdingBall(scenario.addPlayer(Coordinate(9, 15), team = TeamSide.AWAY), heldBall)
        scenario.addLooseBall(looseCoord, looseBall)

        val rendered = renderBoard(scenario.board)

        assertThat(rendered).contains("H")
        assertThat(rendered).contains("A")
        assertThat(rendered).contains("h")
        assertThat(rendered).contains("%")
        assertThat(rendered.lines()[8][6]).isEqualTo('o')
    }
}
