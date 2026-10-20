package com.jingyue;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.time.*;
import static com.jingyue.Values.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real MySQL + Redis + Kafka acceptance. Isolated jingyue_ database only. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TicketIntegrationTest {
    @Autowired Db db;@Autowired RedisOps ops;@Autowired TicketAdmission admission;@Autowired TicketWriter writer;
    @Autowired OrderService orders;@Autowired AdminController admin;@Autowired TicketPipeline pipeline;@Autowired TestRestTemplate rest;
    @LocalServerPort int port;
    static final AtomicLong sequence=new AtomicLong(System.currentTimeMillis()*100);
    long fixtureAttraction;
    @BeforeAll void prepareScope(){fixtureAttraction=sequence.incrementAndGet();db.update("INSERT INTO tb_shop(id,name,type_id,address,x,y,images,open_hours) VALUES(?,'独立验收景点',1,'演示地址',120,30,'/assets/valley.webp','全天演示')",fixtureAttraction);db.update("INSERT INTO tb_attraction_profile(shop_id,city_code,city_name,category,introduction,admission_notice,published) VALUES(?,'TEST','验收','验收','验收专用','不是真实景区',1)",fixtureAttraction);db.update("INSERT INTO tb_redeemer_scope(user_id,attraction_id) VALUES(3,?)",fixtureAttraction);}
    void as(long id,String role){Identity.set(map("id",Long.toString(id),"role",role,"name","验收用户"));}
    @AfterEach void clear(){Identity.clear();}
    @AfterAll void retireFixtures(){for(Map<String,Object> s:db.rows("SELECT s.id FROM tb_ticket_sku s JOIN tb_ticket_product p ON p.id=s.product_id WHERE p.description='独立验收数据'")){ops.redis.opsForHash().put(RedisOps.STATE,"sale:"+text(s,"id"),"PAUSED");db.update("UPDATE tb_ticket_sku SET state='PAUSED' WHERE id=?",s.get("id"));}db.update("UPDATE tb_ticket_product SET status='INACTIVE' WHERE description='独立验收数据'");db.update("UPDATE tb_attraction_profile SET published=0 WHERE shop_id=?",fixtureAttraction);Identity.clear();}
    void await(BooleanSupplier condition)throws Exception{long until=System.currentTimeMillis()+25000;while(!condition.getAsBoolean()){if(System.currentTimeMillis()>until)fail("最终一致性未在25秒内收敛");Thread.sleep(40);}}
    long fixture(int stock){as(2,"ADMIN");long p=sequence.incrementAndGet();db.update("INSERT INTO tb_ticket_product(id,attraction_id,name,description) VALUES(?,?,'验收成人票','独立验收数据')",p,fixtureAttraction);LocalDate date=LocalDate.now(ZONE);long day=date.atStartOfDay(ZONE).toInstant().toEpochMilli(),until=date.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()-1;Map<String,Object> b=map("productId",Long.toString(p),"visitDate",date.toString(),"validFrom",instant(day),"validUntil",instant(until),"saleStart",instant(day-86400000),"saleEnd",instant(until-900000),"priceCent",6800,"totalStock",stock);
        Map<String,Object> out=(Map<String,Object>)admin.sku(b);long sku=number(out,"skuId");admin.control(sku,"warmup");admin.control(sku,"publish");return sku;}
    Map<String,Object> submit(long user,long sku,String rid){as(user,"USER");return admission.submit(map("skuId",Long.toString(sku),"configVersion","1","clientRequestId",rid));}
    Map<String,Object> event(String order){String payload=ops.field("pending:"+order);if(!payload.isEmpty())return object(payload);for(Object v:ops.redis.opsForHash().values(RedisOps.STATE)){String s=v.toString();if(s.startsWith("{")&&s.contains("\"orderId\":\""+order+"\""))return object(s);}throw new AssertionError("缺少原受理身份");}
    String created(long user,long sku)throws Exception{String id=text(submit(user,sku,UUID.randomUUID().toString()),"orderId");await(()->db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE id=?",id)==1);return id;}
    @Test @Order(1) void fullLifecycleAndUniquePaymentsAndRedemptions()throws Exception{
        long sku=fixture(5),user=sequence.incrementAndGet();String id=created(user,sku);as(user,"USER");assertEquals("PENDING_PAYMENT",orders.detail(id).get("state"));
        assertFalse((Boolean)orders.pay(id,map("outcome","FAIL")).get("paid"));assertTrue((Boolean)orders.pay(id,map("outcome","SUCCESS","amountCent",1)).get("paid"));assertTrue((Boolean)orders.pay(id,map("outcome","REPEAT")).get("duplicate"));
        assertEquals(6800,db.count("SELECT amount_cent FROM tb_ticket_payment WHERE order_id=?",id));assertEquals(1,db.count("SELECT COUNT(*) FROM tb_admission_ticket WHERE order_id=?",id));String credential=text(orders.ticket(id),"credential");
        as(4,"REDEEMER");assertEquals(403,assertThrows(ApiFault.class,()->orders.redeem(map("credential",credential,"clientRequestId",UUID.randomUUID().toString()))).status);
        List<Boolean> results=parallel(100,i->{as(3,"REDEEMER");return (Boolean)orders.redeem(map("credential",credential,"clientRequestId",UUID.randomUUID().toString())).get("duplicate");});assertEquals(1,results.stream().filter(x->!x).count());assertEquals(1,db.count("SELECT COUNT(*) FROM tb_redemption r JOIN tb_admission_ticket t ON t.id=r.ticket_id WHERE t.order_id=?",id));assertEquals("USED",db.need("SELECT state FROM tb_ticket_order WHERE id=?",id).get("state"));assertEquals(4,db.count("SELECT available_stock FROM tb_ticket_sku WHERE id=?",sku));
    }
    @Test @Order(2) void closeRebuyAndOldCreateReleaseReplay()throws Exception{
        long sku=fixture(3),user=sequence.incrementAndGet();String id=created(user,sku);Map<String,Object> e=event(id);as(user,"USER");orders.cancel(id);await(()->"RELEASED".equals(db.need("SELECT release_state FROM tb_ticket_order WHERE id=?",id).get("release_state")));String next=created(user,sku);writer.createBatch(Collections.singletonList(e));
        Long n=ops.redis.execute(RedisOps.script("release.lua",Long.class),Collections.singletonList(RedisOps.STATE),Long.toString(sku),Long.toString(user),id,text(e,"epoch"),"");assertEquals(0L,n);assertEquals(next,ops.field("claim:"+sku+":"+user));assertEquals(2,db.count("SELECT available_stock FROM tb_ticket_sku WHERE id=?",sku));assertEquals("2",ops.field("stock:"+sku));as(user,"USER");assertThrows(ApiFault.class,()->orders.pay(id,map("outcome","SUCCESS")));
    }
    @Test @Order(3) void sameRequest200AndCrossSkuConflict()throws Exception{
        long sku=fixture(20),user=sequence.incrementAndGet();String rid=UUID.randomUUID().toString();List<String> results=parallel(200,i->text(submit(user,sku,rid),"orderId"));String order=results.get(0);for(String r:results)assertEquals(order,r);await(()->db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE id=?",order)==1);assertEquals("19",ops.field("stock:"+sku));long other=fixture(3);as(user,"USER");assertEquals("REQUEST_CONFLICT",assertThrows(ApiFault.class,()->submit(user,other,rid)).code);
    }
    @Test @Order(4) void thousandBuyersStock100AndRequestClaims()throws Exception{
        long sku=fixture(100),base=sequence.addAndGet(2000);List<Boolean> accepted=parallel(1000,i->{try{return "PROCESSING".equals(submit(base+i,sku,UUID.randomUUID().toString()).get("result"));}catch(ApiFault f){if(!Arrays.asList("SOLD_OUT","RATE_LIMITED").contains(f.code))throw f;return false;}});assertEquals(100,accepted.stream().filter(Boolean::booleanValue).count());await(()->db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=?",sku)==100);assertEquals(0,db.count("SELECT available_stock FROM tb_ticket_sku WHERE id=?",sku));assertEquals("0",ops.field("stock:"+sku));assertEquals(100,db.count("SELECT COUNT(*) FROM tb_ticket_active_claim WHERE sku_id=?",sku));
    }
    @Test @Order(5) void paymentAndCancelRace100Each()throws Exception{
        long sku=fixture(2),user=sequence.incrementAndGet();String id=created(user,sku);parallel(200,i->{as(user,"USER");try{if(i%2==0)orders.pay(id,map("outcome","SUCCESS"));else orders.cancel(id);}catch(ApiFault f){assertTrue(Arrays.asList("NOT_CANCELLABLE","NOT_PAYABLE","RATE_LIMITED").contains(f.code));}return true;});Map<String,Object> o=db.need("SELECT * FROM tb_ticket_order WHERE id=?",id);if("PAID".equals(o.get("state"))){assertEquals(1,db.count("SELECT COUNT(*) FROM tb_admission_ticket WHERE order_id=?",id));assertEquals(1,db.count("SELECT available_stock FROM tb_ticket_sku WHERE id=?",sku));}else{assertEquals("CLOSED",o.get("state"));assertEquals(0,db.count("SELECT COUNT(*) FROM tb_admission_ticket WHERE order_id=?",id));await(()->"RELEASED".equals(db.need("SELECT release_state FROM tb_ticket_order WHERE id=?",id).get("release_state")));assertEquals("2",ops.field("stock:"+sku));}
    }
    @Test @Order(6) void luaReservationInterruptedAndRefundInterrupted()throws Exception{
        long sku=fixture(2),user=sequence.incrementAndGet();String id=id(),rid=UUID.randomUUID().toString();DefaultRedisScript<List> script=RedisOps.script("accept.lua",List.class);assertThrows(Exception.class,()->ops.redis.execute(script,Arrays.asList(RedisOps.STATE,RedisOps.STREAM,RedisOps.HEALTH),Long.toString(sku),Long.toString(user),rid,digest(sku+"|1|1"),id,UUID.randomUUID().toString(),"1","5000","50000","30000","after_commit"));assertEquals(id,ops.field("fence:"+sku));assertEquals("1",ops.field("stock:"+sku));pipeline.recover();await(()->db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE id=?",id)==1);assertEquals("",ops.field("fence:"+sku));
        Map<String,Object> e=event(id);as(user,"USER");orders.cancel(id);await(()->"RELEASED".equals(db.need("SELECT release_state FROM tb_ticket_order WHERE id=?",id).get("release_state")));assertEquals("2",ops.field("stock:"+sku));
        // Standalone reservation journal, no DB RELEASE worker can race the injection.
        long isolated=fixture(2),u=sequence.incrementAndGet();String o=id(),epoch=text(db.need("SELECT epoch FROM tb_ticket_sku WHERE id=?",isolated),"epoch");ops.redis.opsForHash().putAll(RedisOps.STATE,map("stock:"+isolated,"1","claim:"+isolated+":"+u,o));DefaultRedisScript<Long> refund=RedisOps.script("release.lua",Long.class);
        assertThrows(Exception.class,()->ops.redis.execute(refund,Collections.singletonList(RedisOps.STATE),Long.toString(isolated),Long.toString(u),o,epoch,"after_commit"));assertEquals("2",ops.field("stock:"+isolated));assertEquals("1",ops.field("released:"+o));assertEquals(0L,ops.redis.execute(refund,Collections.singletonList(RedisOps.STATE),Long.toString(isolated),Long.toString(u),o,epoch,""));assertEquals("2",ops.field("stock:"+isolated));assertEquals("",ops.field("fence:"+isolated));
    }
    @Test @Order(7) void durableRejectionBlocksLateCreate()throws Exception{
        long sku=fixture(2),user=sequence.incrementAndGet();String id=id(),rid=UUID.randomUUID().toString(),epoch=text(db.need("SELECT epoch FROM tb_ticket_sku WHERE id=?",sku),"epoch");Map<String,Object> e=map("schemaVersion",1,"eventId",UUID.randomUUID().toString(),"orderId",id,"userId",Long.toString(user),"skuId",Long.toString(sku),"clientRequestId",rid,"digest",digest(sku+"|1|1"),"acceptedAt",Long.toString(System.currentTimeMillis()),"configVersion","1","epoch",epoch);
        ops.redis.opsForHash().putAll(RedisOps.STATE,map("stock:"+sku,"1","claim:"+sku+":"+user,id));writer.reject(e,2,"确定无法创建，验收拒绝门槛");writer.createBatch(Collections.singletonList(e));assertEquals(0,db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE id=?",id));assertEquals(2,db.count("SELECT available_stock FROM tb_ticket_sku WHERE id=?",sku));await(()->"2".equals(ops.field("stock:"+sku)));assertEquals("REJECTED",db.need("SELECT result FROM tb_ticket_request_result WHERE order_id=?",id).get("result"));
    }
    @Test @Order(8) void missingMetadataFailsClosedAndCannotRewarm()throws Exception{
        long sku=fixture(3);ops.redis.opsForHash().delete(RedisOps.STATE,"meta:"+sku);assertEquals("NOT_READY",assertThrows(ApiFault.class,()->submit(sequence.incrementAndGet(),sku,UUID.randomUUID().toString())).code);as(2,"ADMIN");assertThrows(ApiFault.class,()->admin.control(sku,"warmup"));assertThrows(ApiFault.class,()->admin.control(sku,"publish"));ops.redis.opsForHash().put(RedisOps.STATE,"sale:"+sku,"PAUSED");db.update("UPDATE tb_ticket_sku SET state='PAUSED',control_state='RECONCILE_REQUIRED' WHERE id=?",sku);
    }
    @Test @Order(9) void authenticatedHttpOwnershipAndRoleBoundaries()throws Exception{
        assertEquals(401,rest.getForEntity("/api/v1/orders",Map.class).getStatusCodeValue());long sku=fixture(2),user=sequence.incrementAndGet();String id=created(user,sku);String token=token();ops.redis.opsForHash().put("jy:v1:login:"+token,"user",json(map("id",Long.toString(user+1),"role","USER","name","其他用户")));ops.redis.expire("jy:v1:login:"+token,1800,TimeUnit.SECONDS);HttpHeaders h=new HttpHeaders();h.setBearerAuth(token);HttpEntity<?> request=new HttpEntity<>(h);assertEquals(404,rest.exchange("/api/v1/orders/"+id,HttpMethod.GET,request,Map.class).getStatusCodeValue());assertEquals(403,rest.exchange("/api/v1/admin/ticket-skus",HttpMethod.GET,request,Map.class).getStatusCodeValue());assertEquals(403,rest.exchange("/api/v1/redemptions",HttpMethod.POST,new HttpEntity<>(map("credential","fake","clientRequestId","abc123456"),h),Map.class).getStatusCodeValue());ops.redis.delete("jy:v1:login:"+token);assertEquals(401,rest.exchange("/api/v1/orders",HttpMethod.GET,request,Map.class).getStatusCodeValue());
    }
    @Test @Order(10) void pauseVersionTimeAndBackpressureBeforeReservation()throws Exception{
        long sku=fixture(3),u=sequence.incrementAndGet();as(2,"ADMIN");admin.control(sku,"pause");await(()->"PAUSED".equals(ops.field("sale:"+sku)));Map<String,Object> r=submit(u,sku,UUID.randomUUID().toString());assertEquals("REJECTED",r.get("result"));assertEquals("3",ops.field("stock:"+sku));
        as(2,"ADMIN");admin.control(sku,"publish");as(u,"USER");Map<String,Object> bad=admission.submit(map("skuId",Long.toString(sku),"configVersion","2","clientRequestId",UUID.randomUUID().toString()));assertEquals("REJECTED",bad.get("result"));assertEquals("3",ops.field("stock:"+sku));
        String health=ops.redis.opsForValue().get(RedisOps.HEALTH);ops.redis.opsForValue().set(RedisOps.HEALTH,json(map("at",0,"lag",0)));try{assertEquals("BACKPRESSURE",assertThrows(ApiFault.class,()->submit(sequence.incrementAndGet(),sku,UUID.randomUUID().toString())).code);}finally{ops.redis.opsForValue().set(RedisOps.HEALTH,health);}assertEquals("3",ops.field("stock:"+sku));
    }
    @Test @Order(11) void differentRequests200OneUserOneSku()throws Exception{
        long sku=fixture(10),u=sequence.incrementAndGet();List<String> r=parallel(200,i->{try{return text(submit(u,sku,UUID.randomUUID().toString()),"orderId");}catch(ApiFault f){assertEquals("RATE_LIMITED",f.code);return "";}});await(()->db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=?",sku)==1);assertEquals("9",ops.field("stock:"+sku));assertEquals(1,db.count("SELECT COUNT(*) FROM tb_ticket_active_claim WHERE sku_id=?",sku));
    }
    Map<String,Object> eventFor(long sku,long user){return map("schemaVersion",1,"eventId",UUID.randomUUID().toString(),"orderId",id(),"userId",Long.toString(user),"skuId",Long.toString(sku),"clientRequestId",UUID.randomUUID().toString(),"digest",digest(sku+"|1|1"),"acceptedAt",Long.toString(System.currentTimeMillis()),"configVersion","1","epoch",text(db.need("SELECT epoch FROM tb_ticket_sku WHERE id=?",sku),"epoch"));}
    @Test @Order(12) void sqlInsertFailureRollsBackWholeBatch(){
        long sku=fixture(5);Map<String,Object> a=eventFor(sku,sequence.incrementAndGet()),b=eventFor(sku,sequence.incrementAndGet());String conflicting=id();
        // Fault fixture: a conflicting unique claim makes the SQL insert fail AFTER debit + order insert.
        db.update("INSERT INTO tb_ticket_active_claim(user_id,sku_id,order_id) VALUES(?,?,?)",b.get("userId"),sku,conflicting);
        try{assertThrows(Exception.class,()->writer.createBatch(Arrays.asList(a,b)));assertEquals(5,db.count("SELECT available_stock FROM tb_ticket_sku WHERE id=?",sku));assertEquals(0,db.count("SELECT COUNT(*) FROM tb_ticket_request_result WHERE sku_id=?",sku));assertEquals(0,db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=?",sku));}finally{db.update("DELETE FROM tb_ticket_active_claim WHERE order_id=?",conflicting);}
    }
    @Test @Order(13) void kafkaReplayAfterCommitDoesNotDebitAgain()throws Exception{
        long sku=fixture(3),u=sequence.incrementAndGet();String id=created(u,sku);Map<String,Object> e=event(id);pipeline.kafka.send(pipeline.topic,id,json(e)).get(10,TimeUnit.SECONDS);pipeline.kafka.send(pipeline.topic,id,json(e)).get(10,TimeUnit.SECONDS);Thread.sleep(300);assertEquals(1,db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=?",sku));assertEquals(2,db.count("SELECT available_stock FROM tb_ticket_sku WHERE id=?",sku));
        Map<String,Object> conflict=new LinkedHashMap<>(e);conflict.put("epoch","different");assertThrows(ApiFault.class,()->writer.createBatch(Collections.singletonList(conflict)));
    }
    @Test @Order(14) void poisonRecordStoredBeforeAcknowledgment(){
        String payload="{invalid_"+sequence.incrementAndGet();java.util.concurrent.atomic.AtomicBoolean ack=new java.util.concurrent.atomic.AtomicBoolean();org.apache.kafka.clients.consumer.ConsumerRecord<String,String> r=new org.apache.kafka.clients.consumer.ConsumerRecord<>(pipeline.topic,15,sequence.incrementAndGet(),"unknown",payload);
        pipeline.consume(Collections.singletonList(r),()->{assertEquals(1,db.count("SELECT COUNT(*) FROM tb_ticket_failure WHERE original_payload=? AND original_partition=15 AND original_offset=?",payload,r.offset()));ack.set(true);});assertTrue(ack.get());
        db.update("UPDATE tb_ticket_failure SET state='QUARANTINED' WHERE original_payload=?",payload);
    }
    @Test @Order(15) void noteRelikeOldReplayAndAnotherUserVersions()throws Exception{
        long blog=sequence.incrementAndGet(),u=sequence.incrementAndGet(),other=sequence.incrementAndGet();db.update("INSERT INTO tb_blog(id,shop_id,user_id,title,content,images) VALUES(?,?,1,'点赞验收','验收数据','')",blog,fixtureAttraction);db.update("INSERT INTO tb_blog_like_baseline(blog_id,anonymous_likes) VALUES(?,0)",blog);for(long id:new long[]{u,other})db.update("INSERT INTO tb_user(id,phone,nick_name,role) VALUES(?,?,?,'USER')",id,"T"+id,"验收用户");
        com.hmdp.service.like.async.AsyncBlogLikeService likes=contextLikes();likes.change(blog,u,true);likes.change(blog,u,false);likes.change(blog,u,true);likes.change(blog,other,true);
        await(()->db.count("SELECT liked FROM tb_blog WHERE id=?",blog)==2);String table="tb_blog_like_state_"+String.format("%02d",blog%16);long version=db.count("SELECT version FROM "+table+" WHERE blog_id=? AND user_id=?",blog,u);
        com.hmdp.service.like.async.LikeShardRouter router=new com.hmdp.service.like.async.LikeShardRouter();String stream=router.prefix(blog)+"outbox";
        ops.redis.execute(new DefaultRedisScript<String>("redis.call('INCR',KEYS[2]);return redis.call('XADD',KEYS[1],'*','blogId',ARGV[1],'userId',ARGV[2],'version',ARGV[3],'liked','0','time',ARGV[4])",String.class),Arrays.asList(stream,router.prefix(blog)+"pending:"+blog),Long.toString(blog),Long.toString(u),Long.toString(version-1),Long.toString(System.currentTimeMillis()));await(()->ops.redis.opsForStream().size(stream)==0);assertEquals(2,db.count("SELECT liked FROM tb_blog WHERE id=?",blog));assertEquals(2,db.count("SELECT COUNT(*) FROM tb_blog_like WHERE blog_id=?",blog));
    }
    @Autowired com.hmdp.service.like.async.AsyncBlogLikeService likeService;
    com.hmdp.service.like.async.AsyncBlogLikeService contextLikes(){return likeService;}
    @Autowired CatalogCache cache;
    @Test @Order(16) void versionedCacheRejectsOldFillAndReliableOutbox()throws Exception{
        long attraction=1;Map<String,Object> old=cache.detail(attraction);as(2,"ADMIN");Map<String,Object> row=db.need("SELECT s.*,p.* FROM tb_shop s JOIN tb_attraction_profile p ON p.shop_id=s.id WHERE s.id=1");long version=number(row,"content_version")+1;
        admin.editAttraction(1,map("name",row.get("name"),"address",row.get("address"),"openHours",row.get("open_hours"),"image",row.get("images"),"introduction",row.get("introduction"),"admissionNotice",row.get("admission_notice"),"published",true));
        await(()->db.count("SELECT COUNT(*) FROM tb_ticket_outbox WHERE event_id=? AND state='DONE'","cache:1:"+version)==1);assertEquals(Long.toString(version),text(cache.detail(1),"contentVersion"));
        String key="jy:v1:catalog:{1}";assertEquals(0L,ops.redis.execute(cache.fill,Arrays.asList(key+":version",key+":value"),text(old,"contentVersion"),json(old),"120"));assertEquals("CONTENT_CHANGED",assertThrows(ApiFault.class,()->cache.remember(1,old)).code);assertEquals(Long.toString(version),text(cache.detail(1),"contentVersion"));
    }
    @Test @Order(17) void missedNotificationExpiresLocalCache()throws Exception{
        // An independent L1 deliberately has no Pub/Sub subscription, modelling a dropped notification.
        CatalogCache disconnected=new CatalogCache(db,ops,admin.redisson);Map<String,Object> old=disconnected.detail(1);long v=number(old,"contentVersion")+1;
        as(2,"ADMIN");admin.editAttraction(1,map("name",old.get("name"),"address",old.get("address"),"openHours",old.get("openHours"),"image",db.need("SELECT images FROM tb_shop WHERE id=1").get("images"),"introduction",old.get("introduction"),"admissionNotice",old.get("admissionNotice"),"published",true));
        await(()->db.count("SELECT COUNT(*) FROM tb_ticket_outbox WHERE event_id=? AND state='DONE'","cache:1:"+v)==1);assertEquals(text(old,"contentVersion"),text(disconnected.detail(1),"contentVersion"));Thread.sleep(31000);assertEquals(Long.toString(v),text(disconnected.detail(1),"contentVersion"));
    }
    @Test @Order(18) void expiryClosesUnpaidButNeverRefundsPaid()throws Exception{
        long sku=fixture(3),u=sequence.incrementAndGet();String unpaid=created(u,sku);Map<String,Object> o=db.need("SELECT * FROM tb_ticket_order WHERE id=?",unpaid);assertEquals(900000,number(o,"pay_deadline")-number(o,"created_at"));
        // Controlled clock boundary: move the deadline, never directly change an order state.
        db.update("UPDATE tb_ticket_order SET pay_deadline=? WHERE id=?",System.currentTimeMillis()-1,unpaid);orders.expiry();await(()->"RELEASED".equals(db.need("SELECT release_state FROM tb_ticket_order WHERE id=?",unpaid).get("release_state")));assertEquals("3",ops.field("stock:"+sku));
        String paid=created(u,sku);as(u,"USER");orders.pay(paid,map("outcome","SUCCESS"));String credential=text(orders.ticket(paid),"credential");long past=System.currentTimeMillis()-1;db.update("UPDATE tb_admission_ticket SET valid_until=? WHERE order_id=?",past,paid);db.update("UPDATE tb_ticket_order SET valid_until=? WHERE id=?",past,paid);
        as(3,"REDEEMER");assertEquals("TICKET_UNUSABLE",assertThrows(ApiFault.class,()->orders.redeem(map("credential",credential,"clientRequestId",UUID.randomUUID().toString()))).code);orders.expiry();assertEquals("EXPIRED",db.need("SELECT state FROM tb_ticket_order WHERE id=?",paid).get("state"));assertEquals(2,db.count("SELECT available_stock FROM tb_ticket_sku WHERE id=?",sku));assertEquals("2",ops.field("stock:"+sku));assertEquals(1,db.count("SELECT COUNT(*) FROM tb_ticket_active_claim WHERE order_id=?",paid));
    }
    @Test @Order(19) void futureDateIndependentInventoryAndEarlyRedemptionRejected()throws Exception{
        long sku=fixture(3),product=db.count("SELECT product_id FROM tb_ticket_sku WHERE id=?",sku);LocalDate next=LocalDate.now(ZONE).plusDays(1);long start=next.atStartOfDay(ZONE).toInstant().toEpochMilli(),end=next.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()-1;as(2,"ADMIN");Map<String,Object> out=(Map<String,Object>)admin.sku(map("productId",Long.toString(product),"visitDate",next.toString(),"validFrom",instant(start),"validUntil",instant(end),"saleStart",instant(System.currentTimeMillis()-60000),"saleEnd",instant(end-900000),"priceCent",7000,"totalStock",4));long other=number(out,"skuId");admin.control(other,"warmup");admin.control(other,"publish");long u=sequence.incrementAndGet();String first=created(u,sku),future=created(u,other);as(u,"USER");orders.pay(future,map("outcome","SUCCESS"));String credential=text(orders.ticket(future),"credential");assertEquals("2",ops.field("stock:"+sku));assertEquals("3",ops.field("stock:"+other));as(3,"REDEEMER");assertEquals("TICKET_UNUSABLE",assertThrows(ApiFault.class,()->orders.redeem(map("credential",credential,"clientRequestId",UUID.randomUUID().toString()))).code);
        String forged=credential.substring(0,credential.length()-1)+(credential.endsWith("A")?"B":"A");assertEquals("INVALID_TICKET",assertThrows(ApiFault.class,()->orders.redeem(map("credential",forged,"clientRequestId",UUID.randomUUID().toString()))).code);
    }
    @Test @Order(20) void oneTimeCodeAndExpiredTokenCannotRenew()throws Exception{
        ResponseEntity<Map> c=rest.postForEntity("/api/v1/auth/code",map("phone","13900000001"),Map.class);assertEquals(200,c.getStatusCodeValue());Map<String,Object> b=map("phone","13900000001","code",c.getBody().get("code"),"role","ADMIN","userId","2");ResponseEntity<Map> login=rest.postForEntity("/api/v1/auth/login",b,Map.class);assertEquals(200,login.getStatusCodeValue());assertEquals("USER",((Map<?,?>)login.getBody().get("user")).get("role"));assertEquals(400,rest.postForEntity("/api/v1/auth/login",b,Map.class).getStatusCodeValue());String token=text(login.getBody(),"token");ops.redis.expire("jy:v1:login:"+token,1,TimeUnit.MILLISECONDS);Thread.sleep(5);HttpHeaders h=new HttpHeaders();h.setBearerAuth(token);assertEquals(401,rest.exchange("/api/v1/auth/me",HttpMethod.GET,new HttpEntity<>(h),Map.class).getStatusCodeValue());assertFalse(Boolean.TRUE.equals(ops.redis.hasKey("jy:v1:login:"+token)));
    }
    @Test @Order(21) void insufficientRemainingWindowDurablyRejectsWithoutAddingDbStock()throws Exception{
        long sku=fixture(2);Map<String,Object> original=db.need("SELECT * FROM tb_ticket_sku WHERE id=?",sku);long until=System.currentTimeMillis()+30000,saleEnd=until-900000;db.update("UPDATE tb_ticket_sku SET valid_until=?,sale_end=? WHERE id=?",until,saleEnd,sku);Map<String,Object> e=eventFor(sku,sequence.incrementAndGet());e.put("acceptedAt",Long.toString(saleEnd-1));String order=text(e,"orderId");ops.redis.opsForHash().putAll(RedisOps.STATE,map("stock:"+sku,"1","claim:"+sku+":"+text(e,"userId"),order));
        writer.createBatch(Collections.singletonList(e));assertEquals("REJECTED",db.need("SELECT result FROM tb_ticket_request_result WHERE order_id=?",order).get("result"));assertEquals(2,db.count("SELECT available_stock FROM tb_ticket_sku WHERE id=?",sku));await(()->"2".equals(ops.field("stock:"+sku)));writer.createBatch(Collections.singletonList(e));assertEquals(0,db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE id=?",order));
    }
    @Test @Order(23) void archivedJournalPreservesRequestAndReplayIdempotency()throws Exception{
        long sku=fixture(3),user=sequence.incrementAndGet();String rid=UUID.randomUUID().toString();String id=text(submit(user,sku,rid),"orderId");Map<String,Object> original=event(id);
        await(()->db.count("SELECT COUNT(*) FROM tb_ticket_request_result WHERE order_id=? AND result='CREATED'",id)==1&&ops.field("pending:"+id).isEmpty());
        // Invoke the same compaction script on an isolated durable gate; production adds a 60s age condition.
        List compacted=ops.redis.execute(RedisOps.script("journal_gc.lua",List.class),Collections.singletonList(RedisOps.STATE),id,Long.toString(user),rid);
        assertTrue(compacted.contains(id));assertEquals("",ops.field("req:"+user+":"+rid));assertEquals(id,ops.field("claim:"+sku+":"+user));
        assertEquals(id,text(submit(user,sku,rid),"orderId"));writer.createBatch(Collections.singletonList(original));assertEquals("2",ops.field("stock:"+sku));assertEquals(2,db.count("SELECT available_stock FROM tb_ticket_sku WHERE id=?",sku));
        as(user,"USER");orders.cancel(id);await(()->"3".equals(ops.field("stock:"+sku)));String replacement=created(user,sku);writer.createBatch(Collections.singletonList(original));
        assertEquals(replacement,ops.field("claim:"+sku+":"+user));assertEquals("2",ops.field("stock:"+sku));assertEquals(1,db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE id=? AND state='CLOSED'",id));
        pipeline.compactJournal();assertFalse(ops.field("released:"+id).isEmpty());
    }
    @Test @Order(24) void skuMoneyBoundsAndDraftProductEditing()throws Exception{
        long sku=fixture(1),product=db.count("SELECT product_id FROM tb_ticket_sku WHERE id=?",sku);as(2,"ADMIN");LocalDate d=LocalDate.now(ZONE).plusDays(2);long from=d.atStartOfDay(ZONE).toInstant().toEpochMilli(),until=d.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli()-1;
        Map<String,Object> b=map("productId",Long.toString(product),"visitDate",d.toString(),"validFrom",instant(from),"validUntil",instant(until),"saleStart",instant(System.currentTimeMillis()-60000),"saleEnd",instant(until-900000),"priceCent",4294967296L,"totalStock",1);
        assertEquals("INVALID_PARAMETER",assertThrows(ApiFault.class,()->admin.sku(b)).code);b.put("priceCent",0);long free=number((Map<String,Object>)admin.sku(b),"skuId");assertEquals(0,db.count("SELECT price_cent FROM tb_ticket_sku WHERE id=?",free));
        assertEquals("PRODUCT_FROZEN",assertThrows(ApiFault.class,()->admin.editProduct(product,map("name","不可改","description","已发布"))).code);
        admin.editProduct(product,map("status","INACTIVE"));await(()->db.count("SELECT COUNT(*) FROM tb_ticket_sku WHERE id=? AND control_state=''",sku)==1);assertEquals("INVALID_STATE",assertThrows(ApiFault.class,()->admin.control(sku,"publish")).code);
    }
    @Test @Order(25) void massExpiryBatchesPreservePaidOrderAndRefundExactlyOnce()throws Exception{
        long sku=fixture(201),base=sequence.addAndGet(1000);List<String> ids=parallel(201,i->text(submit(base+i,sku,UUID.randomUUID().toString()),"orderId"));
        await(()->db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=?",sku)==201);String paid=ids.get(200);as(base+200,"USER");orders.pay(paid,map("outcome","SUCCESS"));
        db.update("UPDATE tb_ticket_order SET pay_deadline=? WHERE sku_id=? AND state='PENDING_PAYMENT'",System.currentTimeMillis()-1,sku);orders.expiry();
        await(()->db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE sku_id=? AND state='CLOSED' AND release_state='RELEASED'",sku)==200);
        assertEquals("200",ops.field("stock:"+sku));assertEquals(200,db.count("SELECT available_stock FROM tb_ticket_sku WHERE id=?",sku));assertEquals(1,db.count("SELECT COUNT(*) FROM tb_ticket_active_claim WHERE sku_id=?",sku));
        assertEquals(1,db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE id=? AND state='PAID'",paid));orders.expiry();pipeline.dispatchDb();assertEquals("200",ops.field("stock:"+sku));
    }
    @Test @Order(26) void reconciliationSkipsUnreservedRejectionsAndReadsConsistentSqlSnapshot()throws Exception{
        long sku=fixture(1),user=sequence.incrementAndGet();String order=created(user,sku);Map<String,Object> rejected=submit(sequence.incrementAndGet(),sku,UUID.randomUUID().toString());assertEquals("REJECTED",text(rejected,"result"));as(2,"ADMIN");
        Map<String,Object> snapshot=(Map<String,Object>)admin.reconcile();List<Map<String,Object>> items=(List<Map<String,Object>>)snapshot.get("items");Map<String,Object> row=items.stream().filter(i->Long.toString(sku).equals(text(i,"skuId"))).findFirst().get();assertEquals(true,row.get("dbInvariant"));assertEquals(1L,row.get("PENDING_PAYMENT"));assertEquals(0,row.get("processing"));
    }
    @Test @Order(27) void serverEnforcesSaleWindowsAndIgnoresClientMoneyAndIdentity()throws Exception{
        long sku=fixture(3),user=sequence.incrementAndGet();String raw=ops.field("meta:"+sku);Map<String,Object> meta=object(raw);long now=System.currentTimeMillis();
        meta.put("saleStart",Long.toString(now+60000));ops.redis.opsForHash().put(RedisOps.STATE,"meta:"+sku,json(meta));assertEquals("REJECTED",text(submit(user,sku,UUID.randomUUID().toString()),"result"));
        meta.put("saleStart",Long.toString(now-60000));meta.put("saleEnd",Long.toString(now-1));ops.redis.opsForHash().put(RedisOps.STATE,"meta:"+sku,json(meta));assertEquals("REJECTED",text(submit(user,sku,UUID.randomUUID().toString()),"result"));assertEquals("3",ops.field("stock:"+sku));
        ops.redis.opsForHash().put(RedisOps.STATE,"meta:"+sku,raw);as(user,"USER");Map<String,Object> result=admission.submit(map("skuId",Long.toString(sku),"configVersion","1","clientRequestId",UUID.randomUUID().toString(),"priceCent",1,"userId","999","visitDate","1900-01-01"));String order=text(result,"orderId");await(()->db.count("SELECT COUNT(*) FROM tb_ticket_order WHERE id=?",order)==1);assertEquals(user,db.count("SELECT user_id FROM tb_ticket_order WHERE id=?",order));assertEquals(6800,db.count("SELECT amount_cent FROM tb_ticket_order WHERE id=?",order));
        as(2,"ADMIN");admin.control(sku,"end");assertEquals("REJECTED",text(submit(sequence.incrementAndGet(),sku,UUID.randomUUID().toString()),"result"));assertEquals("2",ops.field("stock:"+sku));assertEquals("NOT_READY",assertThrows(ApiFault.class,()->submit(user,Long.MAX_VALUE,UUID.randomUUID().toString())).code);
    }
    @Test @Order(28) void failedCacheInvalidationRemainsDurableAndRebuildFailureDoesNotCacheOldData()throws Exception{
        long a=fixtureAttraction;Map<String,Object> old=cache.detail(a);String key="jy:v1:catalog:{"+a+"}",versionKey=key+":version";ops.redis.delete(versionKey);ops.redis.opsForHash().put(versionKey,"injected","wrong-type");long version=number(old,"contentVersion")+1;as(2,"ADMIN");
        try{
            admin.editAttraction(a,map("name",old.get("name"),"address",old.get("address"),"openHours",old.get("openHours"),"image","/assets/valley.webp","introduction","缓存故障恢复后的介绍","admissionNotice",old.get("admissionNotice"),"published",true));pipeline.dispatchDb();
            assertEquals(1,db.count("SELECT COUNT(*) FROM tb_ticket_outbox WHERE event_id=? AND state='PENDING'","cache:"+a+":"+version));cache.local.invalidate(a);ops.redis.delete(key+":value");assertThrows(Exception.class,()->cache.detail(a));assertNull(cache.local.getIfPresent(a));
        }finally{ops.redis.delete(versionKey);db.update("UPDATE tb_ticket_outbox SET next_attempt_at=0 WHERE event_id=?","cache:"+a+":"+version);}
        pipeline.dispatchDb();await(()->db.count("SELECT COUNT(*) FROM tb_ticket_outbox WHERE event_id=? AND state='DONE'","cache:"+a+":"+version)==1);assertEquals("缓存故障恢复后的介绍",cache.detail(a).get("introduction"));
    }
    @Test @Order(99) void allSqlInventoryAndIdentityInvariantsAfterDrain()throws Exception{
        await(()->ops.redis.opsForStream().size(RedisOps.STREAM)==0&&db.count("SELECT COUNT(*) FROM tb_ticket_outbox WHERE state='PENDING'")==0);
        assertEquals(0,db.count("SELECT COUNT(*) FROM tb_ticket_sku s WHERE s.total_stock<>s.available_stock+(SELECT COUNT(*) FROM tb_ticket_order o WHERE o.sku_id=s.id AND o.state IN('PENDING_PAYMENT','PAID','USED','EXPIRED'))"));
        assertEquals(0,db.count("SELECT COUNT(*) FROM tb_ticket_order o LEFT JOIN tb_ticket_active_claim c ON c.order_id=o.id AND c.sku_id=o.sku_id AND c.user_id=o.user_id WHERE o.state IN('PENDING_PAYMENT','PAID','USED','EXPIRED') AND c.order_id IS NULL"));
        assertEquals(0,db.count("SELECT COUNT(*) FROM tb_ticket_active_claim c LEFT JOIN tb_ticket_order o ON o.id=c.order_id AND o.user_id=c.user_id AND o.sku_id=c.sku_id WHERE o.id IS NULL OR o.state='CLOSED'"));
        assertEquals(0,db.count("SELECT COUNT(*) FROM tb_ticket_order o LEFT JOIN tb_ticket_payment p ON p.order_id=o.id LEFT JOIN tb_admission_ticket t ON t.order_id=o.id WHERE o.state IN('PAID','USED','EXPIRED') AND (p.id IS NULL OR t.id IS NULL OR p.amount_cent<>o.amount_cent)"));
        for(Map<String,Object> s:db.rows("SELECT id,available_stock,control_state FROM tb_ticket_sku")){if(!text(s,"control_state").isEmpty()||ops.field("meta:"+text(s,"id")).isEmpty())continue;assertEquals(text(s,"available_stock"),ops.field("stock:"+text(s,"id")),"Redis/DB余量不一致 sku="+text(s,"id"));}
    }
    <T> List<T> parallel(int count,java.util.function.IntFunction<T> fn)throws Exception{ExecutorService pool=Executors.newFixedThreadPool(Math.min(64,count));List<Future<T>> futures=new ArrayList<>();try{for(int i=0;i<count;i++){final int n=i;futures.add(pool.submit(()->{try{return fn.apply(n);}finally{Identity.clear();}}));}List<T> out=new ArrayList<>();for(Future<T> f:futures)out.add(f.get(60,TimeUnit.SECONDS));return out;}finally{pool.shutdownNow();}}
}
