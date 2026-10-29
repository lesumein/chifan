// 사전 데이터 검사 + 외부 주소 사용 검사. 실행: node tests/check-data.js
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..');
const read = p => JSON.parse(fs.readFileSync(path.join(root, p), 'utf8'));
const cats = read('data/categories.json'), foods = read('data/foods.json'), req = read('data/requests.json'), gloss = read('data/glossary.json');
const errs = [];
const catIds = new Set(cats.map(c => c.id));
const ids = new Set();
for (const f of foods) {
  for (const k of ['id', 'cat', 'ko', 'zh', 'py', 'desc', 'emoji']) if (!f[k]) errs.push(`${f.id}: ${k} 없음`);
  if (ids.has(f.id)) errs.push(`id 중복: ${f.id}`);
  ids.add(f.id);
  if (!/^[a-z0-9-]+$/.test(f.id)) errs.push(`id 형식: ${f.id}`);
  if (!catIds.has(f.cat)) errs.push(`${f.id}: 없는 카테고리 ${f.cat}`);
  if (![0, 1, 2, 3].includes(f.spicy)) errs.push(`${f.id}: spicy는 0~3`);
  if (!/^[㐀-鿿，]+$/.test(f.zh)) errs.push(`${f.id}: zh에 한자 외 문자 "${f.zh}"`);
  if (f.img && !fs.existsSync(path.join(root, 'img', f.id + '.jpg'))) errs.push(`${f.id}: img/${f.id}.jpg 없음`);
}
const zh = new Map();
for (const f of foods) { if (zh.has(f.zh)) errs.push(`zh 중복: ${f.zh} (${zh.get(f.zh)}, ${f.id})`); zh.set(f.zh, f.id); }
const gids = new Set(req.groups.map(g => g.id)), rids = new Set();
for (const r of req.items) {
  if (!gids.has(r.group)) errs.push(`요청사항 ${r.id}: 없는 그룹`);
  if (rids.has(r.id)) errs.push(`요청사항 id 중복: ${r.id}`);
  rids.add(r.id);
}
const ggids = new Set(gloss.groups.map(g => g.id)), gzh = new Set();
for (const g of gloss.items) {
  if (!g.zh || !g.ko) errs.push(`단어장: 빈 칸 ${JSON.stringify(g)}`);
  if (!ggids.has(g.group)) errs.push(`단어장 ${g.zh}: 없는 그룹 ${g.group}`);
  if (gzh.has(g.zh)) errs.push(`단어장 중복: ${g.zh}`);
  gzh.add(g.zh);
}
const sw = fs.readFileSync(path.join(root, 'sw.js'), 'utf8');
for (const f of ['data/categories.json', 'data/foods.json', 'data/requests.json', 'data/glossary.json', 'data/hanja.json'])
  if (!sw.includes(`'${f}'`)) errs.push(`sw.js 캐시 목록에 ${f} 없음`);
if (foods.length < 100 || foods.length > 200) errs.push(`음식 개수 ${foods.length} (100~200 목표)`);
for (const c of cats) if (!foods.some(f => f.cat === c.id)) errs.push(`빈 카테고리 ${c.id}`);

// 외부 주소: 배달 H5·스토어 주소(config.js) 말고는 없어야 함 (중국에서 막히는 CDN·폰트 방지)
const allow = [/^https:\/\/h5\.waimai\.meituan\.com\//, /^https:\/\/play\.google\.com\/store\/apps\/details/, /^https:\/\/apps\.apple\.com\/cn\/app\//, /^http:\/\/www\.w3\.org\/2000\/svg/];
const walk = d => fs.readdirSync(d, { withFileTypes: true }).flatMap(e =>
  e.name.startsWith('.') || ['tests', 'node_modules', 'screenshots', 'android', 'apk'].includes(e.name) || e.name === 'hanja.LICENSE.txt' ? [] :
  e.isDirectory() ? walk(path.join(d, e.name)) : [path.join(d, e.name)]);
for (const file of walk(root).filter(f => /\.(html|css|js|json|webmanifest|svg)$/.test(f))) {
  const text = fs.readFileSync(file, 'utf8');
  for (const m of text.matchAll(/https?:\/\/[^\s'"`)<>]+/g)) {
    if (!allow.some(a => a.test(m[0]))) errs.push(`외부 주소 ${path.relative(root, file)}: ${m[0]}`);
  }
}

const byCat = Object.fromEntries(cats.map(c => [c.id, foods.filter(f => f.cat === c.id).length]));
console.log(`음식 ${foods.length}개`, byCat, `/ 검증 필요 ${foods.filter(f => f.check).length}개 / 요청사항 ${req.items.length}개 / 단어장 ${gloss.items.length}개`);
if (errs.length) { console.error(errs.join('\n')); process.exit(1); }
console.log('OK');
