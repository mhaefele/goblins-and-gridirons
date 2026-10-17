import { describe, expect, it } from "vitest";
import type { DomainEvent, LegalActionWire } from "../api/client";
import {
    appendMotionStep,
    armourPrecedesInjury,
    assertHeldBallAtMotionOrigin,
    assertLooseBallAtMotionOrigin,
    assertPlayerAtMotionOrigin,
    ballMotionFromEvents,
    buildDiceBeats,
    challengeClaimCelebrationFromEvent,
    cleanSweepCelebrationFromEvent,
    diceAnchorFromEvents,
    flashCoordsFromEvents,
    injuryReservesExit,
    outcomeShout,
    pieceMotionFromEvents,
    presentationModeFor,
    reservesExitPath,
    touchdownCelebrationFromEvents,
    touchdownReservesExit,
} from "./presentEvents";
import type { PieceSnapshotDto } from "../api/client";

describe("presentationModeFor", () => {
    it("blocks move / dice / bounce / throw / injury / big moments", () => {
        expect(presentationModeFor({ kind: "playerMoved", playerId: "h0", from: { x: 1, y: 1 }, to: { x: 2, y: 1 } })).toBe("block");
        expect(presentationModeFor({ kind: "diceRolled", purpose: "BOUNCE", d8: 3 })).toBe("block");
        expect(presentationModeFor({ kind: "ballBounced", from: { x: 1, y: 1 }, to: { x: 2, y: 2 } })).toBe("block");
        expect(presentationModeFor({ kind: "playerInjured", playerId: "a2", from: { x: 4, y: 9 } })).toBe("block");
        expect(presentationModeFor({ kind: "diceRolled", purpose: "ARMOUR", d6: 2, threshold: 8, checkResult: "FAIL" })).toBe("block");
        expect(presentationModeFor({ kind: "touchdownScored", playerId: "h0", team: "HOME", at: { x: 6, y: 1 }, homeScore: 1, awayScore: 0 })).toBe(
            "block",
        );
        expect(presentationModeFor({ kind: "challengeCardClaimed" } as DomainEvent)).toBe("block");
        expect(presentationModeFor({ kind: "cleanSweep" } as DomainEvent)).toBe("block");
    });

    it("runs status and pickup cues in parallel (animate, don't hold)", () => {
        expect(
            presentationModeFor({
                kind: "playerStatusChanged",
                playerId: "orc-2",
                playerAt: { x: 5, y: 5 },
                from: "STANDING",
                to: "PRONE",
            }),
        ).toBe("parallel");
        expect(presentationModeFor({ kind: "ballPickedUp", playerId: "h0", playerAt: { x: 3, y: 3 } })).toBe("parallel");
    });

    it("skips protocol / turn-clock events", () => {
        expect(presentationModeFor({ kind: "playerActionRequired", team: "HOME", decisionType: "REGULAR_PLAYER_ACTION" })).toBe("skip");
        expect(presentationModeFor({ kind: "teamActionConsumed", actionsRemaining: 1 })).toBe("skip");
        expect(presentationModeFor({ kind: "actionStarted", playerId: "h0", playerAt: { x: 1, y: 1 }, actionType: "RUN" })).toBe("skip");
    });
});

describe("deferred-fold presentation contract", () => {
    it("blocks die, move, bounce, injury, and TD-remove so FX run before fold", () => {
        expect(presentationModeFor({ kind: "diceRolled", purpose: "BLOCK", blockFaces: ["SHOVE"], blockResult: "SHOVE" })).toBe("block");
        expect(presentationModeFor({ kind: "playerMoved", playerId: "orc-2", from: { x: 5, y: 5 }, to: { x: 6, y: 5 } })).toBe("block");
        expect(presentationModeFor({ kind: "ballBounced", from: { x: 1, y: 1 }, to: { x: 2, y: 2 } })).toBe("block");
        expect(presentationModeFor({ kind: "playerInjured", playerId: "a2", from: { x: 4, y: 9 } })).toBe("block");
        expect(presentationModeFor({ kind: "playerRemovedAfterTouchdown", playerId: "h0", from: { x: 6, y: 1 } })).toBe("block");
    });

    it("requires armour dice before playerInjured when both are present (backend stream order)", () => {
        expect(
            armourPrecedesInjury([
                { kind: "diceRolled", purpose: "ARMOUR", d6: 2, threshold: 8, checkResult: "FAIL" },
                { kind: "playerInjured", playerId: "a2", from: { x: 4, y: 9 } },
            ]),
        ).toBe(true);
        expect(
            armourPrecedesInjury([
                { kind: "playerInjured", playerId: "a2", from: { x: 4, y: 9 } },
                { kind: "diceRolled", purpose: "ARMOUR", d6: 2, threshold: 8, checkResult: "FAIL" },
            ]),
        ).toBe(false);
    });
});

describe("buildDiceBeats", () => {
    it("keeps block and armour as separate sequential beats with dice faces", () => {
        const events: DomainEvent[] = [
            { kind: "actionStarted", playerId: "dwarf-1", playerAt: { x: 4, y: 5 }, actionType: "BLOCK" },
            {
                kind: "diceRolled",
                purpose: "BLOCK",
                blockFaces: ["SMASH", "MISS"],
                blockResult: "SMASH",
            },
            {
                kind: "playerStatusChanged",
                playerId: "orc-2",
                playerAt: { x: 5, y: 5 },
                from: "STANDING",
                to: "PRONE",
            },
            { kind: "diceRolled", purpose: "ARMOUR", d6: 2, threshold: 8, checkResult: "FAIL" },
        ];
        const beats = buildDiceBeats(events);
        expect(beats).toHaveLength(2);
        expect(beats[0]!.outcome).toBe("SMASH!");
        expect(beats[0]!.kind).toBe("block");
        expect(beats[0]!.dice.map((die) => die.result)).toEqual(["SMASH", "MISS"]);
        expect(beats[0]!.anchor).toEqual({ x: 5, y: 5 });
        expect(beats[1]!.outcome).toBe("BROKEN!");
        expect(beats[1]!.kind).toBe("d6");
        expect(beats[1]!.dice[0]!.result).toBe("2");
        expect(beats[1]!.dice[0]!.tumble).toContain("6");
    });

    it("shouts FLATTEN for the block result", () => {
        const events: DomainEvent[] = [
            {
                kind: "diceRolled",
                purpose: "BLOCK",
                blockFaces: ["FLATTEN"],
                blockResult: "FLATTEN",
            },
        ];
        expect(buildDiceBeats(events)[0]!.outcome).toBe("FLATTEN!");
    });

    it("anchors block dice on the submitted destination", () => {
        const events: DomainEvent[] = [
            { kind: "actionStarted", playerId: "dwarf-1", playerAt: { x: 4, y: 5 }, actionType: "BLOCK" },
            {
                kind: "diceRolled",
                purpose: "BLOCK",
                blockFaces: ["SHOVE"],
                blockResult: "SHOVE",
            },
        ];
        const submitted: LegalActionWire = {
            id: "block_dwarf-1_5_6",
            kind: "block",
            playerId: "dwarf-1",
            actionType: "BLOCK",
            targetCoordinate: { x: 5, y: 6 },
            destination: { x: 5, y: 6 },
        };
        expect(diceAnchorFromEvents(events, submitted)).toEqual({ x: 5, y: 6 });
        expect(buildDiceBeats(events, submitted)[0]!.anchor).toEqual({ x: 5, y: 6 });
    });

    it("pairs each bounce die with the next ball hop and lingers on a player for re-bounce", () => {
        const events: DomainEvent[] = [
            { kind: "diceRolled", purpose: "BOUNCE", d8: 3 },
            { kind: "ballBounced", from: { x: 2, y: 2 }, to: { x: 3, y: 2 } },
            { kind: "diceRolled", purpose: "BOUNCE", d8: 7 },
            { kind: "ballBounced", from: { x: 3, y: 2 }, to: { x: 4, y: 3 } },
        ];
        const beats = buildDiceBeats(events);
        expect(beats).toHaveLength(2);
        expect(beats[0]!.followBall).toEqual({
            from: { x: 2, y: 2 },
            to: { x: 3, y: 2 },
            durationMs: expect.any(Number),
            lingerOnOccupant: true,
        });
        expect(beats[1]!.followBall).toEqual({
            from: { x: 3, y: 2 },
            to: { x: 4, y: 3 },
            durationMs: expect.any(Number),
            lingerOnOccupant: false,
        });
        expect(ballMotionFromEvents(events)).toBeNull();
    });

    it("builds an arcing throw flight from ballThrown", () => {
        const motion = ballMotionFromEvents([
            { kind: "ballThrown", from: { x: 2, y: 5 }, to: { x: 6, y: 8 } },
        ]);
        expect(motion).toEqual({
            path: [
                { x: 2, y: 5 },
                { x: 6, y: 8 },
            ],
            durationMs: expect.any(Number),
            flight: "arc",
        });
        expect(motion!.durationMs).toBeGreaterThan(400);
    });
});

describe("touchdownCelebrationFromEvents", () => {
    it("keeps the scorer square for the celebration pin", () => {
        const events: DomainEvent[] = [
            {
                kind: "touchdownScored",
                playerId: "h0",
                team: "HOME",
                at: { x: 6, y: 1 },
                homeScore: 1,
                awayScore: 0,
            },
            { kind: "playerRemovedAfterTouchdown", playerId: "h0", from: { x: 6, y: 1 } },
        ];
        expect(touchdownCelebrationFromEvents(events)).toEqual({
            kind: "touchdown",
            playerId: "h0",
            team: "HOME",
            at: { x: 6, y: 1 },
            homeScore: 1,
            awayScore: 0,
        });
    });
});

describe("challengeClaimCelebrationFromEvent", () => {
    it("captures title, points, and score tick endpoints", () => {
        const claim = challengeClaimCelebrationFromEvent(
            {
                kind: "challengeCardClaimed",
                team: "AWAY",
                slotIndex: 1,
                claimCondition: "GRAB_IT",
                challengeTitle: "Grab It!",
                pointsAwarded: 2,
                homeScore: 1,
                awayScore: 3,
                claimsThisTurn: 1,
            } as DomainEvent,
            1,
            1,
        );
        expect(claim).toEqual({
            kind: "challengeClaim",
            team: "AWAY",
            slotIndex: 1,
            challengeCondition: "GRAB_IT",
            title: "Grab It!",
            pointsAwarded: 2,
            fromHomeScore: 1,
            fromAwayScore: 1,
            homeScore: 1,
            awayScore: 3,
        });
        expect(presentationModeFor({ kind: "challengeCardClaimed" } as DomainEvent)).toBe("block");
        expect(
            cleanSweepCelebrationFromEvent(
                {
                    kind: "cleanSweep",
                    team: "HOME",
                    pointsAwarded: 2,
                    homeScore: 5,
                    awayScore: 2,
                } as DomainEvent,
                3,
                2,
            ),
        ).toMatchObject({ kind: "cleanSweep", fromHomeScore: 3, homeScore: 5 });
    });
});

describe("reserves exit flight", () => {
    it("flies off the right sideline toward the dugout", () => {
        const path = reservesExitPath({ x: 5, y: 8 }, "AWAY", 11);
        expect(path[0]).toEqual({ x: 5, y: 8 });
        expect(path.at(-1)!.x).toBeGreaterThan(11);
    });

    it("builds an injury exit from the wounded square", () => {
        const exit = injuryReservesExit(
            [{ kind: "playerInjured", playerId: "a2", from: { x: 4, y: 9 } }],
            new Map([["a2", "AWAY"]]),
            11,
        );
        expect(exit).toEqual(
            expect.objectContaining({
                playerId: "a2",
                team: "AWAY",
                reason: "injury",
            }),
        );
        expect(exit!.path[0]).toEqual({ x: 4, y: 9 });
    });

    it("builds a post-touchdown exit from the celebration square", () => {
        const exit = touchdownReservesExit(
            { kind: "touchdown", playerId: "h0", team: "HOME", at: { x: 6, y: 1 }, homeScore: 1, awayScore: 0 },
            11,
        );
        expect(exit.reason).toBe("touchdown");
        expect(exit.path[0]).toEqual({ x: 6, y: 1 });
    });
});

describe("outcomeShout", () => {
    it("maps throw check results to catch / fumble / wide", () => {
        expect(
            outcomeShout({ kind: "diceRolled", purpose: "THROW", d6: 6, threshold: 3, checkResult: "PASS" }),
        ).toBe("CATCH!");
        expect(
            outcomeShout({ kind: "diceRolled", purpose: "THROW", d6: 1, threshold: 3, checkResult: "FUMBLE" }),
        ).toBe("FUMBLE!");
        expect(
            outcomeShout({ kind: "diceRolled", purpose: "THROW", d6: 2, threshold: 3, checkResult: "FAIL" }),
        ).toBe("WIDE!");
    });

    it("shouts SAFE for armour that holds (not ball-carrier 'held')", () => {
        expect(
            outcomeShout({ kind: "diceRolled", purpose: "ARMOUR", d6: 5, threshold: 4, checkResult: "PASS" }),
        ).toBe("SAFE!");
        expect(
            outcomeShout({ kind: "diceRolled", purpose: "ARMOUR", d6: 2, threshold: 8, checkResult: "FAIL" }),
        ).toBe("BROKEN!");
    });

    it("shouts INJURY on Violent 8", () => {
        expect(outcomeShout({ kind: "diceRolled", purpose: "VIOLENT", d8: 8 })).toBe("INJURY!");
        expect(outcomeShout({ kind: "diceRolled", purpose: "VIOLENT", d8: 3 })).toBe("3!");
    });
});

describe("pieceMotionFromEvents", () => {
    it("builds a path from consecutive playerMoved steps", () => {
        const events: DomainEvent[] = [
            { kind: "playerMoved", playerId: "h0", from: { x: 1, y: 1 }, to: { x: 2, y: 2 } },
            { kind: "playerMoved", playerId: "h0", from: { x: 2, y: 2 }, to: { x: 3, y: 3 } },
        ];
        const motion = pieceMotionFromEvents(events);
        expect(motion).toEqual(
            expect.objectContaining({
                playerId: "h0",
                path: [
                    { x: 1, y: 1 },
                    { x: 2, y: 2 },
                    { x: 3, y: 3 },
                ],
            }),
        );
        expect(motion!.durationMs).toBeGreaterThan(100);
        expect(motion!.durationMs).toBeLessThan(400);
    });

    it("appends a continuing SSE step onto a coalesced path", () => {
        const first = pieceMotionFromEvents([
            { kind: "playerMoved", playerId: "h0", from: { x: 1, y: 1 }, to: { x: 2, y: 1 } },
        ])!;
        const next = pieceMotionFromEvents([
            { kind: "playerMoved", playerId: "h0", from: { x: 2, y: 1 }, to: { x: 3, y: 1 } },
        ])!;
        expect(appendMotionStep(first, next)?.path).toEqual([
            { x: 1, y: 1 },
            { x: 2, y: 1 },
            { x: 3, y: 1 },
        ]);
    });
});

describe("flashCoordsFromEvents", () => {
    it("flashes mover path ends, status changes, and submitted block destination", () => {
        const events: DomainEvent[] = [
            { kind: "actionStarted", playerId: "dwarf-1", playerAt: { x: 4, y: 5 }, actionType: "BLOCK" },
            {
                kind: "playerStatusChanged",
                playerId: "orc-2",
                playerAt: { x: 5, y: 5 },
                from: "STANDING",
                to: "PRONE",
            },
        ];
        const submitted: LegalActionWire = {
            id: "block_dwarf-1_5_5",
            kind: "block",
            playerId: "dwarf-1",
            actionType: "BLOCK",
            targetCoordinate: { x: 5, y: 5 },
            destination: { x: 5, y: 5 },
        };
        const flashes = flashCoordsFromEvents(events, submitted);
        expect(flashes).toEqual(
            expect.arrayContaining([
                { x: 4, y: 5 },
                { x: 5, y: 5 },
            ]),
        );
    });

    it("flashes every step of a submitted path", () => {
        const submitted: LegalActionWire = {
            id: "run_h0_1_1_2_1_3_1",
            kind: "run",
            playerId: "h0",
            actionType: "RUN",
            targetCoordinate: { x: 3, y: 1 },
            path: [
                { x: 1, y: 1 },
                { x: 2, y: 1 },
                { x: 3, y: 1 },
            ],
        };
        expect(flashCoordsFromEvents([], submitted)).toEqual(submitted.path);
    });
});

function piecesFixture(overrides: Partial<PieceSnapshotDto> = {}): PieceSnapshotDto {
    return {
        players: overrides.players ?? [],
        looseBallLocations: overrides.looseBallLocations ?? [],
    };
}

describe("assertMotionOrigin", () => {
    it("passes when the player is still on the motion origin", () => {
        const pieces = piecesFixture({
            players: [{ id: "h0", team: "HOME", status: "STANDING", at: { x: 2, y: 3 }, holdsBall: false }],
        });
        expect(() => assertPlayerAtMotionOrigin(pieces, "h0", { x: 2, y: 3 }, "playerMoved#9")).not.toThrow();
    });

    it("fails when the player has already left the origin square", () => {
        const pieces = piecesFixture({
            players: [{ id: "h0", team: "HOME", status: "STANDING", at: { x: 3, y: 3 }, holdsBall: false }],
        });
        expect(() => assertPlayerAtMotionOrigin(pieces, "h0", { x: 2, y: 3 }, "playerMoved#9")).toThrow(
            /Presentation drift \(playerMoved#9\).*h0.*\(2,3\).*(\(3,3\))/,
        );
    });

    it("fails when the player is already in reserves", () => {
        const pieces = piecesFixture({
            players: [{ id: "h0", team: "HOME", status: "RESERVES", at: null, holdsBall: false }],
        });
        expect(() => assertPlayerAtMotionOrigin(pieces, "h0", { x: 6, y: 1 }, "playerRemovedAfterTouchdown#4")).toThrow(
            /off-pitch \(status=RESERVES\)/,
        );
    });

    it("requires a loose ball at bounce origin", () => {
        const pieces = piecesFixture({ looseBallLocations: [{ x: 4, y: 4 }] });
        expect(() => assertLooseBallAtMotionOrigin(pieces, { x: 4, y: 4 }, "ballBounced#2")).not.toThrow();
        expect(() => assertLooseBallAtMotionOrigin(pieces, { x: 1, y: 1 }, "ballBounced#2")).toThrow(/loose ball should be at \(1,1\)/);
    });

    it("requires a carrier at throw origin", () => {
        const pieces = piecesFixture({
            players: [{ id: "h0", team: "HOME", status: "STANDING", at: { x: 5, y: 5 }, holdsBall: true }],
        });
        expect(() => assertHeldBallAtMotionOrigin(pieces, { x: 5, y: 5 }, "ballThrown#3")).not.toThrow();
        expect(() => assertHeldBallAtMotionOrigin(pieces, { x: 1, y: 1 }, "ballThrown#3")).toThrow(/held ball should be at \(1,1\)/);
    });
});
