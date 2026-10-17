package com.mhaefele.gridiron.game.cards

import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.model.catalog.ArmourStat
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.ThrowRange
import com.mhaefele.gridiron.support.GameScenario
import com.mhaefele.gridiron.support.TestProfiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DoOverRollTest {

    @Test
    fun `armour injury is already optimal`() {
        assertThat(DoOverRoll.mayImproveArmour(1, ArmourStat.Threshold(5), flatten = false)).isFalse()
    }

    @Test
    fun `armour hold may improve`() {
        assertThat(DoOverRoll.mayImproveArmour(6, ArmourStat.Threshold(5), flatten = false)).isTrue()
    }

    @Test
    fun `throw success is already optimal`() {
        val setup = GameScenario.board()
        val thrower = setup.addPlayer(4, 6, profile = TestProfiles.lineman())
        assertThat(
            DoOverRoll.mayImproveThrow(
                setup.board,
                thrower.player.id,
                naturalRoll = 6,
                range = ThrowRange.SHORT,
                obstructed = false,
                false,
                false,
            ),
        ).isFalse()
    }

    @Test
    fun `throw fail may improve`() {
        val setup = GameScenario.board()
        val thrower = setup.addPlayer(4, 6, profile = TestProfiles.lineman())
        assertThat(
            DoOverRoll.mayImproveThrow(
                setup.board,
                thrower.player.id,
                naturalRoll = 1,
                range = ThrowRange.SHORT,
                obstructed = false,
                false,
                false,
            ),
        ).isTrue()
    }

    @Test
    fun `smash block face is already optimal`() {
        val setup = GameScenario.board()
        val blocker = setup.addPlayer(5, 8, profile = TestProfiles.withMove(2))
        assertThat(
            DoOverRoll.mayImproveBlock(
                setup.board,
                blocker.player.id,
                faces = listOf(BlockDieFace.SMASH),
            ),
        ).isFalse()
    }

    @Test
    fun `miss block face may improve`() {
        val setup = GameScenario.board()
        val blocker = setup.addPlayer(5, 8, profile = TestProfiles.withMove(2))
        assertThat(
            DoOverRoll.mayImproveBlock(
                setup.board,
                blocker.player.id,
                faces = listOf(BlockDieFace.MISS),
            ),
        ).isTrue()
    }

    @Test
    fun `enforcer with smash and miss may still improve`() {
        val setup = GameScenario.board()
        val enforcer = TeamCatalog.team("bloodsworn").roster.first { it.name == "Skullreaver" }
        val blocker = setup.addPlayer(5, 8, profile = enforcer)
        assertThat(
            DoOverRoll.mayImproveBlock(
                setup.board,
                blocker.player.id,
                faces = listOf(BlockDieFace.SMASH, BlockDieFace.MISS),
            ),
        ).isTrue()
    }
}
