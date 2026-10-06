import { test, expect } from '@playwright/test';
import { ACCOUNT, signIn } from '../support/account.js';

// Signed out to start with, and with a session of its own: signing out of the shared one would
// sign every other test out too.
test.use({ storageState: { cookies: [], origins: [] } });

test('sends a signed-out visitor to sign in', async ({ page }) => {
    await page.goto('/dashboard');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('heading', { name: 'Sign in to your account' })).toBeVisible();
    // Setup is done, so the sign-in page no longer offers it.
    await expect(page.getByRole('link', { name: /Set up the first user/ })).toHaveCount(0);
});

test('refuses a wrong password', async ({ page }) => {
    await page.goto('/login');
    await page.getByPlaceholder('Username').fill(ACCOUNT.username);
    await page.getByPlaceholder('Password').fill(`${ACCOUNT.password}-wrong`);
    await page.getByRole('button', { name: 'Sign in' }).click();

    await expect(page.getByRole('alert').filter({ hasText: 'Invalid username or password.' })).toBeVisible();
    await expect(page).toHaveURL(/\/login$/);
});

test('signs in and out', async ({ page }) => {
    await page.goto('/login');
    await signIn(page);
    await expect(page.getByRole('banner').getByText(ACCOUNT.username, { exact: true })).toBeVisible();

    // The session survives a reload.
    await page.reload();
    await expect(page.getByRole('heading', { name: 'My Files' })).toBeVisible();

    await page.getByRole('button', { name: 'Logout' }).click();
    await expect(page).toHaveURL(/\/login$/);

    // Signing out ended the session on the server, not just in the page.
    await page.goto('/dashboard');
    await expect(page).toHaveURL(/\/login$/);
    expect((await page.request.get('/api/me')).status()).toBe(401);
});
