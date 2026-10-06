import { defineConfig, devices } from '@playwright/test';
import { STORAGE_STATE } from './support/account.js';

// Runs against a stack that is already up (docker compose --profile app up --wait); it does not
// start one, so the tests see the same image, database and volume a user would.
export default defineConfig({
    testDir: './tests',
    fullyParallel: true,
    forbidOnly: Boolean(process.env.CI),
    // One retry on CI, so a flaky test shows up as flaky in the report instead of failing the run.
    retries: process.env.CI ? 1 : 0,
    reporter: [['list'], ['html', { open: 'never' }]],
    use: {
        baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080',
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure',
    },
    projects: [
        // Finishes first-run setup if the stack is fresh, then signs in once for every other test.
        { name: 'setup', testMatch: /account\.setup\.js/ },
        {
            name: 'chromium',
            use: { ...devices['Desktop Chrome'], storageState: STORAGE_STATE },
            dependencies: ['setup'],
        },
    ],
});
