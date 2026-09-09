const { chromium } = require('playwright');
const OUT = 'C:\\Users\\fati\\AppData\\Local\\Temp\\claude\\c--Users-fati-Favorites-Downloads-perftest-frontend-updated--9-\\9f8c68d7-740b-4185-8f1d-50c396f36707\\scratchpad';

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

  await page.goto('http://localhost:3000/login', { waitUntil: 'load' });
  await page.click('text=Testeur >> nth=0');
  await page.click('button[type="submit"]');
  await page.waitForSelector('text=Dashboard', { timeout: 10000 });

  await page.goto('http://localhost:3000/scenarios', { waitUntil: 'load' });
  await page.waitForTimeout(700);
  await page.locator('tr', { hasText: 'login scenario' }).first().locator('button[title="Exécuter le scénario"]').click();
  await page.waitForTimeout(500);
  const numberInputs = page.locator('input[type="number"]');
  await numberInputs.nth(0).fill('1');
  await numberInputs.nth(1).fill('5');
  await page.click('button:has-text("Suivant")');
  await page.waitForTimeout(300);
  await page.click('button:has-text("Lancer le test")');
  await page.waitForTimeout(6000);

  await page.goto('http://localhost:3000/metriques', { waitUntil: 'load' });
  await page.waitForTimeout(700);
  const selects = page.locator('select');
  await selects.nth(1).selectOption({ index: 1 });
  await page.waitForTimeout(300);
  await selects.nth(2).selectOption({ index: 1 });
  await page.waitForTimeout(300);
  await selects.nth(3).selectOption({ index: 1 });
  await page.waitForTimeout(500);
  const body = await page.textContent('body');
  console.log('Has "CPU moyen":', body.includes('CPU moyen'));
  await page.screenshot({ path: `${OUT}/METRIQUES-CPU-final2.png`, fullPage: true });

  await browser.close();
})().catch((e) => { console.error('SCRIPT ERROR', e); process.exit(1); });
