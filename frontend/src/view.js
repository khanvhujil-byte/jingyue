import { icon } from './icons.js';

export const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
export function imageUrl(value, fallback = './assets/valley.svg') {
  const first = String(value || '').split(',')[0].trim();
  if (/^https?:\/\//i.test(first) || /^\.?\/?(?:assets|imgs)\/[\w./%-]+$/i.test(first)) return first;
  return fallback;
}
export const price = value => (Number(value || 0) / 100).toFixed(2).replace(/\.00$/, '');
export const money = value => `<span class="currency">¥</span>${price(value)}`;
export const cover = (value, label, cls = '') => `<img class="${cls}" src="${esc(imageUrl(value))}" alt="${esc(label)}" loading="lazy">`;
export const routeLink = (path, label, cls = '') => `<a href="#${path}" class="${cls}">${label}</a>`;
export const empty = (title, text, action = '') => `<div class="empty-state">${icon('mountain')}<h3>${esc(title)}</h3><p>${esc(text)}</p>${action}</div>`;
export function sectionHead(kicker, title, action = '') { return `<div class="section-head"><div><span class="eyebrow">${kicker}</span><h2>${title}</h2></div>${action}</div>`; }
export function placeCard(p) {
  return `<article class="place-card"><a href="#/place/${encodeURIComponent(p.id)}" class="place-cover">${cover(p.images, p.name)}<span class="image-label">${esc(p.tag || '周末好去处')}</span><span class="corner-arrow">${icon('arrow')}</span></a><div class="card-meta"><span>${icon('pin')}${esc(p.area || '目的地')}</span></div><h3>${routeLink(`/place/${p.id}`, esc(p.name))}</h3><div class="card-bottom"><span>${esc(p.openHours || '开放时间以场馆公告为准')}</span><strong>¥${esc(p.avgPrice || '—')}<small>参考价</small></strong></div></article>`;
}
export function journalCard(b) {
  return `<article class="journal-card"><a href="#/journal/${encodeURIComponent(b.id)}" class="journal-cover">${cover(b.images, b.title)}<span class="journal-tag">旅途手记</span></a><div class="journal-body"><h3>${routeLink(`/journal/${b.id}`, esc(b.title))}</h3><div class="journal-author"><span class="avatar small">${esc((b.name || '旅人').slice(0, 1))}</span><span>${esc(b.name || '景约旅人')}</span><button class="like-button ${b.isLike ? 'is-liked' : ''}" data-action="like" data-id="${esc(b.id)}" aria-label="${b.isLike ? '取消点赞' : '点赞'}" aria-pressed="${!!b.isLike}" ${b.likePending ? 'disabled' : ''}>${icon('heart')}<span>${Number(b.liked) || 0}</span></button></div></div></article>`;
}
export function orderLabel(state) {
  return ({ SUBMITTING: '正在提交', PENDING: '排队处理中', UNKNOWN: '正在确认结果', WAITING: '结果待确认', CREATED: '订单已创建' })[state] || '查询处理结果';
}
export function ticketAvailability(t, now = Date.now()) {
  if (t.status != null && Number(t.status) !== 1) return { disabled: true, text: '暂未开放抢购' };
  if (Number(t.type) !== 1) return { disabled: true, text: '暂未开放抢购' };
  const begin = new Date(t.beginTime).getTime(), end = new Date(t.endTime).getTime();
  if (!Number.isFinite(begin) || !Number.isFinite(end)) return { disabled: true, text: '活动时间待公布' };
  if (now < begin) return { disabled: true, text: '尚未开抢' };
  if (now > end) return { disabled: true, text: '活动已结束' };
  if (!(Number(t.stock) > 0)) return { disabled: true, text: '名额已抢完' };
  return { disabled: false, text: '立即抢购' };
}
export function ticketCard(t, place, record) {
  const availability = ticketAvailability(t);
  const button = record ? orderLabel(record.state) : availability.text;
  const hint = record?.state === 'CREATED' ? `订单号：${record.orderId}` : record ? '已提交的请求请勿重复抢购，可前往票夹查询。' : '每位用户限购一份 · 指定活动票种';
  const period = value => { const d = new Date(value); return Number.isFinite(d.getTime()) ? d.toLocaleDateString('zh-CN') : '待公布'; };
  return `<article class="ticket-card"><div class="ticket-main"><span class="pill amber">限量活动票</span><h3>${esc(t.title)}</h3><p>${esc(t.subTitle || place.name)}</p><div class="ticket-price">${money(t.payValue)}<del>¥${price(t.actualValue)}</del></div><div class="ticket-period">${icon('clock')}抢购期 ${period(t.beginTime)}—${period(t.endTime)}</div></div><div class="ticket-cut"></div><div class="ticket-action"><span class="quota">${t.stock == null ? '配额待更新' : `参考余量 ${esc(t.stock)} 份`}</span><button class="button primary" data-action="buy" data-id="${esc(t.id)}" ${record || availability.disabled ? 'disabled' : ''}>${esc(button)}${!record && !availability.disabled ? icon('arrow') : ''}</button><small role="status">${esc(hint)}</small>${record ? routeLink('/orders', '查看处理结果 →', 'text-link') : ''}</div></article>`;
}
