import { assetSlug, urlsByStem } from "./slug";

export const bonusBadgeSlug = assetSlug;

/** One PNG per `BonusPlay`, named by [bonusBadgeSlug] of the enum name. */
const BONUS_BADGE_URLS = urlsByStem(
    import.meta.glob<string>("./ui/bonuses/*.png", { eager: true, query: "?url", import: "default" }),
    "./ui/bonuses/",
);

/** Resolve by slug or display name (`"Momentum"` / `"momentum"`). */
export function bonusBadgeUrl(keyOrLabel: string | null | undefined): string | null {
    if (keyOrLabel == null || keyOrLabel === "") return null;
    return BONUS_BADGE_URLS[bonusBadgeSlug(keyOrLabel)] ?? null;
}
