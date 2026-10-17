import { describe, expect, it } from "vitest";
import type { BlockDieFace, ChallengeClaimCondition, LegalActionWire } from "../api/client";
import {
    detectForcedDecision,
    ENFORCER_BLOCK_ORDER_SUMMARY,
    isAbilityUseOrDeclineOnly,
    isBlockFaceOrderOnly,
    isBonusPlayOrDeclineOnly,
    isHeldDiceBonusMix,
} from "./forcedDecision";

const playerBase = {
    id: "a",
    playerId: "p1",
    targetCoordinate: { x: 0, y: 0 },
} as const;

const playerOnly = {
    id: "a",
    playerId: "p1",
} as const;

const coachBase = {
    id: "a",
} as const;

function playMomentum(): LegalActionWire {
    return {
        ...coachBase,
        id: "play-momentum",
        kind: "playMomentum",
        actionType: "PLAY_MOMENTUM",
        bonusPlay: "Momentum",
        description: "Momentum card text from the server.",
    };
}

function playDeadEye(): LegalActionWire {
    return {
        ...coachBase,
        id: "play-accurate",
        kind: "playDeadEye",
        actionType: "PLAY_DEAD_EYE",
        bonusPlay: "Dead Eye",
        description: "Dead Eye card text from the server.",
    };
}

function playSpikedGauntlets(): LegalActionWire {
    return {
        ...coachBase,
        id: "play-bladed",
        kind: "playSpikedGauntlets",
        actionType: "PLAY_SPIKED_GAUNTLETS",
        bonusPlay: "Spiked Gauntlets",
        description: "Spiked Gauntlets card text from the server.",
    };
}

function adjustBlock(faces: BlockDieFace[] = ["MISS"]): LegalActionWire {
    return {
        ...playerOnly,
        id: "adjust-rt",
        kind: "adjustBlockByDoOver",
        actionType: "ADJUST_BLOCK_BY_DO_OVER",
        bonusPlay: "Do-Over",
        description: "Do-Over card text from the server.",
        destination: { x: 1, y: 1 },
        faces,
        assisted: false,
    };
}

function declineBonus(): LegalActionWire {
    return { ...coachBase, id: "decline", kind: "declineBonus", actionType: "SKIP" };
}

function skip(): LegalActionWire {
    return { ...playerOnly, id: "skip", kind: "skip", actionType: "SKIP" };
}

function blockReroll(reroll: boolean, faces: BlockDieFace[] = ["MISS", "SHOVE"]): LegalActionWire {
    return {
        ...playerOnly,
        id: `block-${reroll}`,
        kind: "chooseBlockReroll",
        actionType: "CHOOSE_BLOCK_REROLL",
        destination: { x: 1, y: 1 },
        faces,
        assisted: false,
        reroll,
    };
}

function throwReroll(reroll: boolean, naturalRoll = 2): LegalActionWire {
    return {
        ...playerOnly,
        id: `throw-${reroll}`,
        kind: "chooseThrowReroll",
        actionType: "CHOOSE_THROW_REROLL",
        destination: { x: 2, y: 2 },
        naturalRoll,
        range: "SHORT",
        obstructed: true,
        reroll,
    };
}

function blockFaceOrder(orderedFaces: BlockDieFace[], id = orderedFaces.join("-")): LegalActionWire {
    return {
        ...playerOnly,
        id: `order-${id}`,
        kind: "chooseBlockFaceOrder",
        actionType: "CHOOSE_BLOCK_FACE_ORDER",
        destination: { x: 1, y: 1 },
        orderedFaces,
    };
}

describe("isBonusPlayOrDeclineOnly", () => {
    it("matches play + skip", () => {
        expect(isBonusPlayOrDeclineOnly([adjustBlock(), declineBonus()])).toBe(true);
    });

    it("rejects board actions", () => {
        expect(
            isBonusPlayOrDeclineOnly([
                adjustBlock(),
                skip(),
                { ...playerBase, id: "run", kind: "run", actionType: "RUN", path: [{ x: 0, y: 0 }, { x: 1, y: 0 }] },
            ]),
        ).toBe(false);
    });

    it("rejects ability re-roll mix", () => {
        expect(isBonusPlayOrDeclineOnly([adjustBlock(), blockReroll(true), blockReroll(false)])).toBe(false);
    });
});

describe("isHeldDiceBonusMix", () => {
    it("matches Do-Over plus block re-roll", () => {
        expect(
            isHeldDiceBonusMix([
                adjustBlock(),
                blockReroll(false),
                blockReroll(true),
                declineBonus(),
            ]),
        ).toBe(true);
    });
});

describe("isAbilityUseOrDeclineOnly", () => {
    it("matches block re-roll pair", () => {
        expect(isAbilityUseOrDeclineOnly([blockReroll(false), blockReroll(true)])).toBe(true);
    });

    it("rejects mixed kinds", () => {
        expect(isAbilityUseOrDeclineOnly([blockReroll(true), skip()])).toBe(false);
    });
});

describe("isBlockFaceOrderOnly", () => {
    it("matches face-order permutations", () => {
        expect(
            isBlockFaceOrderOnly([
                blockFaceOrder(["MISS", "SMASH"]),
                blockFaceOrder(["SMASH", "MISS"]),
            ]),
        ).toBe(true);
    });

    it("rejects mixed kinds", () => {
        expect(isBlockFaceOrderOnly([blockFaceOrder(["MISS", "SMASH"]), skip()])).toBe(false);
    });
});

describe("detectForcedDecision", () => {
    it("builds a Momentum prompt titled from the server bonusPlay name", () => {
        const decision = detectForcedDecision([playMomentum(), declineBonus()]);
        expect(decision?.kind).toBe("bonus");
        if (decision?.kind !== "bonus") return;
        expect(decision.title).toBe("Play Momentum?");
        expect(decision.plays[0]?.description).toBe("Momentum card text from the server.");
    });

    it("builds a mixed Do-Over + block re-roll prompt", () => {
        const decision = detectForcedDecision([
            adjustBlock(),
            blockReroll(false),
            blockReroll(true),
            declineBonus(),
        ]);
        expect(decision?.kind).toBe("bonus");
        if (decision?.kind !== "bonus") return;
        expect(decision.abilityReroll?.useLabel).toBe("Re-roll block dice");
        expect(decision.skip?.kind).toBe("declineBonus");
        expect(decision.impact).toContain("Current block dice");
    });

    it("uses Adjust* fields for Do-Over impact (no lastDiceSummary needed)", () => {
        const decision = detectForcedDecision([adjustBlock(["MISS"]), declineBonus()]);
        expect(decision?.kind).toBe("bonus");
        if (decision?.kind !== "bonus") return;
        expect(decision.impact).toBe("Current block dice (Unassisted): Miss");
    });

    it("does not show stale lastDiceSummary for pre-roll bonuses like Dead Eye", () => {
        const decision = detectForcedDecision([playDeadEye(), declineBonus()], {
            lastDiceSummary: "BROKEN! (3)",
        });
        expect(decision?.kind).toBe("bonus");
        if (decision?.kind !== "bonus") return;
        expect(decision.impact).toBeNull();
    });

    it("does not show stale lastDiceSummary for Spiked Gauntlets (pre-armour modifier)", () => {
        const decision = detectForcedDecision([playSpikedGauntlets(), declineBonus()], {
            lastDiceSummary: "BOUNCE 1! (1)",
        });
        expect(decision?.kind).toBe("bonus");
        if (decision?.kind !== "bonus") return;
        expect(decision.impact).toBeNull();
    });

    it("builds an Second Swing prompt with faces", () => {
        const decision = detectForcedDecision([blockReroll(false), blockReroll(true)], {
            pendingAbility: "Second Swing",
            abilityDescription: "Re-roll the Block Dice once.",
        });
        expect(decision?.kind).toBe("ability");
        if (decision?.kind !== "ability") return;
        expect(decision.title).toContain("Second Swing");
        expect(decision.impact).toBe("Current block dice (Unassisted): Miss · Shove");
        expect(decision.description).toContain("Re-roll");
    });

    it("builds an Pinpoint Passer prompt with throw roll", () => {
        const decision = detectForcedDecision([throwReroll(false), throwReroll(true)], {
            pendingAbility: "Pinpoint Passer",
        });
        expect(decision?.kind).toBe("ability");
        if (decision?.kind !== "ability") return;
        expect(decision.impact).toBe("Current throw roll: 2 (short, obstructed)");
    });

    it("builds an Enforcer face-order prompt", () => {
        const decision = detectForcedDecision(
            [blockFaceOrder(["MISS", "SMASH"]), blockFaceOrder(["SMASH", "MISS"])],
            {
                pendingAbility: "Enforcer",
            },
        );
        expect(decision?.kind).toBe("blockFaceOrder");
        if (decision?.kind !== "blockFaceOrder") return;
        expect(decision.title).toContain("Enforcer");
        expect(decision.summary).toBe(ENFORCER_BLOCK_ORDER_SUMMARY);
        expect(decision.impact).toBe("Rolled faces: Miss · Smash");
        expect(decision.choices).toHaveLength(2);
        expect(decision.choices.map((c) => c.orderedFaces)).toEqual([
            ["MISS", "SMASH"],
            ["SMASH", "MISS"],
        ]);
    });

    it("builds a claim-challenge prompt", () => {
        const claim = (slot: number, title: string, points: number): LegalActionWire => ({
            ...coachBase,
            id: `claim-${slot}`,
            kind: "claimChallenge",
            actionType: "CLAIM_CHALLENGE",
            slotIndex: slot,
            claimCondition: "DROP_THEM",
            challengeTitle: title,
            points,
        });
        const decision = detectForcedDecision([
            claim(0, "Drop Them!", 2),
            claim(1, "Drop Them!", 2),
        ]);
        expect(decision?.kind).toBe("claim");
        if (decision?.kind !== "claim") return;
        expect(decision.choices).toHaveLength(2);
        expect(decision.title).toContain("challenge");
    });

    it("builds a replace-challenge prompt with challenge titles and server claim text", () => {
        const replace = (slot: number, claimCondition: ChallengeClaimCondition, title: string): LegalActionWire => ({
            ...coachBase,
            id: `replace-${slot}`,
            kind: "replaceChallenge",
            actionType: "REPLACE_CHALLENGE",
            slotIndex: slot,
            claimCondition,
            challengeTitle: title,
            description: `${title} card text`,
        });
        const decision = detectForcedDecision([
            replace(0, "DROP_THEM", "Drop Them!"),
            replace(1, "PLAY_TO_THE_STANDS", "Play to the Stands!"),
            replace(2, "GRAB_IT", "Grab It!"),
        ]);
        expect(decision?.kind).toBe("replace");
        if (decision?.kind !== "replace") return;
        expect(decision.choices).toHaveLength(3);
        expect(decision.choices.map((c) => c.challengeTitle)).toEqual([
            "Drop Them!",
            "Play to the Stands!",
            "Grab It!",
        ]);
        expect(decision.choices[0]?.description).toBe("Drop Them! card text");
    });
});
