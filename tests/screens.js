// 폰 크기 화면 테스트 + 스크린샷. 실행: node tests/screens.js  (결과: screenshots/)
const { chromium, devices } = require('playwright');
const { spawn } = require('child_process');
const path = require('path'), fs = require('fs'), assert = require('assert');

const root = path.join(__dirname, '..');
const out = path.join(root, 'screenshots');
const PORT = 8123, BASE = `http://127.0.0.1:${PORT}/`;
const H5 = 'https://h5.waimai.meituan.com/waimai/mindex/home';
fs.mkdirSync(out, { recursive: true });

const DEVICES = { pixel7: devices['Pixel 7'], iphone13: devices['iPhone 13'] };

async function waitServer() {
  for (let i = 0; i < 50; i++) {
    try { const r = await fetch(BASE); if (r.ok) return; } catch (e) {}
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error('서버가 안 뜸');
}

async function run(browser, name, dev) {
  const ctx = await browser.newContext({ ...dev, locale: 'ko-KR', serviceWorkers: 'allow' });
  await ctx.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: BASE });
  // 외부(배달앱) 접속은 막아 둔다: 테스트는 우리 화면만 본다
  await ctx.route(/^https?:\/\/(?!127\.0\.0\.1)/, r => r.abort());
  await ctx.addInitScript(() => {
    window.__opened = [];
    window.open = (u) => { window.__opened.push(u); return {}; };
    window.__went = [];
    window.__chifanGo = (u) => { window.__went.push(u); };
  });
  const page = await ctx.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
  const shot = n => page.screenshot({ path: path.join(out, `${name}-${n}.png`) });

  await page.goto(BASE);
  await page.waitForSelector('.card');
  await shot('01-home');

  // 0) 첫 실행: 배달앱 설치 안내 → 스토어 열기 + '美团' 복사, [이미 있어요]로 숨김
  assert.ok(await page.isVisible('#setupCard'));
  await page.click('#installBtn');
  await page.waitForSelector('.toast.is-on');
  assert.strictEqual(await page.evaluate(() => navigator.clipboard.readText()), '美团');
  if (name === 'pixel7') {
    assert.deepStrictEqual(await page.evaluate(() => window.__went), ['market://details?id=com.sankuai.meituan']);
    await page.waitForTimeout(1800); // 스토어 앱이 안 열리면 Play 웹으로
    assert.deepStrictEqual(await page.evaluate(() => window.__went.slice(1)), ['https://play.google.com/store/apps/details?id=com.sankuai.meituan']);
  } else {
    assert.deepStrictEqual(await page.evaluate(() => window.__went), ['https://apps.apple.com/cn/app/id423084029']);
  }
  await shot('00-install');
  await page.click('#setupDone');
  assert.ok(!(await page.isVisible('#setupCard')));

  // 1) 카드 탭 → 자동 복사 + 토스트
  await page.click('[data-cat="soup"]');
  await page.click('.card[data-id="malatang"]');
  await page.waitForSelector('.toast.is-on');
  assert.match(await page.textContent('#toast'), /복사됨: 麻辣烫/);
  assert.strictEqual(await page.evaluate(() => navigator.clipboard.readText()), '麻辣烫');
  await shot('02-copied');

  // 2) 열기: 로드 시 자동 이동 없음. [웹] → H5, [앱으로 열기] → 폰 종류 자동 인식 후 앱 시도, 안 되면 H5
  assert.deepStrictEqual(await page.evaluate(() => window.__opened), [], '로드 시 자동으로 열면 안 됨');
  await page.click('#openWebBtn');
  assert.deepStrictEqual(await page.evaluate(() => window.__opened), [H5]);
  await page.evaluate(() => { window.__went = []; });
  await page.click('#openAppBtn');
  if (name === 'pixel7') {
    // 복사한 검색어(麻辣烫)를 앱 검색 경로로 넘긴다
    assert.deepStrictEqual(await page.evaluate(() => window.__went),
      ['intent://www.meituan.com/search?q=' + encodeURIComponent('麻辣烫') +
       '#Intent;scheme=imeituan;package=com.sankuai.meituan;S.browser_fallback_url=' + encodeURIComponent(H5) + ';end']);
  } else {
    const ios = 'imeituan://www.meituan.com/search?q=' + encodeURIComponent('麻辣烫');
    assert.deepStrictEqual(await page.evaluate(() => window.__went), [ios]);
    await page.waitForTimeout(1800); // 앱이 안 열리면 웹으로
    assert.deepStrictEqual(await page.evaluate(() => window.__went), [ios, H5]);
  }

  // 3) 한국어 검색: 초성 / 부분 / 입력 중
  const search = async q => {
    await page.fill('#q', q);
    await page.waitForTimeout(200);
    return page.$$eval('.card', els => els.map(e => e.dataset.id));
  };
  assert.strictEqual((await search('마라탕'))[0], 'malatang');
  assert.strictEqual((await search('말'))[0], 'malatang');
  assert.ok((await search('ㅁㄹㅌ')).includes('malatang'));
  assert.ok((await search('짜장면')).includes('hanshi-zhajiangmian'));
  assert.ok((await search('짜장면')).includes('zhajiangmian'));
  assert.ok((await search('꿔바')).includes('guobaorou'));
  assert.ok((await search('mala')).includes('malatang'));
  assert.ok((await search('麻辣')).includes('malatang'));
  const r = await search('ㅉㅈㅁ');
  assert.ok(r.includes('zhajiangmian') && r.includes('hanshi-zhajiangmian'));
  await shot('03-search');
  await page.click('#qClear');

  // 4) 상세 시트 + 사진 저장(IndexedDB)
  await page.click('[data-cat="noodle"]');
  await page.click('[data-more="dandanmian"]');
  await page.waitForSelector('.sheet.is-on');
  await page.waitForTimeout(250);
  await shot('04-detail');
  await page.setInputFiles('#photoInput', path.join(root, 'icons', 'icon-512.png'));
  await page.waitForSelector('.card[data-id="dandanmian"] .art.has-photo');
  await page.click('#sheetClose');
  await page.waitForTimeout(250);
  await shot('05-photo');
  await page.reload();
  await page.waitForSelector('.card[data-id="dandanmian"] .art.has-photo'); // 새로고침 뒤에도 남아 있음
  assert.match(await page.textContent('#dockText'), /麻辣烫/, '새로고침 뒤에도 마지막 복사한 검색어 유지');
  assert.ok(!(await page.isVisible('#setupCard')), '설치 안내는 [이미 있어요] 뒤 다시 안 나옴');

  // 5) 요청사항 문구
  await page.click('.tab[data-view="memo"]');
  for (const id of ['less-spicy', 'no-coriander', 'no-cutlery', 'thanks', 'door', 'no-spicy']) await page.click(`[data-req="${id}"]`);
  assert.strictEqual(await page.textContent('#memoZh'), '不要香菜，不需要餐具，放门口就行，不要辣，谢谢');
  await page.click('#memoCopy');
  assert.strictEqual(await page.evaluate(() => navigator.clipboard.readText()), '不要香菜，不需要餐具，放门口就行，不要辣，谢谢');
  await shot('06-memo');
  await page.click('.tab[data-view="menu"]');

  // 6) 정보 시트
  await page.click('#aboutBtn');
  await page.waitForTimeout(250);
  await shot('07-about');
  await page.click('#sheetClose');

  // 7) 오프라인 (서비스워커)
  await page.evaluate(() => navigator.serviceWorker.ready);
  await page.reload(); // SW가 페이지를 잡은 상태로
  await page.waitForSelector('.card');
  await ctx.setOffline(true);
  await page.reload();
  await page.waitForSelector('.card');
  assert.ok((await page.$$('.card')).length > 5, '오프라인에서도 카드가 보여야 함');
  await ctx.setOffline(false);

  assert.deepStrictEqual(errors, [], '콘솔 오류');
  await ctx.close();
  console.log(`✓ ${name}`);
}

async function inApp(browser) {
  const ua = devices['Pixel 7'].userAgent + ' KAKAOTALK 10.0.0';
  const ctx = await browser.newContext({ ...devices['Pixel 7'], userAgent: ua, locale: 'ko-KR' });
  await ctx.route(/^https?:\/\/(?!127\.0\.0\.1)/, r => r.abort());
  const page = await ctx.newPage();
  await page.goto(BASE);
  await page.waitForSelector('.card');
  assert.ok(await page.isVisible('#inappBanner'));
  assert.ok(!(await page.isVisible('#openAppBtn')), '앱 안 브라우저에서는 [앱으로 열기] 숨김');
  await page.click('#setupDone');
  await page.screenshot({ path: path.join(out, 'pixel7-08-inapp.png') });
  await ctx.close();
  console.log('✓ 인앱 브라우저 안내');
}

(async () => {
  const server = spawn('python3', ['-m', 'http.server', String(PORT), '--bind', '127.0.0.1'], { cwd: root, stdio: 'ignore' });
  let code = 0;
  try {
    await waitServer();
    const browser = await chromium.launch();
    for (const [name, dev] of Object.entries(DEVICES)) await run(browser, name, dev);
    await inApp(browser);
    await browser.close();
  } catch (e) {
    console.error(e); code = 1;
  } finally {
    server.kill();
  }
  process.exit(code);
})();
