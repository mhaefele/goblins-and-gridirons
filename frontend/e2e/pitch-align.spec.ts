import { expect, test } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const shotDir = path.join(here, "screenshots");

test("pitch align debug page screenshots both boards with grid overlays", async ({ page }) => {
    await page.goto("/pitch-align");
    await expect(page.getByTestId("pitch-align-debug")).toBeVisible();
    await expect(page.getByTestId("pitch-align-default")).toBeVisible();
    await expect(page.getByTestId("pitch-align-barricade")).toBeVisible();

    for (const id of ["default", "barricade"] as const) {
        const section = page.getByTestId(`pitch-align-${id}`);
        const gridPanel = page.getByTestId(`pitch-align-${id}-grid`);
        const playPanel = page.getByTestId(`pitch-align-${id}-play`);
        await expect(gridPanel.getByTestId("pitch-board-image")).toBeVisible();
        await expect(playPanel.getByTestId("pitch-board-image")).toBeVisible();
        await expect(gridPanel.getByTestId("pitch-align-overlay")).toBeVisible();
        await expect(playPanel.getByTestId("pitch-align-overlay")).toHaveCount(0);
        await expect(gridPanel.getByTestId("pitch-board-image")).toHaveAttribute("href", /.+/);
        await section.screenshot({
            path: path.join(shotDir, `pitch-align-${id}.png`),
            animations: "disabled",
        });
    }
});
