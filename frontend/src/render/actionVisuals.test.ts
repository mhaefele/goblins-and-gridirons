import { describe, expect, it } from "vitest";
import {
    abilityBannerDecline,
    describeAction,
    formatPlayerDecisionType,
    formatPotentialChallengeClaims,
    playerAbilityLabel,
    remainingMoveSquaresFromLegal,
    squareHighlightsForActions,
} from "./actionVisuals";
import type { LegalActionWire } from "../api/client";

describe("formatPotentialChallengeClaims", () => {
    it("phrases CERTAIN vs POTENTIAL from server fields", () => {
        expect(
            formatPotentialChallengeClaims([
                {
                    claimCondition: "KEEP_RUNNING",
                    challengeTitle: "Keep Running!",
                    points: 2,
                    certainty: "CERTAIN",
                },
                {
                    claimCondition: "DROP_THEM",
                    challengeTitle: "Drop Them!",
                    points: 2,
                    certainty: "POTENTIAL",
                },
            ]),
        ).toBe("earns Keep Running! (+2) · may earn Drop Them! (+2)");
    });
});

describe("describeAction challenge cues", () => {
    it("appends claim hints on tagged actions and leaves untagged clean", () => {
        const tagged: LegalActionWire = {
            kind: "block",
            id: "b",
            playerId: "p1",
            actionType: "BLOCK",
            targetCoordinate: { x: 6, y: 8 },
            destination: { x: 6, y: 8 },
            potentialChallengeClaims: [
                {
                    claimCondition: "DROP_THEM",
                    challengeTitle: "Drop Them!",
                    points: 2,
                    certainty: "POTENTIAL",
                },
            ],
        };
        const plain: LegalActionWire = {
            kind: "block",
            id: "b2",
            playerId: "p1",
            actionType: "BLOCK",
            targetCoordinate: { x: 6, y: 8 },
            destination: { x: 6, y: 8 },
        };
        expect(describeAction(tagged)).toContain("may earn Drop Them! (+2)");
        expect(describeAction(plain)).not.toContain("earn");
    });

    it("marks highlights when a square action could claim a challenge", () => {
        const actions: LegalActionWire[] = [
            {
                kind: "run",
                id: "r",
                playerId: "p1",
                actionType: "RUN",
                targetCoordinate: { x: 4, y: 6 },
                path: [
                    { x: 3, y: 6 },
                    { x: 4, y: 6 },
                ],
                potentialChallengeClaims: [
                    {
                        claimCondition: "KEEP_RUNNING",
                        challengeTitle: "Keep Running!",
                        points: 2,
                        certainty: "CERTAIN",
                    },
                ],
            },
        ];
        const hl = squareHighlightsForActions(actions).get("4,6");
        expect(hl?.offersChallengeClaim).toBe(true);
        expect(squareHighlightsForActions([{ ...actions[0]!, potentialChallengeClaims: [] }]).get("4,6")?.offersChallengeClaim).toBe(
            false,
        );
    });
});

describe("ballEncounter cues", () => {
    it("appends pickup / bounce copy from server tags", () => {
        const pickup: LegalActionWire = {
            kind: "run",
            id: "r",
            playerId: "p1",
            actionType: "RUN",
            targetCoordinate: { x: 4, y: 6 },
            path: [
                { x: 3, y: 6 },
                { x: 4, y: 6 },
            ],
            ballEncounter: "PICK_UP",
        };
        const bounce: LegalActionWire = {
            kind: "mark",
            id: "m",
            playerId: "p1",
            actionType: "MARK",
            targetCoordinate: { x: 5, y: 6 },
            path: [
                { x: 4, y: 6 },
                { x: 5, y: 6 },
            ],
            ballEncounter: "BOUNCE",
        };
        expect(describeAction(pickup)).toContain("picks up ball");
        expect(describeAction(bounce)).toContain("bounces ball");
    });

    it("marks highlights for pickup vs bounce", () => {
        const pickup = squareHighlightsForActions([
            {
                kind: "run",
                id: "r",
                playerId: "p1",
                actionType: "RUN",
                targetCoordinate: { x: 4, y: 6 },
                path: [
                    { x: 3, y: 6 },
                    { x: 4, y: 6 },
                ],
                ballEncounter: "PICK_UP",
            },
        ]).get("4,6");
        expect(pickup?.willPickUpBall).toBe(true);
        expect(pickup?.willBounceBall).toBeFalsy();

        const bounce = squareHighlightsForActions([
            {
                kind: "mark",
                id: "m",
                playerId: "p1",
                actionType: "MARK",
                targetCoordinate: { x: 5, y: 6 },
                path: [
                    { x: 4, y: 6 },
                    { x: 5, y: 6 },
                ],
                ballEncounter: "BOUNCE",
            },
        ]).get("5,6");
        expect(bounce?.willBounceBall).toBe(true);
        expect(bounce?.willPickUpBall).toBeFalsy();
    });

    it("tints throw highlights by check difficulty", () => {
        const handOff = squareHighlightsForActions([
            {
                kind: "throw",
                id: "t0",
                playerId: "p1",
                actionType: "THROW",
                targetCoordinate: { x: 4, y: 6 },
                target: { destination: { x: 4, y: 6 }, range: "HAND_OFF", obstructed: false },
            },
        ]).get("4,6");
        expect(handOff?.throwDifficulty).toBe("handOff");

        const shortClear = squareHighlightsForActions([
            {
                kind: "throwTeammate",
                id: "tt",
                playerId: "ogre",
                actionType: "THROW_TEAMMATE",
                teammateId: "gob",
                targetCoordinate: { x: 5, y: 6 },
                target: { destination: { x: 5, y: 6 }, range: "SHORT", obstructed: false },
            },
        ]).get("5,6");
        expect(shortClear?.throwDifficulty).toBe("short");

        const hardLong = squareHighlightsForActions([
            {
                kind: "throw",
                id: "t1",
                playerId: "p1",
                actionType: "THROW",
                targetCoordinate: { x: 6, y: 6 },
                target: { destination: { x: 6, y: 6 }, range: "LONG", obstructed: false },
            },
        ]).get("6,6");
        expect(hardLong?.throwDifficulty).toBe("hard");

        const hardObstructed = squareHighlightsForActions([
            {
                kind: "throw",
                id: "t2",
                playerId: "p1",
                actionType: "THROW",
                targetCoordinate: { x: 7, y: 6 },
                target: { destination: { x: 7, y: 6 }, range: "SHORT", obstructed: true },
            },
        ]).get("7,6");
        expect(hardObstructed?.throwDifficulty).toBe("hard");
    });
});

describe("formatPlayerDecisionType", () => {
    it("names Undying offer", () => {
        expect(formatPlayerDecisionType("UNDYING_OFFER")).toBe("Undying — stand up (or skip)");
    });

    it("names Ball Guard placement on block continue", () => {
        expect(formatPlayerDecisionType("BLOCK_CONTINUE", "Ball Guard")).toBe(
            "Ball Guard — choose bounce square",
        );
    });

    it("falls back when ability is missing", () => {
        expect(formatPlayerDecisionType("BLOCK_CONTINUE")).toBe("Continue block");
    });

    it("shows remaining move budget for continue and random-move prompts", () => {
        expect(formatPlayerDecisionType("RUN_CONTINUE", null, 2)).toBe("Continue run — up to 2 squares");
        expect(formatPlayerDecisionType("RUN_CONTINUE", null, 1)).toBe("Continue run — up to 1 square");
        expect(formatPlayerDecisionType("RUN_CONTINUE")).toBe("Continue run — remaining squares");
    });
});

describe("abilityBannerDecline", () => {
    const quickReleaseThrow: LegalActionWire = {
        kind: "quickReleaseThrow",
        id: "throw",
        playerId: "carrier",
        actionType: "QUICK_RELEASE_THROW",
        targetCoordinate: { x: 1, y: 0 },
        target: { destination: { x: 1, y: 0 }, range: "SHORT", obstructed: false },
    };
    const continueBlock: LegalActionWire = {
        kind: "continueBlock",
        id: "cont",
        playerId: "blocker",
        actionType: "CONTINUE_BLOCK",
        targetCoordinate: { x: 2, y: 1 },
        destination: { x: 2, y: 1 },
    };
    const skip: LegalActionWire = {
        kind: "skip",
        id: "skip",
        playerId: "p1",
        actionType: "SKIP",
    };

    it("labels Quick Release continue-block as Take the block", () => {
        expect(abilityBannerDecline([quickReleaseThrow, continueBlock])).toEqual({
            action: continueBlock,
            label: "Take the block",
        });
    });

    it("labels optional ability Skip", () => {
        expect(abilityBannerDecline([skip])).toEqual({ action: skip, label: "Skip" });
    });

    it("prefers continue-block over Skip when both exist", () => {
        expect(abilityBannerDecline([skip, continueBlock])?.label).toBe("Take the block");
    });

    it("returns null when there is no decline", () => {
        expect(abilityBannerDecline([quickReleaseThrow])).toBeNull();
    });
});

describe("remainingMoveSquaresFromLegal", () => {
    it("returns the longest Run path among legal actions", () => {
        expect(
            remainingMoveSquaresFromLegal([
                {
                    kind: "run",
                    id: "a",
                    playerId: "p1",
                    actionType: "RUN",
                    targetCoordinate: { x: 2, y: 0 },
                    path: [
                        { x: 0, y: 0 },
                        { x: 1, y: 0 },
                        { x: 2, y: 0 },
                    ],
                },
                {
                    kind: "run",
                    id: "b",
                    playerId: "p1",
                    actionType: "RUN",
                    targetCoordinate: { x: 1, y: 0 },
                    path: [
                        { x: 0, y: 0 },
                        { x: 1, y: 0 },
                    ],
                },
                {
                    kind: "skip",
                    id: "s",
                    playerId: "p1",
                    actionType: "SKIP",
                },
            ]),
        ).toBe(2);
    });

    it("returns null when there are no Run paths", () => {
        expect(
            remainingMoveSquaresFromLegal([
                {
                    kind: "declareRun",
                    id: "d",
                    playerId: "p1",
                    actionType: "RUN",
                    targetCoordinate: { x: 0, y: 0 },
                },
            ]),
        ).toBeNull();
    });
});
