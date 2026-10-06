import { test, expect, action, row, textFile, upload } from '../support/files.js';

const previewText = async (page, name) => {
    await action(page, 'Preview', name).click();
    const dialog = page.getByRole('dialog', { name });
    const text = await dialog.getByLabel(`Contents of ${name}`).textContent();
    await dialog.getByRole('button', { name: 'Done' }).click();
    await expect(dialog).toBeHidden();
    return text;
};

test.describe('a file uploaded twice', () => {
    test.beforeEach(async ({ page }) => {
        await upload(page, textFile('plan.txt', 'first draft\n'));
        await upload(page, textFile('plan.txt', 'second draft\n'));
        // Replaced in place: still one row, with the new content.
        await expect(row(page, 'plan.txt')).toHaveCount(1);
        expect(await previewText(page, 'plan.txt')).toBe('second draft\n');
    });

    test('restores the earlier version in place', async ({ page }) => {
        await action(page, 'Versions of', 'plan.txt').click();
        const dialog = page.getByRole('dialog', { name: 'Versions of plan.txt' });
        const versions = dialog.getByRole('listitem');
        await expect(versions).toHaveCount(1);
        await expect(versions).toContainText('Version 1');

        await versions.filter({ hasText: 'Version 1' }).getByRole('button', { name: 'Restore', exact: true }).click();
        await expect(page.getByText('Version 1 restored.')).toBeVisible();
        await expect(dialog).toBeHidden();
        expect(await previewText(page, 'plan.txt')).toBe('first draft\n');

        // The content it replaced was kept as a version of its own.
        await action(page, 'Versions of', 'plan.txt').click();
        await expect(versions).toHaveCount(2);
        await expect(versions.filter({ hasText: 'Version 2' })).toHaveCount(1);
    });

    test('restores the earlier version as a copy', async ({ page }) => {
        await action(page, 'Versions of', 'plan.txt').click();
        const dialog = page.getByRole('dialog', { name: 'Versions of plan.txt' });
        await dialog.getByRole('button', { name: 'Restore as copy' }).click();
        await expect(page.getByText('Version 1 restored as a copy.')).toBeVisible();
        await expect(dialog).toBeHidden();

        // The original keeps its content; the copy sits next to it under a new name.
        expect(await previewText(page, 'plan.txt')).toBe('second draft\n');
        const copy = page.getByRole('button', { name: /^Preview plan.+\.txt$/ }).filter({ hasNotText: /^plan\.txt$/ });
        await expect(copy).toHaveCount(1);
        expect(await previewText(page, (await copy.textContent()).trim())).toBe('first draft\n');
    });
});
