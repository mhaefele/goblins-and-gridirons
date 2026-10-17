import { abilityBadgeUrl } from "../assets/abilityBadges";

export type AbilityBadgeSize = "sm" | "md" | "lg";

type AbilityBadgeProps = {
    /** `PlayerAbility` name (`Ball Guard`) or its slug. */
    ability: string;
    size?: AbilityBadgeSize;
    className?: string;
    /** Accessible name; defaults to a human label when known. */
    alt?: string;
};

export function resolveAbilityBadgeUrl(ability: string | null | undefined): string | null {
    if (ability == null || ability === "") return null;
    return abilityBadgeUrl(ability);
}

/**
 * Diamond ability icon — reuse anywhere (cards, inline next to copy, banners).
 * Size tokens: sm ≈ text row, md ≈ description, lg ≈ ~1/5 of card art.
 */
export default function AbilityBadge({ ability, size = "md", className, alt }: AbilityBadgeProps) {
    const src = resolveAbilityBadgeUrl(ability);
    if (src == null) return null;

    const label = alt ?? ability;
    const classes = ["ability-badge", `ability-badge--${size}`, className].filter(Boolean).join(" ");
    return <img className={classes} src={src} alt={label} draggable={false} />;
}
