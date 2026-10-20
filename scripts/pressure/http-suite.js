'use strict';
// One Node process and one keep-alive Agent across all stages. Java maintenance
// subprocesses own fixture resets/audits; no load or maintenance is overlapped.
const http=require('node:http');
const fs=require('node:fs');
const path=require('node:path');
const {spawn}=require('node:child_process');
const args={};for(let i=2;i<process.argv.length;i+=2)args[process.argv[i].replace(/^--/,'')]=process.argv[i+1];
const fixtureDirectory=path.resolve(args.fixture);
const project=path.resolve(args.project || (fs.existsSync(path.join(__dirname,'../../pom.xml'))?path.join(__dirname,'../..'):path.join(__dirname,'../project')));
const runner=path.resolve(args.runner || __dirname);
const clients=+(args.clients || 3500);
const rate=+(args.rate || 3500);
const seconds=+(args.seconds || 30);
const outputDirectory=path.resolve(args.output || path.join(fixtureDirectory,`suite-${Date.now()}`));
fs.mkdirSync(outputDirectory,{recursive:true});
const agent=new http.Agent({keepAlive:true,maxSockets:4096,maxFreeSockets:4096,scheduling:'fifo'});
const results=[];
const controlKey=Symbol.for('hmdp.pressure.control');
async function primeConnections(label){
  const base=new URL(args.base || 'http://127.0.0.1:8081/api');
  let success=0,failures=0;const started=Date.now();
  await Promise.all(Array.from({length:clients},()=>new Promise(resolve=>{
    let finished=false;
    const finish=ok=>{if(finished)return;finished=true;clearTimeout(timer);ok?success++:failures++;resolve();};
    const req=http.request({hostname:base.hostname,port:base.port,path:'/js/common.js',method:'GET',agent},res=>{res.resume();res.once('end',()=>finish(res.statusCode===200));res.once('error',()=>finish(false));});
    const timer=setTimeout(()=>{req.destroy();finish(false);},8000);req.once('error',()=>finish(false));req.end();
  })));
  // HTTP Agent moves the last response's socket into its free pool on the next turn.
  await new Promise(resolve=>setImmediate(resolve));
  const idleSockets=Object.values(agent.freeSockets).reduce((total,sockets)=>total+sockets.length,0);
  const report={kind:'static-connection-prime',excludedFromBusinessMetrics:true,requests:clients,success,failures,idleSockets,elapsedMilliseconds:Date.now()-started};
  fs.writeFileSync(path.join(outputDirectory,`${label}-connection-prime.json`),JSON.stringify(report,null,2));
  console.log(JSON.stringify(report));
  if(failures>0||idleSockets<clients)throw Error(`Connection prime did not produce ${clients} reusable sockets`);
}
function fixture(action,extra=[]){return new Promise((resolve,reject)=>{
  const powershell=args.powershell || 'powershell.exe';
  const quote=value=>"'"+String(value).replace(/'/g,"''")+"'";
  const command=`& ${quote(path.join(runner,'run-fixture.ps1'))} -Action ${quote(action)} -FixtureDirectory ${quote(fixtureDirectory)} -Project ${quote(project)}${args.jdk?' -JdkPath '+quote(args.jdk):''} -Extra @(${extra.map(quote).join(',')})`;
  const child=spawn(powershell,['-NoProfile','-Command',command],{stdio:['ignore','pipe','pipe'],windowsHide:true});
  let stdout='',stderr='';child.stdout.on('data',data=>{stdout+=data;process.stdout.write(data);});child.stderr.on('data',data=>{stderr+=data;process.stderr.write(data);});
  child.once('error',reject);child.once('exit',code=>code===0?resolve(stdout):reject(Error(`Fixture ${action} failed: ${stderr||stdout}`)));
});}
async function loadStage(kind,scenario,mode,options={}){
  await fixture('reset',[kind]);
  const label=options.label || `${kind}-${scenario}-${mode}-${clients}`;
  if(mode==='burst')await primeConnections(label);
  const reportFile=path.join(outputDirectory,`${label}.json`);
  global[controlKey]={agent,args:{manifest:path.join(fixtureDirectory,'fixture.json'),base:args.base || 'http://127.0.0.1:8081/api',kind,scenario,mode,clients:options.clients || clients,rate,seconds:options.seconds || seconds,timeout:args.timeout || 8000,output:reportFile}};
  const driverPath=require.resolve('./http-load.js');delete require.cache[driverPath];
  const report=await require(driverPath).mainPromise;
  await fixture('audit',[reportFile,args['audit-wait'] || '180']);
  const auditFile=path.join(fixtureDirectory,path.basename(reportFile).replace('.json','-audit.json'));
  const audit=JSON.parse(fs.readFileSync(auditFile,'utf8'));fs.copyFileSync(auditFile,reportFile.replace('.json','-audit.json'));
  results.push({label,report:reportFile,audit:reportFile.replace('.json','-audit.json'),success:report.success,networkErrors:report.networkErrors,deadlineDrops:report.deadlineDrops,successRps:report.successRpsDuringInjection,confirmedChangesRps:report.confirmedChangesRpsDuringInjection,conservativeEndToEndVerifiedRps:audit.conservativeEndToEndVerifiedRps,peakConnectedInflight:report.peakConnectedInflight,peakWrittenInflight:report.peakWrittenInflight,status:audit.status});
  fs.writeFileSync(path.join(outputDirectory,'suite-summary.json'),JSON.stringify(results,null,2));
}
async function main(){
  if(args.warmup!=='false')await loadStage('likes','distributed','closed',{clients:512,seconds:10,label:'warmup-likes-512-closed-10s'});
  const stages=[['likes','hot','burst'],['likes','hot','open'],['likes','distributed','burst'],['likes','distributed','open'],['seckill','hot','burst'],['seckill','voucher-rounds','open']];
  for(const stage of stages){if(args.kind&&args.kind!=='both'&&args.kind!==stage[0])continue;await loadStage(...stage);}
  console.log(JSON.stringify({suiteComplete:true,output:outputDirectory,results}));
}
main().catch(error=>{console.error(error.stack);process.exitCode=1;}).finally(()=>{delete global[controlKey];agent.destroy();});
