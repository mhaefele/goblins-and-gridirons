package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.game.action.dto.DomainEvent
import com.mhaefele.gridiron.game.dice.dto.BlockDieFace
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.integration.support.MatchApi
import com.mhaefele.gridiron.integration.support.player
import com.mhaefele.gridiron.model.pending.PlayerDecisionType
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.ArmourStat
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.ActionType
import com.mhaefele.gridiron.shared.CoachControl
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.PlayerId
import com.mhaefele.gridiron.shared.TeamSide
import com.mhaefele.gridiron.support.MatchBuilder
import com.mhaefele.gridiron.support.PlacedPlayer
import com.mhaefele.gridiron.support.TestProfiles
import com.mhaefele.gridiron.support.assertNone
import com.mhaefele.gridiron.support.bareEvents
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * PA.F.1 / D1.C-fix — Enforcer applies every die in order (including vs Prone), armour per KD,
 * auto follow-up after Shove.
 */
class EnforcerAbilityApiTest : BaseApiTest() {

    private fun enforcerProfile() =
        TeamCatalog.team("bloodsworn").roster.first { it.name == "Skullreaver" }

    /** AV 3+ so Flatten can still hold on a 6 (natural−1 ≥ 3). */
    private fun softTargetProfile() =
        TestProfiles.dwarfLineman().let { p ->
            p.copy(stats = p.stats.copy(armour = ArmourStat.Threshold(3)))
        }

    private fun playEnforcerBlock(match: MatchApi, enforcerId: PlayerId) {
        val block = match.legalActions<LegalAction.Block>()
            .single { it.playerId == enforcerId }
        match.play(block)
    }

    private fun chooseOrder(
        match: MatchApi,
        faces: List<BlockDieFace>,
    ): LegalAction.ChooseBlockFaceOrder =
        match.legalActions<LegalAction.ChooseBlockFaceOrder>()
            .single { it.orderedFaces == faces }

    /** After Block: play the HUMAN order prompt if present (no-op when auto-sorted). */
    private fun resolveEnforcerOrderIfPrompted(match: MatchApi, faces: List<BlockDieFace>) {
        val orders = match.legalActions<LegalAction.ChooseBlockFaceOrder>()
        if (orders.isEmpty()) return
        match.play(orders.single { it.orderedFaces == faces })
    }

    /**
     * Blocker (5,6) → target (6,6); shove lands on (7,6). Assist at (6,5) when 3 dice needed.
     */
    private fun enforcerMatch(diceCount: Int): Triple<
        PlacedPlayer,
        PlacedPlayer,
        MatchApi,
        > {
        require(diceCount in 2..3)
        lateinit var enforcer: PlacedPlayer
        lateinit var target: PlacedPlayer
        val match = match {
            turnNumber(1)
            actionsRemaining(2)
            enforcer = addPlayer(5, 6, profile = enforcerProfile())
            target = addPlayer(6, 6, team = TeamSide.AWAY, profile = softTargetProfile())
            addPlayer(2, 6, profile = TestProfiles.dwarfLineman()) // teammate keeps menu after Miss turn-end
            if (diceCount == 3) {
                addPlayer(6, 5, profile = TestProfiles.dwarfLineman()) // assist → 3rd die
            }
        }
        return Triple(enforcer, target, match)
    }

    @Test
    fun `enforcer miss then smash still knocks down`() {
        val (enforcer, target, match) = enforcerMatch(2)
        dice.queueBlockDice(BlockDieFace.MISS, BlockDieFace.SMASH)
        dice.queueD6(6) // armour holds (queued before Block — auto or prompted order)
        playEnforcerBlock(match, enforcer.player.id)
        resolveEnforcerOrderIfPrompted(match, listOf(BlockDieFace.MISS, BlockDieFace.SMASH))

        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(player(match.pieces(), enforcer.player.id).at).isEqualTo(Coordinate(5, 6))
    }

    @Test
    fun `trainer seat auto-sorts enforcer order without prompting`() {
        lateinit var enforcer: PlacedPlayer
        lateinit var target: PlacedPlayer
        val seeded = MatchBuilder().apply {
            turnNumber(1)
            actionsRemaining(2)
            enforcer = addPlayer(5, 6, profile = enforcerProfile())
            target = addPlayer(6, 6, team = TeamSide.AWAY, profile = softTargetProfile())
            addPlayer(2, 6, profile = TestProfiles.dwarfLineman()) // teammate keeps menu after Miss turn-end
        }.toMatchState().let { state ->
            state.copy(config = state.config.copy(homeControlledBy = CoachControl.HARNESS))
        }
        matchId = seedMatch(seeded)
        val match = MatchApi(api, matchId)

        dice.queueBlockDice(BlockDieFace.MISS, BlockDieFace.SMASH)
        dice.queueD6(6)
        playEnforcerBlock(match, enforcer.player.id)

        assertThat(match.legalActions<LegalAction.ChooseBlockFaceOrder>()).isEmpty()
        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.PRONE)
        match.legalActions().assertNone(ActionType.CHOOSE_BLOCK_FACE_ORDER)
        match.legalActions().assertNone(ActionType.SKIP)
    }

    @Test
    fun `enforcer smash then smash can force two armour checks`() {
        val (enforcer, target, match) = enforcerMatch(2)
        dice.queueBlockDice(BlockDieFace.SMASH, BlockDieFace.SMASH)
        dice.queueD6(6) // first armour holds
        dice.queueD6(1) // second armour fails → injured
        playEnforcerBlock(match, enforcer.player.id)
        // Single distinct perm → auto-sort; no order prompt.
        assertThat(match.legalActions<LegalAction.ChooseBlockFaceOrder>()).isEmpty()

        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.RESERVES)
        assertThat(player(match.pieces(), enforcer.player.id).at).isEqualTo(Coordinate(6, 6))
    }

    @Test
    fun `enforcer successful shove always follows up into vacated square`() {
        val (enforcer, target, match) = enforcerMatch(2)
        dice.queueBlockDice(BlockDieFace.SHOVE, BlockDieFace.MISS)
        playEnforcerBlock(match, enforcer.player.id)
        val response = match.play(chooseOrder(match, listOf(BlockDieFace.SHOVE, BlockDieFace.MISS)))

        val pieces = match.pieces()
        assertThat(player(pieces, target.player.id).at).isEqualTo(Coordinate(7, 6))
        assertThat(player(pieces, enforcer.player.id).at).isEqualTo(Coordinate(6, 6))
        assertThat(response.events.bareEvents())
            .noneMatch { it is DomainEvent.InputRequired.PlayerAction && it.decisionType == PlayerDecisionType.BLOCK_CONTINUE }
    }

    @Test
    fun `enforcer knockdown with armour held has no follow-up prompt`() {
        val (enforcer, target, match) = enforcerMatch(2)
        dice.queueBlockDice(BlockDieFace.SMASH, BlockDieFace.MISS)
        dice.queueD6(6)
        playEnforcerBlock(match, enforcer.player.id)
        val response = match.play(chooseOrder(match, listOf(BlockDieFace.SMASH, BlockDieFace.MISS)))

        assertThat(player(match.pieces(), target.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(response.events.bareEvents())
            .noneMatch { it is DomainEvent.InputRequired.PlayerAction && it.decisionType == PlayerDecisionType.BLOCK_CONTINUE }
        assertThat(player(match.pieces(), enforcer.player.id).at).isEqualTo(Coordinate(5, 6))
    }

    @Test
    fun `enforcer prone shove into obstacle is not a knockdown`() {
        lateinit var enforcer: PlacedPlayer
        lateinit var target: PlacedPlayer
        val match = match {
            turnNumber(1)
            actionsRemaining(2)
            enforcer = addPlayer(5, 6, profile = enforcerProfile())
            target = addPlayer(6, 6, team = TeamSide.AWAY, profile = softTargetProfile())
            // Occupied landing square for a shove away from the blocker
            addPlayer(7, 6, team = TeamSide.AWAY, profile = TestProfiles.orcLineman())
        }

        dice.queueBlockDice(BlockDieFace.SMASH, BlockDieFace.SHOVE)
        dice.queueD6(6) // smash armour holds; prone shove into occupied must not roll armour
        playEnforcerBlock(match, enforcer.player.id)
        val response = match.play(chooseOrder(match, listOf(BlockDieFace.SMASH, BlockDieFace.SHOVE)))

        val pieces = match.pieces()
        assertThat(player(pieces, target.player.id).status).isEqualTo(PlayerStatus.PRONE)
        assertThat(player(pieces, target.player.id).at).isEqualTo(Coordinate(6, 6))
        assertThat(player(pieces, enforcer.player.id).at).isEqualTo(Coordinate(5, 6))
        assertThat(
            response.events.bareEvents().count { it is DomainEvent.DiceRolled && it.purpose == com.mhaefele.gridiron.game.action.dto.DiceRollPurpose.ARMOUR },
        ).isEqualTo(1)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("twoAndThreeDiceCases")
    fun `enforcer resolves face order with armour path`(c: EnforcerCase) {
        val (enforcer, target, match) = enforcerMatch(c.faces.size)
        dice.queueBlockDice(*c.faces.toTypedArray())
        c.armourRolls.forEach { dice.queueD6(it) }
        playEnforcerBlock(match, enforcer.player.id)
        resolveEnforcerOrderIfPrompted(match, c.faces)

        val pieces = match.pieces()
        val targetPiece = player(pieces, target.player.id)
        assertThat(targetPiece.status)
            .describedAs(c.label)
            .isEqualTo(c.expectedTargetStatus)
        match.legalActions().assertNone(ActionType.CHOOSE_BLOCK_FACE_ORDER)
        match.legalActions().assertNone(ActionType.SKIP)

        if (c.expectedTargetStatus == PlayerStatus.RESERVES) {
            assertThat(player(pieces, enforcer.player.id).at)
                .describedAs("${c.label} auto follow-up")
                .isEqualTo(c.expectedBlockerAtAfterInjury)
        }
    }

    data class EnforcerCase(
        val label: String,
        val faces: List<BlockDieFace>,
        /** Natural D6 rolls for each armour check in order (6 holds AV3+/Flatten; 1 fails). */
        val armourRolls: List<Int>,
        val expectedTargetStatus: PlayerStatus,
        val expectedBlockerAtAfterInjury: Coordinate = Coordinate(6, 6),
    )

    companion object {
        private val FACES = listOf(
            BlockDieFace.MISS,
            BlockDieFace.SHOVE,
            BlockDieFace.TACKLE,
            BlockDieFace.SMASH,
            BlockDieFace.FLATTEN,
        )

        private fun causesArmour(face: BlockDieFace): Boolean =
            face == BlockDieFace.SMASH || face == BlockDieFace.FLATTEN || face == BlockDieFace.TACKLE

        /**
         * All ordered n-tuples of block faces × armour paths (hold each check, or fail at check k).
         * Open-board geometry: Shove always pushes to a clear square (no collision KD).
         */
        @JvmStatic
        fun twoAndThreeDiceCases(): Stream<EnforcerCase> {
            val out = mutableListOf<EnforcerCase>()
            for (n in 2..3) {
                for (seq in cartesian(FACES, n)) {
                    out += expandArmourPaths(seq)
                }
            }
            return out.stream()
        }

        private fun expandArmourPaths(faces: List<BlockDieFace>): List<EnforcerCase> {
            val armourIndexes = faces.mapIndexedNotNull { i, f -> i.takeIf { causesArmour(f) } }
            if (armourIndexes.isEmpty()) {
                return listOf(
                    EnforcerCase(
                        label = "${faces.joinToString("→")} / no-armour",
                        faces = faces,
                        armourRolls = emptyList(),
                        expectedTargetStatus = PlayerStatus.STANDING,
                    ),
                )
            }
            val cases = mutableListOf<EnforcerCase>()
            // Fail on the k-th armour check (0-based among armour checks), or all hold.
            for (failAt in 0..armourIndexes.size) {
                val rolls = MutableList(armourIndexes.size) { 6 }
                val injured = failAt < armourIndexes.size
                if (injured) {
                    rolls[failAt] = 1
                    // Trailing armour rolls never happen
                    while (rolls.size > failAt + 1) rolls.removeAt(rolls.lastIndex)
                }
                val status = when {
                    injured -> PlayerStatus.RESERVES
                    else    -> PlayerStatus.PRONE // at least one KD face, all armour held
                }
                // After injury, blocker auto-follows onto wherever the target died.
                // Open-board: only shoves before the killing face move the target.
                val blockerAt = expectedInjurySquare(faces, armourIndexes[failAt.coerceAtMost(armourIndexes.lastIndex)])
                    .takeIf { injured }
                    ?: Coordinate(6, 6)
                cases += EnforcerCase(
                    label = "${faces.joinToString("→")} / armour=${
                        if (injured) "fail#${failAt + 1}" else "all-hold"
                    }",
                    faces = faces,
                    armourRolls = rolls,
                    expectedTargetStatus = status,
                    expectedBlockerAtAfterInjury = if (injured) blockerAt else Coordinate(6, 6),
                )
            }
            return cases
        }

        /** Square the target occupies when the armour check at [killingFaceIndex] injures them. */
        private fun expectedInjurySquare(faces: List<BlockDieFace>, killingFaceIndex: Int): Coordinate {
            var at = Coordinate(6, 6)
            var blocker = Coordinate(5, 6)
            for (i in 0 until killingFaceIndex) {
                when (faces[i]) {
                    BlockDieFace.SHOVE                                            -> {
                        val next = Coordinate(at.x + (at.x - blocker.x).coerceIn(-1, 1), at.y + (at.y - blocker.y).coerceIn(-1, 1))
                        // auto follow
                        blocker = at
                        at = next
                    }
                    BlockDieFace.SMASH, BlockDieFace.FLATTEN, BlockDieFace.TACKLE -> {
                        // KD in place; armour held → stay; no follow-up
                    }
                    BlockDieFace.MISS                                             -> Unit
                }
            }
            return at
        }

        private fun <T> cartesian(items: List<T>, n: Int): List<List<T>> {
            if (n == 0) return listOf(emptyList())
            var acc = listOf(emptyList<T>())
            repeat(n) {
                acc = acc.flatMap { prefix -> items.map { prefix + it } }
            }
            return acc
        }
    }
}
