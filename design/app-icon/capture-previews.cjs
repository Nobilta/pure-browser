// Optional design export tool; Playwright is not an application dependency.
const { chromium } = require('playwright');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const page = await browser.newPage({
      viewport: { width: 1160, height: 780 },
      deviceScaleFactor: 2,
    });
    await page.goto(pathToFileURL(path.join(__dirname, 'comparison.html')).href);
    await page.locator('[data-name="leaf-p"]').screenshot({ path: path.join(__dirname, 'selected.png') });
    await page.screenshot({ path: path.join(__dirname, 'comparison.png'), fullPage: true });
    await page.getByRole('button', { name: '深色背景' }).click();
    await page.screenshot({ path: path.join(__dirname, 'comparison-dark.png'), fullPage: true });
    console.log('Exported comparison.png and comparison-dark.png');
  } finally {
    await browser.close();
  }
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
