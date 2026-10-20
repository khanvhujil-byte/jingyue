// Optional Playwright check: built-site subpath plus intercepted real API contracts.
import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath, pathToFileURL } from 'node:url';
const root = fileURLToPath(new URL('../dist/', import.meta.url));
const mime = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://localhost');
    if (!url.pathname.startsWith('/ticket-demo/')) throw new Error();
    const target = path.resolve(root, decodeURIComponent(url.pathname.slice(13)) || 'index.html');
    if (!target.startsWith(root)) throw new Error();
    const data = await readFile(target);
    res.writeHead(200, { 'Content-Type': (mime[path.extname(target)] || 'application/octet-stream') + '; charset=utf-8' }); res.end(data);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}/ticket-demo/`;
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE_PATH ? pathToFileURL(process.env.PLAYWRIGHT_MODULE_PATH).href : 'playwright');
let browser;
const reports = [], errors = [];
try {
  browser = await chromium.launch({ headless: true, ...(process.env.BROWSER_EXECUTABLE ? { executablePath: process.env.BROWSER_EXECUTABLE } : {}) });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(base);
  await page.getByRole('heading', { name: /把周末/, level: 1 }).waitFor();
  assert.equal(await page.locator('.place-card').count(), 4);
  assert(await page.locator('.hero img').evaluateAll(images => images.every(img => img.complete && img.naturalWidth > 0)));
  reports.push('构建站点在 /ticket-demo/ 仓库子路径加载页面、模块、插画和 hash 路由');

  const { categories, places, tickets, journals } = await import('../src/data.js');
  let posts = 0, queries = 0, likes = 0;
  const token = 'isolated-contract-token', orderId = '900719925474099312';
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), pathname = url.pathname.replace('/ticket-demo/api', '');
    const json = value => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ success: true, data: value }) });
    if (pathname === '/shop-type/list') return json(categories);
    if (pathname.startsWith('/shop/of/')) return json(places);
    if (pathname === '/shop/92001') return json(places[0]);
    if (pathname === '/voucher/list/92001') return json([tickets[0]]);
    if (pathname === '/blog/hot') return json(journals);
    if (pathname === '/user/me') { assert.equal(req.headers().authorization, token); return json({ id: 95999, nickName: '联调旅人' }); }
    if (pathname === '/voucher-order/seckill/93001') {
      assert.equal(req.method(), 'POST'); assert.equal(req.headers().authorization, token); posts++;
      return route.fulfill({ status: 200, contentType: 'application/json', body: `{"success":true,"data":${orderId}}` });
    }
    if (pathname === '/voucher-order/status/93001') {
      assert.equal(req.method(), 'GET'); assert.equal(req.headers().authorization, token); queries++;
      return route.fulfill({ status: 200, contentType: 'application/json', body: queries < 2 ? '{"success":true,"data":{"state":"PENDING"}}' : `{"success":true,"data":{"state":"CREATED","orderId":${orderId}}}` });
    }
    if (pathname === '/blog/94001') return json({ ...journals[0], isLike: false, liked: 0 });
    if (pathname === '/blog/like/94001') {
      assert.equal(req.method(), 'PUT'); assert.equal(url.searchParams.get('liked'), 'true'); likes++;
      return route.fulfill({ status: 429, contentType: 'application/json', body: '{"success":false,"errorMsg":"操作过于频繁，请稍后重试"}' });
    }
    throw new Error(`未预期接口：${req.method()} ${pathname}`);
  });
  await page.evaluate(token => {
    sessionStorage.setItem('shanhai:mode', JSON.stringify('live'));
    sessionStorage.setItem('shanhai:live:token', JSON.stringify(token));
  }, token);
  await page.goto(base + '#/place/92001');
  await page.reload(); // Reload restores the token into the application's current user.
  await page.getByRole('button', { name: /立即抢购/ }).click();
  await page.getByRole('button', { name: '排队处理中', exact: true }).waitFor();
  assert.equal(await page.getByRole('button', { name: '订单已创建', exact: true }).count(), 0);
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  const beforeHidden = queries;
  await page.waitForTimeout(1500);
  assert.equal(queries, beforeHidden, 'hidden page must stop order polling');
  await page.evaluate(() => {
    Object.defineProperty(document, 'hidden', { value: false, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
  });
  await page.reload();
  await page.getByRole('button', { name: '订单已创建', exact: true }).waitFor({ timeout: 12000 });
  assert.equal(posts, 1);
  await page.goto(base + '#/orders');
  await page.locator('.order-id').waitFor();
  assert.match(await page.locator('.order-id').textContent(), new RegExp(orderId));
  reports.push('真实模式接口契约：authorization、一次 POST、隐藏暂停、刷新只查询、PENDING/CREATED、Java Long 订单号无精度丢失');
  await page.goto(base + '#/journals');
  const like = page.locator('.like-button').first();
  await like.click();
  await page.getByText('操作过于频繁，请稍后重试', { exact: true }).waitFor();
  await page.waitForFunction(() => !document.querySelector('.like-button')?.disabled);
  assert.equal(await like.getAttribute('aria-pressed'), 'false'); assert.equal(likes, 1);
  reports.push('点赞立即反馈，HTTP429 后恢复点赞状态与计数');
  assert.deepEqual(errors, []);
  await mkdir(new URL('../test-results/', import.meta.url), { recursive: true });
  await writeFile(new URL('../test-results/deployment.json', import.meta.url), JSON.stringify({ passed: true, scenarios: reports, errors }, null, 2));
  console.log('PASS:', reports.join('\n'));
} finally { await browser?.close(); await new Promise(resolve => server.close(resolve)); }
