export const storage = {
  get(key, fallback = null) { try { const raw = sessionStorage.getItem(key); return raw === null ? fallback : JSON.parse(raw); } catch { return fallback; } },
  set(key, value) { try { sessionStorage.setItem(key, JSON.stringify(value)); return true; } catch { return false; } },
  remove(key) { try { sessionStorage.removeItem(key); } catch { /* session-only fallback */ } }
};
export function currentMode() { return storage.get('shanhai:mode', 'demo') === 'live' ? 'live' : 'demo'; }
export function tokenKey() { return `shanhai:${currentMode()}:token`; }
export function token() { return storage.get(tokenKey(), ''); }
export function orderKey(userId) { return `shanhai:${currentMode()}:orders:${userId}`; }
