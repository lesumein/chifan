// icons/*.svg → PNG (홈 화면 아이콘용). 실행: node tests/make-icons.js
const { chromium } = require('playwright');
const fs = require('fs'), path = require('path');
const dir = path.join(__dirname, '..', 'icons');
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  for (const [src, out, size] of [
    ['icon.svg', 'icon-192.png', 192], ['icon.svg', 'icon-512.png', 512],
    ['icon-maskable.svg', 'icon-maskable-512.png', 512]]) {
    const svg = fs.readFileSync(path.join(dir, src), 'utf8');
    await page.setViewportSize({ width: size, height: size });
    await page.setContent(`<style>html,body{margin:0;background:transparent}svg{display:block;width:${size}px;height:${size}px}</style>${svg}`);
    await page.locator('svg').screenshot({ path: path.join(dir, out), omitBackground: true });
  }
  await browser.close();
})();
