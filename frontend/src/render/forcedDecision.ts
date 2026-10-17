import type { AdjustDoOverWire, ForcedBonusPlayWire, LegalActionWire, PlayerAbility } from "../api/client";
import { isBonusCardChoice, playerAbilityLabel } from "./actionVisuals";

export type ForcedBonusDecision = {
    kind: "bonus";
    title: string;
    /** Each play carries its server card text (`description`). */
    plays: ForcedBonusPlayWire[];
    skip: Extract<LegalActionWire, { kind: "declineBonus" }> | null;
    impact: string | null;
    /** When Do-Over shares a held-dice pause with OS / Pinpoint Passer. */
    abilityReroll?: {
        use: LegalActionWire;
        keep: LegalActionWire;
        useLabel: string;
        keepLabel: string;
    } | null;
};

export type ForcedAbilityDecision = {
    kind: "ability";
    title: string;
    ability: PlayerAbility | null;
    description: string | null;
    impact: string | null;
    use: LegalActionWire;
    decline: LegalActionWire;
    useLabel: string;
    declineLabel: string;
};

export type ForcedClaimDecision = {
    kind: "claim";
    title: string;
    choices: Extract<LegalActionWire, { kind: "claimChallenge" }>[];
};

export type ForcedReplaceDecision = {
    kind: "replace";
    title: string;
    /** Each choice carries its server claim-condition text (`description`). */
    choices: Extract<LegalActionWire, { kind: "replaceChallenge" }>[];
};

export type ForcedBlockFaceOrderDecision = {
    kind: "blockFaceOrder";
    title: string;
    ability: PlayerAbility | null;
    /** Always-visible coach explainer. */
    summary: string;
    /** Rolled faces as a multiset (order here is not resolution order). */
    impact: string;
    choices: Extract<LegalActionWire, { kind: "chooseBlockFaceOrder" }>[];
};

export type ForcedDecision =
    | ForcedBonusDecision
    | ForcedAbilityDecision
    | ForcedClaimDecision
    | ForcedReplaceDecision
    | ForcedBlockFaceOrderDecision;

export function isAdjustDoOverKind(kind: string): kind is AdjustDoOverWire["kind"] {
    return (
        kind === "adjustThrowByDoOver" ||
        kind === "adjustBlockByDoOver" ||
        kind === "adjustArmourByDoOver"
    );
}

function formatBlockFace(face: string): string {
    return face
        .split("_")
        .map((part) => part.charAt(0) + part.slice(1).toLowerCase())
        .join(" ");
}

function formatThrowRange(range: string): string {
    return range.toLowerCase().replace("_", "-");
}

/** True when every legal action is play/adjust/decline of a bonus window (no board targets). */
export function isBonusPlayOrDeclineOnly(actions: LegalActionWire[]): boolean {
    if (actions.length === 0) return false;
    let hasPlay = false;
    for (const action of actions) {
        if (isBonusCardChoice(action)) {
            hasPlay = true;
            continue;
        }
        if (action.kind === "declineBonus") continue;
        return false;
    }
    return hasPlay;
}

/**
 * Held-dice pause with Do-Over plus OS / Pinpoint Passer choose-reroll actions.
 * DeclineBonus (skip Do-Over) may also be present.
 */
export function isHeldDiceBonusMix(actions: LegalActionWire[]): boolean {
    if (actions.length === 0) return false;
    let hasAdjust = false;
    let hasReroll = false;
    for (const action of actions) {
        if (isAdjustDoOverKind(action.kind)) {
            hasAdjust = true;
            continue;
        }
        if (action.kind === "declineBonus") continue;
        if (action.kind === "chooseBlockReroll" || action.kind === "chooseThrowReroll") {
            hasReroll = true;
            continue;
        }
        return false;
    }
    return hasAdjust && hasReroll;
}

/** True when every legal action is an ability re-roll use/keep pair. */
export function isAbilityUseOrDeclineOnly(actions: LegalActionWire[]): boolean {
    if (actions.length === 0) return false;
    const kinds = new Set(actions.map((a) => a.kind));
    if (kinds.size !== 1) return false;
    const kind = kinds.values().next().value;
    return kind === "chooseBlockReroll" || kind === "chooseThrowReroll";
}

/** True when every legal action is a claim-challenge pick. */
export function isClaimChallengeOnly(actions: LegalActionWire[]): boolean {
    if (actions.length === 0) return false;
    return actions.every((a) => a.kind === "claimChallenge");
}

/** True when every legal action is a replace-challenge pick. */
export function isReplaceChallengeOnly(actions: LegalActionWire[]): boolean {
    if (actions.length === 0) return false;
    return actions.every((a) => a.kind === "replaceChallenge");
}

/** True when every legal action is an Enforcer face-order permutation. */
export function isBlockFaceOrderOnly(actions: LegalActionWire[]): boolean {
    if (actions.length === 0) return false;
    return actions.every((a) => a.kind === "chooseBlockFaceOrder");
}

/** Coach-facing copy for the Enforcer face-order modal. */
export const ENFORCER_BLOCK_ORDER_SUMMARY =
    "Normal blocks pick one die. Enforcer applies every rolled face in the order you choose — including against a Prone target (each Smash/Flatten/Tackle is another armour check; Shove still pushes). The chain stops only if they are Injured. Follow-ups happen automatically.";

export function formatBlockFaceOrderLabel(faces: string[]): string {
    return faces.map(formatBlockFace).join(" → ");
}

function adjustImpact(action: AdjustDoOverWire): string {
    if (action.kind === "adjustBlockByDoOver") {
        const faces = action.faces.map(formatBlockFace).join(" · ");
        const assist = action.assisted ? "Assisted" : "Unassisted";
        return `Current block dice (${assist}): ${faces}`;
    }
    if (action.kind === "adjustThrowByDoOver") {
        const range = formatThrowRange(action.range);
        const lane = action.obstructed ? ", obstructed" : "";
        return `Current throw roll: ${action.naturalRoll} (${range}${lane})`;
    }
    return `Current armour roll: ${action.naturalRoll}`;
}

function blockRerollImpact(action: Extract<LegalActionWire, { kind: "chooseBlockReroll" }>): string {
    const faces = action.faces.map(formatBlockFace).join(" · ");
    const assist = action.assisted ? "Assisted" : "Unassisted";
    return `Current block dice (${assist}): ${faces}`;
}

function throwRerollImpact(action: Extract<LegalActionWire, { kind: "chooseThrowReroll" }>): string {
    const range = formatThrowRange(action.range);
    const lane = action.obstructed ? ", obstructed" : "";
    return `Current throw roll: ${action.naturalRoll} (${range}${lane})`;
}

function bonusImpact(plays: ForcedBonusPlayWire[], lastDiceSummary: string | null): string | null {
    const adjust = plays.find((p): p is AdjustDoOverWire => isAdjustDoOverKind(p.kind));
    if (adjust != null) {
        return adjustImpact(adjust);
    }
    const names = plays.map((p) => p.bonusPlay);
    // Do-Over re-rolls dice already on the table; Spiked Gauntlets is pre-armour (no roll yet).
    const affectsHeldRoll = names.some((name) => name === "Do-Over");
    if (affectsHeldRoll && lastDiceSummary != null && lastDiceSummary.length > 0) {
        return `Current roll: ${lastDiceSummary}`;
    }
    const freeOrBudgeted = names.some((name) => name === "Back on Your Feet" || name === "Fresh Legs");
    if (freeOrBudgeted) {
        return "Play makes this action free; Decline keeps it as a normal (budgeted) action.";
    }
    return null;
}

export function detectForcedDecision(
    actions: LegalActionWire[],
    options: {
        pendingAbility?: PlayerAbility | null;
        abilityDescription?: string | null;
        lastDiceSummary?: string | null;
    } = {},
): ForcedDecision | null {
    if (isClaimChallengeOnly(actions)) {
        const choices = actions.filter(
            (a): a is Extract<LegalActionWire, { kind: "claimChallenge" }> => a.kind === "claimChallenge",
        );
        return {
            kind: "claim",
            title: choices.length === 1 ? `Claim ${choices[0]!.challengeTitle}?` : "Claim a challenge card?",
            choices,
        };
    }

    if (isReplaceChallengeOnly(actions)) {
        const choices = actions.filter(
            (a): a is Extract<LegalActionWire, { kind: "replaceChallenge" }> => a.kind === "replaceChallenge",
        );
        return {
            kind: "replace",
            title: "Replace a challenge card",
            choices,
        };
    }

    if (isBonusPlayOrDeclineOnly(actions) || isHeldDiceBonusMix(actions)) {
        const plays = actions.filter(isBonusCardChoice);
        const skip =
            actions.find(
                (a): a is Extract<LegalActionWire, { kind: "declineBonus" }> => a.kind === "declineBonus",
            ) ?? null;
        const blockUse = actions.find((a) => a.kind === "chooseBlockReroll" && a.reroll) ?? null;
        const blockKeep = actions.find((a) => a.kind === "chooseBlockReroll" && !a.reroll) ?? null;
        const throwUse = actions.find((a) => a.kind === "chooseThrowReroll" && a.reroll) ?? null;
        const throwKeep = actions.find((a) => a.kind === "chooseThrowReroll" && !a.reroll) ?? null;
        const abilityReroll =
            blockUse != null && blockKeep != null
                ? {
                      use: blockUse,
                      keep: blockKeep,
                      useLabel: "Re-roll block dice",
                      keepLabel: "Keep block dice",
                  }
                : throwUse != null && throwKeep != null
                  ? {
                        use: throwUse,
                        keep: throwKeep,
                        useLabel: "Re-roll throw",
                        keepLabel: "Keep throw roll",
                    }
                  : null;
        const title =
            plays.length === 1
                ? (() => {
                      const name = plays[0]!.bonusPlay;
                      return name.endsWith("!") ? `Play ${name}` : `Play ${name}?`;
                  })()
                : "Play a bonus card?";
        const sampleBlock = actions.find((a) => a.kind === "chooseBlockReroll");
        const sampleThrow = actions.find((a) => a.kind === "chooseThrowReroll");
        const impactFromAbility =
            sampleBlock?.kind === "chooseBlockReroll"
                ? blockRerollImpact(sampleBlock)
                : sampleThrow?.kind === "chooseThrowReroll"
                  ? throwRerollImpact(sampleThrow)
                  : null;
        return {
            kind: "bonus",
            title,
            plays,
            skip,
            impact: bonusImpact(plays, options.lastDiceSummary ?? null) ?? impactFromAbility,
            abilityReroll,
        };
    }

    if (isAbilityUseOrDeclineOnly(actions)) {
        const sample = actions[0]!;
        if (sample.kind === "chooseBlockReroll") {
            const use = actions.find((a) => a.kind === "chooseBlockReroll" && a.reroll) ?? null;
            const decline = actions.find((a) => a.kind === "chooseBlockReroll" && !a.reroll) ?? null;
            if (use == null || decline == null) return null;
            const ability = options.pendingAbility ?? null;
            return {
                kind: "ability",
                title: ability != null ? `${playerAbilityLabel(ability)}?` : "Re-roll block dice?",
                ability,
                description: options.abilityDescription ?? null,
                impact: blockRerollImpact(sample),
                use,
                decline,
                useLabel: "Re-roll block dice",
                declineLabel: "Keep block dice",
            };
        }
        if (sample.kind === "chooseThrowReroll") {
            const use = actions.find((a) => a.kind === "chooseThrowReroll" && a.reroll) ?? null;
            const decline = actions.find((a) => a.kind === "chooseThrowReroll" && !a.reroll) ?? null;
            if (use == null || decline == null) return null;
            const ability = options.pendingAbility ?? null;
            return {
                kind: "ability",
                title: ability != null ? `${playerAbilityLabel(ability)}?` : "Re-roll throw?",
                ability,
                description: options.abilityDescription ?? null,
                impact: throwRerollImpact(sample),
                use,
                decline,
                useLabel: "Re-roll throw",
                declineLabel: "Keep throw roll",
            };
        }
    }

    if (isBlockFaceOrderOnly(actions)) {
        const choices = actions.filter(
            (a): a is Extract<LegalActionWire, { kind: "chooseBlockFaceOrder" }> =>
                a.kind === "chooseBlockFaceOrder",
        );
        const sample = choices[0]!;
        const ability = options.pendingAbility ?? null;
        const faces = [...sample.orderedFaces].sort().map(formatBlockFace).join(" · ");
        return {
            kind: "blockFaceOrder",
            title: ability != null ? `${playerAbilityLabel(ability)} — choose order` : "Choose block die order",
            ability,
            summary: ENFORCER_BLOCK_ORDER_SUMMARY,
            impact: `Rolled faces: ${faces}`,
            choices,
        };
    }

    return null;
}
