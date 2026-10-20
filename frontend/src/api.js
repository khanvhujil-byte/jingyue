import { storage, currentMode, token, tokenKey } from './storage.js';
import { demoRequest } from './demo.js';

export class ApiError extends Error {
  constructor(message, options = {}) { super(message); Object.assign(this, options); }
}

// Preserve Java Long IDs without corrupting quoted strings or decimal numbers.
export function parseJsonExact(text) {
  let output = '', i = 0;
  while (i < text.length) {
    if (text[i] === '"') {
      const start = i++;
      while (i < text.length) { if (text[i] === '\\') i += 2; else if (text[i++] === '"') break; }
      output += text.slice(start, i);
    } else if (text[i] === '-' || /[0-9]/.test(text[i])) {
      const match = text.slice(i).match(/^-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/);
      if (!match) { output += text[i++]; continue; }
      const raw = match[0];
      output += /^-?\d+$/.test(raw) && !Number.isSafeInteger(Number(raw)) ? JSON.stringify(raw) : raw;
      i += raw.length;
    } else output += text[i++];
  }
  return JSON.parse(output);
}

export async function request(path, { method = 'GET', body, signal, timeout = 8000 } = {}) {
  if (currentMode() === 'demo') return demoRequest(path, { method, body, signal });
  const control = new AbortController();
  const cancel = () => control.abort();
  if (signal?.aborted) control.abort();
  signal?.addEventListener('abort', cancel, { once: true });
  const timer = setTimeout(cancel, timeout);
  try {
    const headers = {};
    if (token()) headers.authorization = token();
    if (body) headers['Content-Type'] = 'application/json';
    const response = await fetch(`./api${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined, signal: control.signal, cache: 'no-store' });
    if (response.status === 401) {
      storage.remove(tokenKey());
      window.dispatchEvent(new Event('session-expired'));
      throw new ApiError('登录已过期，请重新登录', { status: 401, definitive: true });
    }
    const data = parseJsonExact(await response.text());
    if (!response.ok || !data.success) {
      const message = data.errorMsg || (response.status === 429 ? '操作过于频繁，请稍后再试' : '服务暂时不可用');
      const known = ['库存不足', '重复下单', '秒杀库存尚未就绪，请等待库存核对完成', '下单人数较多，请稍后重试', '操作过于频繁，请稍后重试', '秒杀库存尚未初始化'];
      throw new ApiError(message, { status: response.status, definitive: [401, 429].includes(response.status) || known.includes(message) });
    }
    return data.data;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (signal?.aborted) throw new DOMException('页面已离开', 'AbortError');
    throw new ApiError(control.signal.aborted ? '请求超时，正在确认处理结果' : '连接失败，请检查后端服务', { definitive: false });
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', cancel);
  }
}

export const api = {
  me: () => request('/user/me'),
  categories: () => request('/shop-type/list'),
  places: ({ name = '', typeId = '', page = 1 } = {}, signal) => request(typeId
    ? `/shop/of/type?typeId=${encodeURIComponent(typeId)}&current=${page}`
    : `/shop/of/name?name=${encodeURIComponent(name)}&current=${page}`, { signal }),
  place: (id, signal) => request(`/shop/${encodeURIComponent(id)}`, { signal }),
  tickets: (id, signal) => request(`/voucher/list/${encodeURIComponent(id)}`, { signal }),
  journals: (page = 1, signal) => request(`/blog/hot?current=${page}`, { signal }),
  journal: (id, signal) => request(`/blog/${encodeURIComponent(id)}`, { signal }),
  likers: id => request(`/blog/likes/${encodeURIComponent(id)}`),
  like: (id, liked) => request(`/blog/like/${encodeURIComponent(id)}?liked=${liked}`, { method: 'PUT' }),
  code: phone => request(`/user/code?phone=${encodeURIComponent(phone)}`, { method: 'POST' }),
  login: body => request('/user/login', { method: 'POST', body }),
  buy: id => request(`/voucher-order/seckill/${encodeURIComponent(id)}`, { method: 'POST' }),
  order: id => request(`/voucher-order/status/${encodeURIComponent(id)}`),
  myJournals: (page = 1) => request(`/blog/of/me?current=${page}`),
  publish: body => request('/blog', { method: 'POST', body }),
  sign: () => request('/user/sign', { method: 'POST' }),
  signCount: () => request('/user/sign/count'),
  following: id => request(`/follow/or/not/${encodeURIComponent(id)}`),
  follow: (id, value) => request(`/follow/${encodeURIComponent(id)}/${value}`, { method: 'PUT' }),
  feed: (lastId = Date.now(), offset = 0) => request(`/blog/of/follow?lastId=${lastId}&offset=${offset}`)
};
