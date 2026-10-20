import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const root=path.dirname(fileURLToPath(import.meta.url));
const port=Number(process.env.JINGYUE_WEB_PORT||5190),backend=Number(process.env.JINGYUE_PORT||8089);
const backendHost=process.env.JINGYUE_BACKEND_HOST||'127.0.0.1',bind=process.env.JINGYUE_WEB_BIND||'127.0.0.1';
const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.webp':'image/webp','.svg':'image/svg+xml','.png':'image/png'};
http.createServer((req,res)=>{
  res.setHeader('X-Content-Type-Options','nosniff');res.setHeader('Referrer-Policy','same-origin');
  if(req.url.startsWith('/api/v1/')){
    const upstream=http.request({host:backendHost,port:backend,path:req.url,method:req.method,headers:{...req.headers,host:`${backendHost}:${backend}`}},r=>{res.writeHead(r.statusCode,r.headers);r.pipe(res)});
    upstream.on('error',()=>{if(!res.headersSent)res.writeHead(503,{'Content-Type':'application/json; charset=utf-8'});res.end(JSON.stringify({code:'GATEWAY_UNAVAILABLE',message:'服务暂不可用，请保留原请求稍后查询'}))});
    upstream.setTimeout(15000,()=>upstream.destroy(new Error('upstream timeout')));
    req.on('aborted',()=>upstream.destroy());res.on('close',()=>{if(!res.writableEnded)upstream.destroy()});
    req.pipe(upstream);return;
  }
  if(!['GET','HEAD'].includes(req.method)){res.writeHead(405);res.end();return;}
  let uri;try{uri=decodeURIComponent(new URL(req.url,'http://localhost').pathname)}catch{res.writeHead(400);res.end();return;}
  const file=path.resolve(root,'.'+(uri==='/'?'/index.html':uri));
  if(!file.startsWith(root+path.sep)||!['.html','.js','.css','.webp','.svg','.png'].includes(path.extname(file))){res.writeHead(404);res.end();return;}
  fs.stat(file,(e,s)=>{if(e||!s.isFile()){res.writeHead(404);res.end();return;}res.setHeader('Content-Type',mime[path.extname(file)]);res.setHeader('Cache-Control',uri.startsWith('/assets/')?'public,max-age=86400':'no-cache');res.setHeader('Content-Length',s.size);if(req.method==='HEAD')res.end();else fs.createReadStream(file).pipe(res)});
}).listen(port,bind,()=>console.log(`景约：http://127.0.0.1:${port}（接口代理 → ${backend}，保留 /api/v1 路径）`));
