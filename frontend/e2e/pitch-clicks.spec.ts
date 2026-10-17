import { expect, test, type Page } from "@playwright/test";

type SnapshotPlayer = {
    id: string;
    at: { x: number; y: number } | null;
    status: string;
};

type LegalAction = {
    kind: string;
    playerId: string;
    actionType: string;
    targetCoordinate: { x: number; y: number };
    path?: { x: number; y: number }[];
};

async function createMatch(page: Page) {
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Create match" })).toBeVisible();
    await expect(page.getByTestId("create-match-submit")).toBeEnabled();
    await page.getByTestId("create-match-submit").click();
    await expect(page.getByText("Select away team")).toBeVisible();
    await page.getByTestId("create-match-submit").click();
    const kickOff = page.getByTestId("matchup-continue");
    await expect(kickOff).toBeVisible({ timeout: 15_000 });
    await kickOff.click();
    await expect(kickOff).toBeHidden({ timeout: 10_000 });
    await expect(page.locator(".hud")).toBeVisible({ timeout: 30_000 });
    await expect(page.locator(".pitch-svg")).toBeVisible();
}

async function matchId(page: Page): Promise<string> {
    const id = await page.locator("[data-match-id]").getAttribute("data-match-id");
    expect(id).toBeTruthy();
    return id!;
}

async function fetchSnapshot(page: Page): Promise<{ players: SnapshotPlayer[] }> {
    const id = await matchId(page);
    const response = await page.request.get(`/api/matches/${encodeURIComponent(id)}/snapshot`);
    expect(response.ok()).toBeTruthy();
    const body = (await response.json()) as { pieces: { players: SnapshotPlayer[] } };
    return { players: body.pieces.players };
}

async function fetchLegalActions(page: Page): Promise<LegalAction[]> {
    const id = await matchId(page);
    const response = await page.request.get(`/api/matches/${encodeURIComponent(id)}/legal-actions`);
    expect(response.ok()).toBeTruthy();
    return (await response.json()) as LegalAction[];
}

async function dismissDiceTheatre(page: Page) {
    const dice = page.getByTestId("action-dice");
    // In-pitch dice auto-advance; click skips until gone.
    for (let i = 0; i < 8; i++) {
        if (!(await dice.isVisible().catch(() => false))) return;
        await dice.click({ force: true }).catch(() => undefined);
        await page.waitForTimeout(50);
    }
}

async function confirmRunIfChooser(page: Page) {
    const chooser = page.locator(".action-chooser");
    if (!(await chooser.isVisible().catch(() => false))) return;
    const runBtn = chooser.locator("button").filter({ hasText: /^Run / }).first();
    if (await runBtn.isVisible().catch(() => false)) {
        await runBtn.click();
        return;
    }
    const choice = chooser.locator("button").filter({ hasNotText: "Cancel" }).first();
    await choice.click();
}

test("play pitch uses painted board underlay aligned to the playable floor", async ({ page }) => {
    await createMatch(page);
    const board = page.getByTestId("pitch-board-image");
    await expect(board).toBeVisible();
    // Content-rect placement (not full 13×17 stretch) — see BOARD_IMAGE_PLAYABLE_PX.
    const x = Number(await board.getAttribute("x"));
    const y = Number(await board.getAttribute("y"));
    const width = Number(await board.getAttribute("width"));
    const height = Number(await board.getAttribute("height"));
    expect(x).toBeGreaterThan(0);
    expect(y).toBeGreaterThan(0);
    expect(width).toBeGreaterThan(11);
    expect(width).toBeLessThan(13);
    expect(height).toBeGreaterThan(15);
    expect(height).toBeLessThan(17);
    // Single board image — not a flood of per-cell tiles.
    await expect(page.getByTestId("pitch-svg").locator("image")).toHaveCount(1);
});

test("hit-target centers resolve to their own data-coord (no neighbor stroke steal)", async ({ page }) => {
    await createMatch(page);
    await dismissDiceTheatre(page);

    const actions = await fetchLegalActions(page);
    const run = actions.find((action) => action.kind === "run");
    expect(run).toBeTruthy();
    await page.locator(`.token[data-player-id="${run!.playerId}"]`).click();

    const hits = page.locator(".pitch-hit-target");
    const count = await hits.count();
    expect(count).toBeGreaterThan(3);

    for (let i = 0; i < count; i++) {
        const hit = hits.nth(i);
        const coord = await hit.getAttribute("data-coord");
        expect(coord).toBeTruthy();
        const box = await hit.evaluate((el) => {
            const r = el.getBoundingClientRect();
            return { cx: r.x + r.width / 2, cy: r.y + r.height / 2, w: r.width, h: r.height };
        });
        // Cell should be roughly square — oversized boxes mean stroke/layout bleed.
        expect(box.w / box.h).toBeGreaterThan(0.85);
        expect(box.w / box.h).toBeLessThan(1.15);

        const under = await page.evaluate(({ cx, cy }) => {
            const el = document.elementFromPoint(cx, cy);
            return el?.getAttribute("data-coord") ?? null;
        }, { cx: box.cx, cy: box.cy });
        expect(under, `center of hit ${coord} resolved to ${under}`).toBe(coord);
    }
});

test("hover target outline matches the legal square under the cursor before click", async ({ page }) => {
    await createMatch(page);
    await dismissDiceTheatre(page);

    const actions = await fetchLegalActions(page);
    const run = actions.find((action) => action.kind === "run");
    expect(run).toBeTruthy();

    const token = page.locator(`.token[data-player-id="${run!.playerId}"]`);
    await token.click();

    const hit = page.locator(`.pitch-hit-target[data-coord="${run!.targetCoordinate.x},${run!.targetCoordinate.y}"]`);
    await expect(hit).toBeVisible();
    await hit.hover();

    const hover = page.getByTestId("pitch-hover-target");
    await expect(hover).toBeVisible();
    await expect(hover).toHaveAttribute("data-coord", `${run!.targetCoordinate.x},${run!.targetCoordinate.y}`);

    const hint = page.getByTestId("pitch-hover-hint");
    await expect(hint).toContainText(`(${run!.targetCoordinate.x}, ${run!.targetCoordinate.y})`);
});

test("pitch square clicks move the selected player to that exact coordinate", async ({ page }) => {
    await createMatch(page);

    let verifiedMoves = 0;
    const maxAttempts = 24;

    for (let attempt = 0; attempt < maxAttempts && verifiedMoves < 5; attempt++) {
        await dismissDiceTheatre(page);

        const actions = await fetchLegalActions(page);
        const run = actions.find((action) => action.kind === "run");
        if (run == null) {
            await page.waitForTimeout(400);
            continue;
        }

        const expected = run.targetCoordinate;
        expect(run.path?.at(-1)).toEqual(expected);

        const token = page.locator(`.token[data-player-id="${run.playerId}"]`);
        await expect(token).toBeVisible({ timeout: 10_000 });
        await token.click();

        const hit = page.locator(`.pitch-hit-target[data-coord="${expected.x},${expected.y}"]`);
        await expect(hit).toBeVisible({ timeout: 5_000 });
        await hit.hover();
        await expect(page.getByTestId("pitch-hover-target")).toHaveAttribute("data-coord", `${expected.x},${expected.y}`);

        const before = await fetchSnapshot(page);
        expect(before.players.find((p) => p.id === run.playerId)?.at).not.toEqual(expected);

        await hit.click();
        await confirmRunIfChooser(page);
        await dismissDiceTheatre(page);

        await expect
            .poll(async () => {
                const snap = await fetchSnapshot(page);
                return snap.players.find((p) => p.id === run.playerId)?.at ?? null;
            }, { timeout: 15_000 })
            .toEqual(expected);

        verifiedMoves += 1;
    }

    expect(verifiedMoves).toBeGreaterThanOrEqual(5);
});

test("many highlighted destinations each land on their labeled coordinate", async ({ page }) => {
    await createMatch(page);
    await dismissDiceTheatre(page);

    const actions = await fetchLegalActions(page);
    const runs = actions.filter((action) => action.kind === "run").slice(0, 8);
    expect(runs.length).toBeGreaterThan(3);

    // Use the same player for a batch of destinations from one selection, verifying each click.
    const playerId = runs[0]!.playerId;
    const forPlayer = runs.filter((action) => action.playerId === playerId);
    expect(forPlayer.length).toBeGreaterThan(0);

    const token = page.locator(`.token[data-player-id="${playerId}"]`);
    await token.click();

    // Only one move can succeed per turn action — verify the first destination thoroughly,
    // then confirm every remaining highlight's data-coord matches its API position (no drift).
    for (const run of forPlayer) {
        const hit = page.locator(`.pitch-hit-target[data-coord="${run.targetCoordinate.x},${run.targetCoordinate.y}"]`);
        await expect(hit, `hit target missing for API destination (${run.targetCoordinate.x},${run.targetCoordinate.y})`).toBeVisible();
        const attr = await hit.getAttribute("data-coord");
        expect(attr).toBe(`${run.targetCoordinate.x},${run.targetCoordinate.y}`);
    }

    const chosen = forPlayer[forPlayer.length - 1]!;
    const hit = page.locator(`.pitch-hit-target[data-coord="${chosen.targetCoordinate.x},${chosen.targetCoordinate.y}"]`);
    await hit.hover();
    await expect(page.getByTestId("pitch-hover-target")).toHaveAttribute(
        "data-coord",
        `${chosen.targetCoordinate.x},${chosen.targetCoordinate.y}`,
    );
    await hit.click();
    await confirmRunIfChooser(page);

    await expect
        .poll(async () => {
            const snap = await fetchSnapshot(page);
            return snap.players.find((p) => p.id === playerId)?.at ?? null;
        }, { timeout: 15_000 })
        .toEqual(chosen.targetCoordinate);
});
