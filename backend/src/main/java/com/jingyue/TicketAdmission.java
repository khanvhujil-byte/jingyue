package com.jingyue;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.*;
import static com.jingyue.Values.*;

@Service
public class TicketAdmission {
    final Db db; final RedisOps ops;
    @Value("${jingyue.rate.query-user:50}") int queryRate;
    private final RedisScript<List> accept=RedisOps.script("accept.lua",List.class);
    @Value("${jingyue.backpressure.sample-max-age-ms}") long maxAge;
    @Value("${jingyue.backpressure.max-kafka-lag}") long maxLag;
    @Value("${jingyue.backpressure.max-outbox}") long maxOutbox;
    @Value("${jingyue.rate.submit-global}") int globalRate;
    @Value("${jingyue.rate.submit-sku}") int skuRate;
    @Value("${jingyue.rate.submit-user}") int userRate;
    public TicketAdmission(Db db,RedisOps ops) { this.db=db;this.ops=ops; }
    public Map<String,Object> submit(Map<String,Object> body) {
        long user=Identity.id(); String sku=Long.toString(positive(body,"skuId"));
        String rid=required(body,"clientRequestId",80),ver=Long.toString(positive(body,"configVersion"));
        if(!rid.matches("[A-Za-z0-9_-]{8,80}")) throw new ApiFault(400,"INVALID_REQUEST_ID","请求号格式不正确");
        String digest=digest(sku+"|"+ver+"|1");
        // The Redis admission journal is the hot-path idempotency read. The
        // durable request_result unique key remains the consumer-side fence;
        // query() reads it when the client needs a terminal database result.
        List result=ops.redis.execute(accept,Arrays.asList(RedisOps.STATE,RedisOps.STREAM,RedisOps.HEALTH),sku,Long.toString(user),rid,digest,id(),UUID.randomUUID().toString(),ver,Long.toString(maxAge),Long.toString(maxLag),Long.toString(maxOutbox),"",Integer.toString(globalRate),Integer.toString(skuRate),Integer.toString(userRate));
        if(result==null) return map("result","UNKNOWN","clientRequestId",rid);
        String code=result.get(0).toString(),data=result.get(1).toString();
        if("REQUEST_CONFLICT".equals(code)) conflict();
        if("PROCESSING".equals(code)) return redisResult(object(data));
        if("ACTIVE_CLAIM".equals(code)) return map("result","ACTIVE_CLAIM","orderId",data,"message","此日期票已有有效申请，请查看原订单");
        if("BACKPRESSURE".equals(code)||"RATE_LIMITED".equals(code)) throw new ApiFault(429,code,"当前排队较多或操作较频繁，请稍后重试");
        throw new ApiFault(409,code,"SOLD_OUT".equals(code)?"该日期票已售罄":"当前票种暂不可提交，请刷新日期与售卖信息");
    }
    static void conflict() { throw new ApiFault(409,"REQUEST_CONFLICT","原请求号不能用于另一份购票意图"); }
    public Map<String,Object> query(String rid) {
        long user=Identity.id(); ops.limit("query:"+user,queryRate);
        Map<String,Object> row=db.one("SELECT * FROM tb_ticket_request_result WHERE user_id=? AND client_request_id=?",user,rid);
        if(row!=null) return dbResult(row);
        String data=ops.field("req:"+user+":"+rid);
        return data.isEmpty()?map("result","UNKNOWN","clientRequestId",rid):redisResult(object(data));
    }
    public Map<String,Object> current(long sku) {
        String order=ops.field("claim:"+sku+":"+Identity.id());
        if(!order.isEmpty()) {
            Map<String,Object> r=db.one("SELECT * FROM tb_ticket_request_result WHERE order_id=? AND user_id=?",order,Identity.id());
            if(r!=null) return dbResult(r);
            String event=ops.field("event:"+order);
            if(event.isEmpty()) event=ops.field("pending:"+order);
            if(event.isEmpty()) { return map("result","PROCESSING","orderId",order); }
            else return redisResult(object(event));
        }
        Map<String,Object> r=db.one("SELECT r.* FROM tb_ticket_active_claim c JOIN tb_ticket_request_result r ON r.order_id=c.order_id WHERE c.user_id=? AND c.sku_id=?",Identity.id(),sku);
        return r==null?map("result","UNKNOWN"):dbResult(r);
    }
    Map<String,Object> redisResult(Map<String,Object> e) { return map("result",e.getOrDefault("result","PROCESSING"),"reason",e.getOrDefault("reason",""),"orderId",e.get("orderId"),"clientRequestId",e.get("clientRequestId")); }
    Map<String,Object> dbResult(Map<String,Object> r) {
        Map<String,Object> o=db.one("SELECT state,release_state FROM tb_ticket_order WHERE id=? AND user_id=?",r.get("order_id"),Identity.id());
        return map("result",r.get("result"),"reason",r.get("reason"),"orderId",text(r,"order_id"),"clientRequestId",r.get("client_request_id"),"orderState",o==null?null:o.get("state"),"releaseState",o==null?null:o.get("release_state"));
    }
}
