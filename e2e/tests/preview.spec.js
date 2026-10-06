import { test, expect, action, row, textFile, upload } from '../support/files.js';

// The smallest valid PNG: one opaque pixel.
const PNG = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=',
    'base64',
);

// A one-page PDF with a cross-reference table whose offsets are right, so it is a real PDF.
const pdf = () => {
    const objects = [
        '<< /Type /Catalog /Pages 2 0 R >>',
        '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>',
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

test('previews an image', async ({ page }) => {
    await upload(page, { name: 'pixel.png', mimeType: 'image/png', buffer: PNG });
    await action(page, 'Preview', 'pixel.png').click();

    const dialog = page.getByRole('dialog', { name: 'pixel.png' });
    const image = dialog.getByRole('img', { name: 'pixel.png' });
    await expect(image).toBeVisible();
    // Loaded and decoded, not a broken-image icon.
    await expect.poll(() => image.evaluate((img) => img.complete && img.naturalWidth)).toBe(1);
    await expect(dialog.getByText('Could not load the image.')).toHaveCount(0);
});

test('previews a PDF', async ({ page }) => {
    await upload(page, { name: 'one-page.pdf', mimeType: 'application/pdf', buffer: pdf() });
    await action(page, 'Preview', 'one-page.pdf').click();

    const frame = page.getByRole('dialog', { name: 'one-page.pdf' }).locator('iframe[title="Preview of one-page.pdf"]');
    await expect(frame).toBeVisible();

    // Headless Chromium has no PDF viewer to look inside, so check what the frame was given: the
    // file, inline, as a PDF, and allowed into a frame on this page.
    const response = await page.request.get(await frame.getAttribute('src'));
    expect(response.status()).toBe(200);
    expect(response.headers()['content-type']).toBe('application/pdf');
    expect(response.headers()['content-disposition']).toMatch(/^inline/);
    expect(response.headers()['x-frame-options']).not.toBe('DENY');
    expect(Buffer.from(await response.body()).equals(pdf())).toBe(true);
});

test('previews a text file as text', async ({ page }) => {
    const content = '# Notes\n\n<script>alert("not run")</script>\n';
    await upload(page, textFile('notes.md', content));
    await action(page, 'Preview', 'notes.md').click();

    const dialog = page.getByRole('dialog', { name: 'notes.md' });
    // Markup in the file shows as written.
    await expect(dialog.getByLabel('Contents of notes.md')).toHaveText(content.trimEnd());

    // Download from the preview gets the whole file.
    const downloading = page.waitForEvent('download');
    await dialog.getByRole('button', { name: 'Download' }).click();
    expect((await downloading).suggestedFilename()).toBe('notes.md');

    await dialog.getByRole('button', { name: 'Done' }).click();
    await expect(dialog).toBeHidden();
    await expect(row(page, 'notes.md')).toBeVisible();
});
