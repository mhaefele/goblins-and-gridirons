import { defineConfig, devices } from "@playwright/test";

const frontendPort = 4173;

export default defineConfig({
    testDir: "./e2e",
    fullyParallel: false,
    forbidOnly: !!process.env.CI,
    retries: process.env.CI ? 1 : 0,
    workers: 1,
    reporter: "list",
    timeout: 120_000,
    use: {
        baseURL: `http://localhost:${frontendPort}`,
        trace: "on-first-retry",
        ...devices["Desktop Chrome"],
    },
    webServer: [
        {
            command: "mvn -f ../backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=scripted-dice",
            url: "http://localhost:8080/health",
            reuseExistingServer: !process.env.CI,
            timeout: 180_000,
            cwd: ".",
        },
        {
            command: `npm run build && npm run preview -- --port ${frontendPort}`,
            url: `http://localhost:${frontendPort}`,
            reuseExistingServer: !process.env.CI,
            timeout: 120_000,
            cwd: ".",
        },
    ],
});
