import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { expect } from '@playwright/test';

const REPO_ROOT = fileURLToPath(new URL('../..', import.meta.url));

export const STORAGE_STATE = fileURLToPath(new URL('../.auth/user.json', import.meta.url));

// The account setup creates on a fresh stack. Against a stack set up some other way, pass its
// credentials in E2E_USERNAME and E2E_PASSWORD.
export const ACCOUNT = {
    username: process.env.E2E_USERNAME ?? 'e2e-admin',
    password: process.env.E2E_PASSWORD ?? 'e2e-password-not-secret',
};

// SetupService prints the code between two rules of '=' as XXXXX-XXXXX, from an alphabet without
// 0, 1, I or O.
const SETUP_CODE = /create one with this setup code:\s+([A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5})/g;

/**
 * The setup code, read from the app container's log the way the README tells a user to find it.
 * E2E_SETUP_CODE takes its place for a server that is not run by Docker Compose. Docker Compose
 * itself reads COMPOSE_PROJECT_NAME and COMPOSE_FILE, for a stack started under another name.
 */
export const readSetupCode = () => {
    if (process.env.E2E_SETUP_CODE) return process.env.E2E_SETUP_CODE;

    const log = execFileSync('docker', ['compose', '--profile', 'app', 'logs', '--no-color', '--no-log-prefix', 'app'], {
        cwd: REPO_ROOT,
        encoding: 'utf8',
        maxBuffer: 64 * 1024 * 1024,
    });
    // The code changes on every restart, so only the last one printed works.
    const code = [...log.matchAll(SETUP_CODE)].at(-1)?.[1];
    if (!code) {
        throw new Error(
            'No setup code in "docker compose logs app". Is the stack running (docker compose --profile app up --wait)? '
                + 'Set E2E_SETUP_CODE for a server Docker Compose did not start.',
        );
    }
    return code;
};

/** Signs in through the sign-in page and waits for the file list. */
export const signIn = async (page, { username, password } = ACCOUNT) => {
    await page.getByPlaceholder('Username').fill(username);
    await page.getByPlaceholder('Password').fill(password);
    await page.getByRole('button', { name: 'Sign in' }).click();
    await expect(page).toHaveURL(/\/dashboard/);
    await expect(page.getByRole('heading', { name: 'My Files' })).toBeVisible();
};
