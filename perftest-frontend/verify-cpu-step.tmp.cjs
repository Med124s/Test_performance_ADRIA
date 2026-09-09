const { chromium } = require('playwright');
const OUT = 'C:\\Users\\fati\\AppData\\Local\\Temp\\claude\\c--Users-fati-Favorites-Downloads-perftest-frontend-updated--9-\\9f8c68d7-740b-4185-8f1d-50c396f36707\\scratchpad';

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

  await page.goto('http://localhost:3000/login', { waitUntil: 'load' });
  await page.click('text=Testeur >> nth=0');
  await page.click('button[type="submit"]');
  await page.waitForSelector('text=Dashboard', { timeout: 10000 });

  await page.goto('http://localhost:3000/scenarios?q=parcours%20bancaire%20complet', { waitUntil: 'load' });
  await page.waitForTimeout(600);
  const row = page.locator('tr', { hasText: 'parcours bancaire complet' }).first();
  await row.locator('button[title="Exécuter le scénario"]').click();
  await page.waitForTimeout(500);
  await page.click('button:has-text("Suivant")');
  await page.waitForTimeout(300);
  await page.click('button:has-text("Lancer le test")');
  await page.waitForSelector('text=Exécution terminée', { timeout: 20000 });
  await page.waitForTimeout(1500);

  await browser.close();
})().catch((e) => { console.error('SCRIPT ERROR', e); process.exit(1); });
