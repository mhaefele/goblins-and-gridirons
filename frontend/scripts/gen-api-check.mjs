#!/usr/bin/env node
/**
 * Local drift guard: regenerate OpenAPI types and fail if they differ from the committed file.
 * Requires the backend serving /v3/api-docs (typically localhost:8080).
 */
import { execFileSync } from "node:child_process";
import { copyFileSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const committed = join(root, "src/api/generated/openapi.ts");
const specUrl = process.env.OPENAPI_URL ?? "http://localhost:8080/v3/api-docs";
const dir = mkdtempSync(join(tmpdir(), "gridiron-gen-api-"));
const generated = join(dir, "openapi.ts");

try {
    execFileSync(
        "npx",
        ["openapi-typescript", specUrl, "-o", generated],
        { cwd: root, stdio: "inherit" },
    );
    const a = readFileSync(committed, "utf8");
    const b = readFileSync(generated, "utf8");
    if (a === b) {
        console.log("gen:api:check OK — committed types match live OpenAPI.");
        process.exit(0);
    }
    console.error("gen:api:check FAILED — committed src/api/generated/openapi.ts is stale.");
    console.error("Run `npm run gen:api` with the backend up, then commit the regenerated file.");
    process.exit(1);
} catch (err) {
    console.error("gen:api:check failed to regenerate types:", err instanceof Error ? err.message : err);
    process.exit(1);
} finally {
    rmSync(dir, { recursive: true, force: true });
}
