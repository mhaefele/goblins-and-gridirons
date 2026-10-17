package com.mhaefele.gridiron.model.catalog

import com.mhaefele.gridiron.model.board.BoardDefinition
import com.mhaefele.gridiron.shared.Coordinate

/** Built-in pitch layouts keyed by [PitchDefinitionId]. */
object PitchCatalog {
    val DEFAULT_ID = PitchDefinitionId("default")
    val BARRICADE_ID = PitchDefinitionId("barricade")

    /** Dungeon stone pitch: center trapdoor with four corner pillar obstacles (11×15). */
    val default: BoardDefinition = BoardDefinition(
        playWidth = 11,
        playHeight = 15,
        trapdoors = setOf(Coordinate(6, 8)),
        blockedSquares = setOf(
            Coordinate(3, 4),
            Coordinate(4, 4),
            Coordinate(3, 5),
            Coordinate(4, 5),
            Coordinate(8, 4),
            Coordinate(9, 4),
            Coordinate(8, 5),
            Coordinate(9, 5),
            Coordinate(3, 11),
            Coordinate(4, 11),
            Coordinate(3, 12),
            Coordinate(4, 12),
            Coordinate(8, 11),
            Coordinate(9, 11),
            Coordinate(8, 12),
            Coordinate(9, 12),
        ),
    )

    /** Wooden-floor pitch: two center-row trapdoors and seven spiked barricades (11×15). */
    val barricade: BoardDefinition = BoardDefinition(
        playWidth = 11,
        playHeight = 15,
        trapdoors = setOf(
            Coordinate(3, 8),
            Coordinate(9, 8),
        ),
        blockedSquares = setOf(
            Coordinate(6, 3),
            Coordinate(6, 8),
            Coordinate(6, 13),
            Coordinate(3, 5),
            Coordinate(4, 5),
            Coordinate(8, 5),
            Coordinate(9, 5),
            Coordinate(3, 11),
            Coordinate(4, 11),
            Coordinate(8, 11),
            Coordinate(9, 11),
        ),
    )

    fun definition(id: PitchDefinitionId): BoardDefinition = definition(id.value)

    fun definition(id: String): BoardDefinition = when (id) {
        DEFAULT_ID.value   -> default
        BARRICADE_ID.value -> barricade
        else               -> throw IllegalArgumentException("Unknown pitch definition id: $id")
    }

    fun allDefinitions(): List<Pair<PitchDefinitionId, String>> =
        listOf(
            DEFAULT_ID to "Dungeon (pillar obstacles)",
            BARRICADE_ID to "Barricade",
        )
}
