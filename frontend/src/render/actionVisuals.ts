import type { Coordinate, ForcedBonusPlayWire, LegalActionType, LegalActionWire, PlayerAbility, PlayerDecisionType, PlayerId } from "../api/client";

type ActionTypeColor = { fill: string; stroke: string; label: string };

/** `PLAY_*` types are absent: they use `BONUS_PLAY_COLOR` and the server's `bonusPlay` name as label. */
export const ACTION_TYPE_COLORS: Partial<Record<LegalActionType, ActionTypeColor>> = {
    RUN: { fill: "#22c55e", stroke: "#15803d", label: "Run" },
    MARK: { fill: "#38bdf8", stroke: "#0369a1", label: "Mark" },
    SIDESTEP: { fill: "#a3e635", stroke: "#4d7c0f", label: "Sidestep" },
    THROW: { fill: "#c084fc", stroke: "#7e22ce", label: "Throw" },
    THROW_TEAMMATE: { fill: "#e879f9", stroke: "#a21caf", label: "Throw team-mate" },
    BLOCK: { fill: "#f97316", stroke: "#c2410c", label: "Block" },
    FOUL: { fill: "#ef4444", stroke: "#991b1b", label: "Foul" },
    STAND_UP: { fill: "#fbbf24", stroke: "#b45309", label: "Stand up" },
    RESERVES: { fill: "#94a3b8", stroke: "#475569", label: "Reserves" },
    SKIP: { fill: "#64748b", stroke: "#334155", label: "Skip" },
    CANCEL_THROW_TEAMMATE: { fill: "#64748b", stroke: "#334155", label: "Cancel throw" },
    CHOOSE_BLOCK_REROLL: { fill: "#f97316", stroke: "#c2410c", label: "Block re-roll" },
    CHOOSE_BLOCK_FACE_ORDER: { fill: "#f97316", stroke: "#c2410c", label: "Block order" },
    CHOOSE_THROW_REROLL: { fill: "#c084fc", stroke: "#7e22ce", label: "Throw re-roll" },
    ADJUST_THROW_BY_DO_OVER: { fill: "#f59e0b", stroke: "#b45309", label: "Re-roll throw" },
    ADJUST_BLOCK_BY_DO_OVER: { fill: "#f59e0b", stroke: "#b45309", label: "Re-roll block" },
    ADJUST_ARMOUR_BY_DO_OVER: { fill: "#f59e0b", stroke: "#b45309", label: "Re-roll armour" },
    PLACE_LOOSE_BALL: { fill: "#fbbf24", stroke: "#b45309", label: "Place ball" },
    CONTINUE_BLOCK: { fill: "#f97316", stroke: "#c2410c", label: "Continue block" },
    QUICK_RELEASE_THROW: { fill: "#c084fc", stroke: "#7e22ce", label: "Quick Release" },
    CLAIM_CHALLENGE: { fill: "#ef4444", stroke: "#b91c1c", label: "Claim challenge" },
    REPLACE_CHALLENGE: { fill: "#f87171", stroke: "#b91c1c", label: "Replace challenge" },
};

/** Pitch tint for throw check difficulty — Long and/or obstructed share −1 (capped). */
export type ThrowDifficulty = "handOff" | "short" | "hard";

export const THROW_DIFFICULTY_COLORS: Record<ThrowDifficulty, { fill: string; stroke: string }> = {
    handOff: { fill: "#e9d5ff", stroke: "#a78bfa" },
    short: { fill: "#c084fc", stroke: "#7e22ce" },
    hard: { fill: "#fb7185", stroke: "#be123c" },
};

export type SquareHighlight = {
    coordinate: Coordinate;
    actionType: LegalActionType;
    /** When multiple action types share a square, prefer BLOCK/THROW over movement for color. */
    primaryType: LegalActionType;
    /** Throw / quick-release / throw-teammate check band (null for non-throws). */
    throwDifficulty?: ThrowDifficulty;
    /** True when at least one action on this square requires a bonus card. */
    requiresBonus?: boolean;
    /** True when at least one action offers an optional available bonus card. */
    offersAvailableBonus?: boolean;
    /** True when at least one action could earn an active challenge (D1.F.4 / FF.10). */
    offersChallengeClaim?: boolean;
    /** True when at least one action picks up a loose ball on this square. */
    willPickUpBall?: boolean;
    /** True when at least one action bounces a loose ball from this square. */
    willBounceBall?: boolean;
};

const PRIORITY: LegalActionType[] = [
    "BLOCK",
    "FOUL",
    "CONTINUE_BLOCK",
    "THROW",
    "THROW_TEAMMATE",
    "QUICK_RELEASE_THROW",
    "CHOOSE_BLOCK_REROLL",
    "CHOOSE_THROW_REROLL",
    "PLACE_LOOSE_BALL",
    "MARK",
    "STAND_UP",
    "RESERVES",
    "SIDESTEP",
    "RUN",
    "SKIP",
    "CANCEL_THROW_TEAMMATE",
];

function priorityRank(type: LegalActionType): number {
    const index = PRIORITY.indexOf(type);
    return index === -1 ? PRIORITY.length : index;
}

const THROW_DIFFICULTY_RANK: Record<ThrowDifficulty, number> = {
    handOff: 0,
    short: 1,
    hard: 2,
};

export function throwDifficultyForAction(action: LegalActionWire): ThrowDifficulty | null {
    if (action.kind !== "throw" && action.kind !== "throwTeammate" && action.kind !== "quickReleaseThrow") {
        return null;
    }
    if (action.target.range === "HAND_OFF") return "handOff";
    if (action.target.range === "LONG" || action.target.obstructed) return "hard";
    return "short";
}

function hardestThrowDifficulty(actions: LegalActionWire[]): ThrowDifficulty | undefined {
    let hardest: ThrowDifficulty | undefined;
    for (const action of actions) {
        const difficulty = throwDifficultyForAction(action);
        if (difficulty == null) continue;
        if (hardest == null || THROW_DIFFICULTY_RANK[difficulty] > THROW_DIFFICULTY_RANK[hardest]) {
            hardest = difficulty;
        }
    }
    return hardest;
}

const BONUS_PLAY_COLOR = { fill: "#f59e0b", stroke: "#b45309" };

export function highlightColors(highlight: SquareHighlight): { fill: string; stroke: string } {
    if (highlight.throwDifficulty != null) {
        return THROW_DIFFICULTY_COLORS[highlight.throwDifficulty];
    }
    return ACTION_TYPE_COLORS[highlight.primaryType] ?? BONUS_PLAY_COLOR;
}

export function formatActionType(actionType: LegalActionType): string {
    return ACTION_TYPE_COLORS[actionType]?.label ?? actionType;
}

export function playerAbilityLabel(ability: PlayerAbility): string {
    return ability;
}

/** Wire `ability` fields carry the backend `PlayerAbility` name; empty means none. */
export function parsePlayerAbility(raw: string | null | undefined): PlayerAbility | null {
    return raw == null || raw === "" ? null : (raw as PlayerAbility);
}

export function formatPlayerDecisionType(
    decisionType: PlayerDecisionType,
    ability?: PlayerAbility | null,
    remainingMoveSquares?: number | null,
): string {
    const abilityName = ability != null ? playerAbilityLabel(ability) : null;
    const moveBudget =
        remainingMoveSquares != null && remainingMoveSquares > 0
            ? `up to ${remainingMoveSquares} square${remainingMoveSquares === 1 ? "" : "s"}`
            : null;
    switch (decisionType) {
        case "REGULAR_PLAYER_ACTION":
            return "Choose a player action";
        case "INITIAL_PLAYER_PLACEMENT":
            return "Initial player placement";
        case "EMERGENCY_RESERVES":
            return "Emergency reserves";
        case "UNDYING_OFFER":
            return "Undying — stand up (or skip)";
        case "THROW_TEAMMATE_CONTINUE":
            return "Throw team-mate — pick a landing square (or cancel)";
        case "THROW_CONTINUE":
            return abilityName ? `${abilityName} — continue throw` : "Continue throw";
        case "BLOCK_CONTINUE":
            return abilityName === "Ball Guard"
                ? "Ball Guard — choose bounce square"
                : abilityName
                  ? `${abilityName} — continue block`
                  : "Continue block";
        case "FOUL_CONTINUE":
            return "Continue foul";
        case "STAND_UP_CONTINUE":
            return "Stand up — play Back on Your Feet or skip";
        case "RESERVES_CONTINUE":
            return "Reserves — play Reserves bonus or skip";
        case "RUN_CONTINUE":
            return moveBudget != null ? `Continue run — ${moveBudget}` : "Continue run — remaining squares";
        case "MARK_CONTINUE":
            return "Continue mark — play a bonus or skip";
        case "SIDESTEP_CONTINUE":
            return "Continue sidestep — play a bonus or skip";
        case "BALL_GUARD_CONTINUE":
            return "Ball Guard — choose bounce square";
        case "KNOCK_DOWN_CONTINUE":
            return "Knock down — play a bonus or skip";
        case "ARMOUR_CHECK_CONTINUE":
            return "Armour check — play a bonus or skip";
        case "GANG_UP_OFFER":
            return "Play Gang Up or skip";
        case "HOLD_THE_LINE_OFFER":
            return "Play Hold the Line or skip";
        case "EXTRA_EFFORT_OFFER":
            return "Play Extra Effort or skip";
        case "DANCE_STEP_OFFER":
            return abilityName ? `${abilityName} — free Sidestep (or skip)` : "Dance Step — free Sidestep (or skip)";
        case "CLAIM_CHALLENGE":
            return "Claim a challenge card";
        case "REPLACE_CHALLENGE":
            return "Replace a challenge card";
        default: {
            const _exhaustive: never = decisionType;
            return String(_exhaustive);
        }
    }
}

/** Max Run path length among legal actions — remaining Move for continue / random-move prompts. */
export function remainingMoveSquaresFromLegal(actions: LegalActionWire[]): number | null {
    let maxSteps = 0;
    for (const action of actions) {
        if (action.kind !== "run") continue;
        maxSteps = Math.max(maxSteps, Math.max(0, action.path.length - 1));
    }
    return maxSteps > 0 ? maxSteps : null;
}

/** Server-tagged challenge-earn hints — display only (no client claim rules). */
export function formatPotentialChallengeClaims(
    claims: NonNullable<LegalActionWire["potentialChallengeClaims"]>,
): string {
    return claims
        .map((claim) => {
            const verb = claim.certainty === "CERTAIN" ? "earns" : "may earn";
            return `${verb} ${claim.challengeTitle} (+${claim.points})`;
        })
        .join(" · ");
}

function claimCueSuffix(action: LegalActionWire): string {
    const claims = action.potentialChallengeClaims;
    if (claims == null || claims.length === 0) return "";
    return ` · ${formatPotentialChallengeClaims(claims)}`;
}

function ballEncounterSuffix(action: LegalActionWire): string {
    switch (action.ballEncounter) {
        case "PICK_UP":
            return " · picks up ball";
        case "BOUNCE":
            return " · bounces ball";
        default:
            return "";
    }
}

export function describeAction(action: LegalActionWire): string {
    const at =
        "targetCoordinate" in action
            ? `(${action.targetCoordinate.x}, ${action.targetCoordinate.y})`
            : "";
    const bonusSuffix =
        action.requiredBonusCard != null
            ? ` · uses ${action.requiredBonusCard}`
            : action.availableBonusCards != null && action.availableBonusCards.length > 0
              ? ` · may use ${action.availableBonusCards.join(" / ")}`
              : "";
    if (isBonusCardChoice(action)) return `Play ${action.bonusPlay}`;
    const ballSuffix = ballEncounterSuffix(action);
    const claimSuffix = claimCueSuffix(action);
    switch (action.kind) {
        case "throw":
        case "quickReleaseThrow":
        case "throwTeammate": {
            const range = action.target.range.toLowerCase().replace("_", "-");
            const lane = action.target.obstructed ? " obstructed" : "";
            return `${formatActionType(action.actionType)} ${range}${lane} → ${at}${bonusSuffix}${ballSuffix}${claimSuffix}`;
        }
        case "throwTeammateSelect":
            return `${formatActionType(action.actionType)} — choose ${action.teammateId}${bonusSuffix}${claimSuffix}`;
        case "cancelThrowTeammate":
            return "Cancel throw team-mate — pick a different action";
        case "block":
            return `${formatActionType(action.actionType)} opponent at ${at}${bonusSuffix}${ballSuffix}${claimSuffix}`;
        case "foul":
            return `${formatActionType(action.actionType)} prone opponent at ${at}${bonusSuffix}${ballSuffix}${claimSuffix}`;
        case "continueBlock":
            return "Take the block — decline quick-release";
        case "run":
        case "mark":
        case "sidestep": {
            const steps = Math.max(0, action.path.length - 1);
            return `${formatActionType(action.actionType)} ${steps} step${steps === 1 ? "" : "s"} → ${at}${bonusSuffix}${ballSuffix}${claimSuffix}`;
        }
        case "standUp":
            return `${formatActionType(action.actionType)} here ${at}${bonusSuffix}${ballSuffix}${claimSuffix}`;
        case "declareRun":
            return `${formatActionType(action.actionType)} — roll Move${bonusSuffix}${claimSuffix}`;
        case "reserves":
            return `${formatActionType(action.actionType)} deploy → ${at}${bonusSuffix}${ballSuffix}${claimSuffix}`;
        case "skip":
            return "Skip — decline this optional follow-up";
        case "declineBonus":
            return "Decline — do not play a bonus card";
        case "placeLooseBall":
            return `Place ball (${action.from.x}, ${action.from.y}) → ${at}`;
        case "chooseBlockReroll":
            return action.reroll ? "Re-roll block dice" : "Keep block dice";
        case "chooseBlockFaceOrder":
            return `Resolve as ${action.orderedFaces.map(formatBlockFaceForLabel).join(" → ")}`;
        case "chooseThrowReroll":
            return action.reroll ? "Re-roll throw" : "Keep throw roll";
        case "claimChallenge":
            return `Claim ${action.challengeTitle} (+${action.points})`;
        case "replaceChallenge":
            return `Replace ${action.challengeTitle}`;
        default: {
            const _exhaustive: never = action;
            return String(_exhaustive);
        }
    }
}

function formatBlockFaceForLabel(face: string): string {
    return face
        .split("_")
        .map((part) => part.charAt(0) + part.slice(1).toLowerCase())
        .join(" ");
}

/** Longer tooltip for action buttons / chooser. */
export function describeActionTooltip(action: LegalActionWire): string {
    const base = describeAction(action);
    if (isBonusCardChoice(action)) return `${base}. ${action.description}`;
    switch (action.kind) {
        case "run":
            return action.ballEncounter === "PICK_UP"
                ? `${base}. Ends on the ball — picks it up.`
                : action.ballEncounter === "BOUNCE"
                  ? `${base}. Ends on the ball — it bounces.`
                  : `${base}. Click to move along the legal path.`;
        case "mark":
            return action.ballEncounter === "PICK_UP"
                ? `${base}. Ends on the ball — picks it up (Sure Scoop).`
                : action.ballEncounter === "BOUNCE"
                  ? `${base}. Ends on the ball — it bounces.`
                  : `${base}. End adjacent to an opponent (marking).`;
        case "sidestep":
            return action.ballEncounter === "PICK_UP"
                ? `${base}. Steps onto the ball — picks it up (Sure Scoop).`
                : action.ballEncounter === "BOUNCE"
                  ? `${base}. Steps onto the ball — it bounces.`
                  : `${base}. One-square sidestep.`;
        case "block":
            return `${base}. Resolve block dice against that player.`;
        case "foul":
            return `${base}. Roll one block die on the Foul chart (Spotted / Take That / Slipped / Got Them).`;
        case "throw":
            return `${base}. ${action.target.obstructed ? "Line of sight is obstructed (−1)." : "Clear lane."}`;
        case "throwTeammateSelect":
            return `${base}. Then pick a landing square (or cancel).`;
        case "throwTeammate":
            return `${base}. ${action.target.obstructed ? "Path is obstructed (−1)." : "Clear path."}`;
        case "cancelThrowTeammate":
            return `${base}. Unwinds the throw without spending the action.`;
        case "standUp":
            return `${base}. Costs the action; then you may continue if rules allow.`;
        case "declareRun":
            return `${base}. Roll D6 for Move, then choose a path up to that many squares.`;
        case "reserves":
            return action.ballEncounter === "BOUNCE"
                ? `${base}. Lands on the ball — it bounces.`
                : `${base}. Bring a reserve onto the end zone.`;
        case "skip":
            return `${base}. Leave the player where they are.`;
        case "declineBonus":
            return `${base}. Continue without playing a bonus from hand.`;
        case "placeLooseBall":
            return `${base}. Ball lands here instead of rolling D8.`;
        case "chooseBlockReroll":
            return action.reroll
                ? `${base}. You must accept the re-roll result.`
                : `${base}. Keep the dice already rolled.`;
        case "chooseBlockFaceOrder":
            return `${base}. Enforcer resolves each die in this order.`;
        case "chooseThrowReroll":
            return action.reroll
                ? `${base}. You must accept the re-roll result.`
                : `${base}. Keep the throw roll already made.`;
        case "continueBlock":
            return `${base}. Resolve the deferred block.`;
        case "quickReleaseThrow":
            return `${base}. Free quick-release throw or hand-off.`;
        case "claimChallenge":
            return `${base}. Take this active challenge into your bonus hand.`;
        case "replaceChallenge":
            return `${base}. Discard this active and draw a replacement.`;
        default: {
            const _exhaustive: never = action;
            return String(_exhaustive);
        }
    }
}

/** Server `BonusCardChoice` subtypes (Play* and Do-Over adjusts) — the only legal actions carrying `bonusPlay`. */
export function isBonusCardChoice(action: LegalActionWire): action is ForcedBonusPlayWire {
    return "bonusPlay" in action;
}

export function actionsForPlayer(actions: LegalActionWire[], playerId: PlayerId): LegalActionWire[] {
    return actions.filter((action) => "playerId" in action && action.playerId === playerId);
}

export function eligiblePlayerIds(actions: LegalActionWire[]): Set<PlayerId> {
    return new Set(
        actions.flatMap((action) => ("playerId" in action ? [action.playerId] : [])),
    );
}

/** Square targets for pitch highlights / clicks — SKIP / modal-only prompts are excluded. */
export function boardTargetActions(actions: LegalActionWire[]): Array<Extract<LegalActionWire, { targetCoordinate: Coordinate }>> {
    return actions.filter(
        (action): action is Extract<LegalActionWire, { targetCoordinate: Coordinate }> =>
            "targetCoordinate" in action &&
            action.kind !== "chooseBlockReroll" &&
            action.kind !== "chooseBlockFaceOrder" &&
            action.kind !== "chooseThrowReroll",
    );
}

export function squareHighlightsForActions(actions: LegalActionWire[]): Map<string, SquareHighlight> {
    const bySquare = new Map<string, LegalActionWire[]>();
    for (const action of boardTargetActions(actions)) {
        const key = `${action.targetCoordinate.x},${action.targetCoordinate.y}`;
        const bucket = bySquare.get(key) ?? [];
        bucket.push(action);
        bySquare.set(key, bucket);
    }

    const highlights = new Map<string, SquareHighlight>();
    for (const [key, bucket] of bySquare) {
        const primary = [...bucket].sort((a, b) => priorityRank(a.actionType) - priorityRank(b.actionType))[0]!;
        highlights.set(key, {
            coordinate: primary.targetCoordinate,
            actionType: primary.actionType,
            primaryType: primary.actionType,
            throwDifficulty: hardestThrowDifficulty(bucket),
            requiresBonus: bucket.some((action) => action.requiredBonusCard != null),
            offersAvailableBonus: bucket.some(
                (action) => (action.availableBonusCards?.length ?? 0) > 0 && action.requiredBonusCard == null,
            ),
            offersChallengeClaim: bucket.some(
                (action) => (action.potentialChallengeClaims?.length ?? 0) > 0,
            ),
            willPickUpBall: bucket.some((action) => action.ballEncounter === "PICK_UP"),
            willBounceBall: bucket.some((action) => action.ballEncounter === "BOUNCE"),
        });
    }
    return highlights;
}

export function actionsAtCoordinate(actions: LegalActionWire[], coordinate: Coordinate): LegalActionWire[] {
    const key = `${coordinate.x},${coordinate.y}`;
    return boardTargetActions(actions).filter((action) => `${action.targetCoordinate.x},${action.targetCoordinate.y}` === key);
}

export function skipActionForPlayer(actions: LegalActionWire[], playerId: PlayerId | null): Extract<LegalActionWire, { kind: "skip" }> | null {
    if (playerId == null) return null;
    return (
        actions.find(
            (action): action is Extract<LegalActionWire, { kind: "skip" }> =>
                action.kind === "skip" && action.playerId === playerId,
        ) ?? null
    );
}

export function cancelThrowTeammateActionForPlayer(
    actions: LegalActionWire[],
    playerId: PlayerId | null,
): Extract<LegalActionWire, { kind: "cancelThrowTeammate" }> | null {
    if (playerId == null) return null;
    return (
        actions.find(
            (action): action is Extract<LegalActionWire, { kind: "cancelThrowTeammate" }> =>
                action.kind === "cancelThrowTeammate" && action.playerId === playerId,
        ) ?? null
    );
}

/**
 * Optional decline for an ability prompt (Quick Release continue-block, free-grant Skip, …).
 * Shown on the local coach's ability banner — not on the opponent's.
 */
export function abilityBannerDecline(actions: LegalActionWire[]): {
    action: LegalActionWire;
    label: string;
} | null {
    const continueBlock = actions.find(
        (action): action is Extract<LegalActionWire, { kind: "continueBlock" }> => action.kind === "continueBlock",
    );
    if (continueBlock != null) {
        return { action: continueBlock, label: "Take the block" };
    }
    const skip = actions.find((action): action is Extract<LegalActionWire, { kind: "skip" }> => action.kind === "skip");
    if (skip != null) {
        return { action: skip, label: "Skip" };
    }
    return null;
}

export function chooseBlockRerollActions(
    actions: LegalActionWire[],
    playerId: PlayerId | null,
): Extract<LegalActionWire, { kind: "chooseBlockReroll" }>[] {
    if (playerId == null) return [];
    return actions.filter(
        (action): action is Extract<LegalActionWire, { kind: "chooseBlockReroll" }> =>
            action.kind === "chooseBlockReroll" && action.playerId === playerId,
    );
}

export function chooseThrowRerollActions(
    actions: LegalActionWire[],
    playerId: PlayerId | null,
): Extract<LegalActionWire, { kind: "chooseThrowReroll" }>[] {
    if (playerId == null) return [];
    return actions.filter(
        (action): action is Extract<LegalActionWire, { kind: "chooseThrowReroll" }> =>
            action.kind === "chooseThrowReroll" && action.playerId === playerId,
    );
}
