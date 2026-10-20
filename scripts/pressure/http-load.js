'use strict';
// Real HTTP load: one in-flight request per authenticated logical client.
// Uses only Node built-ins. Tokens never appear in console output or result files.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { performance, monitorEventLoopDelay } = require('node:perf_hooks');
const loadControl = global[Symbol.for('hmdp.pressure.control')];
const args = loadControl ? { ...loadControl.args } : {};
if (!loadControl) for (let i = 2; i < process.argv.length; i += 2) args[process.argv[i].replace(/^--/, '')] = process.argv[i + 1];
const manifestPath = path.resolve(args.manifest || 'fixture.json');
const fixture = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const target = new URL(args.base || 'http://127.0.0.1:8081');
if (target.hostname !== '127.0.0.1') throw Error('Only loopback load testing is supported');
const kind = args.kind || 'likes';
const mode = args.mode || 'burst';
const scenario = args.scenario || 'hot';
const clientsCount = +(args.clients || 3200);
const seconds = +(args.seconds || 30);
const rate = +(args.rate || 3500);
const timeoutMs = +(args.timeout || 8000);
if (!['likes', 'seckill'].includes(kind) || !['burst', 'open', 'closed'].includes(mode)) throw Error('Invalid workload');
if (clientsCount < 1 || clientsCount > fixture.clients.length || seconds < 1 || seconds > 120 || rate > 20000 || timeoutMs > 20000) throw Error('Bounded load settings exceeded');
const output = path.resolve(args.output || path.join(path.dirname(manifestPath), `${kind}-${scenario}-${mode}-${Date.now()}.json`));
fs.mkdirSync(path.dirname(output), { recursive: true });
const sharedAgent = loadControl && loadControl.agent;
const agent = sharedAgent || new http.Agent({ keepAlive: true, maxSockets: Math.max(4096, clientsCount), maxFreeSockets: Math.max(4096, clientsCount), scheduling: 'fifo' });
const clients = fixture.clients.slice(0, clientsCount).map((x, index) => ({
  ...x, index, desired: true, lastLiked: false, acknowledged: 0, busy: false, uncertain: false,
  voucherIndex: 0, blogId: fixture.blogIds[scenario === 'hot' ? 0 : 1 + index % (fixture.blogIds.length - 1)]
}));
let inflight = 0, peakInflight = 0, connectedInflight = 0, peakConnectedInflight = 0, peakAssignedSockets = 0, writtenInflight = 0, peakWrittenInflight = 0;
let completed = 0, success = 0, confirmedChanges = 0, ambiguousSuccess = 0, rateLimited = 0, businessErrors = 0, networkErrors = 0, httpErrors = 0;
let timeoutCount = 0, overFrontend2s = 0, dispatched = 0, scheduled = 0, deadlineDrops = 0, clientExhausted = 0;
let successWithinInjection = 0, confirmedChangesWithinInjection = 0;
const latency = [], successLatency = [], errors = {}, secondsBuckets = new Map(), acceptedOrders = [], failedRequests = [];
const started = performance.now();
let workloadStarted, workloadFinished, lastProgress = started, stopped = false;
const idle = clients.slice();
let idleHead = 0;
const eventLoopDelay = monitorEventLoopDelay({ resolution: 20 }); eventLoopDelay.enable();
const countError = (key) => { errors[key] = (errors[key] || 0) + 1; };
function slotSecond(now) {
  const second = Math.floor((now - workloadStarted) / 1000);
  if (!secondsBuckets.has(second)) secondsBuckets.set(second, { second, dispatched: 0, success: 0, confirmedChanges: 0, failures: 0 });
  return secondsBuckets.get(second);
}
function takeIdle() {
  while (idleHead < idle.length) {
    const client = idle[idleHead++];
    if (idleHead > 10000 && idleHead * 2 > idle.length) { idle.splice(0, idleHead); idleHead = 0; }
    if (kind !== 'seckill' || client.voucherIndex < fixture.voucherIds.length) return client;
    clientExhausted++;
  }
  return null;
}
function dispatch(client) {
  if (kind === 'seckill' && client.voucherIndex >= fixture.voucherIds.length) { clientExhausted++; return; }
  client.busy = true; inflight++; dispatched++;
  if (mode === 'closed') scheduled++;
  peakInflight = Math.max(peakInflight, inflight);
  const begin = performance.now(); slotSecond(begin).dispatched++;
  const voucherId = kind === 'seckill' ? fixture.voucherIds[client.voucherIndex] : null;
  const desired = client.desired;
  const api = kind === 'likes' ? `/blog/like/${client.blogId}?liked=${desired}` : `/voucher-order/seckill/${voucherId}`;
  let connected = false, finished = false, written = false;
  const req = http.request({ protocol: target.protocol, hostname: target.hostname, port: target.port, path: `${target.pathname.replace(/\/$/, '')}${api}`, method: kind === 'likes' ? 'PUT' : 'POST',
    headers: { authorization: client.token, 'content-length': '0', accept: 'application/json' }, agent }, res => {
    let raw = '';
    res.setEncoding('utf8');
    res.on('data', chunk => { raw += chunk; if (raw.length > 65536) req.destroy(Error('Response exceeded bound')); });
    res.on('end', () => {
      if (finished) return;
      if (res.statusCode === 429) { rateLimited++; countError('http_429'); return finish(false); }
      if (res.statusCode < 200 || res.statusCode >= 300) { httpErrors++; countError(`http_${res.statusCode}`); return finish(false); }
      let body;
      try { body = JSON.parse(raw); } catch (_) { httpErrors++; countError(`invalid_json_http_${res.statusCode}`); return finish(false); }
      if (!body.success || (kind === 'likes' && body.data !== desired)) {
        businessErrors++; countError(String(body.errorMsg || 'unexpected_business_result').slice(0, 100)); return finish(false);
      }
      if (kind === 'seckill' && (body.data == null || typeof body.data === 'object')) { businessErrors++; countError('missing_order_id'); return finish(false); }
      success++; client.acknowledged++; successLatency.push(performance.now() - begin);
      const withinInjection = mode === 'burst' || performance.now() <= workloadStarted + seconds * 1000;
      if (withinInjection) successWithinInjection++;
      const bucket = slotSecond(performance.now()); bucket.success++;
      if (!client.uncertain) { confirmedChanges++; bucket.confirmedChanges++; if (withinInjection) confirmedChangesWithinInjection++; } else { ambiguousSuccess++; client.uncertain = false; }
      if (kind === 'likes') { client.lastLiked = desired; client.desired = !desired; }
      else {
        // Preserve decimal order ID from raw JSON: Java Long may exceed Number precision.
        const match = raw.match(/"data"\s*:\s*"?(\d+)"?/);
        acceptedOrders.push({ userId: client.userId, voucherId, orderId: match ? match[1] : String(body.data) });
        client.voucherIndex++;
      }
      finish(true);
    });
    res.on('error', networkFailure);
  });
  function networkFailure(error) {
    if (finished) return;
    networkErrors++; client.uncertain = true;
    if (error.code === 'LOAD_TIMEOUT') timeoutCount++;
    countError(error.code || error.name || 'network_error');
    if (failedRequests.length < 10000) failedRequests.push({ userId:client.userId,blogId:kind==='likes'?client.blogId:undefined,desired:kind==='likes'?desired:undefined,voucherId:kind==='seckill'?voucherId:undefined,code:error.code||error.name,offsetMilliseconds:performance.now()-workloadStarted });
    // A lost seckill response has unknown outcome. Never count a duplicate retry
    // as new work; move to a fresh user/voucher pair and audit unknowns separately.
    if (kind === 'seckill') client.voucherIndex++;
    finish(false);
  }
  const deadline = setTimeout(() => { const error = Error('Request deadline'); error.code = 'LOAD_TIMEOUT'; req.destroy(error); }, timeoutMs);
  function finish(ok) {
    if (finished) return;
    finished = true; clearTimeout(deadline);
    const ms = performance.now() - begin; latency.push(ms); if (ms > 2000) overFrontend2s++;
    completed++; inflight--; if (connected) connectedInflight--; if (written) writtenInflight--;
    if (!ok) slotSecond(performance.now()).failures++;
    client.busy = false;
    if (!stopped) {
      if (mode === 'closed' && performance.now() < workloadStarted + seconds * 1000) dispatch(client);
      else idle.push(client);
    }
  }
  req.on('socket', socket => {
    if (finished) return;
    const liveSockets = Object.values(agent.sockets).reduce((n, a) => n + a.length, 0);
    peakAssignedSockets = Math.max(peakAssignedSockets, liveSockets);
    const markConnected = () => { if (!finished && !connected) { connected = true; connectedInflight++; peakConnectedInflight = Math.max(peakConnectedInflight, connectedInflight); } };
    if (socket.connecting) socket.once('connect', markConnected); else markConnected();
  });
  req.on('error', networkFailure);
  req.on('finish', () => { if (!finished) { written = true; writtenInflight++; peakWrittenInflight = Math.max(peakWrittenInflight, writtenInflight); } });
  req.end();
}
function percentile(values, p) { return values.length ? values[Math.max(0, Math.ceil(values.length * p) - 1)] : null; }
const progress = setInterval(() => {
  const now = performance.now();
  if (now - lastProgress >= 5000) { console.log(JSON.stringify({ kind, mode, elapsedSeconds: +((now - workloadStarted) / 1000).toFixed(1), dispatched, success, inflight, rateLimited, businessErrors, networkErrors })); lastProgress = now; }
}, 1000);
function stopScheduling() { stopped = true; workloadFinished = performance.now(); }
async function main() {
  workloadStarted = performance.now();
  if (mode === 'burst') {
    scheduled = clientsCount;
    // All distinct sessions are dispatched before the event loop processes replies.
    for (const client of clients) dispatch(client);
    stopScheduling();
  } else if (mode === 'closed') {
    scheduled = 0; for (const client of clients) dispatch(client);
    await new Promise(resolve => setTimeout(resolve, seconds * 1000)); stopScheduling();
  } else {
    const budget = Math.floor(seconds * rate);
    await new Promise(resolve => {
      const tick = setInterval(() => {
        const elapsed = performance.now() - workloadStarted;
        const due = Math.min(budget, Math.floor(elapsed * rate / 1000));
        while (scheduled < due) {
          scheduled++;
          const client = takeIdle();
          if (client) dispatch(client); else deadlineDrops++;
        }
        if (elapsed >= seconds * 1000) {
          clearInterval(tick);
          deadlineDrops += budget - scheduled; scheduled = budget;
          stopScheduling(); resolve();
        }
      }, 5);
    });
  }
  while (inflight > 0) await new Promise(resolve => setTimeout(resolve, 20));
  const ended = performance.now();
  clearInterval(progress); eventLoopDelay.disable(); if (!sharedAgent) agent.destroy();
  latency.sort((a,b) => a-b); successLatency.sort((a,b) => a-b);
  const elapsedSeconds = (ended - workloadStarted) / 1000;
  const injectionSeconds = mode === 'burst' ? elapsedSeconds : (workloadFinished - workloadStarted) / 1000;
  const report = { startedAt: new Date(Date.now() - (ended - started)).toISOString(), finishedAt: new Date().toISOString(), base: target.origin + target.pathname,
    kind, mode, scenario, clients: clientsCount, configuredSeconds: seconds, targetRate: mode === 'open' ? rate : null, timeoutMs,
    elapsedSeconds, injectionSeconds, scheduled, dispatched, completed, success, confirmedChanges, ambiguousSuccess,
    rateLimited, businessErrors, httpErrors, networkErrors, timeoutCount, deadlineDrops, clientExhausted,
    successWithinInjection, confirmedChangesWithinInjection,
    successRpsDuringInjection: successWithinInjection / injectionSeconds, confirmedChangesRpsDuringInjection: confirmedChangesWithinInjection / injectionSeconds,
    successRpsIncludingDrain: success / elapsedSeconds, confirmedChangesRpsIncludingDrain: confirmedChanges / elapsedSeconds,
    peakInflight, peakConnectedInflight, peakWrittenInflight, peakAssignedSockets, overFrontend2s,
    latencyMs: { p50: percentile(latency,.5), p95: percentile(latency,.95), p99: percentile(latency,.99), max: percentile(latency,1) },
    successfulLatencyMs: { p50: percentile(successLatency,.5), p95: percentile(successLatency,.95), p99: percentile(successLatency,.99) },
    eventLoopDelayMs: { p95: eventLoopDelay.percentile(95)/1e6, max: eventLoopDelay.max/1e6 },
    perSecond: Array.from(secondsBuckets.values()).sort((a,b) => a.second-b.second), errors, failedRequests,
    expectedStates: kind === 'likes' ? clients.map(c => ({ userId: c.userId, blogId: c.blogId, liked: c.lastLiked, acknowledged: c.acknowledged, uncertain: c.uncertain })) : undefined,
    acceptedOrders: kind === 'seckill' ? acceptedOrders : undefined,
    limitations: ['Client, backend, Redis and MySQL share one host.', 'HTTP acceptance and database persistence are measured separately by the audit helper.', 'Rejections, unknown responses and duplicate no-ops are not confirmed state changes.'] };
  fs.writeFileSync(output, JSON.stringify(report, null, 2));
  const compact = { ...report }; delete compact.expectedStates; delete compact.acceptedOrders; delete compact.perSecond;
  delete compact.failedRequests;
  console.log(JSON.stringify({ ...compact, report: output }));
  return report;
}
const mainPromise=main();
module.exports={mainPromise};
mainPromise.catch(error => { clearInterval(progress); if (!sharedAgent) agent.destroy(); console.error(error.stack); process.exitCode = 1; });
