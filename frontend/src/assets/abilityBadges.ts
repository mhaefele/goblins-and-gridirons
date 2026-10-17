import { assetSlug, urlsByStem } from "./slug";

/** Slug for card-facing ability titles (`CardAbilityDto.key` / `PlayerAbility` name). */
export const abilityBadgeSlug = assetSlug;

/** One PNG per `PlayerAbility`, named by [abilityBadgeSlug] of the enum name. */
const ABILITY_BADGE_URLS = urlsByStem(
    import.meta.glob<string>("./ui/abilities/*.png", { eager: true, query: "?url", import: "default" }),
    "./ui/abilities/",
);

/** Resolve by slug or card title (`"Ball Guard"` / `"ball-guard"`). */
export function abilityBadgeUrl(keyOrLabel: string | null | undefined): string | null {
    if (keyOrLabel == null || keyOrLabel === "") return null;
    return ABILITY_BADGE_URLS[abilityBadgeSlug(keyOrLabel)] ?? null;
}
