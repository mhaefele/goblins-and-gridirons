import { expect, test, type Page } from "@playwright/test";

import { assertOccupancyConsistent } from "./helpers";

async function createMatch(page: Page) {
    await page.goto("/");
    await expect(page.getByTestId("create-match-submit")).toBeEnabled();
    await page.getByTestId("create-match-submit").click();
    await expect(page.getByText("Select away team")).toBeVisible();
    await page.getByTestId("create-match-submit").click();
    const kickOff = page.getByTestId("matchup-continue");
    await expect(kickOff).toBeVisible({ timeout: 5_000 });
    await kickOff.click();
    await expect(page.locator(".hud")).toBeVisible({ timeout: 30_000 });
}

async function playOneAction(page: Page): Promise<boolean> {
    const eligible = page.locator('.token[data-eligible="true"]').first();
    if (!(await eligible.isVisible().catch(() => false))) return false;
    await eligible.click();
    const actionButton = page.locator(".legal-actions__choice").first();
    if (!(await actionButton.isVisible().catch(() => false))) return false;
    await actionButton.click();
    return true;
}

test("match play keeps occupancy invariants across several actions", async ({ page }) => {
    await createMatch(page);

    let actionsPlayed = 0;
    const maxActions = 6;
    let lastHomeScore = 0;

    while (actionsPlayed < maxActions) {
        const scoreBeforeText = await page.locator(".hud__score--home").textContent().catch(() => null);
        if (scoreBeforeText != null) {
            lastHomeScore = Number.parseInt(scoreBeforeText.replace(/\D/g, ""), 10) || lastHomeScore;
        }

        const played = await playOneAction(page);
        if (!played) {
            await page.waitForTimeout(500);
            continue;
        }
        actionsPlayed += 1;

        await page.locator(".event-log__entry").first().waitFor({ state: "visible", timeout: 15_000 }).catch(() => undefined);

        await assertOccupancyConsistent(page);

        const scoreAfterText = await page.locator(".hud__score--home").textContent();
        if (scoreAfterText != null) {
            const homeAfter = Number.parseInt(scoreAfterText.replace(/\D/g, ""), 10);
            expect(homeAfter).toBeGreaterThanOrEqual(lastHomeScore);
            lastHomeScore = homeAfter;
        }
    }

    expect(actionsPlayed).toBeGreaterThan(0);
});
