const { chromium } = require('playwright');
const OUT = 'C:\\Users\\fati\\AppData\\Local\\Temp\\claude\\c--Users-fati-Favorites-Downloads-perftest-frontend-updated--9-\\9f8c68d7-740b-4185-8f1d-50c396f36707\\scratchpad';

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on('pageerror', (e) => console.log('PAGEERROR', e.message));
  page.on('console', (m) => { if (m.type() === 'error') console.log('CONSOLE ERROR', m.text()); });

  await page.goto('http://localhost:3000/login', { waitUntil: 'load' });
  await page.click('text=Testeur >> nth=0');
  await page.click('button[type="submit"]');
  await page.waitForSelector('text=Dashboard', { timeout: 10000 });

  await page.goto('http://localhost:3000/metriques', { waitUntil: 'load' });
  await page.waitForTimeout(1000);
  const body1 = await page.textContent('body');
  console.log('Metriques has "SERVER MONITORING":', body1.includes('SERVER MONITORING'));
  console.log('Metriques has "CPU moyen":', body1.includes('CPU moyen'));
  await page.screenshot({ path: `${OUT}/NOMONITOR-metriques.png`, fullPage: true });

  await page.goto('http://localhost:3000/executions', { waitUntil: 'load' });
  await page.waitForTimeout(700);
  const detailBtn = page.locator('button[title="Voir les détails"]').first();
  await detailBtn.click();
  await page.waitForTimeout(1000);
  const body2 = await page.textContent('body');
  console.log('ExecutionDetail has "SERVER MONITORING":', body2.includes('SERVER MONITORING'));
  await page.screenshot({ path: `${OUT}/NOMONITOR-execdetail.png`, fullPage: true });

  await browser.close();
})().catch((e) => { console.error('SCRIPT ERROR', e); process.exit(1); });
