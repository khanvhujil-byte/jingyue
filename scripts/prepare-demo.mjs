// Explicit operator action; app startup never overwrites inventory or resumes a paused batch.
const base=process.env.JINGYUE_URL||'http://127.0.0.1:8089';let token='';
async function api(p,m='GET',b){const r=await fetch(base+'/api/v1'+p,{method:m,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(b?{'Content-Type':'application/json'}:{})},body:b?JSON.stringify(b):undefined});const j=await r.json();if(!r.ok)throw Error(j.code+': '+j.message);return j}
const c=await api('/auth/code','POST',{phone:'13900000002'});token=(await api('/auth/login','POST',{phone:'13900000002',code:c.code})).token;
let page=1,done=0,skipped=0,total=Infinity;
while((page-1)*100<total){const r=await api('/admin/ticket-skus?page='+page++);total=r.total;for(const s of r.items){if(Number(s.product_id)<1001||Number(s.product_id)>1012)continue;if(s.state==='DRAFT'){await api('/admin/ticket-skus/'+s.id+'/warmup','POST',{});await api('/admin/ticket-skus/'+s.id+'/publish','POST',{});done++}else if(s.state==='READY'){await api('/admin/ticket-skus/'+s.id+'/publish','POST',{});done++}else skipped++}}
await api('/auth/logout','POST',{});console.log(JSON.stringify({published:done,skipped,message:'只处理原始演示票种；暂停批次及既有库存不覆盖。'}));
