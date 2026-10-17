const SKIP_DICE_KEY = "gridiron.skipDiceAnimations";

export function readSkipDiceAnimations(): boolean {
    if (typeof window === "undefined") return false;
    try {
        return window.localStorage.getItem(SKIP_DICE_KEY) === "1";
    } catch {
        return false;
    }
}

export function writeSkipDiceAnimations(skip: boolean): void {
    if (typeof window === "undefined") return;
    try {
        window.localStorage.setItem(SKIP_DICE_KEY, skip ? "1" : "0");
    } catch {
        // Ignore quota / private-mode failures — in-memory toggle still works.
    }
}
