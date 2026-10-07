import { test, expect, action, row, textFile, upload } from '../support/files.js';

// A one-page PDF that says `line` in Helvetica, so there is text in it to find.
const pdfSaying = (line) => {
    const stream = `BT /F1 12 Tf 72 700 Td (${line}) Tj ET`;
    const objects = [
        '<< /Type /Catalog /Pages 2 0 R >>',
        '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
        '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
        `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
    ];
    let body = '%PDF-1.4\n';
    const offsets = objects.map((object, i) => {
        const offset = body.length;
        body += `${i + 1} 0 obj\n${object}\nendobj\n`;
        return offset;
    });
    const xref = body.length;
    body += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
    body += offsets.map((offset) => `${String(offset).padStart(10, '0')} 00000 n \n`).join('');
    body += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
    return Buffer.from(body, 'latin1');
};

/**
 * Searches the open folder until the results show what `expectation` waits for. Files are indexed
 * in the background a moment after they are stored, so a search typed straight after an upload
 * can come back before they are in it; typing it again asks again.
 */
const searchUntil = async (page, text, expectation) => {
    const box = page.getByRole('textbox', { name: 'Search files' });
    await expect(async () => {
        await box.fill('');
        await box.fill(text);
        await expectation();
    }).toPass({ timeout: 15_000 });
};

test('finds files by the words inside them, and previews one from the results', async ({ page }) => {
    await upload(
        page,
        textFile('recipe.txt', 'Whisk the eggs, then fold in the marmalade gently.'),
        textFile('shopping.txt', 'Bread and milk.'),
        { name: 'menu.pdf', mimeType: 'application/pdf', buffer: pdfSaying('Toast with marmalade') },
    );

    await searchUntil(page, 'marmalade', async () => {
        await expect(page.getByText('2 matches in this folder and below, best first.')).toBeVisible({ timeout: 2_000 });
    });
    await expect(row(page, 'recipe.txt')).toBeVisible();
    await expect(row(page, 'menu.pdf')).toBeVisible();
    await expect(row(page, 'shopping.txt')).toHaveCount(0);
    // Each result shows where its text matched, with the word marked.
    await expect(row(page, 'recipe.txt').locator('mark')).toHaveText('marmalade');
    await expect(row(page, 'recipe.txt')).toContainText('fold in the marmalade gently');
    await expect(row(page, 'menu.pdf').locator('mark')).toHaveText('marmalade');

    await action(page, 'Preview', 'recipe.txt').click();
    const dialog = page.getByRole('dialog', { name: 'recipe.txt' });
    await expect(dialog.getByLabel('Contents of recipe.txt')).toContainText('fold in the marmalade');
    await dialog.getByRole('button', { name: 'Done' }).click();
    await expect(dialog).toBeHidden();
});

test('drops a file from the results once it is deleted from them', async ({ page }) => {
    // Another file keeps the folder from emptying, which would take the search box with it.
    await upload(page, textFile('ephemeral.txt', 'A thought about gooseberries.'), textFile('keeper.txt', 'Nothing else.'));
    await searchUntil(page, 'gooseberries', async () => {
        await expect(row(page, 'ephemeral.txt')).toBeVisible({ timeout: 2_000 });
    });

    await action(page, 'Delete', 'ephemeral.txt').click();
    const dialog = page.getByRole('dialog', { name: 'Delete Item' });
    await dialog.getByRole('button', { name: 'Delete' }).click();
    await expect(dialog).toBeHidden();

    // The results refresh along with the file list, without searching again.
    await expect(page.getByText('No files match your search.')).toBeVisible();
    await expect(page.getByRole('textbox', { name: 'Search files' })).toHaveValue('gooseberries');
});
