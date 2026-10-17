import type { ReactNode } from "react";
import type { BlockedCluster } from "./blockedClusters";

type BlockedObstacleProps = {
    cluster: BlockedCluster;
    dungeon: boolean;
};

/** One obstacle graphic spanning a blocked cluster — edge-to-edge under the red outline. */
export default function BlockedObstacle({ cluster, dungeon }: BlockedObstacleProps) {
    const { x, y } = cluster.origin;
    const { width, height } = cluster;
    const uid = `obs-${x}-${y}`;
    if (dungeon) {
        return <DungeonPillar x={x} y={y} width={width} height={height} uid={uid} />;
    }
    return <BarricadeHeap x={x} y={y} width={width} height={height} uid={uid} />;
}

function DungeonPillar({
    x,
    y,
    width,
    height,
    uid,
}: {
    x: number;
    y: number;
    width: number;
    height: number;
    uid: string;
}) {
    const inset = 0.05;
    const left = x + inset;
    const top = y + inset;
    const w = width - inset * 2;
    const h = height - inset * 2;

    if (width >= 2 && height >= 2) {
        return <StoneRuin left={left} top={top} w={w} h={h} uid={uid} />;
    }

    return (
        <g className="blocked-obstacle blocked-obstacle--pillar" aria-hidden="true" pointerEvents="none">
            <rect x={left} y={top} width={w} height={h} rx={0.06} fill="#1c1917" />
            <rect x={left + 0.06} y={top + 0.06} width={w - 0.12} height={h - 0.12} rx={0.04} fill="#57534e" />
            <rect x={left + 0.06} y={top + 0.06} width={w - 0.12} height={h * 0.18} fill="#a8a29e" />
            <line
                x1={left + w * 0.5}
                y1={top + 0.06 + h * 0.18}
                x2={left + w * 0.5}
                y2={top + h - 0.06}
                stroke="#292524"
                strokeWidth={0.03}
            />
            <rect x={left} y={top} width={w} height={h} rx={0.06} fill="none" stroke="#d6d3d1" strokeWidth={0.03} opacity={0.65} />
        </g>
    );
}

/** 2×2 dungeon: isometric spiky stone pillar — reads as solid volume, not flat brick tile. */
function StoneRuin({ left, top, w, h, uid }: { left: number; top: number; w: number; h: number; uid: string }) {
    const cx = left + w / 2;
    const cy = top + h / 2;
    const shaftId = `${uid}-shaft`;
    const capId = `${uid}-cap`;
    const tipId = `${uid}-tip`;

    // Shaft trapezoid (perspective: wider at base)
    const baseL = left + 0.18;
    const baseR = left + w - 0.18;
    const baseY = top + h - 0.16;
    const topL = left + 0.32;
    const topR = left + w - 0.32;
    const topY = top + 0.42;
    const shaftD = `M${baseL} ${baseY} L${baseR} ${baseY} L${topR} ${topY} L${topL} ${topY} Z`;

    // Cap octagon-ish plate
    const capY = topY - 0.08;
    const spikes: ReactNode[] = [];
    const spikeCount = 8;
    for (let i = 0; i < spikeCount; i++) {
        const angle = (Math.PI * 2 * i) / spikeCount - Math.PI / 2;
        const midR = 0.48;
        const outerR = 0.58;
        const ox = cx + Math.cos(angle) * outerR * Math.min(w, h) * 0.52;
        const oy = cy - 0.28 + Math.sin(angle) * outerR * Math.min(w, h) * 0.45;
        const a1 = angle - 0.18;
        const a2 = angle + 0.18;
        const lx = cx + Math.cos(a1) * midR * Math.min(w, h) * 0.45;
        const ly = cy - 0.28 + Math.sin(a1) * midR * Math.min(w, h) * 0.38;
        const rx = cx + Math.cos(a2) * midR * Math.min(w, h) * 0.45;
        const ry = cy - 0.28 + Math.sin(a2) * midR * Math.min(w, h) * 0.38;
        spikes.push(
            <path
                key={`spike-${i}`}
                d={`M${lx} ${ly} L${ox} ${oy} L${rx} ${ry} Z`}
                fill={i % 2 === 0 ? "#a8a29e" : "#78716c"}
                stroke="#1c1917"
                strokeWidth={0.02}
                strokeLinejoin="round"
            />,
        );
    }

    return (
        <g className="blocked-obstacle blocked-obstacle--pillar-2x2" aria-hidden="true" pointerEvents="none">
            <defs>
                <linearGradient id={shaftId} x1="0" y1="0" x2="1" y2="0">
                    <stop offset="0%" stopColor="#1c1917" />
                    <stop offset="28%" stopColor="#57534e" />
                    <stop offset="55%" stopColor="#a8a29e" />
                    <stop offset="78%" stopColor="#44403c" />
                    <stop offset="100%" stopColor="#0c0a09" />
                </linearGradient>
                <linearGradient id={capId} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#e7e5e4" />
                    <stop offset="55%" stopColor="#a8a29e" />
                    <stop offset="100%" stopColor="#57534e" />
                </linearGradient>
                <radialGradient id={tipId} cx="40%" cy="35%" r="65%">
                    <stop offset="0%" stopColor="#fafaf9" />
                    <stop offset="100%" stopColor="#78716c" />
                </radialGradient>
            </defs>
            {/* Ground shadow */}
            <ellipse cx={cx} cy={top + h - 0.08} rx={w * 0.42} ry={0.12} fill="#0c0a09" opacity={0.55} />
            {/* Plinth */}
            <rect
                x={left + 0.12}
                y={top + h - 0.28}
                width={w - 0.24}
                height={0.18}
                rx={0.04}
                fill="#292524"
                stroke="#57534e"
                strokeWidth={0.025}
            />
            <rect x={left + 0.18} y={top + h - 0.26} width={w - 0.36} height={0.05} fill="#78716c" opacity={0.5} />
            {/* Shaft body */}
            <path d={shaftD} fill={`url(#${shaftId})`} stroke="#1c1917" strokeWidth={0.03} strokeLinejoin="round" />
            {/* Mortar bands */}
            <line
                x1={left + 0.28}
                y1={top + h * 0.58}
                x2={left + w - 0.28}
                y2={top + h * 0.58}
                stroke="#1c1917"
                strokeWidth={0.035}
                opacity={0.7}
            />
            <line
                x1={left + 0.3}
                y1={top + h * 0.72}
                x2={left + w - 0.3}
                y2={top + h * 0.72}
                stroke="#1c1917"
                strokeWidth={0.03}
                opacity={0.55}
            />
            {/* Cap plate */}
            <ellipse cx={cx} cy={capY} rx={w * 0.34} ry={0.14} fill={`url(#${capId})`} stroke="#292524" strokeWidth={0.03} />
            <ellipse cx={cx} cy={capY - 0.04} rx={w * 0.26} ry={0.09} fill="#d6d3d1" opacity={0.55} />
            {/* Spikes around crown */}
            {spikes}
            {/* Central tip */}
            <path
                d={`M${cx - 0.12} ${capY - 0.02} L${cx} ${top + 0.1} L${cx + 0.12} ${capY - 0.02} Z`}
                fill={`url(#${tipId})`}
                stroke="#1c1917"
                strokeWidth={0.025}
                strokeLinejoin="round"
            />
            <ellipse cx={cx - 0.02} cy={top + 0.22} rx={0.04} ry={0.06} fill="#fafaf9" opacity={0.45} />
            {/* Rim light on shaft */}
            <path
                d={`M${topL + 0.06} ${topY + 0.05} L${baseL + 0.08} ${baseY - 0.05}`}
                fill="none"
                stroke="#e7e5e4"
                strokeWidth={0.04}
                opacity={0.35}
                strokeLinecap="round"
            />
        </g>
    );
}

function BarricadeHeap({
    x,
    y,
    width,
    height,
    uid,
}: {
    x: number;
    y: number;
    width: number;
    height: number;
    uid: string;
}) {
    const inset = 0.06;
    const left = x + inset;
    const top = y + inset;
    const w = width - inset * 2;
    const h = height - inset * 2;

    if (width >= 2 && height >= 2) {
        return <CrateBarricade left={left} top={top} w={w} h={h} uid={uid} />;
    }

    if (width >= 2) {
        return (
            <g className="blocked-obstacle blocked-obstacle--barricade-2x1" aria-hidden="true" pointerEvents="none">
                <rect x={left} y={top} width={w} height={h} rx={0.06} fill="#451a03" />
                <rect
                    x={left + 0.06}
                    y={top + 0.12}
                    width={w - 0.12}
                    height={h - 0.24}
                    rx={0.04}
                    fill="#92400e"
                    stroke="#fde68a"
                    strokeWidth={0.035}
                />
                <rect x={left + 0.1} y={top + 0.18} width={w - 0.2} height={(h - 0.36) / 3} fill="#a16207" />
                <rect x={left + 0.1} y={top + 0.18 + (h - 0.36) / 3} width={w - 0.2} height={(h - 0.36) / 3} fill="#78350f" />
                <rect
                    x={left + 0.1}
                    y={top + 0.18 + (2 * (h - 0.36)) / 3}
                    width={w - 0.2}
                    height={(h - 0.36) / 3}
                    fill="#92400e"
                />
                <line
                    x1={left + w * 0.35}
                    y1={top + 0.18}
                    x2={left + w * 0.35}
                    y2={top + h - 0.18}
                    stroke="#451a03"
                    strokeWidth={0.04}
                />
                <line
                    x1={left + w * 0.65}
                    y1={top + 0.18}
                    x2={left + w * 0.65}
                    y2={top + h - 0.18}
                    stroke="#451a03"
                    strokeWidth={0.04}
                />
            </g>
        );
    }

    return (
        <g className="blocked-obstacle blocked-obstacle--barricade" aria-hidden="true" pointerEvents="none">
            <rect x={left} y={top} width={w} height={h} rx={0.05} fill="#451a03" />
            <rect
                x={left + 0.08}
                y={top + 0.16}
                width={w - 0.16}
                height={h - 0.28}
                rx={0.04}
                fill="#92400e"
                stroke="#fde68a"
                strokeWidth={0.035}
            />
            <line x1={left + 0.08} y1={top + h * 0.42} x2={left + w - 0.08} y2={top + h * 0.42} stroke="#78350f" strokeWidth={0.03} />
            <line x1={left + 0.08} y1={top + h * 0.6} x2={left + w - 0.08} y2={top + h * 0.6} stroke="#78350f" strokeWidth={0.03} />
            <path
                d={`M${left + w * 0.28} ${top + 0.16} L${left + w * 0.5} ${top + 0.02} L${left + w * 0.72} ${top + 0.16}`}
                fill="#a16207"
                stroke="#fef3c7"
                strokeWidth={0.025}
            />
        </g>
    );
}

/** 2×2 pitch: stacked crates, planks, barrel, rope — reads as a scrap barricade, not a zoomed board. */
function CrateBarricade({ left, top, w, h, uid }: { left: number; top: number; w: number; h: number; uid: string }) {
    const floorId = `${uid}-floor`;
    const crate = (cx: number, cy: number, cw: number, ch: number, fill: string, key: string) => (
        <g key={key}>
            <rect x={cx} y={cy} width={cw} height={ch} rx={0.04} fill={fill} stroke="#451a03" strokeWidth={0.025} />
            <line x1={cx + 0.06} y1={cy + ch * 0.33} x2={cx + cw - 0.06} y2={cy + ch * 0.33} stroke="#78350f" strokeWidth={0.02} />
            <line x1={cx + 0.06} y1={cy + ch * 0.66} x2={cx + cw - 0.06} y2={cy + ch * 0.66} stroke="#78350f" strokeWidth={0.02} />
            <line x1={cx + cw * 0.5} y1={cy + 0.05} x2={cx + cw * 0.5} y2={cy + ch - 0.05} stroke="#451a03" strokeWidth={0.022} />
            {/* Corner brackets */}
            <path d={`M${cx + 0.04} ${cy + 0.12} L${cx + 0.04} ${cy + 0.04} L${cx + 0.12} ${cy + 0.04}`} fill="none" stroke="#fbbf24" strokeWidth={0.02} />
            <path
                d={`M${cx + cw - 0.04} ${cy + 0.12} L${cx + cw - 0.04} ${cy + 0.04} L${cx + cw - 0.12} ${cy + 0.04}`}
                fill="none"
                stroke="#fbbf24"
                strokeWidth={0.02}
            />
        </g>
    );

    return (
        <g className="blocked-obstacle blocked-obstacle--barricade-2x2" aria-hidden="true" pointerEvents="none">
            <defs>
                <linearGradient id={floorId} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#78350f" />
                    <stop offset="100%" stopColor="#451a03" />
                </linearGradient>
            </defs>
            <rect x={left} y={top} width={w} height={h} rx={0.07} fill={`url(#${floorId})`} />
            {/* Back planks */}
            <rect x={left + 0.08} y={top + 0.1} width={w - 0.16} height={0.14} rx={0.02} fill="#a16207" stroke="#451a03" strokeWidth={0.02} />
            <rect x={left + 0.1} y={top + 0.26} width={w - 0.2} height={0.1} rx={0.015} fill="#92400e" stroke="#451a03" strokeWidth={0.015} />
            {/* Crates */}
            {crate(left + 0.12, top + 0.42, 0.72, 0.7, "#b45309", "c1")}
            {crate(left + 0.95, top + 0.55, 0.78, 0.62, "#92400e", "c2")}
            {/* Barrel on the side */}
            <ellipse cx={left + w * 0.78} cy={top + 0.38} rx={0.28} ry={0.22} fill="#78350f" stroke="#451a03" strokeWidth={0.025} />
            <ellipse cx={left + w * 0.78} cy={top + 0.34} rx={0.22} ry={0.12} fill="#a16207" opacity={0.85} />
            <path
                d={`M${left + w * 0.55} ${top + 0.38} Q${left + w * 0.78} ${top + 0.55} ${left + w * 0.98} ${top + 0.38}`}
                fill="none"
                stroke="#451a03"
                strokeWidth={0.02}
            />
            <line
                x1={left + w * 0.55}
                y1={top + 0.38}
                x2={left + w * 0.98}
                y2={top + 0.38}
                stroke="#fbbf24"
                strokeWidth={0.018}
                opacity={0.7}
            />
            {/* Rope across */}
            <path
                d={`M${left + 0.2} ${top + 0.48} Q${left + w * 0.5} ${top + 0.28} ${left + w - 0.2} ${top + 0.5}`}
                fill="none"
                stroke="#d6d3d1"
                strokeWidth={0.035}
                strokeLinecap="round"
                opacity={0.8}
            />
            <path
                d={`M${left + 0.22} ${top + 0.52} Q${left + w * 0.5} ${top + 0.34} ${left + w - 0.22} ${top + 0.54}`}
                fill="none"
                stroke="#78716c"
                strokeWidth={0.02}
                strokeLinecap="round"
                opacity={0.7}
            />
            {/* Spikes / nails */}
            <circle cx={left + 0.35} cy={top + 0.17} r={0.03} fill="#fef3c7" />
            <circle cx={left + w * 0.5} cy={top + 0.17} r={0.03} fill="#fef3c7" />
            <circle cx={left + w - 0.35} cy={top + 0.17} r={0.03} fill="#fef3c7" />
            {/* Ground shadow */}
            <rect x={left + 0.06} y={top + h - 0.14} width={w - 0.12} height={0.1} fill="#1c1917" opacity={0.4} />
            <rect x={left} y={top} width={w} height={h} rx={0.07} fill="none" stroke="#fde68a" strokeWidth={0.03} opacity={0.5} />
        </g>
    );
}
