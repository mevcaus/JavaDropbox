import { test as setup, expect } from '@playwright/test';
import { ACCOUNT, STORAGE_STATE, readSetupCode, signIn } from '../support/account.js';

setup('finish first-run setup and sign in', async ({ page }) => {
    await page.goto('/');
    // A fresh install sends every page to setup; afterwards, a signed-out visitor goes to sign-in.
    const heading = page.getByRole('heading', { name: /Create your admin account|Sign in to your account/ });
    await expect(heading).toBeVisible();

    if (page.url().includes('/setup')) {
        await page.getByLabel('Setup code').fill(readSetupCode());
        await page.getByPlaceholder('Username').fill(ACCOUNT.username);
        await page.getByPlaceholder('Password', { exact: true }).fill(ACCOUNT.password);
        await page.getByPlaceholder('Confirm password').fill(ACCOUNT.password);
        await page.getByRole('button', { name: 'Create account' }).click();
        await expect(page.getByRole('heading', { name: 'Sign in to your account' })).toBeVisible();
    }

    // Without setup, this is a second run against the same stack, or one set up by hand. If the
    // sign-in fails, start the stack afresh (docker compose --profile app down --volumes) or pass
    // the account's credentials in E2E_USERNAME and E2E_PASSWORD.
    await signIn(page);
    await page.context().storageState({ path: STORAGE_STATE });
});
