import { readFile } from 'node:fs/promises';
import { test, expect, action, openFolder, textFile, upload } from '../support/files.js';

// The empty storage state is needed: without it a new context starts from the project's, signed in.
const SIGNED_OUT = { storageState: { cookies: [], origins: [] } };

/** Shares the item called name from the open folder and returns the link, leaving the dialog open. */
const createLink = async (page, name) => {
    await action(page, 'Share', name).click();
    const dialog = page.getByRole('dialog', { name: `Share ${name}` });
    await dialog.getByRole('button', { name: 'Generate link' }).click();
    return dialog.getByLabel('Share link').inputValue();
};

/** Opens a link in a browser of its own, as someone with no account would. */
const visit = async (browser, url, use) => {
    const stranger = await browser.newContext(SIGNED_OUT);
    try {
        const visitor = await stranger.newPage();
        await visitor.goto(url);
        await use(visitor);
    } finally {
        await stranger.close();
    }
};

test('opens a shared file as a page that previews it, and downloads it from there', async ({ page, browser }) => {
    const content = 'Agenda\n1. Coffee\n2. Everything else\n';
    await upload(page, textFile('agenda.txt', content));
    const url = await createLink(page, 'agenda.txt');

    await visit(browser, url, async (visitor) => {
        // A page, not a download: what it is, then the file itself.
        await expect(visitor.getByRole('heading', { name: 'agenda.txt' })).toBeVisible();
        await expect(visitor.getByText(/^\d+ B · Link expires /)).toBeVisible();
        await expect(visitor.getByLabel('Contents of agenda.txt')).toHaveText(content.trimEnd());
        await expect(visitor).toHaveTitle('agenda.txt · JavaDropbox');

        const downloading = visitor.waitForEvent('download');
        await visitor.getByRole('link', { name: 'Download' }).click();
        const download = await downloading;
        expect(download.suggestedFilename()).toBe('agenda.txt');
        expect(await readFile(await download.path(), 'utf8')).toBe(content);
    });
});

test('opens a shared folder as a page that lists it, and downloads it as a zip', async ({ page, browser, folder }) => {
    await page.getByRole('button', { name: 'New Folder' }).click();
    await page.getByLabel('Folder name').fill('Photos');
    await page.getByRole('button', { name: 'Create' }).click();
    await upload(page, textFile('readme.txt', 'see Photos'));
    await openFolder(page, 'Photos');
    await upload(page, textFile('beach.txt', 'sand'));
    await page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('button', { name: 'Home' }).click();
    const url = await createLink(page, folder);

    await visit(browser, url, async (visitor) => {
        await expect(visitor.getByRole('heading', { name: folder })).toBeVisible();
        await expect(visitor.getByText(/^Folder · 2 items · /)).toBeVisible();
        const contents = visitor.getByRole('listitem');
        await expect(contents).toHaveCount(2);
        await expect(contents.first()).toContainText('Photos');
        await expect(contents.last()).toContainText('readme.txt');

        await visitor.getByRole('button', { name: 'Photos' }).click();
        await expect(visitor.getByRole('listitem')).toHaveText(['beach.txt4 B']);

        const downloading = visitor.waitForEvent('download');
        await visitor.getByRole('link', { name: 'Download as .zip' }).click();
        const download = await downloading;
        expect(download.suggestedFilename()).toBe(`${folder}.zip`);
    });
});

test('a script fetching the link gets the file, until the link is revoked', async ({ page, playwright, browser, baseURL }) => {
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

    // curl, wget and download managers do not ask for a page, so they get the file at the link.
    const stranger = await playwright.request.newContext({ baseURL, ...SIGNED_OUT });
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

    // In a browser, the page says so.
    await visit(browser, url, async (visitor) => {
        await expect(visitor.getByRole('heading', { name: "This link doesn't work" })).toBeVisible();
        await expect(visitor.getByRole('link', { name: /Download/ })).toHaveCount(0);
    });

    await dialog.getByRole('button', { name: 'Done' }).click();
    await expect(dialog).toBeHidden();
});

test('lists a link again when the dialog is reopened', async ({ page }) => {
    await upload(page, textFile('reopen.txt', 'x'));
    await createLink(page, 'reopen.txt');
    const dialog = page.getByRole('dialog', { name: 'Share reopen.txt' });
    await expect(dialog.getByLabel('Share link')).toBeVisible();
    await dialog.getByRole('button', { name: 'Done' }).click();

    // Only a hash of the token is kept, so the link itself is not shown again, but it is listed.
    await action(page, 'Share', 'reopen.txt').click();
    await expect(dialog.getByLabel('Share link')).toHaveCount(0);
    await expect(dialog.getByRole('list', { name: 'Active links' }).getByRole('listitem')).toHaveCount(1);
});
