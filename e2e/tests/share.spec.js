import { test, expect, action, textFile, upload } from '../support/files.js';

test('shares a file with someone signed out, then revokes the link', async ({ page, playwright, baseURL }) => {
    const content = 'For your eyes only\n';
    await upload(page, textFile('handout.txt', content));

    await action(page, 'Share', 'handout.txt').click();
    const dialog = page.getByRole('dialog', { name: 'Share handout.txt' });
    await dialog.getByLabel('Link expires in').selectOption({ label: '1 hour' });
    await dialog.getByRole('button', { name: 'Generate link' }).click();
    const url = await dialog.getByLabel('Share link').inputValue();
    // On the address the app was opened on, so it works for whoever it is sent to.
    expect(new URL(url).origin).toBe(new URL(baseURL).origin);
    expect(new URL(url).pathname).toMatch(/^\/share\/[^/]+$/);
    await expect(dialog.getByText('This link expires in 1 hour.')).toBeVisible();

    // Someone with the link and no account. The empty storage state is needed: without it the
    // context starts from the project's, signed in.
    const stranger = await playwright.request.newContext({ baseURL, storageState: { cookies: [], origins: [] } });
    try {
        expect((await stranger.get('/api/me')).status()).toBe(401);

        const shared = await stranger.get(url);
        expect(shared.status()).toBe(200);
        expect(shared.headers()['content-disposition']).toContain('handout.txt');
        expect(await shared.text()).toBe(content);

        const revoke = dialog.getByRole('button', { name: /^Revoke the link that expires / });
        await expect(revoke).toHaveCount(1);
        await revoke.click();
        await expect(page.getByText('Link revoked')).toBeVisible();
        await expect(revoke).toHaveCount(0);

        // Revoked links stop working at once, with the same bare 404 as one that never existed.
        expect((await stranger.get(url)).status()).toBe(404);
    } finally {
        await stranger.dispose();
    }

    await dialog.getByRole('button', { name: 'Done' }).click();
    await expect(dialog).toBeHidden();
});

test('lists a link again when the dialog is reopened', async ({ page }) => {
    await upload(page, textFile('reopen.txt', 'x'));

    await action(page, 'Share', 'reopen.txt').click();
    const dialog = page.getByRole('dialog', { name: 'Share reopen.txt' });
    await dialog.getByRole('button', { name: 'Generate link' }).click();
    await expect(dialog.getByLabel('Share link')).toBeVisible();
    await dialog.getByRole('button', { name: 'Done' }).click();

    // Only a hash of the token is kept, so the link itself is not shown again, but it is listed.
    await action(page, 'Share', 'reopen.txt').click();
    await expect(dialog.getByLabel('Share link')).toHaveCount(0);
    await expect(dialog.getByRole('list', { name: 'Active links' }).getByRole('listitem')).toHaveCount(1);
});
