import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { test, expect, action, openFolder, row, textFile, upload } from '../support/files.js';

test('creates a folder and opens it', async ({ page, folder }) => {
    await page.getByRole('button', { name: 'New Folder' }).click();
    const dialog = page.getByRole('dialog', { name: 'Create New Folder' });
    await dialog.getByLabel('Folder name').fill('Reports');
    await dialog.getByRole('button', { name: 'Create' }).click();
    await expect(dialog).toBeHidden();

    await row(page, 'Reports').getByRole('button', { name: 'Reports', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`path=${encodeURIComponent(`${folder}/Reports`)}`));
    const breadcrumb = page.getByRole('navigation', { name: 'Breadcrumb' });
    await expect(breadcrumb.getByText('Reports')).toBeVisible();
    await expect(page.getByText('No files found.')).toBeVisible();

    // The open folder lives in the URL, so a reload stays in it.
    await page.reload();
    await expect(breadcrumb.getByText('Reports')).toBeVisible();
});

test('uploads and downloads a file', async ({ page }) => {
    const content = 'Quarterly numbers\n1,2,3\n';
    await upload(page, textFile('numbers.csv', content));
    await expect(row(page, 'numbers.csv')).toContainText(`${Buffer.byteLength(content)} B`);

    // Still there after a reload: it was stored, not just added to the page.
    await page.reload();
    await expect(row(page, 'numbers.csv')).toBeVisible();

    const downloading = page.waitForEvent('download');
    await action(page, 'Download', 'numbers.csv').click();
    const download = await downloading;
    expect(download.suggestedFilename()).toBe('numbers.csv');
    expect(await readFile(await download.path(), 'utf8')).toBe(content);
});

test('uploads a folder with its subfolders', async ({ page }, testInfo) => {
    const trip = testInfo.outputPath('Trip');
    await mkdir(join(trip, 'Day 1'), { recursive: true });
    await writeFile(join(trip, 'plan.txt'), 'Pack');
    await writeFile(join(trip, 'Day 1', 'notes.txt'), 'Arrived');
    await writeFile(join(trip, '.DS_Store'), 'junk');

    await page.getByLabel('Upload folder').setInputFiles(trip);
    await expect(page.getByText('.DS_Store was not uploaded.')).toBeVisible();
    await expect(page.getByText('Uploaded folder "Trip" (2 files) successfully.')).toBeVisible();

    await openFolder(page, 'Trip');
    await expect(row(page, 'plan.txt')).toContainText('4 B');
    await openFolder(page, 'Day 1');
    await expect(row(page, 'notes.txt')).toContainText('7 B');
});

test('downloads a folder as a zip', async ({ page, folder }) => {
    await upload(page, textFile('a.txt', 'a'), textFile('b.txt', 'b'));
    await page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('button', { name: 'Home' }).click();

    const downloading = page.waitForEvent('download');
    await action(page, 'Download', folder).click();
    const download = await downloading;
    expect(download.suggestedFilename()).toBe(`${folder}.zip`);
    // A zip starts with the local file header signature, PK\3\4.
    expect((await readFile(await download.path())).subarray(0, 4)).toEqual(Buffer.from([0x50, 0x4b, 0x03, 0x04]));
});

test('deletes a file and a folder', async ({ page, folder }) => {
    await upload(page, textFile('draft.txt', 'scratch'), textFile('keep.txt', 'keep'));

    await action(page, 'Delete', 'draft.txt').click();
    const dialog = page.getByRole('dialog', { name: 'Delete Item' });
    await expect(dialog).toContainText('draft.txt');
    await dialog.getByRole('button', { name: 'Delete' }).click();
    await expect(dialog).toBeHidden();
    await expect(row(page, 'draft.txt')).toHaveCount(0);
    await expect(row(page, 'keep.txt')).toBeVisible();

    await page.reload();
    await expect(row(page, 'keep.txt')).toBeVisible();
    await expect(row(page, 'draft.txt')).toHaveCount(0);

    // The folder goes with everything in it.
    await page.getByRole('navigation', { name: 'Breadcrumb' }).getByRole('button', { name: 'Home' }).click();
    await action(page, 'Delete', folder).click();
    await dialog.getByRole('button', { name: 'Delete' }).click();
    await expect(dialog).toBeHidden();
    await expect(row(page, folder)).toHaveCount(0);
    await page.reload();
    await expect(page.getByRole('heading', { name: 'My Files' })).toBeVisible();
    await expect(row(page, folder)).toHaveCount(0);
});
