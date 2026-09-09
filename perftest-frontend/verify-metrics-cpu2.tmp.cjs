const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });

  await page.goto('http://localhost:3000/login', { waitUntil: 'load' });
  await page.click('text=Testeur >> nth=0');
  await page.click('button[type="submit"]');
  await page.waitForSelector('text=Dashboard', { timeout: 10000 });

  await page.goto('http://localhost:3000/scenarios', { waitUntil: 'load' });
  await page.waitForTimeout(700);
  await page.locator('button[title="Exécuter le scénario"]').first().click();
  await page.waitForTimeout(500);
  const numberInputs = page.locator('input[type="number"]');
  await numberInputs.nth(0).fill('1');
  await numberInputs.nth(1).fill('5');
  await page.click('button:has-text("Suivant")');
  await page.waitForTimeout(300);
  await page.click('button:has-text("Lancer le test")');
  // attend vraiment la fin avant de fermer le navigateur
  await page.waitForSelector('text=Exécution terminée', { timeout: 30000 });
  await page.waitForTimeout(1000);

  await browser.close();
})().catch((e) => { console.error('SCRIPT ERROR', e); process.exit(1); });
