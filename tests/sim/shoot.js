// 가짜 배달앱 화면(fixtures/screens.html)을 폰 크기로 캡처한다. 실행: node tests/sim/shoot.js <출력폴더>
const { chromium, devices } = require('playwright');
const path = require('path');
const out = process.argv[2] || path.join(__dirname, 'out');
require('fs').mkdirSync(out, { recursive: true });
(async () => {
  const b = await chromium.launch();
  const ctx = await b.newContext({ ...devices['Pixel 7'], locale: 'zh-CN' });
  const p = await ctx.newPage();
  await p.goto('file://' + path.join(__dirname, 'fixtures', 'screens.html'));
  await p.waitForTimeout(500);
  const ids = await p.$$eval('section.screen', els => els.map(e => e.id));
  for (const id of ids) {
    const el = await p.$('#' + id);
    await el.screenshot({ path: path.join(out, `${id}.png`) });
  }
  console.log(ids.join(' '));
  await b.close();
})();
