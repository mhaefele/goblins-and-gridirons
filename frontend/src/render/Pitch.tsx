import type { CSSProperties, ReactNode } from "react";
import { useRef } from "react";
import type { Coordinate, PitchLayoutDto } from "../api/client";
import type { PitchTileSet } from "../assets/registry";
import { formatActionType, highlightColors, THROW_DIFFICULTY_COLORS, type SquareHighlight, type ThrowDifficulty } from "./actionVisuals";
import BlockedObstacle from "./BlockedObstacle";
import { blockedClusters } from "./blockedClusters";
import { blockedPerimeterSegments } from "./blockedOutlines";
import { boardImagePlacement, type BoardImageContentRect } from "./boardImageLayout";
import {
    clientPointToSquare,
    coordKey,
    gridHeight,
    gridWidth,
    isOutOfBounds,
    isPlayableSquare,
    squareLabel,
} from "./pitchLayout";
import { throwArcPathD } from "./usePathMotion";

export type PitchSquareHighlight = SquareHighlight;

export type ThrowArcPreview = {
    from: Coordinate;
    to: Coordinate;
    /** Obstructed lanes use a denser dash so the risk reads before click. */
    obstructed: boolean;
    /** Check difficulty tint (Long / obstructed → hard). */
    difficulty: ThrowDifficulty;
};

type PitchProps = {
    layout: PitchLayoutDto;
    /** Color-coded legal targets for the selected player only. */
    highlights?: Map<string, PitchSquareHighlight>;
    /** Soft preview of the path for the hovered move target. */
    pathPreview?: Coordinate[];
    /** Hover preview arc for throw targets. */
    throwArcPreview?: ThrowArcPreview | null;
    flashedSquares?: Coordinate[];
    hoverHint?: string | null;
    /** Square currently under the pointer — drawn as a bright target outline. */
    hoverCoordinate?: Coordinate | null;
    /** Native tooltips / hover-strip copy keyed by `x,y`. */
    squareTitles?: Map<string, string>;
    /**
     * Occupied squares skip mouse hit-targets so tokens on those cells still receive
     * hover/click. Empty highlighted squares keep targets for cursor + keyboard.
     */
    occupiedKeys?: Set<string>;
    /** Per-cell textures — used when no full-board underlay is available. */
    tiles?: PitchTileSet | null;
    /**
     * Painted board PNG underlay. When set, skips per-cell tiles and procedural obstacles.
     * Pass `boardImageContent` (measured playable floor) so cells fill floor-to-floor;
     * without it the PNG stretches to the full SVG viewBox.
     */
    boardImageUrl?: string | null;
    /** Normalized playable-floor rect inside the board PNG (frame outside is OOB). */
    boardImageContent?: BoardImageContentRect | null;
    onSquareClick?: ((coordinate: Coordinate) => void) | undefined;
    onSquareHover?: ((coordinate: Coordinate | null) => void) | undefined;
    children?: ReactNode;
    className?: string;
    style?: CSSProperties;
};

function isDungeonPitch(layout: PitchLayoutDto): boolean {
    return layout.pitchDefinitionId === "default";
}

/** Wooden hatch over a dark pit — opens when the ball emerges (`opening`). */
function TrapdoorDecor({ x, y, opening }: { x: number; y: number; opening: boolean }) {
    const cx = x + 0.5;
    const cy = y + 0.5;
    return (
        <g
            aria-hidden="true"
            pointerEvents="none"
            className={`trapdoor-decor${opening ? " trapdoor-decor--opening" : ""}`}
        >
            {/* Pit / void */}
            <rect x={x + 0.1} y={y + 0.1} width={0.8} height={0.8} rx={0.05} fill="#0c0a09" />
            <ellipse cx={cx} cy={cy} rx={0.28} ry={0.22} fill="#1c1917" />
            <ellipse className="trapdoor-decor__glow" cx={cx} cy={cy} rx={0.18} ry={0.14} fill="#fbbf24" />
            {/* Frame / stone lip */}
            <rect
                x={x + 0.08}
                y={y + 0.08}
                width={0.84}
                height={0.84}
                rx={0.06}
                fill="none"
                stroke="#78716c"
                strokeWidth={0.06}
            />
            <rect
                x={x + 0.12}
                y={y + 0.12}
                width={0.76}
                height={0.76}
                rx={0.04}
                fill="none"
                stroke="#44403c"
                strokeWidth={0.03}
            />
            {/* Left door — hinge on outer edge */}
            <g className="trapdoor-decor__door trapdoor-decor__door--left">
                <rect x={x + 0.14} y={y + 0.16} width={0.34} height={0.68} rx={0.03} fill="#57534e" stroke="#292524" strokeWidth={0.025} />
                <rect x={x + 0.16} y={y + 0.18} width={0.3} height={0.64} rx={0.02} fill="#78716c" />
                <line x1={x + 0.16} y1={y + 0.34} x2={x + 0.46} y2={y + 0.34} stroke="#44403c" strokeWidth={0.03} />
                <line x1={x + 0.16} y1={y + 0.5} x2={x + 0.46} y2={y + 0.5} stroke="#44403c" strokeWidth={0.03} />
                <line x1={x + 0.16} y1={y + 0.66} x2={x + 0.46} y2={y + 0.66} stroke="#44403c" strokeWidth={0.03} />
                <rect x={x + 0.14} y={y + 0.16} width={0.06} height={0.68} fill="#a8a29e" opacity={0.35} />
            </g>
            {/* Right door */}
            <g className="trapdoor-decor__door trapdoor-decor__door--right">
                <rect x={x + 0.52} y={y + 0.16} width={0.34} height={0.68} rx={0.03} fill="#57534e" stroke="#292524" strokeWidth={0.025} />
                <rect x={x + 0.54} y={y + 0.18} width={0.3} height={0.64} rx={0.02} fill="#6b6560" />
                <line x1={x + 0.54} y1={y + 0.34} x2={x + 0.84} y2={y + 0.34} stroke="#44403c" strokeWidth={0.03} />
                <line x1={x + 0.54} y1={y + 0.5} x2={x + 0.84} y2={y + 0.5} stroke="#44403c" strokeWidth={0.03} />
                <line x1={x + 0.54} y1={y + 0.66} x2={x + 0.84} y2={y + 0.66} stroke="#44403c" strokeWidth={0.03} />
                <rect x={x + 0.8} y={y + 0.16} width={0.06} height={0.68} fill="#1c1917" opacity={0.35} />
            </g>
            {/* Iron ring pull */}
            <g className="trapdoor-decor__ring">
                <circle cx={cx} cy={cy} r={0.1} fill="none" stroke="#d6d3d1" strokeWidth={0.04} />
                <circle cx={cx} cy={cy} r={0.04} fill="#a8a29e" stroke="#292524" strokeWidth={0.02} />
            </g>
        </g>
    );
}

function tileHref(tiles: PitchTileSet, homeEz: boolean, awayEz: boolean): string {
    if (homeEz) return tiles.homeEndZone;
    if (awayEz) return tiles.awayEndZone;
    return tiles.floor;
}

export default function Pitch({
    layout,
    highlights = new Map(),
    pathPreview = [],
    throwArcPreview = null,
    flashedSquares = [],
    hoverHint = null,
    hoverCoordinate = null,
    squareTitles = new Map(),
    occupiedKeys = new Set(),
    tiles = null,
    boardImageUrl = null,
    boardImageContent = null,
    onSquareClick,
    onSquareHover,
    children,
    className,
    style,
}: PitchProps) {
    const svgRef = useRef<SVGSVGElement | null>(null);
    const width = gridWidth(layout);
    const height = gridHeight(layout);
    const blocked = new Set(layout.blockedSquares.map(coordKey));
    const trapdoors = new Set(layout.trapdoors.map(coordKey));
    const flashes = new Set(flashedSquares.map(coordKey));
    const pathKeys = new Set(pathPreview.map(coordKey));
    const dungeon = isDungeonPitch(layout);
    const hasBoard = boardImageUrl != null && boardImageUrl !== "";
    const boardPlacement =
        hasBoard && boardImageContent != null
            ? boardImagePlacement(layout.playWidth, layout.playHeight, boardImageContent)
            : hasBoard
              ? { x: 0, y: 0, width, height }
              : null;
    const squares: ReactNode[] = [];
    const hitTargets: ReactNode[] = [];

    for (let y = 0; y < height; y++) {
        for (let x = 0; x < width; x++) {
            const key = coordKey({ x, y });
            const isBlocked = blocked.has(key);
            const isTrapdoor = trapdoors.has(key);
            const highlight = highlights.get(key);
            const isFlashed = flashes.has(key);
            const playable = isPlayableSquare(layout, x, y);
            const oob = isOutOfBounds(layout, x, y);
            const homeEz = y === layout.homeEndZoneRow && playable;
            const awayEz = y === layout.awayEndZoneRow && playable;

            let ariaLabel = squareLabel(x, y);
            if (isBlocked) ariaLabel += dungeon ? ", pillar" : ", barricade";
            if (isTrapdoor) ariaLabel += ", trapdoor";
            if (highlight) ariaLabel += `, ${formatActionType(highlight.primaryType)}`;
            if (isFlashed) ariaLabel += ", recent outcome";
            if (homeEz) ariaLabel += ", home end zone";
            if (awayEz) ariaLabel += ", away end zone";

            if (oob) {
                // Painted board includes the wood frame in the gutter cells — don't cover it.
                if (!hasBoard) {
                    squares.push(
                        <rect key={key} x={x} y={y} width={1} height={1} fill="#0c0a09" aria-hidden="true" pointerEvents="none" />,
                    );
                }
            } else if (hasBoard) {
                // Painted board owns the cell; keep a light seam so the unit grid still reads.
                if (!isBlocked) {
                    squares.push(
                        <rect
                            key={`${key}-edge`}
                            x={x}
                            y={y}
                            width={1}
                            height={1}
                            fill="none"
                            stroke="rgba(12,10,9,0.28)"
                            strokeWidth={0.02}
                            aria-hidden="true"
                            pointerEvents="none"
                        />,
                    );
                }
            } else if (!hasBoard && tiles != null) {
                squares.push(
                    <image
                        key={key}
                        href={tileHref(tiles, homeEz, awayEz)}
                        x={x}
                        y={y}
                        width={1}
                        height={1}
                        preserveAspectRatio="none"
                        aria-hidden="true"
                        pointerEvents="none"
                    />,
                );
                // No per-cell hairline under pillars — the cluster art + perimeter own that space.
                if (!isBlocked) {
                    squares.push(
                        <rect
                            key={`${key}-edge`}
                            x={x}
                            y={y}
                            width={1}
                            height={1}
                            fill="none"
                            stroke="rgba(12,10,9,0.35)"
                            strokeWidth={0.02}
                            aria-hidden="true"
                            pointerEvents="none"
                        />,
                    );
                }
            } else {
                const fill = (() => {
                    if (isBlocked) return dungeon ? "#44403c" : "#78350f";
                    if (isTrapdoor) return "#292524";
                    if (homeEz) return "#1e3a8a";
                    if (awayEz) return "#991b1b";
                    const checker = (x + y) % 2 === 0;
                    if (dungeon) return checker ? "#57534e" : "#44403c";
                    return checker ? "#4d7c0f" : "#3f6212";
                })();
                squares.push(
                    <rect
                        key={key}
                        x={x}
                        y={y}
                        width={1}
                        height={1}
                        fill={fill}
                        stroke={isBlocked ? "none" : "rgba(12,10,9,0.65)"}
                        strokeWidth={0.035}
                        aria-hidden="true"
                        pointerEvents="none"
                    />,
                );
            }

            if (pathKeys.has(key) && playable) {
                squares.push(
                    <rect
                        key={`${key}-path`}
                        className="pitch-path-preview"
                        x={x + 0.08}
                        y={y + 0.08}
                        width={0.84}
                        height={0.84}
                        rx={0.12}
                        aria-hidden="true"
                        pointerEvents="none"
                    />,
                );
            }

            if (highlight) {
                const ballCue = highlight.willPickUpBall || highlight.willBounceBall;
                const colors = highlightColors(highlight);
                squares.push(
                    <rect
                        key={`${key}-hl`}
                        x={x}
                        y={y}
                        width={1}
                        height={1}
                        fill={colors.fill}
                        fillOpacity={0.5}
                        stroke={
                            highlight.requiresBonus
                                ? "#f59e0b"
                                : highlight.offersChallengeClaim
                                  ? "#ef4444"
                                  : highlight.willPickUpBall
                                    ? "#facc15"
                                    : highlight.willBounceBall
                                      ? "#e2e8f0"
                                      : highlight.offersAvailableBonus
                                        ? "#d4a017"
                                        : colors.stroke
                        }
                        strokeWidth={
                            highlight.requiresBonus ||
                            highlight.offersChallengeClaim ||
                            highlight.offersAvailableBonus ||
                            ballCue
                                ? 0.11
                                : 0.08
                        }
                        strokeDasharray={
                            highlight.requiresBonus
                                ? "0.14 0.1"
                                : highlight.willBounceBall && !highlight.willPickUpBall
                                  ? "0.12 0.08"
                                  : undefined
                        }
                        strokeOpacity={
                            !highlight.requiresBonus &&
                            (highlight.offersChallengeClaim ||
                                highlight.offersAvailableBonus ||
                                ballCue)
                                ? 0.9
                                : 1
                        }
                        className={isFlashed ? "pitch-square--flash" : undefined}
                        aria-hidden="true"
                        pointerEvents="none"
                    />,
                );
                if (ballCue) {
                    const cx = x + 0.78;
                    const cy = y + 0.22;
                    squares.push(
                        <g
                            key={`${key}-ball-cue`}
                            className={
                                highlight.willPickUpBall
                                    ? "pitch-ball-cue pitch-ball-cue--pickup"
                                    : "pitch-ball-cue pitch-ball-cue--bounce"
                            }
                            aria-hidden="true"
                            pointerEvents="none"
                        >
                            <circle
                                cx={cx}
                                cy={cy}
                                r={0.16}
                                fill={highlight.willPickUpBall ? "#fde68a" : "#cbd5e1"}
                                stroke={highlight.willPickUpBall ? "#ca8a04" : "#64748b"}
                                strokeWidth={0.04}
                            />
                            <text
                                x={cx}
                                y={cy + 0.045}
                                textAnchor="middle"
                                fontSize={0.18}
                                fontWeight={700}
                                fill={highlight.willPickUpBall ? "#713f12" : "#334155"}
                            >
                                {highlight.willPickUpBall ? "↑" : "↺"}
                            </text>
                        </g>,
                    );
                }
            } else if (isFlashed && playable) {
                squares.push(
                    <rect
                        key={`${key}-flash`}
                        x={x}
                        y={y}
                        width={1}
                        height={1}
                        fill="none"
                        stroke="#fde68a"
                        strokeWidth={0.08}
                        className="pitch-square--flash"
                        aria-hidden="true"
                        pointerEvents="none"
                    />,
                );
            }

            // Keep hatch visible during flash so the open animation plays with ball emerge.
            if (isTrapdoor && !highlight) {
                squares.push(<TrapdoorDecor key={`${key}-trapdoor`} x={x} y={y} opening={isFlashed} />);
            }

            const titleText = squareTitles.get(key) ?? ariaLabel;

            // Occupied cells: leave the square open so the token receives hover/select.
            // Empty cells still get a hit-target (cursor + keyboard). SVG onClick covers the rest.
            if (highlight && onSquareClick && !occupiedKeys.has(key)) {
                // No stroke — SVG strokeWidth defaults to 1 user unit (a full cell), so a
                // "transparent" stroke would extend into neighbors and steal their clicks.
                hitTargets.push(
                    <g key={`${key}-hit`}>
                        <title>{titleText}</title>
                        <rect
                            className="pitch-hit-target"
                            data-coord={key}
                            data-testid={`pitch-hit-${key}`}
                            x={x}
                            y={y}
                            width={1}
                            height={1}
                            fill="transparent"
                            stroke="none"
                            aria-label={titleText}
                            role="button"
                            tabIndex={0}
                            style={{ cursor: "pointer" }}
                            onMouseDown={(event) => {
                                if (event.detail > 0) event.preventDefault();
                            }}
                            onClick={(event) => {
                                event.stopPropagation();
                                onSquareClick({ x, y });
                            }}
                            onKeyDown={(event) => {
                                if (event.key === "Enter" || event.key === " ") {
                                    event.preventDefault();
                                    onSquareClick({ x, y });
                                }
                            }}
                        />
                    </g>,
                );
            }
        }
    }

    const obstacles = hasBoard ? [] : blockedClusters(layout.blockedSquares);
    const perimeter = blockedPerimeterSegments(layout.blockedSquares);
    const hoverKey = hoverCoordinate != null ? coordKey(hoverCoordinate) : null;
    const classNames = ["pitch-svg", className].filter(Boolean).join(" ");

    function squareFromPointer(clientX: number, clientY: number): Coordinate | null {
        const svg = svgRef.current;
        if (svg == null) return null;
        const coord = clientPointToSquare(svg, clientX, clientY);
        if (coord == null) return null;
        if (coord.x < 0 || coord.y < 0 || coord.x >= width || coord.y >= height) return null;
        return coord;
    }

    return (
        <div className="pitch-frame">
            <svg
                ref={svgRef}
                className={classNames}
                viewBox={`0 0 ${width} ${height}`}
                preserveAspectRatio="xMidYMid meet"
                aria-label={`Pitch ${layout.pitchDefinitionId}`}
                style={style}
                data-testid="pitch-svg"
                onPointerMove={(event) => {
                    if (!onSquareHover) return;
                    onSquareHover(squareFromPointer(event.clientX, event.clientY));
                }}
                onPointerLeave={() => onSquareHover?.(null)}
                onClick={(event) => {
                    if (!onSquareClick) return;
                    const target = event.target as Element | null;
                    if (target?.closest?.(".token, .dugout-token, .pitch-hit-target")) return;
                    const coord = squareFromPointer(event.clientX, event.clientY);
                    if (coord == null) return;
                    onSquareClick(coord);
                }}
            >
                {boardPlacement != null ? (
                    <>
                        {/* Cover gutters if the painted frame doesn't reach the SVG edge. */}
                        <rect
                            x={0}
                            y={0}
                            width={width}
                            height={height}
                            fill="#0c0a09"
                            aria-hidden="true"
                            pointerEvents="none"
                        />
                        <image
                            data-testid="pitch-board-image"
                            href={boardImageUrl ?? undefined}
                            x={boardPlacement.x}
                            y={boardPlacement.y}
                            width={boardPlacement.width}
                            height={boardPlacement.height}
                            preserveAspectRatio="none"
                            aria-hidden="true"
                            pointerEvents="none"
                        />
                    </>
                ) : null}
                {squares}
                {obstacles.map((cluster) => (
                    <BlockedObstacle
                        key={`blocked-${cluster.origin.x},${cluster.origin.y}-${cluster.width}x${cluster.height}`}
                        cluster={cluster}
                        dungeon={dungeon}
                    />
                ))}
                {perimeter.map((segment, index) => (
                    <line
                        key={`blocked-edge-${index}`}
                        className="pitch-blocked-perimeter"
                        x1={segment.x1}
                        y1={segment.y1}
                        x2={segment.x2}
                        y2={segment.y2}
                        stroke="rgba(220, 38, 38, 0.72)"
                        strokeWidth={0.05}
                        strokeLinecap="square"
                        strokeDasharray="0.14 0.1"
                        aria-hidden="true"
                        pointerEvents="none"
                    />
                ))}
                {hoverCoordinate != null &&
                hoverCoordinate.x >= 0 &&
                hoverCoordinate.y >= 0 &&
                hoverCoordinate.x < width &&
                hoverCoordinate.y < height ? (
                    <rect
                        className="pitch-hover-target"
                        data-testid="pitch-hover-target"
                        data-coord={hoverKey ?? undefined}
                        x={hoverCoordinate.x}
                        y={hoverCoordinate.y}
                        width={1}
                        height={1}
                        fill="rgba(250, 250, 249, 0.18)"
                        stroke="#fbbf24"
                        strokeWidth={0.12}
                        aria-hidden="true"
                        pointerEvents="none"
                    />
                ) : null}
                {throwArcPreview != null ? (
                    <path
                        className={`pitch-throw-arc${throwArcPreview.obstructed ? " pitch-throw-arc--obstructed" : ""}`}
                        data-testid="pitch-throw-arc"
                        d={throwArcPathD(throwArcPreview.from, throwArcPreview.to)}
                        fill="none"
                        stroke={THROW_DIFFICULTY_COLORS[throwArcPreview.difficulty].stroke}
                        strokeWidth={0.09}
                        strokeDasharray={throwArcPreview.obstructed ? "0.12 0.14" : "0.22 0.14"}
                        strokeLinecap="round"
                        aria-hidden="true"
                        pointerEvents="none"
                    />
                ) : null}
                {/* Hit targets under tokens so hover/select still works on occupied highlighted squares. */}
                {hitTargets}
                {children}
            </svg>
            <p className="pitch-hover-hint" role="status" data-testid="pitch-hover-hint">
                {hoverHint ?? "Hover a square or player for details"}
            </p>
        </div>
    );
}
