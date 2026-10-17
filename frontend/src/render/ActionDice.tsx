import { useEffect, useState } from "react";
import type { BlockDieFace, Coordinate } from "../api/client";
import { isBlockDieFace, type DiceTheatreState, type ShownDie } from "../state/presentEvents";
import { squareCenter } from "./pitchLayout";

const DIE_SIZE = 0.62;
const DIE_GAP = 0.1;
const FACE_PAD = 0.08;

type ActionDiceProps = {
    theatre: DiceTheatreState;
    /** Optional second square (e.g. block defender) — dice sit beside the contest. */
    beside?: Coordinate | null | undefined;
    onSkip?: (() => void) | undefined;
};

/** Shortened die labels so long face names fit on the cube. */
const SHORT_FACE_LABELS: Partial<Record<BlockDieFace, string>> = { FLATTEN: "FLAT", TACKLE: "TACK" };

function dieFaceLabel(result: string): string {
    return isBlockDieFace(result) ? (SHORT_FACE_LABELS[result] ?? result) : result;
}

/** Distinct glyphs for block faces — readable without relying only on text. */
function BlockFaceGlyph({ face }: { face: BlockDieFace }) {
    const x = FACE_PAD;
    const y = FACE_PAD;
    const w = DIE_SIZE - FACE_PAD * 2;
    const h = DIE_SIZE - FACE_PAD * 2;
    const cx = DIE_SIZE / 2;
    const cy = DIE_SIZE / 2;

    switch (face) {
        case "SMASH":
            return (
                <g className="action-dice__glyph" aria-hidden="true">
                    <path
                        d={`M ${cx} ${y + 0.04} L ${x + w - 0.02} ${cy + 0.02} L ${cx + 0.04} ${cy + 0.06} L ${cx} ${y + h - 0.02} L ${cx - 0.04} ${cy + 0.06} L ${x + 0.02} ${cy + 0.02} Z`}
                        fill="#b91c1c"
                        stroke="#7f1d1d"
                        strokeWidth={0.02}
                    />
                    <path
                        d={`M ${cx - 0.1} ${cy - 0.08} L ${cx + 0.12} ${cy + 0.1} M ${cx + 0.1} ${cy - 0.08} L ${cx - 0.12} ${cy + 0.1}`}
                        stroke="#fef2f2"
                        strokeWidth={0.045}
                        strokeLinecap="round"
                    />
                </g>
            );
        case "FLATTEN":
            return (
                <g className="action-dice__glyph" aria-hidden="true">
                    <circle cx={cx} cy={cy} r={0.16} fill="#9a3412" stroke="#7c2d12" strokeWidth={0.025} />
                    <path
                        d={`M ${cx} ${cy - 0.2} L ${cx + 0.05} ${cy - 0.05} L ${cx + 0.2} ${cy - 0.02} L ${cx + 0.08} ${cy + 0.06} L ${cx + 0.12} ${cy + 0.2} L ${cx} ${cy + 0.1} L ${cx - 0.12} ${cy + 0.2} L ${cx - 0.08} ${cy + 0.06} L ${cx - 0.2} ${cy - 0.02} L ${cx - 0.05} ${cy - 0.05} Z`}
                        fill="#fbbf24"
                        stroke="#92400e"
                        strokeWidth={0.02}
                    />
                </g>
            );
        case "SHOVE":
            return (
                <g className="action-dice__glyph" aria-hidden="true">
                    <path
                        d={`M ${x + 0.06} ${cy} L ${cx - 0.02} ${y + 0.08} L ${cx - 0.02} ${cy - 0.06} L ${x + w - 0.06} ${cy - 0.06} L ${x + w - 0.06} ${cy + 0.06} L ${cx - 0.02} ${cy + 0.06} L ${cx - 0.02} ${y + h - 0.08} Z`}
                        fill="#0369a1"
                        stroke="#0c4a6e"
                        strokeWidth={0.025}
                    />
                </g>
            );
        case "TACKLE":
            return (
                <g className="action-dice__glyph" aria-hidden="true">
                    <ellipse cx={cx} cy={cy + 0.06} rx={0.18} ry={0.1} fill="#166534" stroke="#14532d" strokeWidth={0.02} />
                    <path
                        d={`M ${cx - 0.14} ${cy - 0.02} Q ${cx} ${y + 0.06} ${cx + 0.14} ${cy - 0.02}`}
                        fill="none"
                        stroke="#bbf7d0"
                        strokeWidth={0.05}
                        strokeLinecap="round"
                    />
                    <circle cx={cx - 0.08} cy={cy + 0.04} r={0.035} fill="#bbf7d0" />
                    <circle cx={cx + 0.08} cy={cy + 0.04} r={0.035} fill="#bbf7d0" />
                </g>
            );
        case "MISS":
            return (
                <g className="action-dice__glyph" aria-hidden="true">
                    <circle cx={cx} cy={cy} r={0.17} fill="none" stroke="#57534e" strokeWidth={0.05} />
                    <path
                        d={`M ${cx - 0.1} ${cy - 0.1} L ${cx + 0.1} ${cy + 0.1} M ${cx + 0.1} ${cy - 0.1} L ${cx - 0.1} ${cy + 0.1}`}
                        stroke="#57534e"
                        strokeWidth={0.055}
                        strokeLinecap="round"
                    />
                </g>
            );
        default: {
            const _exhaustive: never = face;
            return _exhaustive;
        }
    }
}

function RollingDieFace({ die, rolling, highlight }: { die: ShownDie; rolling: boolean; highlight: boolean }) {
    const [face, setFace] = useState(die.result);

    useEffect(() => {
        if (!rolling) {
            setFace(die.result);
            return;
        }
        let index = Math.floor(Math.random() * die.tumble.length);
        setFace(die.tumble[index] ?? die.result);
        const timer = window.setInterval(() => {
            index = (index + 1) % die.tumble.length;
            setFace(die.tumble[index] ?? die.result);
        }, 70);
        return () => window.clearInterval(timer);
    }, [rolling, die.result, die.tumble]);

    const blockFace = isBlockDieFace(face) ? face : null;
    const label = dieFaceLabel(face);
    const fontSize = label.length > 4 ? 0.16 : label.length > 2 ? 0.2 : 0.28;
    const cubeClass = rolling
        ? "action-dice__cube action-dice__cube--rolling"
        : highlight
          ? "action-dice__cube action-dice__cube--landed action-dice__cube--winner"
          : "action-dice__cube action-dice__cube--landed";

    return (
        <>
            <rect className={cubeClass} x={0} y={0} width={DIE_SIZE} height={DIE_SIZE} rx={0.1} />
            {blockFace != null ? (
                <BlockFaceGlyph face={blockFace} />
            ) : (
                <text
                    className="action-dice__face"
                    x={DIE_SIZE / 2}
                    y={DIE_SIZE / 2}
                    textAnchor="middle"
                    dominantBaseline="central"
                    fontSize={fontSize}
                    fontWeight={800}
                >
                    {label}
                </text>
            )}
        </>
    );
}

/**
 * Place the die cluster beside the action square (or between two squares for a contest).
 * Prefer a side offset over floating high above the token.
 */
function clusterOrigin(
    anchor: Coordinate,
    beside: Coordinate | null | undefined,
    clusterWidth: number,
): { originX: number; originY: number; shoutX: number; shoutY: number } {
    const a = squareCenter(anchor.x, anchor.y);
    if (beside != null && (beside.x !== anchor.x || beside.y !== anchor.y)) {
        const b = squareCenter(beside.x, beside.y);
        const midX = (a.cx + b.cx) / 2;
        const midY = (a.cy + b.cy) / 2;
        const dx = b.cx - a.cx;
        const dy = b.cy - a.cy;
        const len = Math.hypot(dx, dy) || 1;
        // Perpendicular nudge so dice don't sit on the contest line.
        const nx = -dy / len;
        const ny = dx / len;
        const originX = midX + nx * 0.55 - clusterWidth / 2;
        const originY = midY + ny * 0.55 - DIE_SIZE / 2;
        return {
            originX,
            originY,
            shoutX: originX + clusterWidth / 2,
            shoutY: originY - 0.18,
        };
    }

    // Sit just outside the token — prefer upper-right, but keep close.
    const originX = a.cx + 0.42;
    const originY = a.cy - 0.55;
    return {
        originX,
        originY,
        shoutX: originX + clusterWidth / 2,
        shoutY: originY - 0.18,
    };
}

/** Floating dice beside the action square — tumble, land, shout. No modal. */
export default function ActionDice({ theatre, beside, onSkip }: ActionDiceProps) {
    const beat = theatre.queue[theatre.index];
    if (beat == null || beat.dice.length === 0) return null;

    const rolling = theatre.phase === "rolling";
    const winnerFace = beat.outcome.replace(/!$/, "");
    const clusterWidth = beat.dice.length * DIE_SIZE + (beat.dice.length - 1) * DIE_GAP;
    const { originX, originY, shoutX, shoutY } = clusterOrigin(beat.anchor, beside, clusterWidth);

    return (
        <g
            className={`action-dice action-dice--${theatre.phase}`}
            data-testid="action-dice"
            data-purpose={beat.purpose}
            data-phase={theatre.phase}
            data-coord={`${beat.anchor.x},${beat.anchor.y}`}
            role="img"
            aria-label={rolling ? `Rolling ${beat.purpose}` : beat.outcome}
            onClick={(event) => {
                event.stopPropagation();
                onSkip?.();
            }}
            style={{ cursor: onSkip ? "pointer" : undefined }}
        >
            <title>{beat.detail ? `${beat.outcome} — ${beat.detail}` : beat.outcome}</title>

            {beat.dice.map((die, index) => (
                <g key={`${beat.purpose}-${index}`} transform={`translate(${originX + index * (DIE_SIZE + DIE_GAP)} ${originY})`}>
                    <RollingDieFace die={die} rolling={rolling} highlight={!rolling && die.result === winnerFace} />
                </g>
            ))}

            {rolling ? null : (
                <text
                    className="action-dice__shout"
                    data-testid="dice-theatre-outcome"
                    x={shoutX}
                    y={shoutY}
                    textAnchor="middle"
                    dominantBaseline="auto"
                    fontSize={0.42}
                    fontWeight={900}
                >
                    {beat.outcome}
                </text>
            )}
        </g>
    );
}
