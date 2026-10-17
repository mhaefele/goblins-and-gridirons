package com.mhaefele.gridiron.integration

import com.mhaefele.gridiron.api.dto.response.BallEncounter
import com.mhaefele.gridiron.api.dto.response.LegalAction
import com.mhaefele.gridiron.integration.support.BaseApiTest
import com.mhaefele.gridiron.model.catalog.TeamCatalog
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.TeamSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Legal-actions payload annotates pickup vs bounce when a path ends on a loose ball. */
class BallEncounterAnnotationApiTest : BaseApiTest() {

    @Test
    fun `Run onto open loose ball is annotated PICK_UP`() {
        val match = match {
            addPlayer(4, 10)
            addLooseBall(5, 10)
        }

        val run = match.singleLegalAction<LegalAction.Run> { it.targetCoordinate == Coordinate(5, 10) }

        assertThat(run.ballEncounter).isEqualTo(BallEncounter.PICK_UP)
    }

    @Test
    fun `Mark onto loose ball is annotated BOUNCE`() {
        val match = match {
            addPlayer(4, 10)
            addPlayer(6, 10, team = TeamSide.AWAY)
            addLooseBall(5, 10)
        }

        val mark = match.singleLegalAction<LegalAction.Mark> { it.targetCoordinate == Coordinate(5, 10) }

        assertThat(mark.ballEncounter).isEqualTo(BallEncounter.BOUNCE)
    }

    @Test
    fun `Mark with Sure Scoop onto loose ball is annotated PICK_UP`() {
        val match = match {
            val thrower = addPlayer(4, 10, profile = TeamCatalog.orcs().roster.first { it.name == "Thrower" })
            addPlayer(6, 10, team = thrower.team.opposite())
            addLooseBall(5, 10)
        }

        val mark = match.singleLegalAction<LegalAction.Mark> { it.targetCoordinate == Coordinate(5, 10) }

        assertThat(mark.ballEncounter).isEqualTo(BallEncounter.PICK_UP)
    }

    @Test
    fun `path that does not end on a ball has null ballEncounter`() {
        val match = match {
            addPlayer(4, 10)
            addLooseBall(8, 8)
        }

        val runsAwayFromBall = match.legalActions<LegalAction.Run>()
            .filter { it.targetCoordinate != Coordinate(8, 8) }
        assertThat(runsAwayFromBall).isNotEmpty
        assertThat(runsAwayFromBall).allMatch { it.ballEncounter == null }
    }
}
