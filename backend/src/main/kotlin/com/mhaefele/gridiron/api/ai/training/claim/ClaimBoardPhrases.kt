package com.mhaefele.gridiron.api.ai.training.claim

import com.mhaefele.gridiron.api.ai.training.PlacedPlayer
import com.mhaefele.gridiron.api.ai.training.TrainingBoardBuilder
import com.mhaefele.gridiron.game.action.utils.ThrowRulerOffsets
import com.mhaefele.gridiron.game.action.utils.rulerDistance
import com.mhaefele.gridiron.model.board.BoardSquare
import com.mhaefele.gridiron.model.board.Player
import com.mhaefele.gridiron.model.board.PlayerStatus
import com.mhaefele.gridiron.model.catalog.PlayerAbility
import com.mhaefele.gridiron.model.catalog.hasAbility
import com.mhaefele.gridiron.shared.Coordinate
import com.mhaefele.gridiron.shared.Path
import com.mhaefele.gridiron.shared.TeamSide
import kotlin.math.abs
import kotlin.random.Random

/**
 * Fluent placement phrases for claim drills — read like the board you would set up at the table.
 * Each call registers scatter-avoid on [TrainingBoardBuilder] as pieces land.
 *
 * Curriculum isolation (why so many [TrainingBoardBuilder.beyondMoveOf] / throw-range guards):
 * Lookahead and the model pick the highest-EV legal. If a non-focus Touchdown Run, alternate claim,
 * or accidental Completion target is also legal, the drill teaches the wrong line. Phrases place
 * the *lesson* pieces so the annotated focus action is the clear win; difficulty scatter must not
 * undo that (see avoid marks + [AbstractChallengeClaimTrainingScenario.buildBoard]).
 */
internal object ClaimBoardPhrases {

    /**
     * "Place a runner next to a loose ball, too far from the scoring End Zone to score after pickup."
     * Beyond-move + avoid around the ball keep Grab It from leaking Play to the Stands after pickup.
     */
    fun placeRunnerBesideLooseBall(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        val scoringEndZone = trainerSide.opposite()
        val runner = setup.placeRandomReserve(
            trainerSide,
            playerFilter = setup::isFixedMove,
            squareFilter = { player ->
                setup.allOf(
                    midfieldOpen(setup, trainerSide),
                    setup.beyondMoveOf(scoringEndZone)(player),
                )
            },
        )
        setup.markScatterAvoid(runner)
        val ballSquare = setup.emptyAdjacentSquares(runner.square) { setup.beyondMoveOf(scoringEndZone)(runner.player)(it) }.random()
        setup.markScatterAvoid(setup.placeLooseBallAt(ballSquare))
        setup.emptyAdjacentSquares(ballSquare).filter { it != runner.square }.forEach { setup.markScatterAvoid(it) }
        setup.markOppositionScatterAvoid(ballSquare, *setup.emptyAdjacentSquares(ballSquare).toTypedArray())
    }

    /**
     * "Place a carrier who can run the ball four or more squares toward the scoring End Zone without scoring."
     * Must stay beyond scoring range — otherwise Push Forward and Play to the Stands both annotate and Lookahead scores.
     */
    fun placeCarrierWhoCanAdvanceBallFour(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        val scoringEndZone = trainerSide.opposite()
        val carrier = setup.placeCompatiblePlayerWithBall(
            trainerSide,
            playerFilter = { setup.isFixedMove(it) && setup.fixedMove(it) >= 4 },
            label = "Push Forward carrier",
        ) { player ->
            // Anti-Play to the Stands: start farther from scoring EZ than Move — cannot TD this action.
            filter("beyondMoveOf") { setup.beyondMoveOf(scoringEndZone)(player)(it) }
            // Lesson path: at least one Run gains ≥4 on the Move-the-Ball distance metric.
            filter("canAdvanceBallFour") { canAdvanceBallFourFrom(setup, trainerSide, player)(it) }
            // Backfield + sideline: shrinks throw-ruler overlap with central scatter lanes.
            filter("nearOwnEndZone") { setup.nearOwnEndZone(trainerSide, maxRows = 3)(it) }
            filter("nearSideline") { setup.nearSideline()(it) }
            filter("notEndZoneOrTrapdoor") { !it.isEndZone(trainerSide) && !it.isEndZone(trainerSide.opposite()) && !it.isTrapdoor() }
        }
        setup.markScatterAvoid(carrier)
        setup.registerBallCarrierThrowScatter(carrier.square)
        // Exact teaching-lane cells — both teams' scatter merge these via oppositionScatterAvoid.
        setup.markOppositionScatterAvoid(*advanceBallRunPathSquares(setup, carrier, trainerSide).toTypedArray())
    }

    /** "Place a carrier within run range of the scoring End Zone." Opposition avoid keeps the TD lane clear. */
    fun placeCarrierWhoCanScore(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        val scoringEndZone = trainerSide.opposite()
        val carrier = setup.placeRandomPlayerWithBall(
            trainerSide,
            playerFilter = setup::isFixedMove,
            squareFilter = { player ->
                setup.allOf(
                    setup.withinMoveOf(scoringEndZone, excludeEndZone = true)(player),
                    canScoreFrom(setup, scoringEndZone, player),
                )
            },
        )
        setup.markScatterAvoid(carrier)
        setup.registerBallCarrierThrowScatter(carrier.square)
        setup.markOppositionScatterAvoid(*scoringRunPathSquares(setup, carrier, scoringEndZone).toTypedArray())
    }

    /** "Cluster two teammates, then place a third who can step in beside both." */
    fun placePlayerWhoCanJoinTwoTeammates(
        setup: TrainingBoardBuilder,
        trainerSide: TeamSide,
        /** Keep this cluster away from earlier lesson pieces (Clean Sweep cross-cluster Blocks). */
        minDistanceFromOnPitch: Int = 0,
    ) {
        // Constructive: find anchor+buddy with a join square that still has an empty approach
        // (excluding the anchors). Random midfield pairs often leave join squares whose only
        // empties are the two teammates → "No empty adjacent square".
        data class JoinCast(val anchor: BoardSquare, val buddy: BoardSquare, val join: BoardSquare, val approach: BoardSquare)
        val midfield = midfieldOpen(setup, trainerSide)
        fun farEnough(square: BoardSquare): Boolean =
            minDistanceFromOnPitch <= 0 || setup.board.allSquares().none { other ->
                other.playerId != null && setup.manhattanDistance(square, other) < minDistanceFromOnPitch
            }
        val anchors = setup.board.allSquares().filter { midfield(it) && farEnough(it) }.shuffled()
        val casts = mutableListOf<JoinCast>()
        for (anchor in anchors) {
            val buddies = setup.adjacentSquares(anchor).filter { setup.emptyPlayable(it) && farEnough(it) }
            for (buddy in buddies) {
                val joins = setup.adjacentSquares(anchor).intersect(setup.adjacentSquares(buddy)).filter(setup::emptyPlayable)
                for (join in joins) {
                    val approaches = setup.adjacentSquares(join)
                        .filter { setup.emptyPlayable(it) && it.coordinate != anchor.coordinate && it.coordinate != buddy.coordinate && farEnough(it) }
                    for (approach in approaches) {
                        casts += JoinCast(anchor, buddy, join, approach)
                    }
                }
            }
            if (casts.size >= 32) break // enough variety; avoid O(n³) on a full empty pitch
        }
        check(casts.isNotEmpty()) { "No Huddle Up cast (anchor+buddy+join+approach) on empty pitch" }
        val cast = casts.random()
        val anchor = setup.placeRandomReserve(trainerSide, setOf(cast.anchor))
        val buddy = setup.placeRandomReserve(trainerSide, setOf(cast.buddy))
        val join = setup.placeRandomReserve(
            trainerSide,
            setOf(cast.approach),
            playerFilter = setup::isFixedMove,
        )
        setup.markScatterAvoid(anchor, buddy, join)
        // Keep the landing clear so the joiner's one-step Mark/Run still exists after scatter.
        setup.markScatterAvoidExact(cast.join)
    }

    /** "Two teammates start adjacent; place a runner who can leave them alone." */
    fun placeRunnerWhoCanFanOut(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        val partner = setup.placeRandomReserve(trainerSide, midfieldOpen(setup, trainerSide))
        val runner = setup.placeRandomReserve(
            trainerSide,
            setup.emptyAdjacentSquares(partner.square),
            playerFilter = { setup.isFixedMove(it) && setup.fixedMove(it) >= 2 },
        )
        setup.markScatterAvoid(partner, runner)
        setup.markOppositionScatterAvoid(*setup.squaresAlongRunPaths(setup.runPaths(runner.player)).toTypedArray())
    }

    /**
     * "Thrower holds the ball; a teammate stands a Short pass away (not a Hand-off). Too far to score by running."
     * Beyond-move on the thrower is the main anti-Play to the Stands guard; validateClaimBoard re-checks it.
     */
    fun placeThrowerWithShortReceiver(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        val scoringEndZone = trainerSide.opposite()
        val thrower = setup.placeRandomPlayerWithBall(
            trainerSide,
            playerFilter = { true },
            squareFilter = { player ->
                setup.allOf(
                    midfieldOpen(setup, trainerSide),
                    setup.beyondMoveOf(scoringEndZone)(player),
                    setup.nearOwnEndZone(trainerSide, maxRows = 3),
                )
            },
        )
        val receiverSquares = receiverSquares(setup, thrower.square, trainerSide) { dx, dy ->
            ThrowRulerOffsets.short.any { it.dx == dx && it.dy == dy } && (abs(dx) + abs(dy)) >= 2
        }
        check(receiverSquares.isNotEmpty()) { "No short-pass receiver square from ${thrower.coordinate}" }
        val receiver = setup.placeRandomReserve(
            trainerSide,
            receiverSquares,
            playerFilter = setup::canHoldBall,
        )
        setup.markScatterAvoid(thrower, receiver)
        setup.registerBallCarrierThrowScatter(thrower.square)
    }

    /**
     * "Thrower holds the ball; a teammate stands a Long pass away (negative modifier). Too far to score by running."
     * Same anti-Play to the Stands placement as the Completion short-pass phrase.
     */
    fun placeThrowerWithLongReceiver(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        val scoringEndZone = trainerSide.opposite()
        val thrower = setup.placeRandomPlayerWithBall(
            trainerSide,
            playerFilter = { true },
            squareFilter = { player ->
                setup.allOf(
                    midfieldOpen(setup, trainerSide),
                    setup.beyondMoveOf(scoringEndZone)(player),
                    setup.nearOwnEndZone(trainerSide, maxRows = 3),
                )
            },
        )
        val receiverSquares = receiverSquares(setup, thrower.square, trainerSide) { dx, dy ->
            ThrowRulerOffsets.long.any { it.dx == dx && it.dy == dy }
        }
        check(receiverSquares.isNotEmpty()) { "No long-pass receiver square from ${thrower.coordinate}" }
        val receiver = setup.placeRandomReserve(
            trainerSide,
            receiverSquares,
            playerFilter = setup::canHoldBall,
        )
        setup.markScatterAvoid(thrower, receiver)
        setup.registerBallCarrierThrowScatter(thrower.square)
    }

    /** "Blocker stands adjacent to an opposition ball carrier." */
    fun placeBlockerBesideOppositionCarrier(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        val carrier = setup.placeRandomPlayerWithBall(
            trainerSide.opposite(),
            midfieldOpen(setup, trainerSide.opposite()),
        )
        val blocker = setup.placeRandomReserve(trainerSide, setup.emptyAdjacentSquares(carrier.square))
        setup.markScatterAvoid(blocker, carrier)
    }

    /** "Blocker stands adjacent to an open opposition player." */
    fun placeBlockerBesideOpposition(
        setup: TrainingBoardBuilder,
        trainerSide: TeamSide,
        /** Keep this cluster away from earlier lesson pieces (Clean Sweep shove→reblock leaks). */
        minDistanceFromOnPitch: Int = 0,
    ) {
        val targetFilter = setup.squareCandidates("Block target")
            .filter("midfieldOpen", midfieldOpen(setup, trainerSide.opposite()))
            .filter("awayFromOnPitch") { square ->
                minDistanceFromOnPitch <= 0 || setup.board.allSquares().none { other ->
                    other.playerId != null && setup.manhattanDistance(square, other) < minDistanceFromOnPitch
                }
            }
        val target = setup.placeRandomReserve(trainerSide.opposite(), targetFilter.requireNonEmpty())
        val blockSquares = setup.emptyAdjacentSquares(target.square)
        val blocker = setup.placeRandomReserve(trainerSide, blockSquares)
        setup.markScatterAvoid(blocker, target)
        blockSquares.filter { it != blocker.square }.forEach { setup.markScatterAvoid(it) }
    }

    /** "Runner can step onto a Trapdoor while an opposition player holds a ball." */
    fun placeRunnerWhoCanEndOnTrapdoor(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        val trapdoors = setup.board.allSquares().filter { it.isTrapdoor() && setup.emptyPlayable(it) }
        check(trapdoors.isNotEmpty()) { "No empty trapdoor on the pitch" }
        val trapdoor = trapdoors.random()
        setup.markScatterAvoid(trapdoor)
        val runner = setup.placeRandomReserve(
            trainerSide,
            playerFilter = setup::isFixedMove,
            squareFilter = { player ->
                { square ->
                    setup.emptyPlayable(square) &&
                        !square.isTrapdoor() &&
                        !square.isEndZone(trainerSide) &&
                        !square.isEndZone(trainerSide.opposite()) &&
                        setup.manhattanDistance(square, trapdoor) in 1..setup.fixedMove(player)
                }
            },
        )
        val opp = setup.placeRandomPlayerWithBall(
            trainerSide.opposite(),
            { square: BoardSquare ->
                setup.emptyPlayable(square) && setup.manhattanDistance(square, trapdoor) >= 3 && !square.isTrapdoor()
            },
        )
        setup.markScatterAvoid(runner, opp)
    }

    /**
     * "Marker starts open and can Mark between two unmarked opposition players."
     *
     * Searches the empty pitch for valid triangles (start → one-step land → ≥2 flanks) instead of
     * placing the marker first and hoping land/flanks exist. Pillars, trapdoors, and Y-edge starts
     * used to burn placement retries here.
     *
     * Geometry: flanks sit two steps toward the scoring End Zone from start, so the marker begins
     * Open (not already adjacent) and the Mark landing is uniquely adjacent to both flanks.
     */
    fun placeMarkerWhoCanTieTwo(setup: TrainingBoardBuilder, trainerSide: TeamSide): PlacedPlayer {
        val forward = if (trainerSide == TeamSide.HOME) 1 else -1
        data class Triangle(val start: BoardSquare, val land: BoardSquare, val flankA: BoardSquare, val flankB: BoardSquare)

        fun at(x: Int, y: Int): BoardSquare? =
            runCatching { setup.board.squareAt(Coordinate(x, y)) }.getOrNull()

        fun flankOk(square: BoardSquare): Boolean =
            setup.emptyPlayable(square) && !square.isTrapdoor() && !square.isEndZone(trainerSide) && !square.isEndZone(trainerSide.opposite())

        val midfield = midfieldOpen(setup, trainerSide)
        val triangles = mutableListOf<Triangle>()
        for (start in setup.board.allSquares()) {
            if (!midfield(start)) continue
            // Land must stay enterable for Mark (not trapdoor / EZ / blocked).
            val land = at(start.coordinate.x, start.coordinate.y + forward)?.takeIf(::flankOk) ?: continue
            val flanks = listOf(-1, 0, 1).mapNotNull { dx ->
                at(start.coordinate.x + dx, start.coordinate.y + 2 * forward)?.takeIf(::flankOk)
            }
            if (flanks.size < 2) continue
            for (i in flanks.indices) {
                for (j in (i + 1) until flanks.size) {
                    triangles += Triangle(start, land, flanks[i], flanks[j])
                }
            }
        }
        check(triangles.isNotEmpty()) { "No Lock Them Down triangles (start→land→2 flanks) on empty pitch" }
        val tri = triangles.random()
        val marker = setup.placeRandomReserve(
            trainerSide,
            setOf(tri.start),
            playerFilter = { player -> setup.isFixedMove(player) && setup.fixedMove(player) >= 2 },
        )
        val flankA = setup.placeRandomReserve(trainerSide.opposite(), setOf(tri.flankA))
        val flankB = setup.placeRandomReserve(trainerSide.opposite(), setOf(tri.flankB))
        setup.markScatterAvoid(marker, flankA, flankB)
        // Exact land only — neighbor expansion already covers flanks via markScatterAvoid(players).
        setup.markScatterAvoidExact(tri.land)
        return marker
    }

    /**
     * Opponent can Mark between two unmarked trainer flanks next turn ([LOCK_THEM_DOWN] threat).
     * Places a trainer adjacent to that marker so the opening lesson is Block them first.
     */
    fun placeDenyLockThemDownThreat(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        val marker = placeMarkerWhoCanTieTwo(setup, trainerSide.opposite())
        val blocker = setup.placeRandomReserve(trainerSide, setup.emptyAdjacentSquares(marker.square))
        setup.markScatterAvoid(blocker)
    }

    /**
     * Loose ball with an opposition runner adjacent (Grab It! next turn). Places a trainer
     * adjacent to that runner so the opening lesson is Block them first.
     */
    fun placeDenyPickupThreat(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        placeRunnerBesideLooseBall(setup, trainerSide.opposite())
        val runnerSquare = setup.board.allSquares().single { square ->
            square.playerId?.let { setup.board.players[it] }?.let { it.team == trainerSide.opposite() && it.status == PlayerStatus.STANDING } == true
        }
        val blocker = setup.placeRandomReserve(
            trainerSide,
            setup.emptyAdjacentSquares(runnerSquare) { setup.emptyWithoutLooseBall(it) },
        )
        setup.markScatterAvoid(blocker)
    }

    /**
     * Trainer [withBall] player marked by an adjacent opposition Blocker (Free Up / Drop Them
     * threat next turn). Soft-mask Sidestep off or Block the threat.
     */
    fun placeDenyImminentBlockThreat(setup: TrainingBoardBuilder, trainerSide: TeamSide, withBall: Boolean) {
        val threatened =
            if (withBall) {
                setup.placeRandomPlayerWithBall(trainerSide, midfieldOpen(setup, trainerSide))
            } else {
                setup.placeRandomReserve(trainerSide, midfieldOpen(setup, trainerSide))
            }
        val threat = setup.placeRandomReserve(trainerSide.opposite(), setup.emptyAdjacentSquares(threatened.square))
        setup.markScatterAvoid(threatened, threat)
        setup.emptyAdjacentSquares(threatened.square).filter { it != threat.square }.forEach { setup.markScatterAvoid(it) }
    }

    /** "Three open runners who can each take a Run." */
    fun placeThreeRunners(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        val runners = List(3) { setup.placeRandomReserve(trainerSide, setup::isFixedMove, midfieldOpen(setup, trainerSide),) }
        setup.markScatterAvoid(runners)
    }

    /**
     * "Three specialists: a runner, a marker (with a Mark target), and a blocker (with a Block target)."
     * Runner is placed last, clear of both opposition tokens, so opening Runs stay on the Run specialist.
     */
    fun placeAllHandsCast(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        val markTarget = setup.placeRandomReserve(trainerSide.opposite(), midfieldOpen(setup, trainerSide.opposite()))
        val blockTarget = setup.placeRandomReserve(
            trainerSide.opposite(),
            setup.allOf(
                midfieldOpen(setup, trainerSide.opposite()),
                { square -> setup.manhattanDistance(square, markTarget.square) >= 3 },
            ),
        )
        val approach = setup.emptyAdjacentSquares(markTarget.square)
        val markerStarts = approach.flatMap { setup.adjacentSquares(it).filter(setup::emptyPlayable) }
            .filter { it.coordinate != markTarget.coordinate }
            .toSet()
        val marker = setup.placeRandomReserve(trainerSide, markerStarts.ifEmpty { approach })
        val blocker = setup.placeRandomReserve(trainerSide, setup.emptyAdjacentSquares(blockTarget.square))
        val runner = setup.placeRandomReserve(
            trainerSide,
            playerFilter = setup::isFixedMove,
            squareFilter = { _ ->
                setup.allOf(
                    midfieldOpen(setup, trainerSide),
                    { square ->
                        setup.manhattanDistance(square, markTarget.square) >= 3 && setup.manhattanDistance(square, blockTarget.square) >= 3 && setup.manhattanDistance(square, marker.square) >= 2 && setup.manhattanDistance(square, blocker.square) >= 2
                    },
                )
            },
        )
        setup.markScatterAvoid(runner, marker, blocker, markTarget, blockTarget)
    }

    /** Foul the only breakable (prone) opposition player. Non-Lobber fouler — ranged Blocks must not share SEND_THEM_OFF. */
    fun placeFoulerBesideProneOpposition(setup: TrainingBoardBuilder, trainerSide: TeamSide) {
        val prone = setup.placeRandomProneReserve(trainerSide.opposite(), setup.board.allSquares().filter(setup::emptyPlayable))
        val fouler = setup.placeRandomReserve(
            trainerSide,
            setup.emptyAdjacentSquares(prone.square),
            playerFilter = { !it.hasAbility(PlayerAbility.Lobber) },
        )
        setup.markScatterAvoid(prone, fouler)
    }

    /**
     * Show Them How: one focus star and one opposition player. Picks a variant generator
     * (adjacent, in run range, star prone, foul, throw) — each self-contained, shared helpers below.
     */
    fun placeStarTurnCast(setup: TrainingBoardBuilder, trainerSide: TeamSide) =
        placeStarTurnCast(setup, trainerSide, StarTurnVariant.entries.random())

    internal fun placeStarTurnCast(setup: TrainingBoardBuilder, trainerSide: TeamSide, variant: StarTurnVariant, starHoldsBall: Boolean = false) {
        placeStarTurn(setup, trainerSide, variant, starHoldsBall)
    }

    /** Opening geometry — one variant picked at random by [placeStarTurnCast]. */
    internal enum class StarTurnVariant(
        val opponentStanding: Boolean,
        val starProne: Boolean,
        val distance: IntRange,
        val addThrowBall: Boolean,
    ) {
        ADJACENT(opponentStanding = true, starProne = false, distance = 1..1, addThrowBall = false),
        IN_RUN_RANGE(opponentStanding = true, starProne = false, distance = 2..Int.MAX_VALUE, addThrowBall = false),
        STAR_PRONE(opponentStanding = true, starProne = true, distance = 1..Int.MAX_VALUE, addThrowBall = false),
        FOUL(opponentStanding = false, starProne = false, distance = 1..1, addThrowBall = false),
        THROW(opponentStanding = true, starProne = false, distance = 1..Int.MAX_VALUE, addThrowBall = true),
    }

    private fun placeStarTurn(setup: TrainingBoardBuilder, trainerSide: TeamSide, layout: StarTurnVariant, starHoldsBall: Boolean = false) {
        val scoringEndZone = trainerSide.opposite()
        // Prefer star starts that cannot step onto the trapdoor in one Move — otherwise the first
        // enumerated Run is often a suicide landing and PASS / follow-ups go empty.
        val trapdoors = setup.board.allSquares().filter { it.isTrapdoor() }
        trapdoors.forEach { setup.markScatterAvoidExact(it) }
        val opponent = if (layout.opponentStanding) {
            starTurnOpponentStanding(setup, trainerSide)
        } else {
            starTurnOpponentProne(setup, trainerSide)
        }
        setup.markScatterAvoid(opponent)
        var starSquares = starTurnStarSquares(setup, trainerSide, opponent, scoringEndZone, layout.distance)
        // Drop starts from which every reserve's full Move reaches a trapdoor (suicide-only menus).
        starSquares = starSquares.filter { square ->
            setup.reserves(trainerSide).any { player ->
                starTurnStarFilter(setup)(player) &&
                    trapdoors.none { td -> setup.manhattanDistance(square, td) in 1..setup.fixedMove(player) }
            }
        }.ifEmpty { starSquares }.toSet()
        if (layout.addThrowBall) {
            starSquares = starSquares.filter { square ->
                setup.reserves(trainerSide).any { player ->
                    starTurnStarFilter(setup)(player) && abs(square.coordinate.y - setup.endZoneRow(scoringEndZone)) > setup.fixedMove(player) + 2
                }
            }.toSet()
            check(starSquares.isNotEmpty()) { "No far-from-endzone star square for Show Them How throw variant" }
        }
        val star = if (layout.starProne) {
            starTurnPlaceStarProne(setup, trainerSide, starSquares)
        } else {
            starTurnPlaceStarStanding(setup, trainerSide, starSquares)
        }
        if (layout.addThrowBall) {
            if (starHoldsBall) {
                check(setup.canHoldBall(star.player)) { "Show Them How: star ${star.player.id} cannot hold the ball" }
                setup.giveBallTo(star)
            } else {
                starTurnAddThrowBall(setup, trainerSide, star, opponent)
            }
        }
        setup.tagStarTurnStar(star, opponent)
    }

    /** Standing opposition token in midfield. */
    private fun starTurnOpponentStanding(setup: TrainingBoardBuilder, trainerSide: TeamSide): PlacedPlayer =
        setup.placeRandomReserve(trainerSide.opposite(), setup.board.allSquares().filter(midfieldOpen(setup, trainerSide.opposite())))

    /** Prone opposition token in midfield — Foul lane. */
    private fun starTurnOpponentProne(setup: TrainingBoardBuilder, trainerSide: TeamSide): PlacedPlayer =
        setup.placeRandomProneReserve(trainerSide.opposite(), setup.board.allSquares().filter(midfieldOpen(setup, trainerSide.opposite())))

    private fun starTurnStarFilter(setup: TrainingBoardBuilder): (Player) -> Boolean = { player ->
        setup.isFixedMove(player) && setup.fixedMove(player) >= 1
    }

    private fun starTurnStarSquares(
        setup: TrainingBoardBuilder,
        trainerSide: TeamSide,
        opponent: PlacedPlayer,
        scoringEndZone: TeamSide,
        distance: IntRange,
    ): Set<BoardSquare> =
        setup.board.allSquares().filter { square ->
            setup.emptyPlayable(square) &&
                midfieldOpen(setup, trainerSide)(square) &&
                // beyondMoveOf: star must not be able to Play to the Stands — PASS is chaining three actions, not a TD.
                setup.reserves(trainerSide).any { player ->
                    starTurnStarFilter(setup)(player) && setup.manhattanDistance(square, opponent.square) in distance && setup.manhattanDistance(square, opponent.square) in 1..setup.fixedMove(player) && setup.beyondMoveOf(scoringEndZone)(player)(square)
                }
        }.toSet()

    private fun starTurnPlaceStarStanding(setup: TrainingBoardBuilder, trainerSide: TeamSide, squares: Set<BoardSquare>): PlacedPlayer {
        check(squares.isNotEmpty()) { "No standing star square for Show Them How" }
        return setup.placeRandomReserve(trainerSide, squares, playerFilter = starTurnStarFilter(setup))
    }

    private fun starTurnPlaceStarProne(setup: TrainingBoardBuilder, trainerSide: TeamSide, squares: Set<BoardSquare>): PlacedPlayer {
        check(squares.isNotEmpty()) { "No prone star square for Show Them How" }
        return setup.placeRandomProneReserve(trainerSide, squares, playerFilter = starTurnStarFilter(setup))
    }

    private fun starTurnMidfieldLooseBall(setup: TrainingBoardBuilder, trainerSide: TeamSide): BoardSquare {
        val looseSquares = setup.board.allSquares().filter { setup.emptyPlayable(it) && midfieldOpen(setup, trainerSide)(it) }
        check(looseSquares.isNotEmpty()) { "No loose-ball square for Show Them How throw variant" }
        return setup.placeLooseBallAt(looseSquares.random())
    }

    /** Star holds, loose midfield ball, or standing opposition carrier — never on a prone opponent. */
    private fun starTurnAddThrowBall(setup: TrainingBoardBuilder, trainerSide: TeamSide, star: PlacedPlayer, opponent: PlacedPlayer) {
        when (Random.nextInt(3)) {
            0    -> if (setup.canHoldBall(star.player)) setup.giveBallTo(star)
            1    -> setup.markScatterAvoid(starTurnMidfieldLooseBall(setup, trainerSide))
            else -> when {
                setup.canHoldBall(opponent.player) && opponent.player.status == PlayerStatus.STANDING -> setup.giveBallTo(opponent)
                setup.canHoldBall(star.player)                                                        -> setup.giveBallTo(star)
                else                                                                                  -> setup.markScatterAvoid(starTurnMidfieldLooseBall(setup, trainerSide))
            }
        }
    }

    private fun midfieldOpen(setup: TrainingBoardBuilder, team: TeamSide): (BoardSquare) -> Boolean = { square ->
        setup.emptyPlayable(square) &&
            !square.isEndZone(team) &&
            !square.isEndZone(team.opposite()) &&
            !square.isTrapdoor() &&
            abs(square.coordinate.x - 6) <= 3
    }

    private fun canAdvanceBallFourFrom(setup: TrainingBoardBuilder, trainerSide: TeamSide, player: Player): (BoardSquare) -> Boolean = { square ->
        setup.runPathsFromSquare(player, square, advanceBallFourPathFilter(setup, trainerSide)).isNotEmpty()
    }

    private fun advanceBallRunPathSquares(setup: TrainingBoardBuilder, carrier: PlacedPlayer, trainerSide: TeamSide): Set<BoardSquare> =
        setup.squaresAlongRunPaths(setup.runPaths(carrier.player, advanceBallFourPathFilter(setup, trainerSide))).toSet()

    private fun advanceBallFourPathFilter(setup: TrainingBoardBuilder, trainerSide: TeamSide): (Path) -> Boolean = { path ->
        val scoringEndZone = trainerSide.opposite()
        val to = setup.board.squareAt(path.squares.last())
        !to.isEndZone(scoringEndZone) && pushForwardDistanceGained(setup, trainerSide, setup.board.squareAt(path.squares.first()).coordinate, to.coordinate) >= 4
    }

    private fun pushForwardDistanceGained(setup: TrainingBoardBuilder, trainerSide: TeamSide, from: Coordinate, to: Coordinate): Int {
        val ownRow = setup.endZoneRow(trainerSide)
        return abs(to.y - ownRow) - abs(from.y - ownRow)
    }

    private fun canScoreFrom(setup: TrainingBoardBuilder, scoringEndZone: TeamSide, player: Player): (BoardSquare) -> Boolean = { square ->
        setup.runPathsFromSquare(player, square, setup.runPathEndsInEndZone(scoringEndZone)).isNotEmpty()
    }

    private fun scoringRunPathSquares(setup: TrainingBoardBuilder, carrier: PlacedPlayer, scoringEndZone: TeamSide): Set<BoardSquare> =
        setup.squaresAlongRunPaths(setup.runPaths(carrier.player, setup.runPathEndsInEndZone(scoringEndZone))).toSet()

    private fun receiverSquares(
        setup: TrainingBoardBuilder,
        from: BoardSquare,
        trainerSide: TeamSide,
        offsetOk: (dx: Int, dy: Int) -> Boolean,
    ): List<BoardSquare> =
        setup.board.allSquares().filter { square ->
            val dx = square.coordinate.x - from.coordinate.x
            val dy = square.coordinate.y - from.coordinate.y
            setup.emptyPlayable(square) &&
                !square.isEndZone(trainerSide) &&
                !square.isEndZone(trainerSide.opposite()) &&
                offsetOk(dx, dy) &&
                rulerDistance(from.coordinate, square.coordinate) > 0
        }
}
