'use strict';
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'hmdp-http-driver-'));
const fixture = { clients: Array.from({length:40},(_,i)=>({userId:i+1,token:`temporary-token-${i}`})), blogIds:Array.from({length:17},(_,i)=>100+i), voucherIds:[200,201] };
const manifest = path.join(temporary,'fixture.json');fs.writeFileSync(manifest,JSON.stringify(fixture));
let calls=0,connections=0;
const server=http.createServer((req,res)=>{
  calls++;
  const url=new URL(req.url,'http://127.0.0.1');
  setTimeout(()=>{res.setHeader('content-type','application/json');res.end(url.pathname.includes('seckill')?'{"success":true,"data":9007199254740993123}':JSON.stringify({success:true,data:url.searchParams.get('liked')==='true'}));},3);
});
server.on('connection',()=>connections++);
function run(options){return new Promise((resolve,reject)=>{
  const output=path.join(temporary,`${options.kind}-${options.mode}.json`);
  const args=[path.join(__dirname,'http-load.js'),'--manifest',manifest,'--base',`http://127.0.0.1:${server.address().port}`,'--output',output];
  for(const [key,value]of Object.entries(options))args.push(`--${key}`,String(value));
  const child=spawn(process.execPath,args,{stdio:['ignore','pipe','pipe']});let error='';child.stderr.on('data',x=>error+=x);
  child.on('error',reject);child.on('exit',code=>code===0?resolve(JSON.parse(fs.readFileSync(output,'utf8'))):reject(Error(error)));
});}
server.listen(0,'127.0.0.1',async()=>{
  try{
    const burst=await run({kind:'likes',mode:'burst',clients:32});assert.equal(burst.success,32);assert.equal(burst.confirmedChanges,32);assert.equal(burst.peakInflight,32);assert.equal(burst.expectedStates.filter(x=>x.liked).length,32);assert.equal(burst.networkErrors,0);
    const open=await run({kind:'likes',mode:'open',clients:40,seconds:1,rate:100});assert.equal(open.scheduled,100);assert.equal(open.dispatched,100);assert.equal(open.success,100);assert.equal(open.confirmedChanges,100);assert.equal(open.deadlineDrops,0);assert(open.successWithinInjection<=open.success);assert(open.successRpsDuringInjection<=open.success/open.injectionSeconds+1e-9);
    const seckill=await run({kind:'seckill',mode:'burst',clients:32});assert.equal(seckill.success,32);assert.equal(seckill.acceptedOrders.length,32);assert.equal(seckill.acceptedOrders[0].orderId,'9007199254740993123');assert.equal(calls,164);
    const sharedAgent=new http.Agent({keepAlive:true,maxSockets:64,maxFreeSockets:64});
    try{
      const before=connections;
      for(let i=0;i<2;i++){
        global[Symbol.for('hmdp.pressure.control')]={agent:sharedAgent,args:{manifest,base:`http://127.0.0.1:${server.address().port}`,kind:'likes',mode:'burst',clients:32,output:path.join(temporary,`shared-${i}.json`)}};
        const driver=require.resolve('./http-load.js');delete require.cache[driver];const report=await require(driver).mainPromise;assert.equal(report.success,32);
      }
      assert.equal(connections-before,32,'Two stages must reuse the same 32 sockets');
    }finally{delete global[Symbol.for('hmdp.pressure.control')];sharedAgent.destroy();}
    console.log('HTTP_DRIVER_SELFTEST_PASS: burst, open scheduling, final states, exact long order IDs, shared Agent across stages');
  }catch(error){console.error(error);process.exitCode=1;}
  finally{server.closeAllConnections();server.close();fs.rmSync(temporary,{recursive:true,force:true});}
});
