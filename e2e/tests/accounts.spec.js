import { randomUUID } from 'node:crypto';
import { test, expect } from '@playwright/test';
import { signIn } from '../support/account.js';

// Signed in as the admin setup created (the shared session), inviting someone who uses the link in
// a browser of their own. Each run invites a new username: accounts are never deleted.
test('an admin invites someone, who gets files of their own, and disables them', async ({ page, browser }) => {
    const invitee = { username: `e2e-user-${randomUUID().slice(0, 8)}`, password: 'e2e-invitee-password' };

    await page.goto('/dashboard');
    await page.getByRole('link', { name: 'Users' }).click();
    await expect(page.getByRole('heading', { name: 'Users' })).toBeVisible();

    await page.getByRole('button', { name: 'Invite someone' }).click();
    const dialog = page.getByRole('dialog', { name: 'Invite someone' });
    await dialog.getByLabel('Username').fill(invitee.username);
    await dialog.getByLabel('Storage quota').fill('1');
    await dialog.getByRole('button', { name: 'Create invitation' }).click();
    const link = await page.getByLabel('Invitation link').inputValue();
    expect(new URL(link).pathname).toMatch(/^\/invite\/[\w-]{43}$/);
    await page.getByRole('button', { name: 'Done' }).click();
    await expect(page.getByRole('region', { name: 'Invitations' }).getByText(invitee.username)).toBeVisible();

    // The invitee, in a browser with no session.
    const theirs = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const them = await theirs.newPage();
    await them.goto(link);
    await expect(them.getByRole('heading', { name: 'Create your account' })).toBeVisible();
    await expect(them.getByText(invitee.username)).toBeVisible();
    await them.getByPlaceholder('Password', { exact: true }).fill(invitee.password);
    await them.getByPlaceholder('Confirm password').fill(invitee.password);
    await them.getByRole('button', { name: 'Create account' }).click();
    await expect(them.getByText('Your account is ready. Sign in with your new password.')).toBeVisible();

    await signIn(them, invitee);
    // Nothing of the admin's, nor any other account's: a folder of their own, with their quota.
    await expect(them.getByText('No files found.')).toBeVisible();
    await expect(them.getByText('0 B of 1 GB used')).toBeVisible();
    await expect(them.getByRole('link', { name: 'Users' })).toHaveCount(0);

    // The link worked once.
    const again = await theirs.newPage();
    await again.goto(link);
    await expect(again.getByText('This link has expired or has already been used.')).toBeVisible();

    // Disabled, they are signed out on their next request.
    await page.reload();
    await page.getByRole('button', { name: `Disable ${invitee.username}` }).click();
    await page
        .getByRole('dialog', { name: `Disable ${invitee.username}?` })
        .getByRole('button', { name: 'Disable', exact: true })
        .click();
    await expect(page.getByRole('row').filter({ hasText: invitee.username })).toContainText('Disabled');
    await them.reload();
    await expect(them).toHaveURL(/\/login$/);
    expect((await them.request.get('/api/me')).status()).toBe(401);

    await theirs.close();
});
