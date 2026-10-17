import { describe, expect, it } from "vitest";
import type { DomainEvent, MatchSnapshotDto, PieceSnapshotDto, StoredDomainEvent } from "../api/client";
import { applyEvent, applyStoredEvent } from "../state/applyEvent";
import { boardViewFromSnapshot, type BoardView } from "../state/boardView";
import { assertMotionOriginForEvent } from "../state/presentEvents";
import fixtures from "./fixtures/match-sequence.json";

/** Fold each event only after the deferred-fold motion-origin contract holds. */
function foldEventsAssertingMotionOrigins(initial: MatchSnapshotDto, events: StoredDomainEvent[]): MatchSnapshotDto {
    let view = boardViewFromSnapshot(initial);
    for (const stored of events) {
        assertMotionOriginForEvent(view.pieces, stored.event, `${stored.event.kind}#${stored.id.domainId}`);
        view = applyStoredEvent(view, stored);
    }
    return {
        lastEventId: view.lastEventId,
        gameState: view.gameState,
        pieces: view.pieces,
    };
}

function piecesEqual(a: PieceSnapshotDto, b: PieceSnapshotDto): boolean {
    return JSON.stringify(a) === JSON.stringify(b);
}

function replayAsserting(view: BoardView, events: DomainEvent[]): BoardView {
    let current = view;
    for (const [index, event] of events.entries()) {
        assertMotionOriginForEvent(current.pieces, event, `${event.kind}#${index + 1}`);
        current = applyEvent(current, event);
    }
    return current;
}

describe("applyEvent", () => {
    it("folds run action events to match server snapshot pieces and HUD", () => {
        const createSnapshot = fixtures.createResponse.snapshot as MatchSnapshotDto;
        const runResponse = fixtures.runActionResponse;
        const folded = foldEventsAssertingMotionOrigins(createSnapshot, runResponse.events as StoredDomainEvent[]);
        const expected = runResponse.snapshot as MatchSnapshotDto;

        expect(folded.lastEventId).toBe(expected.lastEventId);
        expect(piecesEqual(folded.pieces, expected.pieces)).toBe(true);
        expect(folded.gameState).toEqual(expected.gameState);
    });

    it("moves player on playerMoved", () => {
        const createSnapshot = fixtures.createResponse.snapshot as MatchSnapshotDto;
        const view = boardViewFromSnapshot(createSnapshot);
        const movedEvent = fixtures.runActionResponse.events.find(
            (stored) => stored.event.kind === "playerMoved",
        )?.event as DomainEvent;

        expect(movedEvent.kind).toBe("playerMoved");
        if (movedEvent.kind !== "playerMoved") return;

        assertMotionOriginForEvent(view.pieces, movedEvent, "playerMoved#solo");
        const next = applyEvent(view, movedEvent);
        const player = next.pieces.players.find((piece) => piece.id === movedEvent.playerId);
        expect(player?.at).toEqual(movedEvent.to);
    });

    it("updates HUD on teamActionConsumed and playerActionRequired is a no-op on board", () => {
        const createSnapshot = fixtures.createResponse.snapshot as MatchSnapshotDto;
        let view = boardViewFromSnapshot(createSnapshot);

        for (const stored of fixtures.runActionResponse.events as StoredDomainEvent[]) {
            const beforePieces = view.pieces;
            assertMotionOriginForEvent(view.pieces, stored.event, `${stored.event.kind}#${stored.id.domainId}`);
            view = applyStoredEvent(view, stored);
            if (stored.event.kind === "playerActionRequired") {
                expect(view.pieces).toEqual(beforePieces);
            }
        }

        expect(view.gameState.actionsRemaining).toBe(2);
        expect(view.lastEventId).toBe(fixtures.runActionResponse.snapshot.lastEventId);
    });

    it("clears the claimed challenge slot and updates score", () => {
        const createSnapshot = fixtures.createResponse.snapshot as MatchSnapshotDto;
        const view = boardViewFromSnapshot({
            ...createSnapshot,
            gameState: {
                ...createSnapshot.gameState,
                homeScore: 0,
                awayScore: 0,
                challenges: {
                    activeSlots: [
                        { challengeCondition: "GRAB_IT", title: "Grab It!", points: 2, description: "" },
                        { challengeCondition: "PLAY_TO_THE_STANDS", title: "Play to the Stands!", points: 1, description: "" },
                        null,
                    ],
                    deckTop: { challengeCondition: "HUDDLE_UP", title: "Huddle Up!", points: 1, description: "" },
                    deckRemaining: 10,
                    discardCount: 0,
                    claimsThisTurn: 0,
                },
            },
        });

        const next = applyEvent(view, {
            kind: "challengeCardClaimed",
            team: "HOME",
            slotIndex: 0,
            claimCondition: "GRAB_IT",
            challengeTitle: "Grab It!",
            pointsAwarded: 2,
            homeScore: 2,
            awayScore: 0,
            claimsThisTurn: 1,
        } as DomainEvent);

        expect(next.gameState.homeScore).toBe(2);
        expect(next.gameState.challenges?.activeSlots[0]).toBeNull();
        expect(next.gameState.challenges?.activeSlots[1]?.challengeCondition).toBe("PLAY_TO_THE_STANDS");
        expect(next.gameState.challenges?.claimsThisTurn).toBe(1);
    });

    it("fills empty challenge slots on challengeTableauRefreshed", () => {
        const createSnapshot = fixtures.createResponse.snapshot as MatchSnapshotDto;
        const view = boardViewFromSnapshot({
            ...createSnapshot,
            gameState: {
                ...createSnapshot.gameState,
                challenges: {
                    activeSlots: [null, null, { challengeCondition: "PLAY_TO_THE_STANDS", title: "Play to the Stands!", points: 1, description: "" }],
                    deckTop: { challengeCondition: "GRAB_IT", title: "Grab It!", points: 1, description: "" },
                    deckRemaining: 12,
                    discardCount: 0,
                    claimsThisTurn: 1,
                },
            },
        });

        const next = applyEvent(view, {
            kind: "challengeTableauRefreshed",
            filledSlots: [0, 1],
            filledCards: [
                { challengeCondition: "GRAB_IT", title: "Grab It!", points: 1, description: "" },
                { challengeCondition: "HUDDLE_UP", title: "Huddle Up!", points: 1, description: "" },
            ],
            deckTop: { challengeCondition: "PUSH_FORWARD", title: "Push Forward!", points: 1, description: "" },
            deckRemaining: 10,
        } as DomainEvent);

        expect(next.gameState.challenges?.activeSlots[0]).toEqual({
            challengeCondition: "GRAB_IT",
            title: "Grab It!",
            points: 1,
            description: "",
        });
        expect(next.gameState.challenges?.activeSlots[1]).toEqual({
            challengeCondition: "HUDDLE_UP",
            title: "Huddle Up!",
            points: 1,
            description: "",
        });
        expect(next.gameState.challenges?.activeSlots[2]?.challengeCondition).toBe("PLAY_TO_THE_STANDS");
        expect(next.gameState.challenges?.deckTop).toEqual({
            challengeCondition: "PUSH_FORWARD",
            title: "Push Forward!",
            points: 1,
            description: "",
        });
        expect(next.gameState.challenges?.deckRemaining).toBe(10);
    });

    it("replaces a challenge slot and bumps discard on challengeCardReplaced", () => {
        const createSnapshot = fixtures.createResponse.snapshot as MatchSnapshotDto;
        const view = boardViewFromSnapshot({
            ...createSnapshot,
            gameState: {
                ...createSnapshot.gameState,
                challenges: {
                    activeSlots: [
                        { challengeCondition: "GRAB_IT", title: "Grab It!", points: 1, description: "" },
                        { challengeCondition: "HUDDLE_UP", title: "Huddle Up!", points: 1, description: "" },
                        { challengeCondition: "PLAY_TO_THE_STANDS", title: "Play to the Stands!", points: 1, description: "" },
                    ],
                    deckTop: { challengeCondition: "PUSH_FORWARD", title: "Push Forward!", points: 1, description: "" },
                    deckRemaining: 10,
                    discardCount: 0,
                    claimsThisTurn: 0,
                },
            },
        });

        const next = applyEvent(view, {
            kind: "challengeCardReplaced",
            slotIndex: 0,
            discardedClaimCondition: "GRAB_IT",
            drawn: { challengeCondition: "PUSH_FORWARD", title: "Push Forward!", points: 1, description: "" },
            deckTop: { challengeCondition: "PUSH_FORWARD", title: "Push Forward!", points: 1, description: "" },
            deckRemaining: 9,
        } as DomainEvent);

        expect(next.gameState.challenges?.activeSlots[0]).toEqual({
            challengeCondition: "PUSH_FORWARD",
            title: "Push Forward!",
            points: 1,
            description: "",
        });
        expect(next.gameState.challenges?.deckTop?.challengeCondition).toBe("PUSH_FORWARD");
        expect(next.gameState.challenges?.deckRemaining).toBe(9);
        expect(next.gameState.challenges?.discardCount).toBe(1);
    });

    it("clears claimsThisTurn on TURN_END and TURN_START", () => {
        const createSnapshot = fixtures.createResponse.snapshot as MatchSnapshotDto;
        const withClaims = boardViewFromSnapshot({
            ...createSnapshot,
            gameState: {
                ...createSnapshot.gameState,
                challenges: {
                    activeSlots: [null, null, null],
                    deckTop: { challengeCondition: "GRAB_IT", title: "Grab It!", points: 1, description: "" },
                    deckRemaining: 10,
                    discardCount: 0,
                    claimsThisTurn: 2,
                },
            },
        });

        const afterEnd = applyEvent(withClaims, {
            kind: "phaseStarted",
            turnNumber: 3,
            phase: "TURN_END",
            activeCoach: "HOME",
        } as DomainEvent);
        expect(afterEnd.gameState.challenges?.claimsThisTurn).toBe(0);

        const midTurn = boardViewFromSnapshot({
            ...createSnapshot,
            gameState: {
                ...createSnapshot.gameState,
                challenges: {
                    activeSlots: [null, null, null],
                    deckTop: null,
                    deckRemaining: 0,
                    discardCount: 0,
                    claimsThisTurn: 1,
                },
            },
        });
        const afterStart = applyEvent(midTurn, {
            kind: "phaseStarted",
            turnNumber: 4,
            phase: "TURN_START",
            activeCoach: "AWAY",
        } as DomainEvent);
        expect(afterStart.gameState.challenges?.claimsThisTurn).toBe(0);

        const afterAction = applyEvent(midTurn, {
            kind: "phaseStarted",
            turnNumber: 4,
            phase: "PLAYER_ACTION",
            activeCoach: "AWAY",
            actionsRemaining: 3,
        } as DomainEvent);
        expect(afterAction.gameState.challenges?.claimsThisTurn).toBe(1);
    });
});

describe("deferred-fold motion origin contract", () => {
    it("passes touchdown celebrate-then-clear order", () => {
        const view = boardViewFromSnapshot({
            lastEventId: 0,
            gameState: (fixtures.createResponse.snapshot as MatchSnapshotDto).gameState,
            pieces: {
                players: [
                    { id: "h0", team: "HOME", status: "STANDING", at: { x: 6, y: 1 }, holdsBall: true },
                    { id: "a0", team: "AWAY", status: "STANDING", at: { x: 5, y: 8 }, holdsBall: false },
                ],
                looseBallLocations: [],
            },
        });

        expect(() =>
            replayAsserting(view, [
                {
                    kind: "touchdownScored",
                    playerId: "h0",
                    team: "HOME",
                    at: { x: 6, y: 1 },
                    homeScore: 1,
                    awayScore: 0,
                },
                { kind: "ballRemovedFromPlay", playerId: "h0" },
                { kind: "playerRemovedAfterTouchdown", playerId: "h0", from: { x: 6, y: 1 } },
            ]),
        ).not.toThrow();
    });

    it("fails when dugout clear folds before touchdown celebration", () => {
        const view = boardViewFromSnapshot({
            lastEventId: 0,
            gameState: (fixtures.createResponse.snapshot as MatchSnapshotDto).gameState,
            pieces: {
                players: [{ id: "h0", team: "HOME", status: "STANDING", at: { x: 6, y: 1 }, holdsBall: true }],
                looseBallLocations: [],
            },
        });

        expect(() =>
            replayAsserting(view, [
                { kind: "ballRemovedFromPlay", playerId: "h0" },
                { kind: "playerRemovedAfterTouchdown", playerId: "h0", from: { x: 6, y: 1 } },
                {
                    kind: "touchdownScored",
                    playerId: "h0",
                    team: "HOME",
                    at: { x: 6, y: 1 },
                    homeScore: 1,
                    awayScore: 0,
                },
            ]),
        ).toThrow(/Presentation drift \(touchdownScored#3\)/);
    });

    it("fails when playerMoved is folded before its motion would run from origin", () => {
        const view = boardViewFromSnapshot({
            lastEventId: 0,
            gameState: (fixtures.createResponse.snapshot as MatchSnapshotDto).gameState,
            pieces: {
                players: [{ id: "h0", team: "HOME", status: "STANDING", at: { x: 2, y: 3 }, holdsBall: false }],
                looseBallLocations: [],
            },
        });
        const earlyFold = applyEvent(view, {
            kind: "playerMoved",
            playerId: "h0",
            from: { x: 2, y: 3 },
            to: { x: 3, y: 3 },
        });

        expect(() =>
            assertMotionOriginForEvent(
                earlyFold.pieces,
                { kind: "playerMoved", playerId: "h0", from: { x: 2, y: 3 }, to: { x: 3, y: 3 } },
                "playerMoved#late",
            ),
        ).toThrow(/Presentation drift \(playerMoved#late\)/);
    });

    it("fails when bounce hops after the ball already folded away from origin", () => {
        const view = boardViewFromSnapshot({
            lastEventId: 0,
            gameState: (fixtures.createResponse.snapshot as MatchSnapshotDto).gameState,
            pieces: {
                players: [],
                looseBallLocations: [{ x: 4, y: 4 }],
            },
        });
        const earlyFold = applyEvent(view, {
            kind: "ballBounced",
            from: { x: 4, y: 4 },
            to: { x: 5, y: 5 },
        });

        expect(() =>
            assertMotionOriginForEvent(
                earlyFold.pieces,
                { kind: "ballBounced", from: { x: 4, y: 4 }, to: { x: 5, y: 5 } },
                "ballBounced#late",
            ),
        ).toThrow(/Presentation drift \(ballBounced#late\)/);
    });
});
