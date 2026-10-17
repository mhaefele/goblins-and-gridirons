/** Kebab slug used for asset filenames (`"Catch and Go"` → `catch-and-go`). */
export function assetSlug(label: string): string {
    return label
        .trim()
        .toLowerCase()
        .replace(/['’]/g, "")
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-+|-+$/g, "");
}

/**
 * Re-key an eager `import.meta.glob` URL map by path after [prefix], without extension
 * (`./tokens/orcs/lineman-prone.png` with prefix `./tokens/` → `orcs/lineman-prone`).
 */
export function urlsByStem(modules: Record<string, string>, prefix: string): Record<string, string> {
    return Object.fromEntries(
        Object.entries(modules).map(([path, url]) => [path.slice(prefix.length, path.lastIndexOf(".")), url]),
    );
}
