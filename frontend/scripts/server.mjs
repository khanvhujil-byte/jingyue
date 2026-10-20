import http from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const port = Number(process.env.PORT || 5173);
const backend = new URL(process.env.API_TARGET || 'http://127.0.0.1:8081');
if (backend.protocol !== 'http:') throw new Error('本地开发代理仅支持 HTTP 后端');
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.webp': 'image/webp', '.ico': 'image/x-icon' };
const publicEntries = new Set(['index.html', 'src', 'assets']);
export const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/imgs/')) {
    const pathname = url.pathname.startsWith('/api/') ? url.pathname.slice(4) : url.pathname;
    const upstream = http.request(new URL(pathname + url.search, backend), { method: req.method, headers: { ...req.headers, host: backend.host } }, response => {
      res.writeHead(response.statusCode, response.headers); response.pipe(res);
    });
    upstream.setTimeout(15000, () => upstream.destroy(new Error('上游超时')));
    upstream.on('error', () => { if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify({ success: false, errorMsg: '后端连接失败，请确认 8081 服务已启动' })); });
    req.on('aborted', () => upstream.destroy()); req.pipe(upstream); return;
  }
  try {
    const relative = decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname).replace(/^\/+/, '');
    const filename = path.resolve(root, relative);
    const top = relative.split(/[\\/]/)[0];
    if (!filename.startsWith(root + path.sep) || !publicEntries.has(top) || relative.includes('\\')) { res.writeHead(403); res.end('禁止访问'); return; }
    if (!(await stat(filename)).isFile()) throw new Error('not file');
    res.writeHead(200, { 'Content-Type': mime[path.extname(filename)] || 'application/octet-stream', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
    res.end(await readFile(filename));
  } catch { res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }); res.end('没有找到这个页面'); }
});
server.listen(port, '127.0.0.1', () => console.log(`山海之间已启动：http://127.0.0.1:${server.address().port}\n真实接口代理：${backend.origin}（移除 /api 前缀）`));
