# Throw Ruler geometry (BG.1)

Rules digest § Throw — range and obstruction follow the cardboard **Throw Ruler**, not abstract Within alone. **Shipped / closed** — see [BACKLOG-done BG.1](../BACKLOG-done.md#bg1--physical-throw-ruler-geometry-2026-07).

## Engine mapping

Calibrated from the physical ruler on an orthogonal pitch: **8** squares centre-to-centre tip-to-tip; Short/Long split at half.

| Rulebook | Engine |
|----------|--------|
| Short (black ruler section) | Centre-to-centre **Euclidean ≤ 4** |
| Long (red ruler section) | Euclidean **> 4 and ≤ 8** |
| Out of range | Euclidean **> 8** — not offered |
| Hand-off | Adjacent (Within 1) — no check |
| Obstruction on the ruler | Any **interior** square on the **centre-to-centre grid line** that is blocked or has an **Open** opposition player; **or** the catcher is Marked |

Obstructions never prevent attempting the throw — they only apply the −1 modifier (capped with Long at −1 total).

## Diagonal math

The ruler is a rigid stick of length 8 (square units). A target square is in range iff its centre is within Euclidean distance ≤ 8 of the thrower’s centre:

`√(dx² + dy²) ≤ 8`

Examples:

| Offset (dx, dy) | Length | Band |
|-----------------|--------|------|
| (8, 0) | 8.00 | Long (on the tip) |
| (4, 0) | 4.00 | Short (outer edge) |
| (4, 4) | 5.66 | Long |
| (5, 5) | 7.07 | Long |
| (6, 6) | 8.49 | Out of range |

`ThrowRulerOffsets` precomputes every integer `(dx, dy)` in Short / Long once at startup (same results as calling `hypot` per square). `throwTeammateLandingMoves` iterates that set instead of the whole pitch.

## Why Euclidean (not Within)

Path **Within** (digest § Player stats and team cards) is for movement / adjacency. The Throw Ruler is a straight physical template — use `rulerDistance` / `ThrowRulerOffsets`, not BFS distance.

To retune bands: change `THROW_RULER_SHORT_MAX` / `THROW_RULER_MAX` in `ThrowGeometry.kt` (offsets rebuild from those constants).

## Obstruction path (ruler-like)

Interior squares are those the **centre-to-centre segment enters with positive length** (Amanatides–Woo grid walk), excluding endpoints. Exact corner crossings take only the diagonal square — a flank that shares a single corner point is not on the ruler.

## Behaviour notes

- Diagonal corridors past pillars — a pillar on a corner-only flank does **not** obstruct
- Open opposition one square off the geometric line — does not obstruct
- Marked catcher — always obstructed, regardless of path
- Short/Long colour split — halfway on the stick (≤4 / ≤8)
