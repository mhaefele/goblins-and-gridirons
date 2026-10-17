import type { Coordinate, PlayerCardDto, PlayerId, PlayerPieceDto } from "../api/client";
import { assetRegistry, tokenKeyFromCard } from "../assets/registry";
import { useMatchStore } from "../state/store";
import { usePathMotion } from "./usePathMotion";

const TEAM_COLORS = {
    HOME: "var(--team-home-accent)",
    AWAY: "var(--team-away-accent)",
} as const;

const TOKEN_RADIUS = 0.48;
const TOKEN_SIZE = TOKEN_RADIUS * 2;

type TokenProps = {
    piece: PlayerPieceDto;
    card?: PlayerCardDto | undefined;
    selected?: boolean;
    /** Glow when this coach's token can act and nothing is selected yet. */
    eligible?: boolean;
    /** Touchdown hero pulse — enlarge/shrink on the scoring square. */
    celebrating?: boolean;
    /** Flying off the pitch into the dugout (injury / post-TD). */
    exiting?: boolean;
    onSelect?: ((playerId: PlayerId) => void) | undefined;
    onHover?: ((playerId: PlayerId | null) => void) | undefined;
};

function positionInitial(card: PlayerCardDto | undefined, playerId: PlayerId): string {
    if (card?.positionLabel) {
        return card.positionLabel.charAt(0).toUpperCase();
    }
    return playerId.charAt(0).toUpperCase();
}

function tokenLabel(piece: PlayerPieceDto, card: PlayerCardDto | undefined): string {
    const position = card?.positionLabel ?? piece.id;
    const status = piece.status === "PRONE" ? "prone" : piece.status === "RESERVES" ? "reserve" : "standing";
    const ball = piece.holdsBall ? ", holding the ball" : "";
    const at = piece.at ? ` at (${piece.at.x}, ${piece.at.y})` : " (dugout)";
    const stats =
        card != null
            ? [
                  card.move != null ? `Move ${card.move}` : "Move D6",
                  `Block ${card.blockDiceCount}D`,
                  card.armourMinimumRoll != null ? `AV ${card.armourMinimumRoll}+` : null,
                  card.throwMinimumRoll != null ? `Throw ${card.throwMinimumRoll}+` : null,
              ]
                  .filter(Boolean)
                  .join(", ")
            : "";
    const ability = card?.ability != null ? ` · ${card.ability.key}` : "";
    return `${position} (${piece.id}) · ${piece.team}${at}, ${status}${ball}${stats ? ` · ${stats}` : ""}${ability}`;
}

function tokenTooltip(piece: PlayerPieceDto, card: PlayerCardDto | undefined, selected: boolean, eligible: boolean): string {
    const base = tokenLabel(piece, card);
    if (selected) return `${base}. Selected — click a highlighted square to act.`;
    if (eligible) return `${base}. Can act this turn — click to select.`;
    if (piece.holdsBall) return `${base}. Ball carrier.`;
    return base;
}

function tokenImageUrl(piece: PlayerPieceDto, card: PlayerCardDto | undefined): string | null {
    if (card == null) return null;
    return assetRegistry.token(tokenKeyFromCard(card, piece));
}

type TokenGraphicProps = {
    cx: number;
    cy: number;
    piece: PlayerPieceDto;
    card?: PlayerCardDto | undefined;
    selected: boolean;
    eligible: boolean;
    clipId: string;
};

function TokenGraphic({ cx, cy, piece, card, selected, eligible, clipId }: TokenGraphicProps) {
    const imageUrl = tokenImageUrl(piece, card);
    const teamColor = TEAM_COLORS[piece.team];
    const initial = positionInitial(card, piece.id);
    const showRing = selected || eligible || piece.holdsBall;
    const ring = selected ? "#fbbf24" : piece.holdsBall ? "#facc15" : "#4ade80";
    const ringWidth = selected || piece.holdsBall ? 0.06 : showRing ? 0.055 : 0.03;

    if (imageUrl != null) {
        return (
            <>
                <defs>
                    <clipPath id={clipId}>
                        <circle cx={cx} cy={cy} r={TOKEN_RADIUS} />
                    </clipPath>
                </defs>
                <circle cx={cx} cy={cy} r={TOKEN_RADIUS} fill="#0c0a09" pointerEvents="none" />
                <image
                    href={imageUrl}
                    x={cx - TOKEN_RADIUS}
                    y={cy - TOKEN_RADIUS}
                    width={TOKEN_SIZE}
                    height={TOKEN_SIZE}
                    preserveAspectRatio="xMidYMid slice"
                    clipPath={`url(#${clipId})`}
                    aria-hidden="true"
                    pointerEvents="none"
                />
                <circle
                    cx={cx}
                    cy={cy}
                    r={TOKEN_RADIUS}
                    fill="none"
                    stroke={showRing ? ring : piece.team === "HOME" ? "#93c5fd" : "#fca5a5"}
                    strokeWidth={ringWidth}
                    pointerEvents="none"
                />
            </>
        );
    }

    return (
        <>
            <circle
                cx={cx}
                cy={cy}
                r={TOKEN_RADIUS}
                fill={teamColor}
                stroke={showRing ? ring : "#0f172a"}
                strokeWidth={ringWidth}
                pointerEvents="none"
            />
            <text x={cx} y={cy} textAnchor="middle" dominantBaseline="central" fontSize={0.32} fontWeight="700" fill="#f8fafc" pointerEvents="none">
                {initial}
            </text>
        </>
    );
}

export default function Token({
    piece,
    card,
    selected = false,
    eligible = false,
    celebrating = false,
    exiting = false,
    onSelect,
    onHover,
}: TokenProps) {
    const pieceMotion = useMatchStore((state) =>
        state.pieceMotion?.playerId === piece.id ? state.pieceMotion : null,
    );
    const ballMotion = useMatchStore((state) => state.ballMotion);
    const reservesExit = useMatchStore((state) =>
        exiting && state.reservesExit?.playerId === piece.id ? state.reservesExit : null,
    );
    const motionPath = celebrating ? null : (reservesExit?.path ?? pieceMotion?.path ?? null);
    const motionMs = celebrating ? null : (reservesExit?.durationMs ?? pieceMotion?.durationMs ?? null);
    const { cx, cy } = usePathMotion(motionPath, motionMs, piece.at ?? null);
    if (piece.at == null) return null;
    const prone = !celebrating && !exiting && piece.status === "PRONE";
    const clipId = `token-clip-${piece.id}`;
    // Hide carrier badge while a throw arc leaves this square (flying ball owns the glyph).
    const hideCarrierBadge =
        ballMotion?.flight === "arc" &&
        piece.holdsBall &&
        piece.at != null &&
        ballMotion.path[0] != null &&
        ballMotion.path[0].x === piece.at.x &&
        ballMotion.path[0].y === piece.at.y;
    const tip = celebrating
        ? `${tokenLabel(piece, card)}. TOUCHDOWN!`
        : exiting
          ? `${tokenLabel(piece, card)}. Returning to reserves`
          : tokenTooltip(piece, card, selected, eligible);

    return (
        <g
            role="button"
            tabIndex={0}
            aria-label={tip}
            aria-pressed={selected}
            data-player-id={piece.id}
            data-eligible={eligible ? "true" : undefined}
            data-celebrating={celebrating ? "true" : undefined}
            data-exiting={exiting ? "true" : undefined}
            className={`token token--${piece.team.toLowerCase()}${selected ? " token--selected" : ""}${eligible ? " token--eligible" : ""}${prone ? " token--prone" : ""}${pieceMotion != null && !celebrating && !exiting ? " token--moving" : ""}${exiting ? " token--exiting" : ""}`}
            transform={
                celebrating
                    ? `translate(${cx} ${cy})`
                    : exiting
                      ? undefined
                      : prone
                        ? `translate(${cx} ${cy}) rotate(90) scale(1, 0.72) translate(${-cx} ${-cy})`
                        : undefined
            }
            opacity={prone ? 0.85 : exiting ? undefined : 1}
            onMouseDown={(event) => {
                // Avoid giant SVG focus rings on click; keyboard users still tab-focus.
                if (event.detail > 0) event.preventDefault();
            }}
            onClick={() => onSelect?.(piece.id)}
            onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    onSelect?.(piece.id);
                }
            }}
            onMouseEnter={() => onHover?.(piece.id)}
            onMouseLeave={() => onHover?.(null)}
            onFocus={() => onHover?.(piece.id)}
            onBlur={() => onHover?.(null)}
            style={{ cursor: onSelect ? "pointer" : undefined }}
        >
            <title>{tip}</title>
            {celebrating ? (
                <g className="token--celebrate">
                    <circle className="token-celebrate-ring" cx={0} cy={0} r={0.62} fill="none" />
                    <circle className="token-celebrate-ring token-celebrate-ring--lag" cx={0} cy={0} r={0.78} fill="none" />
                    <g transform={`translate(${-cx} ${-cy})`}>
                        <circle cx={cx} cy={cy} r={TOKEN_RADIUS} fill="transparent" />
                        <TokenGraphic cx={cx} cy={cy} piece={piece} card={card} selected={true} eligible={false} clipId={clipId} />
                        <BallCarrierBadge cx={cx} cy={cy} />
                    </g>
                </g>
            ) : (
                <>
                    <circle cx={cx} cy={cy} r={TOKEN_RADIUS} fill="transparent" />
                    <TokenGraphic cx={cx} cy={cy} piece={piece} card={card} selected={selected} eligible={eligible} clipId={clipId} />
                    {piece.holdsBall && !hideCarrierBadge ? <BallCarrierBadge cx={cx} cy={cy} /> : null}
                </>
            )}
        </g>
    );
}

type BallGlyphProps = {
    cx: number;
    cy: number;
    r: number;
};

/** Soft shadow under the carrier badge — football sits on the token rim. */
function BallCarrierBadge({ cx, cy }: { cx: number; cy: number }) {
    const bx = cx + 0.32;
    const by = cy + 0.3;
    return (
        <g aria-hidden="true" className="ball-carrier-badge" pointerEvents="none">
            <ellipse cx={bx} cy={by + 0.02} rx={0.26} ry={0.15} fill="#0c0a09" opacity={0.45} />
            <BallGlyph cx={bx} cy={by} r={0.24} />
        </g>
    );
}

/**
 * Inline football (not an external image) so pitch scaling stays reliable.
 * Readable pigskin at token scale — carrier, loose, and in-flight all share this.
 */
export function BallGlyph({ cx, cy, r }: BallGlyphProps) {
    const rx = r * 1.05;
    const ry = r * 0.72;
    const stroke = Math.max(0.03, r * 0.12);
    const lace = Math.max(0.02, r * 0.08);
    return (
        <g aria-hidden="true" className="ball-glyph">
            <ellipse cx={cx} cy={cy} rx={rx} ry={ry} fill="#b45309" stroke="#1c1917" strokeWidth={stroke} />
            <ellipse cx={cx - r * 0.2} cy={cy - r * 0.18} rx={r * 0.45} ry={r * 0.28} fill="#fbbf24" opacity={0.35} />
            <path
                d={`M ${cx - rx * 0.88} ${cy} Q ${cx} ${cy - ry * 0.55} ${cx + rx * 0.88} ${cy}`}
                fill="none"
                stroke="#1c1917"
                strokeWidth={Math.max(0.025, r * 0.1)}
                strokeLinecap="round"
            />
            <line
                x1={cx - r * 0.28}
                y1={cy}
                x2={cx + r * 0.28}
                y2={cy}
                stroke="#fef3c7"
                strokeWidth={Math.max(0.035, r * 0.14)}
                strokeLinecap="round"
            />
            <line x1={cx - r * 0.14} y1={cy - r * 0.22} x2={cx - r * 0.14} y2={cy + r * 0.22} stroke="#fef3c7" strokeWidth={lace} strokeLinecap="round" />
            <line x1={cx} y1={cy - r * 0.24} x2={cx} y2={cy + r * 0.24} stroke="#fef3c7" strokeWidth={lace} strokeLinecap="round" />
            <line x1={cx + r * 0.14} y1={cy - r * 0.22} x2={cx + r * 0.14} y2={cy + r * 0.22} stroke="#fef3c7" strokeWidth={lace} strokeLinecap="round" />
        </g>
    );
}

type LooseBallProps = {
    at: Coordinate;
};

export function LooseBall({ at }: LooseBallProps) {
    const ballMotion = useMatchStore((state) => state.ballMotion);
    const { cx, cy } = usePathMotion(null, null, at, "straight");
    // In-flight / parked bounce owns the glyph — don't double-draw a static loose ball.
    if (ballMotion != null) return null;
    const tip = `Loose ball at (${at.x}, ${at.y}). Legal moves onto this square show pick up or bounce.`;
    return (
        <g role="img" aria-label={tip} className="loose-ball" pointerEvents="none">
            <title>{tip}</title>
            <circle cx={cx} cy={cy} r={0.32} fill="#fde68a" opacity={0.4} />
            <BallGlyph cx={cx} cy={cy} r={0.3} />
        </g>
    );
}

/** Ball in flight — throws (arc) and bounce hops (straight), including size lerp off a carrier. */
export function InFlightBall() {
    const ballMotion = useMatchStore((state) => state.ballMotion);
    const origin = ballMotion?.path[0] ?? null;
    const { cx, cy, t } = usePathMotion(
        ballMotion?.path ?? null,
        ballMotion?.durationMs ?? null,
        origin,
        ballMotion?.flight ?? "straight",
    );
    if (ballMotion == null) return null;
    const radiusFrom = ballMotion.radiusFrom ?? (ballMotion.flight === "arc" ? 0.28 : 0.3);
    const radiusTo = ballMotion.radiusTo ?? radiusFrom;
    const r = radiusFrom + (radiusTo - radiusFrom) * t;
    const flightClass =
        ballMotion.flight === "arc" ? " in-flight-ball--arc" : ballMotion.flight === "bounce" ? " in-flight-ball--bounce" : "";
    return (
        <g
            role="img"
            aria-label={ballMotion.flight === "arc" ? "Ball in flight" : ballMotion.flight === "bounce" ? "Ball bouncing" : "Ball moving"}
            className={`in-flight-ball${flightClass}`}
            data-testid="in-flight-ball"
            data-flight={ballMotion.flight}
            pointerEvents="none"
        >
            <title>{ballMotion.flight === "arc" ? "Ball in flight" : ballMotion.flight === "bounce" ? "Ball bouncing" : "Ball moving"}</title>
            <circle cx={cx} cy={cy} r={r + 0.04} fill="#0c0a09" opacity={0.3} />
            <BallGlyph cx={cx} cy={cy} r={r} />
        </g>
    );
}

type DugoutTokenProps = {
    piece: PlayerPieceDto;
    card?: PlayerCardDto | undefined;
    x: number;
    y: number;
    selected?: boolean;
    eligible?: boolean;
    onSelect?: ((playerId: PlayerId) => void) | undefined;
    onHover?: ((playerId: PlayerId | null) => void) | undefined;
};

export function DugoutToken({ piece, card, x, y, selected = false, eligible = false, onSelect, onHover }: DugoutTokenProps) {
    const tip = tokenTooltip(piece, card, selected, eligible);
    return (
        <g
            role="button"
            tabIndex={0}
            aria-label={tip}
            aria-pressed={selected}
            data-player-id={piece.id}
            data-eligible={eligible ? "true" : undefined}
            className={`dugout-token${selected ? " dugout-token--selected" : ""}${eligible ? " dugout-token--eligible" : ""}`}
            onMouseDown={(event) => {
                if (event.detail > 0) event.preventDefault();
            }}
            onClick={() => onSelect?.(piece.id)}
            onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") {
                    event.preventDefault();
                    onSelect?.(piece.id);
                }
            }}
            onMouseEnter={() => onHover?.(piece.id)}
            onMouseLeave={() => onHover?.(null)}
            onFocus={() => onHover?.(piece.id)}
            onBlur={() => onHover?.(null)}
            style={{ cursor: onSelect ? "pointer" : undefined }}
        >
            <title>{tip}</title>
            {/* Hit target — TokenGraphic is pointer-events:none, same as pitch tokens. */}
            <circle cx={x} cy={y} r={TOKEN_RADIUS} fill="transparent" />
            <TokenGraphic
                cx={x}
                cy={y}
                piece={piece}
                card={card}
                selected={selected}
                eligible={eligible}
                clipId={`dugout-clip-${piece.id}`}
            />
        </g>
    );
}
