package com.jingyue;

import org.springframework.stereotype.Service;
import java.util.*;
import static com.jingyue.Values.*;

/** All transitions lock SKU(s) ascending, then gate/order/claim. No remote I/O in SQL transactions. */
@Service
public class TicketWriter {
    final Db db;
    public TicketWriter(Db db) { this.db=db; }
    public void validate(Map<String,Object> e) {
        for(String k:Arrays.asList("orderId","userId","skuId","configVersion","acceptedAt")) positive(e,k);
        for(String k:Arrays.asList("eventId","clientRequestId","digest","epoch")) required(e,k,100);
        if(number(e,"schemaVersion")!=1||!digest(text(e,"skuId")+"|"+text(e,"configVersion")+"|1").equals(text(e,"digest"))) throw new ApiFault(409,"EVENT_IDENTITY","事件身份不一致");
    }
    public void createBatch(List<Map<String,Object>> events) {
        if(events.isEmpty())return;
        for(Map<String,Object> e:events) validate(e);
        Map<String,Map<String,Object>> unique=new LinkedHashMap<>();
        for(Map<String,Object> e:events){Map<String,Object> old=unique.putIfAbsent(text(e,"orderId"),e);if(old!=null&&!old.equals(e))throw new ApiFault(409,"EVENT_IDENTITY","批次内同一订单身份冲突");}
        List<Map<String,Object>> sorted=new ArrayList<>(unique.values());
        sorted.sort(Comparator.<Map<String,Object>>comparingLong(e->number(e,"skuId")).thenComparingLong(e->number(e,"orderId")));
        db.tx(()->{
            Map<Long,Map<String,Object>> skus=new TreeMap<>();
            for(Map<String,Object> e:sorted) if(!skus.containsKey(number(e,"skuId"))) skus.put(number(e,"skuId"),db.need("SELECT * FROM tb_ticket_sku WHERE id=? FOR UPDATE",e.get("skuId")));
            List<Object> gateArgs=new ArrayList<>();long now=System.currentTimeMillis();
            for(Map<String,Object> e:sorted)Collections.addAll(gateArgs,e.get("orderId"),e.get("userId"),e.get("skuId"),e.get("clientRequestId"),e.get("digest"),"PROCESSING",e.get("acceptedAt"),now);
            db.update("INSERT INTO tb_ticket_request_result(order_id,user_id,sku_id,client_request_id,digest,result,accepted_at,updated_at) VALUES "+tuples(sorted.size(),8)+" ON DUPLICATE KEY UPDATE order_id=order_id",gateArgs.toArray());
            Object[] ids=sorted.stream().map(e->e.get("orderId")).toArray();
            Map<String,Map<String,Object>> gates=new HashMap<>();
            for(Map<String,Object> g:db.rows("SELECT * FROM tb_ticket_request_result WHERE order_id IN ("+marks(ids.length)+") ORDER BY order_id FOR UPDATE",ids))gates.put(text(g,"order_id"),g);
            Map<Long,List<Map<String,Object>>> creates=new TreeMap<>();
            for(Map<String,Object> e:sorted){Map<String,Object> g=gates.get(text(e,"orderId"));if(g==null)throw new ApiFault(409,"REQUEST_CONFLICT","请求号已绑定其他订单");checkGate(g,e);Map<String,Object> sku=skus.get(number(e,"skuId"));checkConfig(sku,e);
                if(!"PROCESSING".equals(text(g,"result")))continue;
                if(number(sku,"valid_until")-now<60000){rejectLocked(e,"门票有效时间不足，未创建订单");continue;}
                creates.computeIfAbsent(number(e,"skuId"),k->new ArrayList<>()).add(e);
            }
            List<Object> orderArgs=new ArrayList<>(),claimArgs=new ArrayList<>(),createdIds=new ArrayList<>();
            for(Map.Entry<Long,List<Map<String,Object>>> group:creates.entrySet()){
                long id=group.getKey();Map<String,Object> sku=skus.get(id);List<Map<String,Object>> items=group.getValue();
                if(db.update("UPDATE tb_ticket_sku SET available_stock=available_stock-? WHERE id=? AND available_stock>=?",items.size(),id,items.size())!=1)throw new ApiFault(409,"STOCK_CONFLICT","数据库库存不足，需要对账");
                Map<String,Object> p=db.need("SELECT p.name,p.attraction_id,s.name AS attraction_name,s.images FROM tb_ticket_product p JOIN tb_shop s ON s.id=p.attraction_id WHERE p.id=?",sku.get("product_id"));
                String snapshot=json(map("attractionId",text(p,"attraction_id"),"attractionName",p.get("attraction_name"),"productName",p.get("name"),"visitDate",text(sku,"visit_date"),"image",text(p,"images").split(",")[0],"priceCent",sku.get("price_cent")));
                for(Map<String,Object> e:items){Collections.addAll(orderArgs,e.get("orderId"),e.get("userId"),e.get("skuId"),e.get("clientRequestId"),"PENDING_PAYMENT",sku.get("price_cent"),p.get("attraction_id"),snapshot,now,Math.min(now+900000,number(sku,"valid_until")),sku.get("valid_from"),sku.get("valid_until"));Collections.addAll(claimArgs,e.get("userId"),e.get("skuId"),e.get("orderId"));createdIds.add(e.get("orderId"));}
            }
            if(!createdIds.isEmpty()){
                db.update("INSERT INTO tb_ticket_order(id,user_id,sku_id,client_request_id,state,amount_cent,attraction_id,snapshot_json,created_at,pay_deadline,valid_from,valid_until) VALUES "+tuples(createdIds.size(),12),orderArgs.toArray());
                db.update("INSERT INTO tb_ticket_active_claim(user_id,sku_id,order_id) VALUES "+tuples(createdIds.size(),3),claimArgs.toArray());
                List<Object> updateArgs=new ArrayList<>();updateArgs.add(now);updateArgs.addAll(createdIds);db.update("UPDATE tb_ticket_request_result SET result='CREATED',updated_at=? WHERE order_id IN ("+marks(createdIds.size())+")",updateArgs.toArray());
                for(Map<String,Object> e:sorted) if(createdIds.contains(e.get("orderId"))) queueJournalGc(e,now+60000);
            }
            return null;
        });
    }
    static String marks(int n){return String.join(",",Collections.nCopies(n,"?"));}
    static String tuples(int rows,int cols){return String.join(",",Collections.nCopies(rows,"("+marks(cols)+")"));}
    void checkGate(Map<String,Object> g,Map<String,Object> e){for(String[] pair:new String[][]{{"user_id","userId"},{"sku_id","skuId"},{"client_request_id","clientRequestId"},{"digest","digest"},{"accepted_at","acceptedAt"}})if(!text(g,pair[0]).equals(text(e,pair[1])))throw new ApiFault(409,"EVENT_IDENTITY","同一订单出现不同业务身份");}
    void checkConfig(Map<String,Object> sku,Map<String,Object> e){long at=number(e,"acceptedAt");if(!text(sku,"config_version").equals(text(e,"configVersion"))||!text(sku,"epoch").equals(text(e,"epoch"))||at<number(sku,"sale_start")||at>=number(sku,"sale_end"))throw new ApiFault(409,"CONFIG_CONFLICT","受理版本或时间不一致，需要运营核对");}
    Map<String,Object> gate(Map<String,Object> e) {
        db.update("INSERT INTO tb_ticket_request_result(order_id,user_id,sku_id,client_request_id,digest,result,accepted_at,updated_at) VALUES(?,?,?,?,?,'PROCESSING',?,?) ON DUPLICATE KEY UPDATE order_id=order_id",e.get("orderId"),e.get("userId"),e.get("skuId"),e.get("clientRequestId"),e.get("digest"),e.get("acceptedAt"),System.currentTimeMillis());
        Map<String,Object> g=db.need("SELECT * FROM tb_ticket_request_result WHERE order_id=? FOR UPDATE",e.get("orderId"));
        for(String[] pair:new String[][]{{"user_id","userId"},{"sku_id","skuId"},{"client_request_id","clientRequestId"},{"digest","digest"},{"accepted_at","acceptedAt"}})
            if(!text(g,pair[0]).equals(text(e,pair[1]))) throw new ApiFault(409,"EVENT_IDENTITY","同一订单出现不同业务身份");
        return g;
    }
    void rejectLocked(Map<String,Object> e,String reason) {
        db.update("UPDATE tb_ticket_request_result SET result='REJECTED',reason=?,updated_at=? WHERE order_id=? AND result='PROCESSING'",reason,System.currentTimeMillis(),e.get("orderId"));
        queueJournalGc(e,System.currentTimeMillis()+60000);
        outbox("release:"+text(e,"orderId"),text(e,"orderId"),"RELEASE",e);
    }
    void queueJournalGc(Map<String,Object> e,long nextAttemptAt) {
        db.update("INSERT IGNORE INTO tb_ticket_journal_gc_task(order_id,user_id,client_request_id,state,next_attempt_at,created_at) VALUES(?,?,?,'PENDING',?,?)",
            e.get("orderId"),e.get("userId"),e.get("clientRequestId"),nextAttemptAt,System.currentTimeMillis());
    }
    public void reject(Map<String,Object> e,long actor,String reason) {
        validate(e);
        db.tx(()->{ Map<String,Object> sku=db.need("SELECT * FROM tb_ticket_sku WHERE id=? FOR UPDATE",e.get("skuId"));checkConfig(sku,e);Map<String,Object> g=gate(e);
            if("CREATED".equals(text(g,"result"))) throw new ApiFault(409,"ALREADY_CREATED","订单已创建，请使用订单关闭流程");
            if("PROCESSING".equals(text(g,"result"))) rejectLocked(e,reason);
            db.audit(actor,"REJECT",text(e,"orderId"),text(g,"result"),"REJECTED");return null; });
    }
    public void outbox(String event,String aggregate,String type,Map<String,Object> payload) {
        db.update("INSERT IGNORE INTO tb_ticket_outbox(event_id,aggregate_id,event_type,payload,created_at) VALUES(?,?,?,?,?)",event,aggregate,type,json(payload),System.currentTimeMillis());
    }
}
