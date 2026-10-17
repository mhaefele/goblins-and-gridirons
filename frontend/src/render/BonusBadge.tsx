import { bonusBadgeUrl } from "../assets/bonusBadges";

export type BonusBadgeSize = "sm" | "md" | "lg";

type BonusBadgeProps = {
    /** `BonusPlay` name (Momentum) or its slug. */
    bonusPlay: string;
    size?: BonusBadgeSize;
    className?: string;
    /** Accessible name; defaults to a human label when known. */
    alt?: string;
};

/** Hexagon bonus-play icon — hand, confirm dialogs, forced-play choices. */
export default function BonusBadge({ bonusPlay, size = "md", className, alt }: BonusBadgeProps) {
    const src = bonusBadgeUrl(bonusPlay);
    if (src == null) return null;

    const label = alt ?? bonusPlay;
    const classes = ["bonus-badge", `bonus-badge--${size}`, className].filter(Boolean).join(" ");
    return <img className={classes} src={src} alt={label} draggable={false} />;
}
