// Optional browser QA: install Playwright or set PLAYWRIGHT_MODULE_PATH to its entry point.
import assert from 'node:assert/strict';
import { mkdir, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
const modulePath = process.env.PLAYWRIGHT_MODULE_PATH;
const { chromium } = await import(modulePath ? pathToFileURL(modulePath).href : 'playwright');
const executablePath = process.env.BROWSER_EXECUTABLE;
const browser = await chromium.launch({ headless: true, ...(executablePath ? { executablePath } : {}) });
const base = process.env.TEST_BASE_URL || 'http://127.0.0.1:5188';
await mkdir(new URL('../test-results/', import.meta.url), { recursive: true });
const screenshot = name => new URL(`../test-results/${name}.png`, import.meta.url).pathname.replace(/^\/([A-Z]:)/, '$1');
const reports = [];
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
  const page = await context.newPage(), errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(base);
  await page.getByRole('heading', { name: /把周末/, level: 1 }).waitFor();
  await page.screenshot({ path: screenshot('desktop-home'), fullPage: true });
  assert.equal(await page.locator('.place-card').count(), 4);
  await page.locator('#search-form input').fill('白屿');
  await page.locator('#search-form button').click();
  await page.getByRole('heading', { name: '探索目的地。' }).waitFor();
  await page.waitForFunction(() => document.querySelectorAll('.place-card').length === 1);
  assert.match(await page.locator('.place-card').textContent(), /白屿/);
  await page.getByRole('button', { name: '山野自然', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('.place-card h3')?.textContent === '青岚山谷');
  await page.locator('.place-card h3 a').click();
  await page.getByRole('heading', { name: '限量活动票', exact: true }).waitFor();
  await page.getByRole('button', { name: /立即抢购/ }).click();
  await page.getByRole('heading', { name: '登录，开启下一段旅程' }).waitFor();
  await page.getByLabel('手机号码', { exact: true }).fill('19900000000');
  await page.getByLabel('验证码', { exact: true }).fill('123456');
  await page.locator('#login-form [type="submit"]').click();
  await page.getByRole('heading', { name: '限量活动票', exact: true }).waitFor();
  await page.evaluate(() => {
    document.querySelector('[data-action="buy"]').addEventListener('click', () => {
      const start = performance.now();
      const observer = new MutationObserver(() => {
        if (document.querySelector('[data-action="buy"]')?.textContent.includes('订单已创建')) {
          window.demoOrderLatency = performance.now() - start;
          observer.disconnect();
        }
      });
      observer.observe(document.querySelector('#app'), { childList: true, subtree: true, characterData: true });
    }, { once: true });
  });
  await page.getByRole('button', { name: /立即抢购/ }).click();
  // Local demo completes without an artificial queue or a mandatory spinner dwell.
  await page.getByRole('button', { name: '订单已创建', exact: true }).waitFor({ timeout: 1500 });
  const demoOrderLatency = await page.evaluate(() => window.demoOrderLatency);
  assert(demoOrderLatency < 1000, `local demo imposed unnecessary delay: ${demoOrderLatency}ms`);
  reports.push(`体验下单点击至结果：${demoOrderLatency.toFixed(1)}ms（本地模拟，非真实后端性能）`);
  await page.locator('.desktop-nav a[href="#/orders"]').click();
  await page.getByRole('heading', { name: '我的票夹。' }).waitFor();
  assert.match(await page.locator('.order-id').textContent(), /900719925474\d{5}/);
  await page.screenshot({ path: screenshot('desktop-orders'), fullPage: true });
  await page.locator('.desktop-nav a[href="#/journals"]').click();
  await page.locator('.journal-card').first().waitFor();
  const like = page.locator('.like-button').first();
  await like.click(); assert.equal(await like.getAttribute('aria-pressed'), 'true');
  await page.waitForFunction(() => !document.querySelector('.like-button').disabled);
  await like.click(); assert.equal(await like.getAttribute('aria-pressed'), 'false');
  await page.locator('.journal-card h3 a').first().click();
  await page.getByRole('button', { name: '关注旅人', exact: true }).click();
  await page.getByRole('button', { name: '已关注', exact: true }).waitFor();
  await page.locator('.header-actions a[href="#/publish"]').click();
  await page.getByLabel('手记标题').fill('我的山海测试手记');
  await page.getByLabel('关联目的地').selectOption('92001');
  await page.getByLabel('旅途记录').fill('山风很好。<script>window.untrusted=true</script>');
  await page.locator('#publish-form button[type="submit"]').click();
  await page.getByRole('heading', { name: '我的山海测试手记', exact: true }).waitFor();
  assert.equal(await page.evaluate(() => window.untrusted), undefined);
  assert.match(await page.locator('.article-text').textContent(), /<script>/);
  await page.goto(base + '/#/profile');
  await page.getByRole('button', { name: /旅人签到/ }).click();
  await page.getByText('本月连续签到 1 天', { exact: true }).waitFor();
  reports.push('体验流程：搜索、分类、登录回跳、提交/排队/成功、精确订单号、点赞取消、关注、发布、XSS转义、签到');
  await page.goto(base + '/');
  await page.getByRole('heading', { name: /把周末/, level: 1 }).waitFor();
  await page.getByRole('button', { name: /连接真实服务/ }).click();
  await page.getByRole('heading', { name: '这次连接没有成功' }).waitFor();
  assert.equal(await page.locator('.place-card').count(), 0);
  reports.push('真实模式断连显示错误，不回退演示数据');
  await page.getByRole('button', { name: /切换体验模式/ }).click();
  await page.getByRole('heading', { name: /把周末/, level: 1 }).waitFor();
  for (const width of [390, 360, 768]) {
    await page.setViewportSize({ width, height: 844 });
    await page.goto(base);
    await page.getByRole('heading', { name: /把周末/, level: 1 }).waitFor();
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `horizontal overflow at ${width}`);
    await page.screenshot({ path: screenshot(`home-${width}`), fullPage: true });
    await page.goto(base + '/#/place/92001');
    await page.getByRole('heading', { name: '限量活动票', exact: true }).waitFor();
    assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `detail overflow at ${width}`);
    if (width === 390) await page.screenshot({ path: screenshot('mobile-detail'), fullPage: true });
  }
  assert.deepEqual(errors, []);
  reports.push('1440/768/390/360 响应式页面与零脚本错误');
  await writeFile(new URL('../test-results/browser.json', import.meta.url), JSON.stringify({ passed: true, scenarios: reports, errors }, null, 2));
  console.log('PASS:', reports.join('\n'));
  await context.close();
} finally { await browser.close(); }
