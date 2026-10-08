import { randomUUID } from 'node:crypto';
import { test as base, expect } from '@playwright/test';

/**
 * Each test gets a folder of its own, created through the API and open in the dashboard, so tests
 * can run in parallel and run again on the same stack without seeing each other's files. The
 * fixture is the folder's name.
 */
export const test = base.extend({
    folder: [async ({ page }, use, testInfo) => {
        const name = `e2e-${testInfo.title.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}-${randomUUID().slice(0, 8)}`;

        // The first response sets the CSRF cookie the API wants echoed back in a header.
        await page.goto('/dashboard');
        await expect(page.getByRole('heading', { name: 'My Files' })).toBeVisible();
        const csrf = (await page.context().cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN');
        const created = await page.request.post('/api/folders', {
            form: { path: '', name },
            headers: { 'X-XSRF-TOKEN': csrf?.value ?? '' },
        });
        expect(created.ok(), await created.text()).toBe(true);

        await page.goto(`/dashboard?path=${encodeURIComponent(name)}`);
        await expect(page.getByRole('navigation', { name: 'Breadcrumb' }).getByText(name)).toBeVisible();
        await use(name);
    }, { auto: true }],
});

export { expect };

/** A file to hand to the upload input, made in memory. */
export const textFile = (name, content) => ({ name, mimeType: 'text/plain', buffer: Buffer.from(content) });

/** The file table's row for the item called name. */
export const row = (page, name) =>
    page.getByRole('row').filter({ has: page.getByText(name, { exact: true }) });

/**
 * Opens the folder called name from the file table and waits until the dashboard shows it. The
 * router changes the URL at once but renders the new folder in a transition, so until the
 * breadcrumb catches up, an upload or a new folder still goes into the folder being left.
 */
export const openFolder = async (page, name) => {
    await row(page, name).getByRole('button', { name, exact: true }).click();
    await expect(page.getByRole('navigation', { name: 'Breadcrumb' }).locator('[aria-current="page"]')).toHaveText(name);
};

/**
 * Uploads files through the Upload button into the open folder and waits for them to be listed.
 * After opening a folder, use openFolder so the upload does not go into the one before it.
 */
export const upload = async (page, ...files) => {
    const uploaded = page.waitForResponse(
        (response) => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/files',
    );
    // Exact, or "Upload folder" matches too. The label reads "Uploading…" until the upload is done.
    const input = page.getByLabel('Upload', { exact: true });
    await input.setInputFiles(files);
    expect((await uploaded).status()).toBe(200);
    // Enabled again once the upload has finished and the list is being refreshed.
    await expect(input).toBeEnabled();
    for (const file of files) {
        await expect(row(page, file.name)).toBeVisible();
    }
};

/** The table's action button for an item, such as "Download" or "Share". */
export const action = (page, label, name) => page.getByRole('button', { name: `${label} ${name}`, exact: true });
