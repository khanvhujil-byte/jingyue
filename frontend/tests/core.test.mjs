import test from 'node:test';
import assert from 'node:assert/strict';
import { parseJsonExact, request } from '../src/api.js';
import { ticketAvailability, imageUrl, esc } from '../src/view.js';
import { storage } from '../src/storage.js';
import { OrderTracker } from '../src/orders.js';
import { api } from '../src/api.js';

const values = new Map();
globalThis.sessionStorage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) };
globalThis.window = new EventTarget();

test('JSON parser preserves unsafe integer IDs without touching strings or decimals', () => {
  const data = parseJsonExact('{"id":9007199254740993,"safe":123,"negative":-9007199254740993,"s":"订单9007199254740993\\\"","decimal":1.23,"exp":1e3}');
  assert.equal(data.id, '9007199254740993'); assert.equal(data.negative, '-9007199254740993');
  assert.equal(data.safe, 123); assert.equal(data.s, '订单9007199254740993"'); assert.equal(data.decimal, 1.23); assert.equal(data.exp, 1000);
});
test('escape and image source validation reject markup and executable protocols', () => {
  assert.equal(esc('<img onerror="x">'), '&lt;img onerror=&quot;x&quot;&gt;');
  assert.equal(imageUrl('javascript:alert(1)'), './assets/valley.svg');
  assert.equal(imageUrl('data:image/svg+xml,test'), './assets/valley.svg');
  assert.equal(imageUrl('./assets/coast.svg'), './assets/coast.svg');
});
test('ticket controls respect type, time, missing stock, and sold out', () => {
  const t = { type: 1, stock: 10, beginTime: '2026-01-01', endTime: '2027-01-01' }, now = new Date('2026-10-20').getTime();
  assert.equal(ticketAvailability(t, now).disabled, false);
  assert.equal(ticketAvailability({ ...t, stock: 0 }, now).text, '名额已抢完');
  assert.equal(ticketAvailability({ ...t, beginTime: '2026-12-01' }, now).text, '尚未开抢');
  assert.equal(ticketAvailability({ ...t, endTime: '2026-05-01' }, now).text, '活动已结束');
  assert.equal(ticketAvailability({ ...t, beginTime: null, endTime: null }, now).disabled, true);
  assert.equal(ticketAvailability({ ...t, type: 0 }, now).disabled, true);
  assert.equal(ticketAvailability({ ...t, status: 2 }, now).disabled, true);
});
test('live API sends authorization, preserves IDs and does not substitute demo data', async () => {
  storage.set('shanhai:mode', 'live'); storage.set('shanhai:live:token', 'test-live');
  const original = globalThis.fetch;
  try {
    globalThis.fetch = async (url, options) => {
      assert.equal(url, './api/voucher-order/seckill/1'); assert.equal(options.headers.authorization, 'test-live');
      return { ok: true, status: 200, text: async () => '{"success":true,"data":9007199254740993}' };
    };
    assert.equal(await request('/voucher-order/seckill/1', { method: 'POST' }), '9007199254740993');
    globalThis.fetch = async () => { throw new Error('network'); };
    await assert.rejects(request('/shop/of/name'), /连接失败/);
  } finally { globalThis.fetch = original; }
});
test('generic server failure is uncertain; known rejection and HTTP429 are definitive', async () => {
  const original = globalThis.fetch;
  try {
    for (const [message, status, definitive] of [['服务器繁忙，请稍后重试', 200, false], ['库存不足', 200, true], ['操作过于频繁', 429, true]]) {
      globalThis.fetch = async () => ({ ok: status === 200, status, text: async () => JSON.stringify({ success: false, errorMsg: message }) });
      await assert.rejects(request('/voucher-order/seckill/1', { method: 'POST' }), error => error.definitive === definitive);
    }
  } finally { globalThis.fetch = original; }
});
test('401 clears only the current-mode credential and notifies UI', async () => {
  const original = globalThis.fetch; let events = 0;
  const listener = () => events++; window.addEventListener('session-expired', listener);
  storage.set('shanhai:demo:token', 'test-demo');
  try {
    globalThis.fetch = async () => ({ status: 401 });
    await assert.rejects(request('/user/me'), error => error.status === 401);
    assert.equal(storage.get('shanhai:live:token'), null); assert.equal(storage.get('shanhai:demo:token'), 'test-demo'); assert.equal(events, 1);
  } finally { globalThis.fetch = original; window.removeEventListener('session-expired', listener); }
});
test('order tracker persists before POST and prevents concurrent or uncertain reposts', async () => {
  const original = api.buy, originalOrder = api.order; let resolve, posts = 0;
  api.buy = () => { posts++; return new Promise(r => resolve = r); };
  api.order = async () => ({ state: 'PENDING' });
  const tracker = new OrderTracker('user-a', () => {}), ticket = { id: 99, title: '票', payValue: 100 }, place = { id: 88, name: '景点' };
  try {
    const running = tracker.buy(ticket, place);
    assert.equal(storage.get(tracker.key)[0].state, 'SUBMITTING');
    await tracker.buy(ticket, place); assert.equal(posts, 1);
    resolve('9007199254740993'); await running;
    assert.equal(tracker.get(99).state, 'PENDING'); assert.equal(tracker.get(99).orderId, undefined);
    await tracker.buy(ticket, place); assert.equal(posts, 1);
  } finally { tracker.stop(); api.buy = original; api.order = originalOrder; }
});
test('definitive rejection removes draft; uncertain submission retains recoverable record', async () => {
  const original = api.buy, originalOrder = api.order;
  api.order = async () => ({ state: 'UNKNOWN' });
  const tracker = new OrderTracker('user-b', () => {}), p = { id: 88, name: '景点' };
  try {
    api.buy = async () => { throw Object.assign(new Error('库存不足'), { definitive: true }); };
    await tracker.buy({ id: 100 }, p); assert.equal(tracker.get(100), undefined);
    api.buy = async () => { throw new Error('网络断开'); };
    await tracker.buy({ id: 101 }, p); assert.equal(tracker.get(101).state, 'UNKNOWN');
  } finally { tracker.stop(); api.buy = original; api.order = originalOrder; }
});

test('committed orders are checked immediately; manual refresh is GET only', async () => {
  storage.set('shanhai:mode', 'live');
  const originalBuy = api.buy, originalOrder = api.order;
  let posts = 0, queries = 0;
  api.buy = async () => { posts++; return '9007199254740993'; };
  api.order = async () => { queries++; return { state: 'CREATED', orderId: '9007199254740993' }; };
  const tracker = new OrderTracker('fast-user', () => {});
  try {
    await tracker.buy({ id: 102, title: '票', payValue: 100 }, { id: 88, name: '景点' });
    assert.equal(queries, 1);
    assert.equal(tracker.get(102).state, 'CREATED');
    assert.equal(tracker.get(102).orderId, '9007199254740993');
    tracker.refresh(102);
    await Promise.resolve();
    assert.equal(queries, 2); assert.equal(posts, 1);
  } finally { tracker.stop(); api.buy = originalBuy; api.order = originalOrder; }
});

test('pending results back off after the immediate read without repeating POST', async t => {
  storage.set('shanhai:mode', 'live');
  t.mock.timers.enable({ apis: ['setTimeout'] });
  t.mock.method(Math, 'random', () => 0);
  const originalBuy = api.buy, originalOrder = api.order;
  let posts = 0, queries = 0;
  api.buy = async () => { posts++; return '9007199254740993'; };
  api.order = async () => { queries++; return { state: 'PENDING' }; };
  const tracker = new OrderTracker('pending-user', () => {});
  const tick = async ms => { t.mock.timers.tick(ms); await Promise.resolve(); };
  try {
    await tracker.buy({ id: 103 }, { id: 88 });
    assert.equal(queries, 1); assert.equal(tracker.get(103).state, 'PENDING');
    tracker.refresh(103); assert.equal(queries, 1);
    await tick(999); assert.equal(queries, 1);
    await tick(1); assert.equal(queries, 2);
    await tick(1999); assert.equal(queries, 2);
    await tick(1); assert.equal(queries, 3);
    await tick(4000); assert.equal(queries, 4);
    await tick(5000); assert.equal(queries, 5); assert.equal(posts, 1);
    tracker.stop();
    await tick(5000); assert.equal(queries, 5);
  } finally { tracker.stop(); api.buy = originalBuy; api.order = originalOrder; }
});
test('order records are scoped by current account and mode', () => {
  storage.set('shanhai:mode', 'demo');
  const demo = new OrderTracker('user-a', () => {}), other = new OrderTracker('user-c', () => {});
  storage.set('shanhai:mode', 'live'); const live = new OrderTracker('user-a', () => {});
  assert.notEqual(demo.key, other.key); assert.notEqual(demo.key, live.key);
  demo.stop(); other.stop(); live.stop();
});
