import { expect, test, type Page } from "@playwright/test";

async function createMatch(page: Page) {
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Create match" })).toBeVisible();
    await expect(page.getByTestId("create-match-submit")).toBeEnabled();

    await page.getByTestId("create-match-submit").click();
    await expect(page.getByText("Select away team")).toBeVisible();
    await page.getByTestId("create-match-submit").click();
    const kickOff = page.getByTestId("matchup-continue");
    await expect(kickOff).toBeVisible({ timeout: 5_000 });
    await kickOff.click();
    await expect(page.locator(".hud")).toBeVisible({ timeout: 30_000 });
    await expect(page.locator(".pitch-svg")).toBeVisible();
}

async function submitOneLegalAction(page: Page) {
    const eligible = page.locator('.token[data-eligible="true"]').first();
    await expect(eligible).toBeVisible({ timeout: 30_000 });
    await eligible.click();
    const actionButton = page.locator(".legal-actions__choice").first();
    await expect(actionButton).toBeVisible({ timeout: 10_000 });
    await actionButton.click();
}

test("create match and submit one legal action", async ({ page }) => {
    await createMatch(page);

    const actionsBefore = await page.getByText(/Actions/).first().textContent();
    await submitOneLegalAction(page);

    await expect(page.locator(".event-log__entry").first()).toBeVisible({ timeout: 15_000 });
    await expect(page.locator(".hud__pips")).toBeVisible();
    expect(actionsBefore).toBeTruthy();
});
