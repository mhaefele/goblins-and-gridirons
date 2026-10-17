import kindHeuristic from "./kinds/heuristic.png?url";
import kindLookahead from "./kinds/lookahead.png?url";
import kindOnnx from "./kinds/onnx.png?url";
import profileBaseline from "./profiles/baseline.png?url";
import profileDefense from "./profiles/defense.png?url";
import profileGeneral from "./profiles/general.png?url";
import profileSpread from "./profiles/spread.png?url";
import profileTd from "./profiles/td.png?url";

const KIND_ICONS: Record<string, string> = {
    LOOKAHEAD: kindLookahead,
    HEURISTIC: kindHeuristic,
    ONNX: kindOnnx,
};

const PROFILE_ICONS: Record<string, string> = {
    baseline: profileBaseline,
    general: profileGeneral,
    spread: profileSpread,
    td: profileTd,
    defense: profileDefense,
};

/** Icon for a computer-opponent kind (Lookahead / Heuristic / Trained model). */
export function opponentKindIcon(kind: string): string | null {
    return KIND_ICONS[kind] ?? null;
}

/** Icon for an ONNX profile folder id (`general`, `td`, …). */
export function opponentProfileIcon(profileId: string): string | null {
    return PROFILE_ICONS[profileId] ?? null;
}
