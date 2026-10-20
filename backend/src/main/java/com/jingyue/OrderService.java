package com.jingyue;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static com.jingyue.Values.*;

@Service
public class OrderService {
    @Value("${jingyue.rate.query-user:50}") int queryRate;
    @Value("${jingyue.rate.payment-user:10}") int paymentRate;
    @Value("${jingyue.rate.redemption-user:150}") int redemptionRate;
    final Db db;final TicketWriter writer;final RedisOps ops;final String signingKey;final boolean mock;
    public OrderService(Db db,TicketWriter writer,RedisOps ops,@Value("${jingyue.ticket-signing-key}") String signingKey,@Value("${jingyue.mock-payment:false}") boolean mock){
        if(signingKey.length()<32)throw new IllegalStateException("必须配置至少32字符的票券签名密钥");
        this.db=db;this.writer=writer;this.ops=ops;this.signingKey=signingKey;this.mock=mock;
    }
    Map<String,Object> owned(String id){return db.need("SELECT * FROM tb_ticket_order WHERE id=? AND user_id=?",id,Identity.id());}
    Map<String,Object> lock(Map<String,Object> ref){db.need("SELECT id FROM tb_ticket_sku WHERE id=? FOR UPDATE",ref.get("sku_id"));return db.need("SELECT * FROM tb_ticket_order WHERE id=? FOR UPDATE",ref.get("id"));}
    public Map<String,Object> view(Map<String,Object> o){Map<String,Object> r=object(text(o,"snapshot_json"));r.putAll(map("orderId",text(o,"id"),"skuId",text(o,"sku_id"),"clientRequestId",o.get("client_request_id"),"state",o.get("state"),"amountCent",o.get("amount_cent"),"createdAt",instant(number(o,"created_at")),"payDeadline",instant(number(o,"pay_deadline")),"validFrom",instant(number(o,"valid_from")),"validUntil",instant(number(o,"valid_until")),"closedReason",o.get("closed_reason"),"releaseState",o.get("release_state")));
        for(String k:Arrays.asList("paid_at","closed_at","used_at"))r.put(k,o.get(k)==null?null:instant(number(o,k)));return r;}
    public Map<String,Object> detail(String id){ops.limit("query:"+Identity.id(),queryRate);return view(owned(id));}
    public Map<String,Object> list(String state,int page,int size){if(page<1||size<1||size>50||!Arrays.asList("","PENDING_PAYMENT","PAID","USED","CLOSED","EXPIRED").contains(state))throw new ApiFault(400,"INVALID_PARAMETER","订单筛选条件不正确");
        List<Map<String,Object>> out=new ArrayList<>();for(Map<String,Object> o:db.rows("SELECT * FROM tb_ticket_order WHERE user_id=? AND (?='' OR state=?) ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",Identity.id(),state,state,size,(page-1)*size))out.add(view(o));
        return map("items",out,"page",page,"total",db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE user_id=? AND (?='' OR state=?)",Identity.id(),state,state));}
    public Map<String,Object> pay(String id,Map<String,Object> body){
        if(!mock)throw new ApiFault(403,"MOCK_DISABLED","模拟支付尚未开启");ops.limit("payment:"+Identity.id(),paymentRate);
        String mode=required(body,"outcome",20);if(!Arrays.asList("SUCCESS","FAIL","REPEAT").contains(mode))throw new ApiFault(400,"INVALID_PARAMETER","请选择模拟支付结果");
        Map<String,Object> ref=owned(id);
        Map<String,Object> result=db.tx(()->{
            Map<String,Object> o=lock(ref);String state=text(o,"state");long now=System.currentTimeMillis();
            if(Arrays.asList("PAID","USED","EXPIRED").contains(state))return map("paid",true,"duplicate",true,"order",view(o));
            if(!"PENDING_PAYMENT".equals(state)||now>=number(o,"pay_deadline"))throw new ApiFault(409,"NOT_PAYABLE","订单已关闭或超过支付截止时间");
            if("FAIL".equals(mode))return map("paid",false,"message","模拟支付失败，订单仍待支付","order",view(o));
            db.update("INSERT INTO tb_ticket_payment(id,order_id,provider_event_id,amount_cent,result,created_at) VALUES(?,?,?,?,'SUCCESS',?)",UUID.randomUUID().toString(),id,"mock:"+id,o.get("amount_cent"),now);
            db.update("INSERT INTO tb_admission_ticket(id,order_id,nonce,state,valid_from,valid_until) VALUES(?,?,?,'VALID',?,?)",UUID.randomUUID().toString(),id,token(),o.get("valid_from"),o.get("valid_until"));
            db.update("UPDATE tb_ticket_order SET state='PAID',paid_at=? WHERE id=? AND state='PENDING_PAYMENT'",now,id);
            return map("paid",true,"duplicate",false,"order",view(db.need("SELECT * FROM tb_ticket_order WHERE id=?",id)));
        });return result;
    }
    public Map<String,Object> cancel(String id){Map<String,Object> ref=owned(id);db.tx(()->{Map<String,Object> o=lock(ref);if("CLOSED".equals(text(o,"state")))return null;if(!"PENDING_PAYMENT".equals(text(o,"state")))throw new ApiFault(409,"NOT_CANCELLABLE","仅待支付订单可以取消");closeLocked(o,"用户取消");return null;});return detail(id);}
    void closeLocked(Map<String,Object> o,String reason){String id=text(o,"id");db.update("UPDATE tb_ticket_order SET state='CLOSED',closed_at=?,closed_reason=?,release_state='PENDING' WHERE id=? AND state='PENDING_PAYMENT'",System.currentTimeMillis(),reason,id);
        if(db.update("UPDATE tb_ticket_sku SET available_stock=available_stock+1 WHERE id=? AND available_stock<total_stock",o.get("sku_id"))!=1)throw new ApiFault(409,"STOCK_CONFLICT","库存不一致，请运营核对");
        if(db.update("DELETE FROM tb_ticket_active_claim WHERE user_id=? AND sku_id=? AND order_id=?",o.get("user_id"),o.get("sku_id"),id)!=1)throw new ApiFault(409,"CLAIM_CONFLICT","资格不一致，请运营核对");
        Map<String,Object> s=db.need("SELECT epoch FROM tb_ticket_sku WHERE id=?",o.get("sku_id"));writer.outbox("release:"+id,id,"RELEASE",map("skuId",text(o,"sku_id"),"userId",text(o,"user_id"),"orderId",id,"epoch",s.get("epoch")));
    }
    @Scheduled(fixedDelay=1000) public void expiry(){try{
        long now=System.currentTimeMillis();Map<Long,List<Map<String,Object>>> groups=new TreeMap<>();
        for(Map<String,Object> ref:db.rows("SELECT id,sku_id FROM tb_ticket_order WHERE (state='PENDING_PAYMENT' AND pay_deadline<=?) OR (state='PAID' AND valid_until<=?) ORDER BY sku_id,id LIMIT 2000",now,now))groups.computeIfAbsent(number(ref,"sku_id"),k->new ArrayList<>()).add(ref);
        for(Map.Entry<Long,List<Map<String,Object>>> group:groups.entrySet())expireSku(group.getKey(),group.getValue());
    }catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("到期扫描未完成，将在下轮补扫：{}",e.getClass().getSimpleName());}}
    void expireSku(long skuId,List<Map<String,Object>> refs){db.tx(()->{
        Map<String,Object> sku=db.need("SELECT * FROM tb_ticket_sku WHERE id=? FOR UPDATE",skuId);
        Object[] ids=refs.stream().map(r->r.get("id")).toArray();List<Map<String,Object>> closing=new ArrayList<>(),expired=new ArrayList<>();long now=System.currentTimeMillis();
        for(Map<String,Object> o:db.rows("SELECT * FROM tb_ticket_order WHERE id IN ("+TicketWriter.marks(ids.length)+") ORDER BY id FOR UPDATE",ids)){
            if("PENDING_PAYMENT".equals(text(o,"state"))&&now>=number(o,"pay_deadline"))closing.add(o);
            else if("PAID".equals(text(o,"state"))&&now>=number(o,"valid_until"))expired.add(o);
        }
        if(!closing.isEmpty()){
            if(db.update("UPDATE tb_ticket_sku SET available_stock=available_stock+? WHERE id=? AND available_stock+?<=total_stock",closing.size(),skuId,closing.size())!=1)throw new ApiFault(409,"STOCK_CONFLICT","到期库存需核对");
            Object[] closeIds=closing.stream().map(o->o.get("id")).toArray();
            if(db.update("DELETE FROM tb_ticket_active_claim WHERE order_id IN ("+TicketWriter.marks(closeIds.length)+")",closeIds)!=closing.size())throw new ApiFault(409,"CLAIM_CONFLICT","到期资格需核对");
            List<Object> update=new ArrayList<>();update.add(now);Collections.addAll(update,closeIds);
            db.update("UPDATE tb_ticket_order SET state='CLOSED',closed_at=?,closed_reason='支付超时',release_state='PENDING' WHERE id IN ("+TicketWriter.marks(closeIds.length)+")",update.toArray());
            List<Object> outbox=new ArrayList<>();for(Map<String,Object> o:closing){String id=text(o,"id");Collections.addAll(outbox,"release:"+id,id,"RELEASE",json(map("skuId",Long.toString(skuId),"userId",text(o,"user_id"),"orderId",id,"epoch",sku.get("epoch"))),now);}
            db.update("INSERT IGNORE INTO tb_ticket_outbox(event_id,aggregate_id,event_type,payload,created_at) VALUES "+TicketWriter.tuples(closing.size(),5),outbox.toArray());
        }
        if(!expired.isEmpty()){
            Object[] expiredIds=expired.stream().map(o->o.get("id")).toArray();String marks=TicketWriter.marks(expiredIds.length);
            db.update("UPDATE tb_ticket_order SET state='EXPIRED' WHERE id IN ("+marks+")",expiredIds);
            db.update("UPDATE tb_admission_ticket SET state='EXPIRED' WHERE order_id IN ("+marks+") AND state='VALID'",expiredIds);
        }return null;
    });}
    String signature(String value){try{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(signingKey.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    public Map<String,Object> ticket(String id){Map<String,Object> o=owned(id);Map<String,Object> t=db.need("SELECT * FROM tb_admission_ticket WHERE order_id=?",id);String base="1."+text(t,"id")+"."+text(t,"nonce");String state=text(t,"state");if("VALID".equals(state)&&System.currentTimeMillis()>=number(t,"valid_until"))state="EXPIRED";
        return map("ticketId",t.get("id"),"state",state,"credential",base+"."+signature(base),"order",view(o),"usedAt",t.get("used_at")==null?null:instant(number(t,"used_at")));}
    public Map<String,Object> redeem(Map<String,Object> body){Identity.role("REDEEMER");long actor=Identity.id();ops.limit("redeem:"+actor,redemptionRate);String credential=required(body,"credential",300),rid=required(body,"clientRequestId",80);String[] parts=credential.split("\\.");
        if(parts.length!=4||!"1".equals(parts[0])||!MessageDigest.isEqual(signature(parts[0]+"."+parts[1]+"."+parts[2]).getBytes(StandardCharsets.UTF_8),parts[3].getBytes(StandardCharsets.UTF_8)))throw new ApiFault(400,"INVALID_TICKET","票券凭证无效");
        Map<String,Object> ref=db.need("SELECT o.* FROM tb_admission_ticket t JOIN tb_ticket_order o ON o.id=t.order_id WHERE t.id=? AND t.nonce=?",parts[1],parts[2]);
        if(db.count("SELECT COUNT(*) FROM tb_redeemer_scope WHERE user_id=? AND attraction_id=?",actor,ref.get("attraction_id"))!=1)throw new ApiFault(403,"WRONG_ATTRACTION","无权核销此景点的票券");
        return db.tx(()->{Map<String,Object> o=lock(ref),t=db.need("SELECT * FROM tb_admission_ticket WHERE id=? FOR UPDATE",parts[1]);Map<String,Object> prior=db.one("SELECT ticket_id FROM tb_redemption WHERE operator_id=? AND request_id=?",actor,rid);
            if(prior!=null&&!text(prior,"ticket_id").equals(parts[1]))throw new ApiFault(409,"REQUEST_CONFLICT","核销请求号已用于其他票券");
            if("USED".equals(text(t,"state")))return map("redeemed",true,"duplicate",true,"usedAt",instant(number(t,"used_at")),"message","此票已核销");
            long now=System.currentTimeMillis();if(!"PAID".equals(text(o,"state"))||!"VALID".equals(text(t,"state"))||now<number(t,"valid_from")||now>=number(t,"valid_until"))throw new ApiFault(409,"TICKET_UNUSABLE","票券未进入使用时间、已过期或状态不可用");
            db.update("UPDATE tb_admission_ticket SET state='USED',used_at=? WHERE id=? AND state='VALID'",now,parts[1]);db.update("UPDATE tb_ticket_order SET state='USED',used_at=? WHERE id=? AND state='PAID'",now,o.get("id"));
            db.update("INSERT INTO tb_redemption(id,ticket_id,operator_id,attraction_id,request_id,created_at) VALUES(?,?,?,?,?,?)",UUID.randomUUID().toString(),parts[1],actor,o.get("attraction_id"),rid,now);db.audit(actor,"REDEEM",text(o,"id"),"PAID","USED");return map("redeemed",true,"duplicate",false,"usedAt",instant(now),"message","核销成功");});
    }
}
