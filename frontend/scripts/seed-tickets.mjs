import { tickets, places } from '../src/data.js';
const origin = new URL(process.env.API_TARGET || 'http://127.0.0.1:8081');
if (!['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname)) throw new Error('示例初始化只允许本机开发服务');
const headers = { 'Content-Type': 'application/json' };
if (process.env.HMDP_TOKEN) headers.authorization = process.env.HMDP_TOKEN;
async function call(path, options = {}) {
  const response = await fetch(new URL(path, origin), { ...options, headers });
  const result = await response.json();
  if (!response.ok || !result.success) throw new Error(result.errorMsg || `接口失败：${path}`);
  return result.data;
}
for (const place of places) {
  const found = await call(`/shop/${place.id}`);
  if (found?.name !== place.name) throw new Error(`目的地 ${place.id} 不是本项目示例数据，请先在独立数据库导入 shanhai-bootstrap.sql`);
}
for (const ticket of tickets) {
  const existing = await call(`/voucher/list/${ticket.shopId}`);
  const prior = existing.find(x => String(x.id) === String(ticket.id));
  if (prior) {
    if (prior.title !== ticket.title) throw new Error(`票种编号 ${ticket.id} 存在其他数据，停止初始化`);
    console.log(`票种 ${ticket.id} 已存在，不覆盖库存，也不重新预热。`); continue;
  }
  await call('/voucher/seckill', { method: 'POST', body: JSON.stringify(ticket) });
  console.log(`已创建 ${ticket.title}；由后端 afterCommit 预热库存。`);
}
console.log('完成。优惠券列表存在缓存；若第一次查询缓存为空或陈旧，等待配置的缓存过期后再刷新。');
