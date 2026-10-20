package com.jingyue;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.script.RedisScript;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;
import static com.jingyue.Values.*;

@Component
public class TicketPipeline {
    final Db db;final RedisOps ops;final TicketWriter writer;final KafkaTemplate<String,String> kafka;final CatalogCache cache;
    final Set<String> sending=ConcurrentHashMap.newKeySet();
    final RedisScript<Long> published=RedisOps.script("publish_ack.lua",Long.class),repair=RedisOps.script("repair.lua",Long.class),release=RedisOps.script("release.lua",Long.class);
    final RedisScript<Long> publishedBatch=RedisOps.script("publish_ack_batch.lua",Long.class);
    final BlockingQueue<PublishAck> publishAcks=new ArrayBlockingQueue<>(10000);
    final RedisScript<List> journalGc=RedisOps.script("journal_gc.lua",List.class);
    @Value("${jingyue.topic}") String topic;
    @Value("${jingyue.group}") String group;
    @Value("${jingyue.journal-gc.mode:queue}") String journalGcMode;
    final RedisScript<List> releaseBatch=RedisOps.scriptText("local function releaseOne(ARGV)\n"+com.hmdp.utils.RedisScriptContent.read("lua/release.lua")+"\nend\nlocal out={} for i=1,#ARGV,4 do out[#out+1]=releaseOne({unpack(ARGV,i,i+3)}) end return out",List.class);
    final AdminClient admin;
    final Logger log=LoggerFactory.getLogger(getClass());
    public TicketPipeline(Db db,RedisOps ops,TicketWriter writer,KafkaTemplate<String,String> kafka,CatalogCache cache,@Value("${spring.kafka.bootstrap-servers}") String bootstrap) {
        this.db=db;this.ops=ops;this.writer=writer;this.kafka=kafka;this.cache=cache;
        Properties p=new Properties();p.put("bootstrap.servers",bootstrap);p.put("default.api.timeout.ms","3000");p.put("request.timeout.ms","2000");admin=AdminClient.create(p);
    }
    @PreDestroy public void stop() { admin.close(java.time.Duration.ofSeconds(2)); }
    @Scheduled(fixedDelay=30) public void publish() {
        try {
            List<MapRecord<String,Object,Object>> records=ops.redis.opsForStream().range(RedisOps.STREAM,org.springframework.data.domain.Range.unbounded(),org.springframework.data.redis.connection.RedisZSetCommands.Limit.limit().count(500));
            if(records==null)return;
            for(MapRecord<String,Object,Object> r:records) {
                String streamId=r.getId().getValue(),payload=r.getValue().get("event").toString();
                if(!sending.add(streamId))continue;
                Map<String,Object> e=object(payload);
                try { kafka.send(topic,text(e,"orderId"),payload).addCallback(ok->{
                    if(!publishAcks.offer(new PublishAck(streamId,text(e,"orderId")))) log.warn("确认清理队列已满，保留原事件等待重试");
                    sending.remove(streamId);
                },ex->{sending.remove(streamId);log.warn("发送未确认，保留原事件重试");});
                } catch(Exception ex) { sending.remove(streamId);throw ex; }
            }
        }catch(Exception e){log.warn("待发送处理暂不可用：{}",e.getClass().getSimpleName());}
    }
    @Scheduled(fixedDelay=10) public void flushPublishAcks() {
        List<PublishAck> batch=new ArrayList<>(250);
        try {
            publishAcks.drainTo(batch,250); if(batch.isEmpty()) return;
            List<String> args=new ArrayList<>(batch.size()*2);
            for(PublishAck a:batch){args.add(a.streamId);args.add(a.orderId);}
            Long done=ops.redis.execute(publishedBatch,Arrays.asList(RedisOps.STATE,RedisOps.STREAM),args.toArray());
            if(done==null||done!=batch.size()) throw new IllegalStateException("确认清理数量不一致");
        } catch(Exception ex) {
            for(PublishAck a:batch) publishAcks.offer(a);
            log.warn("Kafka确认清理失败，保留事件重试：{}",ex.getClass().getSimpleName());
        }
    }
    static final class PublishAck { final String streamId,orderId; PublishAck(String streamId,String orderId){this.streamId=streamId;this.orderId=orderId;} }
    // Continue the hash scan across scheduling ticks; restarting at cursor 0 can starve late repair entries.
    String repairCursor="0";
    final RedisScript<List> repairScan=RedisOps.scriptText("return redis.call('HSCAN',KEYS[1],ARGV[1],'MATCH','repair:*','COUNT',2000)",List.class);
    @Scheduled(fixedDelay=1000) public synchronized void recover() {
        try {
            for(int page=0;page<20;page++){
                List scan=ops.redis.execute(repairScan,Collections.singletonList(RedisOps.STATE),repairCursor);
                if(scan==null||scan.size()!=2)throw new IllegalStateException("恢复扫描结果不完整");
                repairCursor=scan.get(0).toString();List entries=(List)scan.get(1);
                for(int i=0;i<entries.size();i+=2)if(!entries.get(i+1).toString().isEmpty())ops.redis.execute(repair,Arrays.asList(RedisOps.STATE,RedisOps.STREAM),entries.get(i).toString().substring(7));
                if("0".equals(repairCursor))break;
            }
        }catch(Exception e){log.warn("受理恢复扫描暂不可用：{}",e.getClass().getSimpleName());}
    }
    @Scheduled(fixedDelay=1000) public void compactJournal() {
        try {
            if("queue".equalsIgnoreCase(journalGcMode)) { compactJournalQueue(); return; }
            List<Map<String,Object>> rows=db.rows("SELECT r.order_id,r.user_id,r.client_request_id FROM tb_ticket_request_result r LEFT JOIN tb_ticket_journal_gc g ON g.order_id=r.order_id WHERE g.order_id IS NULL AND r.result IN('CREATED','REJECTED') AND r.accepted_at<? ORDER BY r.accepted_at,r.order_id LIMIT 2000",System.currentTimeMillis()-60000);
            if(rows.isEmpty())return;List<String> args=new ArrayList<>();for(Map<String,Object> r:rows)Collections.addAll(args,text(r,"order_id"),text(r,"user_id"),text(r,"client_request_id"));
            List done=ops.redis.execute(journalGc,Collections.singletonList(RedisOps.STATE),args.toArray());if(done==null||done.isEmpty())return;
            List<Object> values=new ArrayList<>();long now=System.currentTimeMillis();for(Object id:done)Collections.addAll(values,id.toString(),now);
            db.update("INSERT IGNORE INTO tb_ticket_journal_gc(order_id,archived_at) VALUES "+TicketWriter.tuples(done.size(),2),values.toArray());
        }catch(Exception ex){log.warn("已落库日志归档暂未完成，将幂等重试：{}",ex.getClass().getSimpleName());}
    }
    void compactJournalQueue() {
        long now=System.currentTimeMillis();
        List<Map<String,Object>> rows=db.rows("SELECT * FROM tb_ticket_journal_gc_task WHERE state='PENDING' AND next_attempt_at<=? ORDER BY next_attempt_at,order_id LIMIT 200",now);
        for(Map<String,Object> r:rows) {
            String order=text(r,"order_id");
            try {
                List done=ops.redis.execute(journalGc,Collections.singletonList(RedisOps.STATE),order,text(r,"user_id"),text(r,"client_request_id"));
                if(done==null||done.isEmpty()) { db.update("UPDATE tb_ticket_journal_gc_task SET attempts=attempts+1,next_attempt_at=?,last_error=? WHERE order_id=? AND state='PENDING'",now+5000,"Redis仍有待处理事件",order); continue; }
                db.tx(()->{db.update("INSERT IGNORE INTO tb_ticket_journal_gc(order_id,archived_at) VALUES(?,?)",order,now);db.update("UPDATE tb_ticket_journal_gc_task SET state='DONE',completed_at=?,last_error='' WHERE order_id=? AND state='PENDING'",now,order);return null;});
            } catch(Exception ex) { db.update("UPDATE tb_ticket_journal_gc_task SET attempts=attempts+1,next_attempt_at=?,last_error=? WHERE order_id=? AND state='PENDING'",now+Math.min(60000,1000L*(1+number(r,"attempts"))),ex.getClass().getSimpleName(),order); }
        }
    }
    @KafkaListener(topics="${jingyue.topic}",groupId="${jingyue.group}")
    public void consume(List<ConsumerRecord<String,String>> batch,Acknowledgment ack) {
        List<Map<String,Object>> events=new ArrayList<>();
        try {for(ConsumerRecord<String,String> r:batch)events.add(object(r.value()));writer.createBatch(events);}
        catch(Exception batchFailure) {
            // A failed batch rolled back entirely. Isolate only after durable storage succeeds.
            for(ConsumerRecord<String,String> r:batch) {
                try {writer.createBatch(Collections.singletonList(object(r.value())));}
                catch(Exception e){saveFailure(r,e);}
            }
        }
        ack.acknowledge(); // Every event is committed or durably isolated before advancing offsets.
    }
    void saveFailure(ConsumerRecord<String,String> r,Exception error) {
        Map<String,Object> e;try{e=object(r.value());}catch(Exception x){e=new HashMap<>();}
        String eid=text(e,"eventId");if(eid.length()>50)eid=eid.substring(0,50);
        // Keep separate durable evidence when conflicting payloads reuse a logical event ID.
        // The complete original eventId remains in original_payload; retries of this location upsert.
        eid=eid+"#"+digest(r.topic()+":"+r.partition()+":"+r.offset()).substring(0,32);
        String code=error instanceof ApiFault?((ApiFault)error).code:"DATABASE_RETRY";
        db.update("INSERT INTO tb_ticket_failure(event_id,order_id,sku_id,original_payload,original_topic,original_partition,original_offset,error_code,reason,first_at,last_at) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE attempts=attempts+1,last_at=VALUES(last_at),error_code=VALUES(error_code)",eid,text(e,"orderId"),text(e,"skuId"),r.value(),r.topic(),r.partition(),r.offset(),code,"事件未创建，保留原文等待重试或人工核对",System.currentTimeMillis(),System.currentTimeMillis());
        if(Arrays.asList("STOCK_CONFLICT","CLAIM_CONFLICT","CONFIG_CONFLICT","EVENT_IDENTITY").contains(code)&&text(e,"skuId").matches("[0-9]+")) {
            final Map<String,Object> conflict=e;
            db.tx(()->{db.update("UPDATE tb_ticket_sku SET control_state='RECONCILE_REQUIRED' WHERE id=?",conflict.get("skuId"));writer.outbox("freeze:"+text(conflict,"eventId"),text(conflict,"skuId"),"PAUSE",map("skuId",text(conflict,"skuId")));return null;});
        }
    }
    @Scheduled(fixedDelay=5000) public void retryFailures() {
        try {for(Map<String,Object> f:db.rows("SELECT * FROM tb_ticket_failure WHERE state='OPEN' AND error_code='DATABASE_RETRY' AND attempts<5 ORDER BY id LIMIT 50")) retry(f,0);}
        catch(Exception e){log.warn("异常重试暂不可用：{}",e.getClass().getSimpleName());}
    }
    public void retry(Map<String,Object> f,long actor) {
        try {writer.createBatch(Collections.singletonList(object(text(f,"original_payload"))));db.tx(()->{db.update("UPDATE tb_ticket_failure SET state='RESOLVED',last_at=? WHERE id=?",System.currentTimeMillis(),f.get("id"));db.audit(actor,"RETRY",text(f,"order_id"),"OPEN","RESOLVED");return null;});}
        catch(Exception e){db.tx(()->{db.update("UPDATE tb_ticket_failure SET attempts=attempts+1,last_at=? WHERE id=?",System.currentTimeMillis(),f.get("id"));db.audit(actor,"RETRY",text(f,"order_id"),text(f,"state"),"RETRY_FAILED");return null;});throw e;}
    }
    @Scheduled(fixedDelay=100) public void dispatchDb() {
        try {
            List<Map<String,Object>> tasks=db.rows("SELECT * FROM tb_ticket_outbox WHERE state='PENDING' AND next_attempt_at<=? ORDER BY created_at,event_id LIMIT 1000",System.currentTimeMillis());if(tasks.isEmpty())return;
            List<String> refundArgs=new ArrayList<>();for(Map<String,Object> task:tasks)if("RELEASE".equals(text(task,"event_type"))){Map<String,Object> e=object(text(task,"payload"));Collections.addAll(refundArgs,text(e,"skuId"),text(e,"userId"),text(e,"orderId"),text(e,"epoch"));}
            List codes=refundArgs.isEmpty()?Collections.emptyList():ops.redis.execute(releaseBatch,Collections.singletonList(RedisOps.STATE),refundArgs.toArray());
            if(codes==null||codes.size()*4!=refundArgs.size())throw new IllegalStateException("归还批次结果待确认");
            int cursor=0;List<Object> done=new ArrayList<>(),closed=new ArrayList<>(),paused=new ArrayList<>();
            for(Map<String,Object> task:tasks){Map<String,Object> e=object(text(task,"payload"));String type=text(task,"event_type");Long releaseCode="RELEASE".equals(type)?((Number)codes.get(cursor++)).longValue():null;
                try {
                    if("RELEASE".equals(type)) {
                        if(releaseCode<0){if(releaseCode!=-3){db.update("UPDATE tb_ticket_sku SET control_state='RECONCILE_REQUIRED' WHERE id=?",e.get("skuId"));ops.redis.opsForHash().put(RedisOps.STATE,"sale:"+text(e,"skuId"),"PAUSED");}throw new IllegalStateException("归还需要恢复或对账");}
                        closed.add(e.get("orderId"));
                    }else if("CACHE".equals(type))cache.invalidate(Long.parseLong(text(e,"attractionId")),number(e,"version"));
                    else if("PAUSE".equals(type)){ops.redis.opsForHash().put(RedisOps.STATE,"sale:"+text(e,"skuId"),"PAUSED");paused.add(e.get("skuId"));}
                    else throw new IllegalStateException("未识别的待办类型");
                    done.add(task.get("event_id"));
                }catch(Exception ex){db.update("UPDATE tb_ticket_outbox SET attempts=attempts+1,next_attempt_at=?,last_error=? WHERE event_id=?",System.currentTimeMillis()+3000,"依赖暂不可用或需核对："+ex.getClass().getSimpleName(),task.get("event_id"));}
            }
            // If SQL commit fails, all original tasks remain pending and the same identities replay safely.
            if(!done.isEmpty())db.tx(()->{db.update("UPDATE tb_ticket_outbox SET state='DONE',last_error='' WHERE event_id IN ("+TicketWriter.marks(done.size())+")",done.toArray());
                if(!closed.isEmpty())db.update("UPDATE tb_ticket_order SET release_state='RELEASED' WHERE state='CLOSED' AND id IN ("+TicketWriter.marks(closed.size())+")",closed.toArray());
                if(!paused.isEmpty())db.update("UPDATE tb_ticket_sku SET state='PAUSED',control_state='' WHERE control_state='PAUSING' AND id IN ("+TicketWriter.marks(paused.size())+")",paused.toArray());return null;});
        }catch(Exception ex){log.warn("数据库待办暂不可用：{}",ex.getClass().getSimpleName());}
    }
    @Scheduled(fixedDelay=1000) public void sampleLag() {
        try {
            List<TopicPartition> partitions=new ArrayList<>();
            admin.describeTopics(Collections.singleton(topic)).all().get(3,TimeUnit.SECONDS).get(topic).partitions().forEach(p->partitions.add(new TopicPartition(topic,p.partition())));
            Map<TopicPartition,OffsetSpec> request=new HashMap<>();for(TopicPartition p:partitions)request.put(p,OffsetSpec.latest());
            Map<TopicPartition,ListOffsetsResult.ListOffsetsResultInfo> ends=admin.listOffsets(request).all().get(3,TimeUnit.SECONDS);
            Map<TopicPartition,OffsetAndMetadata> commits=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(3,TimeUnit.SECONDS);
            long lag=0;List<Map<String,Object>> details=new ArrayList<>();
            for(TopicPartition p:partitions){long end=ends.get(p).offset(),at=commits.containsKey(p)?commits.get(p).offset():0,delta=Math.max(0,end-at);lag+=delta;details.add(map("partition",p.partition(),"lag",delta,"end",end,"committed",at));}
            ops.redis.opsForValue().set(RedisOps.HEALTH,json(map("at",System.currentTimeMillis(),"lag",lag,"partitions",details)));
        } catch(Exception ex){log.warn("Kafka lag 无新采样，过期后受理将暂停");}
    }
}
