import type { Coordinate, LegalActionWire, PlayerId } from "../api/client";
import { actionsAtCoordinate, actionsForPlayer } from "./actionVisuals";

/** What a pitch square click should do. */
export type SquareClickIntent =
    | { kind: "submit"; actions: LegalActionWire[] }
    | { kind: "choose"; actions: LegalActionWire[] }
    | { kind: "deselect" }
    | { kind: "ignore" };

/** What a token (or dugout token) click should do. */
export type TokenClickIntent =
    | { kind: "submit"; actions: LegalActionWire[] }
    | { kind: "choose"; actions: LegalActionWire[] }
    | { kind: "select"; playerId: PlayerId }
    | { kind: "ignore" };

function intentForActions(actions: LegalActionWire[]): Extract<SquareClickIntent, { kind: "submit" | "choose" }> {
    if (actions.length === 1) return { kind: "submit", actions };
    return { kind: "choose", actions };
}

/** True when the selection is the only player who can act — don't clear or swap away. */
export function selectionLockedToSoleEligible(
    eligible: ReadonlySet<PlayerId>,
    selectedPlayerId: PlayerId | null,
): boolean {
    if (selectedPlayerId == null || eligible.size !== 1) return false;
    return eligible.has(selectedPlayerId);
}

/**
 * Square under the cursor — only the selected player's board targets matter.
 * Empty click clears selection (unless locked to a sole eligible); otherwise submit or open the chooser.
 */
export function resolveSquareClick(
    selectedActions: LegalActionWire[],
    selectedPlayerId: PlayerId | null,
    coordinate: Coordinate,
    lockSelection = false,
): SquareClickIntent {
    const at = actionsAtCoordinate(selectedActions, coordinate);
    if (at.length === 0) {
        if (selectedPlayerId == null || lockSelection) return { kind: "ignore" };
        return { kind: "deselect" };
    }
    return intentForActions(at);
}

export type TokenClickInput = {
    interactive: boolean;
    selectedPlayerId: PlayerId | null;
    /** All players who currently have at least one legal action. */
    eligible: ReadonlySet<PlayerId>;
    /** Board actions for the selected player only. */
    selectedActions: LegalActionWire[];
    clickedPlayerId: PlayerId;
    /** Pitch square of the clicked piece; null when in reserves / dugout. */
    clickedAt: Coordinate | null;
};

/**
 * Token click priority:
 * 1. If the selection can already act on this square (throw/block/…), do that —
 *    even when the target is also eligible to select (teammate receivers).
 * 2. Else if locked to the sole eligible player, ignore clicks on anyone else.
 * 3. Else if the piece is eligible, select them.
 * 4. Else select them anyway (card focus / digout).
 */
export function resolveTokenClick(input: TokenClickInput): TokenClickIntent {
    const { interactive, selectedPlayerId, eligible, selectedActions, clickedPlayerId, clickedAt } = input;

    if (interactive && selectedPlayerId != null && clickedAt != null) {
        const at = actionsAtCoordinate(selectedActions, clickedAt);
        if (at.length > 0) return intentForActions(at);
    }

    if (
        interactive &&
        selectionLockedToSoleEligible(eligible, selectedPlayerId) &&
        clickedPlayerId !== selectedPlayerId
    ) {
        return { kind: "ignore" };
    }

    if (interactive && eligible.has(clickedPlayerId)) {
        return { kind: "select", playerId: clickedPlayerId };
    }

    return { kind: "select", playerId: clickedPlayerId };
}

/** Selected player's actions from the full legal list — shared by Play + tests. */
export function selectedPlayerActions(legalActions: LegalActionWire[], selectedPlayerId: PlayerId | null): LegalActionWire[] {
    return selectedPlayerId != null ? actionsForPlayer(legalActions, selectedPlayerId) : [];
}
