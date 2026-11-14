import { describe, expect, it } from "vitest";
import type { Coordinate, LegalActionWire, PlayerId } from "../api/client";
import { eligiblePlayerIds } from "./actionVisuals";
import { resolveSquareClick, resolveTokenClick, selectedPlayerActions, selectionLockedToSoleEligible } from "./pitchClicks";

const thrower: PlayerId = "home-thrower";
const receiver: PlayerId = "home-receiver";
const other: PlayerId = "home-lineman";
const foe: PlayerId = "away-blitzer";

const receiverAt: Coordinate = { x: 5, y: 6 };
const foeAt: Coordinate = { x: 4, y: 5 };
const emptyAt: Coordinate = { x: 2, y: 2 };

function throwToReceiver(): LegalActionWire {
    return {
        kind: "throw",
        playerId: thrower,
        actionType: "THROW",
        targetCoordinate: receiverAt,
        target: { destination: receiverAt, range: "SHORT", obstructed: false, throwCheckNeeded: 3 },
    };
}

function blockFoe(): LegalActionWire {
    return {
        kind: "block",
        playerId: thrower,
        actionType: "BLOCK",
        targetCoordinate: foeAt,
        destination: foeAt,
    };
}

function runFor(playerId: PlayerId, to: Coordinate): LegalActionWire {
    return {
        kind: "run",
        playerId,
        actionType: "RUN",
        targetCoordinate: to,
        path: [{ x: to.x - 1, y: to.y }, to],
    };
}

describe("resolveTokenClick", () => {
    it("throws to an eligible teammate instead of selecting them", () => {
        const legal = [throwToReceiver(), runFor(receiver, { x: 6, y: 6 })];
        const selectedActions = selectedPlayerActions(legal, thrower);
        const eligible = eligiblePlayerIds(legal);

        expect(eligible.has(receiver)).toBe(true);

        expect(
            resolveTokenClick({
                interactive: true,
                selectedPlayerId: thrower,
                eligible,
                selectedActions,
                clickedPlayerId: receiver,
                clickedAt: receiverAt,
            }),
        ).toEqual({ kind: "submit", actions: [throwToReceiver()] });
    });

    it("blocks an opponent on their token", () => {
        const legal = [blockFoe(), runFor(thrower, { x: 3, y: 5 })];
        const selectedActions = selectedPlayerActions(legal, thrower);

        expect(
            resolveTokenClick({
                interactive: true,
                selectedPlayerId: thrower,
                eligible: eligiblePlayerIds(legal),
                selectedActions,
                clickedPlayerId: foe,
                clickedAt: foeAt,
            }),
        ).toEqual({ kind: "submit", actions: [blockFoe()] });
    });

    it("selects an eligible player when nothing is selected yet", () => {
        const legal = [runFor(receiver, { x: 6, y: 6 })];

        expect(
            resolveTokenClick({
                interactive: true,
                selectedPlayerId: null,
                eligible: eligiblePlayerIds(legal),
                selectedActions: [],
                clickedPlayerId: receiver,
                clickedAt: receiverAt,
            }),
        ).toEqual({ kind: "select", playerId: receiver });
    });

    it("switches selection to another eligible player who is not a target", () => {
        const legal = [throwToReceiver(), runFor(other, { x: 1, y: 1 })];
        const selectedActions = selectedPlayerActions(legal, thrower);

        expect(
            resolveTokenClick({
                interactive: true,
                selectedPlayerId: thrower,
                eligible: eligiblePlayerIds(legal),
                selectedActions,
                clickedPlayerId: other,
                clickedAt: { x: 8, y: 8 },
            }),
        ).toEqual({ kind: "select", playerId: other });
    });

    it("opens the chooser when throw and another action share the receiver square", () => {
        const markOnReceiver: LegalActionWire = {
            kind: "mark",
            playerId: thrower,
            actionType: "MARK",
            targetCoordinate: receiverAt,
            path: [{ x: 4, y: 6 }, receiverAt],
        };
        const legal = [throwToReceiver(), markOnReceiver];
        const selectedActions = selectedPlayerActions(legal, thrower);

        expect(
            resolveTokenClick({
                interactive: true,
                selectedPlayerId: thrower,
                eligible: eligiblePlayerIds(legal),
                selectedActions,
                clickedPlayerId: receiver,
                clickedAt: receiverAt,
            }),
        ).toEqual({ kind: "choose", actions: [throwToReceiver(), markOnReceiver] });
    });

    it("selects dugout / reserves pieces without a pitch square", () => {
        const legal = [
            {
                kind: "reserves" as const,
                playerId: other,
                actionType: "RESERVES" as const,
                targetCoordinate: { x: 1, y: 1 },
                placement: { x: 1, y: 1 },
            },
        ];

        expect(
            resolveTokenClick({
                interactive: true,
                selectedPlayerId: null,
                eligible: eligiblePlayerIds(legal),
                selectedActions: [],
                clickedPlayerId: other,
                clickedAt: null,
            }),
        ).toEqual({ kind: "select", playerId: other });
    });

    it("does not treat a token as a throw target when not interactive", () => {
        const legal = [throwToReceiver(), runFor(receiver, { x: 6, y: 6 })];

        expect(
            resolveTokenClick({
                interactive: false,
                selectedPlayerId: thrower,
                eligible: eligiblePlayerIds(legal),
                selectedActions: selectedPlayerActions(legal, thrower),
                clickedPlayerId: receiver,
                clickedAt: receiverAt,
            }),
        ).toEqual({ kind: "select", playerId: receiver });
    });

    it("ignores other tokens when locked to the sole eligible player", () => {
        const legal = [runFor(thrower, emptyAt)];

        expect(
            resolveTokenClick({
                interactive: true,
                selectedPlayerId: thrower,
                eligible: eligiblePlayerIds(legal),
                selectedActions: selectedPlayerActions(legal, thrower),
                clickedPlayerId: other,
                clickedAt: { x: 1, y: 1 },
            }),
        ).toEqual({ kind: "ignore" });
    });

    it("still acts on a target token when locked to the sole eligible player", () => {
        const legal = [blockFoe()];

        expect(
            resolveTokenClick({
                interactive: true,
                selectedPlayerId: thrower,
                eligible: eligiblePlayerIds(legal),
                selectedActions: selectedPlayerActions(legal, thrower),
                clickedPlayerId: foe,
                clickedAt: foeAt,
            }),
        ).toEqual({ kind: "submit", actions: [blockFoe()] });
    });
});

describe("resolveSquareClick", () => {
    it("submits a single action on a highlighted square", () => {
        const legal = [runFor(thrower, emptyAt)];
        expect(resolveSquareClick(selectedPlayerActions(legal, thrower), thrower, emptyAt)).toEqual({
            kind: "submit",
            actions: [runFor(thrower, emptyAt)],
        });
    });

    it("deselects when clicking an empty square while someone is selected", () => {
        const legal = [runFor(thrower, { x: 3, y: 3 })];
        expect(resolveSquareClick(selectedPlayerActions(legal, thrower), thrower, emptyAt)).toEqual({
            kind: "deselect",
        });
    });

    it("ignores empty squares when selection is locked to the sole eligible player", () => {
        const legal = [runFor(thrower, { x: 3, y: 3 })];
        expect(resolveSquareClick(selectedPlayerActions(legal, thrower), thrower, emptyAt, true)).toEqual({
            kind: "ignore",
        });
    });

    it("ignores empty squares when nobody is selected", () => {
        expect(resolveSquareClick([], null, emptyAt)).toEqual({ kind: "ignore" });
    });

    it("opens the chooser when multiple actions share a square", () => {
        const run = runFor(thrower, emptyAt);
        const mark: LegalActionWire = {
            kind: "mark",
            playerId: thrower,
            actionType: "MARK",
            targetCoordinate: emptyAt,
            path: [{ x: 1, y: 2 }, emptyAt],
        };
        expect(resolveSquareClick([run, mark], thrower, emptyAt)).toEqual({
            kind: "choose",
            actions: [run, mark],
        });
    });

    it("opens the chooser when multiple declareRuns share a square", () => {
        const plain: LegalActionWire = {
            kind: "declareRun",
            playerId: thrower,
            actionType: "RUN",
            targetCoordinate: emptyAt,
        };
        const other: LegalActionWire = {
            kind: "declareRun",
            playerId: receiver,
            actionType: "RUN",
            targetCoordinate: emptyAt,
        };
        expect(resolveSquareClick([plain, other], thrower, emptyAt)).toEqual({
            kind: "choose",
            actions: [plain, other],
        });
    });
});

describe("selectionLockedToSoleEligible", () => {
    it("locks only when the selection is the single eligible player", () => {
        const sole = new Set<PlayerId>([thrower]);
        const both = new Set<PlayerId>([thrower, receiver]);

        expect(selectionLockedToSoleEligible(sole, thrower)).toBe(true);
        expect(selectionLockedToSoleEligible(sole, null)).toBe(false);
        expect(selectionLockedToSoleEligible(sole, receiver)).toBe(false);
        expect(selectionLockedToSoleEligible(both, thrower)).toBe(false);
    });
});
