import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { test, expect, dropFiles, openFolder, row, textFile } from '../support/files.js';

// #350: one request takes at most server.tomcat.max-part-count parts (50), so 200 files bound for
// one folder go in several. Each way of uploading is checked against the real server.
const COUNT = 200;
const names = Array.from({ length: COUNT }, (_, i) => `file-${String(i).padStart(3, '0')}.txt`);
// Each file is stored in a transaction of its own, over several requests one after another, while
// the other spec files run against the same stack. Far more than that takes, but not forever.
const STORED = { timeout: 60_000 };

// The files listed in the open folder: every row but the header.
const expectAllListed = async (page) => {
    await expect(page.getByRole('row')).toHaveCount(COUNT + 1);
    await expect(row(page, names[0])).toBeVisible();
    await expect(row(page, names.at(-1))).toBeVisible();
};

// The requests an upload sent, counted as they answer, so the test sees it really was batched.
const countUploads = (page) => {
    const statuses = [];
    page.on('response', (response) => {
        if (response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/files') {
            statuses.push(response.status());
        }
    });
    return statuses;
};

test('uploads 200 files chosen at once', async ({ page }) => {
    const uploads = countUploads(page);

    await page.getByLabel('Upload', { exact: true }).setInputFiles(names.map((name) => textFile(name, name)));

    await expect(page.getByText(`Uploaded ${COUNT} files successfully.`)).toBeVisible(STORED);
    await expectAllListed(page);
    expect(uploads.length).toBeGreaterThan(1);
    expect(uploads.every((status) => status === 200)).toBe(true);
});

test('uploads a folder holding 200 files', async ({ page }, testInfo) => {
    const big = testInfo.outputPath('Big');
    await mkdir(big, { recursive: true });
    for (const name of names) {
        await writeFile(join(big, name), name);
    }
    const uploads = countUploads(page);

    await page.getByLabel('Upload folder').setInputFiles(big);

    await expect(page.getByText(`Uploaded folder "Big" (${COUNT} files) successfully.`)).toBeVisible(STORED);
    expect(uploads.length).toBeGreaterThan(1);
    await openFolder(page, 'Big');
    await expectAllListed(page);
});

test('uploads a dropped folder holding 200 files', async ({ page }) => {
    const uploads = countUploads(page);

    await dropFiles(page.getByText('No files found.'), Object.fromEntries(names.map((name) => [`Big/${name}`, name])));

    await expect(page.getByText(`Uploaded folder "Big" (${COUNT} files) successfully.`)).toBeVisible(STORED);
    expect(uploads.length).toBeGreaterThan(1);
    await openFolder(page, 'Big');
    await expectAllListed(page);
});
