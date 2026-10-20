import { categories, places, tickets, journals } from './data.js';
import { storage, token } from './storage.js';

const copy = value => structuredClone(value);
function state() { return storage.get('shanhai:demo:data', { likes: {}, orders: {}, extra: [], following: {}, signed: false }); }
function save(data) { storage.set('shanhai:demo:data', data); }
const me = { id: 95999, nickName: '山海体验官', icon: '' };
export async function demoRequest(path, { method = 'GET', body, signal } = {}) {
  // Demo data is local. Do not manufacture network or queue latency.
  if (signal?.aborted) throw new DOMException('页面已离开', 'AbortError');
  const url = new URL(path, 'https://demo.invalid'), p = url.pathname, data = state();
  const needLogin = () => { if (!token()) throw Object.assign(new Error('请先登录'), { status: 401, definitive: true }); };
  const decorate = journal => ({ ...copy(journal), isLike: !!data.likes[journal.id], liked: journal.liked + (data.likes[journal.id] ? 1 : 0) });
  const allJournals = [...data.extra, ...journals];
  if (p === '/user/code') return null;
  if (p === '/user/login') { if (body.code !== '123456') throw new Error('体验验证码为 123456'); return 'demo-session'; }
  if (p === '/user/me') { needLogin(); return me; }
  if (p === '/shop-type/list') return copy(categories);
  if (p === '/shop/of/name') return Number(url.searchParams.get('current')) > 1 ? [] : copy(places.filter(x => x.name.includes(url.searchParams.get('name') || '')));
  if (p === '/shop/of/type') return Number(url.searchParams.get('current')) > 1 ? [] : copy(places.filter(x => String(x.typeId) === url.searchParams.get('typeId')));
  if (/^\/shop\/\d+$/.test(p)) { const found = places.find(x => String(x.id) === p.split('/').pop()); if (!found) throw new Error('没有找到这个景点'); return copy(found); }
  if (p.startsWith('/voucher/list/')) return copy(tickets.filter(x => String(x.shopId) === p.split('/').pop()).map(x => ({ ...x, stock: x.stock - (data.orders[x.id] ? 1 : 0) })));
  if (p === '/blog/hot') return Number(url.searchParams.get('current')) > 1 ? [] : allJournals.map(decorate);
  if (p === '/blog/of/me') { needLogin(); return data.extra.map(decorate); }
  if (p === '/blog/of/follow') { needLogin(); return { list: allJournals.filter(x => data.following[x.userId]).map(decorate), offset: 0, minTime: Date.now() }; }
  if (p.startsWith('/blog/likes/')) return data.likes[p.split('/').pop()] ? [me] : [];
  if (p.startsWith('/blog/like/')) { needLogin(); data.likes[p.split('/').pop()] = url.searchParams.get('liked') === 'true'; save(data); return null; }
  if (/^\/blog\/\d+$/.test(p)) { const found = allJournals.find(x => String(x.id) === p.split('/').pop()); if (!found) throw new Error('游记不存在'); return decorate(found); }
  if (p === '/blog' && method === 'POST') { needLogin(); const id = Date.now(); data.extra.unshift({ ...body, id, userId: me.id, name: me.nickName, liked: 0 }); save(data); return id; }
  if (p.startsWith('/voucher-order/seckill/')) {
    needLogin(); const id = p.split('/').pop();
    if (data.orders[id]) throw Object.assign(new Error('重复下单'), { definitive: true });
    if (!tickets.some(x => String(x.id) === id)) throw Object.assign(new Error('票种不存在'), { definitive: true });
    data.orders[id] = { orderId: `900719925474${String(Date.now()).slice(-5)}` }; save(data); return data.orders[id].orderId;
  }
  if (p.startsWith('/voucher-order/status/')) {
    needLogin(); const order = data.orders[p.split('/').pop()];
    return !order ? { state: 'UNKNOWN' } : { state: 'CREATED', orderId: order.orderId };
  }
  if (p.startsWith('/follow/or/not/')) { needLogin(); return !!data.following[p.split('/').pop()]; }
  if (p.startsWith('/follow/')) { needLogin(); data.following[p.split('/')[2]] = p.endsWith('/true'); save(data); return null; }
  if (p === '/user/sign') { needLogin(); data.signed = true; save(data); return null; }
  if (p === '/user/sign/count') { needLogin(); return data.signed ? 1 : 0; }
  throw new Error('体验模式未提供此操作');
}
