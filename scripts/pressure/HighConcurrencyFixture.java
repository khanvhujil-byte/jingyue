import com.fasterxml.jackson.core.type.TypeReference;
import org.yaml.snakeyaml.Yaml;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Owns exact temporary IDs; credentials remain in memory and test tokens stay in fixture.json. */
public class HighConcurrencyFixture extends LiveLikeMigration {
    static final int BASELINE=6;
    static Path directory;
    static Map<String,Object> fixture;
    static Map<String,Object> kafkaConfig;
    static List<Long> longs(Object value) {
        List<Long> result=new ArrayList<>();
        if(value!=null)for(Object n:(List<?>)value)result.add(((Number)n).longValue());
        return result;
    }
    static String ids(List<Long> values) {
        if(values.isEmpty())throw new IllegalArgumentException("No owned IDs");
        StringJoiner join=new StringJoiner(",");for(Long value:values)join.add(value.toString());return join.toString();
    }
    static void save(String filename,Object data)throws IOException {
        Path target=directory.resolve(filename), temp=directory.resolve(filename+".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),data);
        Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);
    }
    static long insert(Connection c,String sql,Object...args)throws SQLException {
        try(PreparedStatement st=c.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS)) {
            for(int i=0;i<args.length;i++)st.setObject(i+1,args[i]);
            if(st.executeUpdate()!=1)throw new IllegalStateException("Fixture insert failed");
            try(ResultSet rs=st.getGeneratedKeys()){if(!rs.next())throw new IllegalStateException("Missing generated ID");return rs.getLong(1);}
        }
    }
    static long number(Connection c,String sql)throws SQLException {
        return ((Number)query(c,sql).get(0).values().iterator().next()).longValue();
    }
    static String prefix(long blog){return "like:{p"+Math.floorMod(blog,16)+"}:";}
    static String shard(long blog){return String.format(Locale.ROOT,"tb_blog_like_state_%02d",Math.floorMod(blog,16));}
    static void installSchema(Connection c,Path project)throws Exception {
        Path before=directory.resolve("schema-original-data.json");
        if(Files.exists(before))throw new IllegalStateException("Original schema/data snapshot exists; retain it and use a fresh directory for schema deployment");
        Map<String,Object> snapshot=new LinkedHashMap<>();
        for(String table:List.of("tb_blog","tb_blog_like","tb_blog_like_baseline","tb_voucher","tb_seckill_voucher","tb_voucher_order")) {
            if(exists(c,table)){snapshot.put(table,query(c,"SELECT * FROM "+table));snapshot.put(table+"Schema",query(c,"SHOW CREATE TABLE "+table));}
        }
        snapshot.put("userIds",query(c,"SELECT id FROM tb_user ORDER BY id"));
        for(int p=0;p<16;p++){String table=String.format(Locale.ROOT,"tb_blog_like_state_%02d",p);if(exists(c,table)){snapshot.put(table,query(c,"SELECT * FROM "+table));snapshot.put(table+"Schema",query(c,"SHOW CREATE TABLE "+table));}}
        save("schema-original-data.json",snapshot);
        String sql=Files.readString(project.resolve("src/main/resources/db/blog_like_async.sql"));List<String> statements=new ArrayList<>();
        for(String piece:sql.split(";")){String clean=piece.replaceAll("(?m)^--.*$", "").trim();if(clean.isEmpty())continue;if(!clean.matches("(?s)^CREATE TABLE IF NOT EXISTS tb_blog_like_state_(?:0[0-9]|1[0-5])\\s*\\(.*"))throw new IllegalStateException("Only the sixteen reviewed IF NOT EXISTS tables may be deployed");statements.add(clean);}
        if(statements.size()!=16)throw new IllegalStateException("Expected sixteen state tables");
        for(String statement:statements)try(Statement st=c.createStatement()){st.execute(statement);}
        Map<String,Object> result=new LinkedHashMap<>();result.put("createdOrVerifiedTables",16);result.put("snapshot",before.toString());
        result.put("businessRowsPreserved",true);save("schema-deployment-report.json",result);System.out.println(JSON.writeValueAsString(result));
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> clients(){return (List<Map<String,Object>>)fixture.get("clients");}
    static void setup(Connection c,Redis redis,int userCount,int voucherCount)throws Exception {
        if(Files.exists(directory.resolve("fixture.json")))throw new IllegalStateException("Use a fresh fixture directory");
        if(userCount<1||userCount>6000||voucherCount<0||voucherCount>128)throw new IllegalArgumentException("Fixture bound exceeded");
        fixture=new LinkedHashMap<>();
        String marker="q"+UUID.randomUUID().toString().replace("-", "").substring(0,6);
        fixture.put("marker",marker);fixture.put("createdAt",OffsetDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
        fixture.put("originalBlogs",query(c,"SELECT id,liked FROM tb_blog ORDER BY id"));
        fixture.put("originalUserCount",number(c,"SELECT COUNT(*) FROM tb_user"));
        fixture.put("originalVouchers",query(c,"SELECT voucher_id,stock FROM tb_seckill_voucher ORDER BY voucher_id"));
        fixture.put("originalOrderCount",number(c,"SELECT COUNT(*) FROM tb_voucher_order"));
        fixture.put("baseline",BASELINE);
        fixture.put("userIds",new ArrayList<Long>());fixture.put("blogIds",new ArrayList<Long>());fixture.put("voucherIds",new ArrayList<Long>());
        fixture.put("clients",new ArrayList<Map<String,Object>>());
        fixture.put("seckillStream","stream.seckill.benchmark."+marker);
        fixture.put("kafkaTopic","hmdp-seckill-benchmark-"+marker);
        fixture.put("kafkaGroup","hmdp-seckill-benchmark-"+marker);
        Object bootstrap=kafkaConfig.getOrDefault("bootstrap-servers","localhost:9092");
        fixture.put("kafkaBootstrap",bootstrap instanceof List?String.join(",",(List<String>)bootstrap):bootstrap.toString());
        save("fixture.json",fixture);
        c.setAutoCommit(false);
        try {
            try(PreparedStatement st=c.prepareStatement("INSERT INTO tb_user(phone,nick_name,icon) VALUES(?,?,?)")) {
                for(int i=0;i<userCount;i++) {
                    st.setString(1,marker+String.format(Locale.ROOT,"%04d",i));st.setString(2,"临时并发验收"+i);st.setString(3,"");st.addBatch();
                }
                st.executeBatch();
            }
            List<Long> users=new ArrayList<>();
            try(PreparedStatement st=c.prepareStatement("SELECT id FROM tb_user WHERE phone LIKE ? ORDER BY id")) {
                st.setString(1,marker+"%");try(ResultSet rs=st.executeQuery()){while(rs.next())users.add(rs.getLong(1));}
            }
            if(users.size()!=userCount)throw new IllegalStateException("Fixture users not isolated");
            fixture.put("userIds",users);
            List<Long> blogs=longs(fixture.get("blogIds"));fixture.put("blogIds",blogs);
            for(int i=0;i<17;i++) {
                long blog=insert(c,"INSERT INTO tb_blog(shop_id,user_id,title,images,content,liked,comments) SELECT id,?,?,?,?,?,0 FROM tb_shop ORDER BY id LIMIT 1",users.get(0),"临时高并发验收-"+marker+"-"+i,"/imgs/test.jpg","验收完成后按精确编号清理",BASELINE);
                blogs.add(blog);
                try(PreparedStatement st=c.prepareStatement("INSERT INTO tb_blog_like_baseline(blog_id,anonymous_likes) VALUES(?,?)")){st.setLong(1,blog);st.setInt(2,BASELINE);st.executeUpdate();}
            }
            List<Long> vouchers=new ArrayList<>();fixture.put("voucherIds",vouchers);
            for(int i=0;i<voucherCount;i++) {
                long voucher=insert(c,"INSERT INTO tb_voucher(shop_id,title,sub_title,rules,pay_value,actual_value,type,status) SELECT id,?,?,?,1,1,1,1 FROM tb_shop ORDER BY id LIMIT 1","临时秒杀验收-"+marker+"-"+i,"验收专用","完成后清理");
                vouchers.add(voucher);
                try(PreparedStatement st=c.prepareStatement("INSERT INTO tb_seckill_voucher(voucher_id,stock,begin_time,end_time) VALUES(?,?,DATE_SUB(NOW(),INTERVAL 1 HOUR),DATE_ADD(NOW(),INTERVAL 1 DAY))")){st.setLong(1,voucher);st.setInt(2,userCount);st.executeUpdate();}
            }
            fixture.put("initialStock",userCount);
            for(int i=0;i<users.size();i++) {
                Map<String,Object> client=new LinkedHashMap<>();client.put("userId",users.get(i));client.put("token","concurrency-"+UUID.randomUUID());clients().add(client);
            }
            // Persist the exact IDs before COMMIT, so interrupted Redis seeding can be cleaned up.
            save("fixture.json",fixture);c.commit();
        }catch(Exception error){c.rollback();fixture.put("setupRolledBack",true);save("fixture.json",fixture);throw error;}
        finally{c.setAutoCommit(true);}
        for(Map<String,Object> client:clients()) {
            String key="login:token:"+client.get("token");
            redis.call("HSET",key,"id",client.get("userId").toString(),"nickName","临时并发验收","icon","");redis.call("EXPIRE",key,"7200");
        }
        for(long voucher:longs(fixture.get("voucherIds"))) {
            Object result=redis.call("SET","seckill:stock:"+voucher,Integer.toString(userCount),"NX");
            if(!"OK".equals(result))throw new IllegalStateException("Owned fixture stock unexpectedly exists");
        }
        fixture.put("setupComplete",true);save("fixture.json",fixture);
        System.out.println(JSON.writeValueAsString(Map.of("setup","complete","users",clients().size(),"blogs",longs(fixture.get("blogIds")).size(),"vouchers",longs(fixture.get("voucherIds")).size(),"manifest",directory.resolve("fixture.json").toString(),"seckillStream",fixture.get("seckillStream"),"kafkaTopic",fixture.get("kafkaTopic"),"kafkaGroup",fixture.get("kafkaGroup"))));
    }
    static Map<String,Object> kafkaLag()throws Exception {
        String topic=fixture.get("kafkaTopic").toString(),group=fixture.get("kafkaGroup").toString();
        Properties properties=new Properties();properties.put("bootstrap.servers",fixture.get("kafkaBootstrap").toString());
        properties.put("request.timeout.ms","3000");properties.put("default.api.timeout.ms","5000");
        try(AdminClient admin=AdminClient.create(properties)) {
            Map<String,Object> result=new LinkedHashMap<>();result.put("topic",topic);result.put("group",group);
            if(!admin.listTopics().names().get(6,TimeUnit.SECONDS).contains(topic)){result.put("topicExists",false);result.put("totalLag",0);return result;}
            TopicDescription description=admin.describeTopics(Collections.singleton(topic)).values().get(topic).get(6,TimeUnit.SECONDS);
            Map<TopicPartition,OffsetSpec> requests=new LinkedHashMap<>();
            description.partitions().forEach(p->requests.put(new TopicPartition(topic,p.partition()),OffsetSpec.latest()));
            Map<TopicPartition,ListOffsetsResult.ListOffsetsResultInfo> ends=admin.listOffsets(requests).all().get(6,TimeUnit.SECONDS);
            Map<TopicPartition,OffsetAndMetadata> committed=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(6,TimeUnit.SECONDS);
            long lag=0;List<Map<String,Object>> partitions=new ArrayList<>();
            for(TopicPartition partition:requests.keySet()) {
                long end=ends.get(partition).offset(),offset=committed.containsKey(partition)?committed.get(partition).offset():0;
                long difference=Math.max(0,end-offset);lag+=difference;
                partitions.add(Map.of("partition",partition.partition(),"endOffset",end,"committedOffset",offset,"lag",difference));
            }
            result.put("topicExists",true);result.put("totalLag",lag);result.put("partitions",partitions);return result;
        }
    }
    @SuppressWarnings("unchecked") static Map<String,Object> stream(Redis redis,String key)throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();result.put("key",key);
        if("none".equals(redis.call("TYPE",key))){result.put("length",0);result.put("pending",0);result.put("lag",0);return result;}
        long length=((Number)redis.call("XLEN",key)).longValue();result.put("length",length);
        long pending=0,lag=0;boolean known=true;
        List<Object> groups=(List<Object>)redis.call("XINFO","GROUPS",key);
        for(Object entry:groups) {
            List<Object> fields=(List<Object>)entry;Map<String,Object> group=new HashMap<>();
            for(int i=0;i<fields.size();i+=2)group.put(fields.get(i).toString(),fields.get(i+1));
            pending+=((Number)group.getOrDefault("pending",0L)).longValue();
            Object groupLag=group.get("lag");
            if(groupLag instanceof Number)lag+=((Number)groupLag).longValue();else known=false;
        }
        // Consumers delete committed/forwarded entries. For Redis versions without lag,
        // a nonempty stream conservatively prevents a 'fully drained' declaration.
        result.put("pending",pending);result.put("lag",known?lag:length);result.put("consumerGroups",groups.size());
        if(groups.isEmpty()&&length>0)result.put("lag",length);
        return result;
    }
    static boolean drained(Redis redis,boolean likes,boolean seckill,List<Map<String,Object>> details)throws Exception {
        boolean ok=true;
        if(likes)for(int p=0;p<16;p++)details.add(stream(redis,"like:{p"+p+"}:outbox"));
        if(seckill)details.add(stream(redis,fixture.get("seckillStream").toString()));
        for(Map<String,Object> item:details)if(((Number)item.get("pending")).longValue()>0||((Number)item.get("lag")).longValue()>0)ok=false;
        return ok;
    }
    static void reset(Connection c,Redis redis,String kind)throws Exception {
        List<Map<String,Object>> streams=new ArrayList<>();
        if(!drained(redis,"likes".equals(kind),"seckill".equals(kind),streams))throw new IllegalStateException("Outbox not drained; refuse fixture reset");
        if("seckill".equals(kind)&&((Number)kafkaLag().get("totalLag")).longValue()!=0)throw new IllegalStateException("Kafka committed lag is not zero; refuse seckill reset");
        if("likes".equals(kind)) {
            List<Long> blogs=longs(fixture.get("blogIds"));
            c.setAutoCommit(false);
            try(Statement st=c.createStatement()) {
                st.executeUpdate("DELETE FROM tb_blog_like WHERE blog_id IN ("+ids(blogs)+")");
                for(int p=0;p<16;p++){String table=String.format(Locale.ROOT,"tb_blog_like_state_%02d",p);if(exists(c,table))st.executeUpdate("DELETE FROM "+table+" WHERE blog_id IN ("+ids(blogs)+")");}
                st.executeUpdate("UPDATE tb_blog SET liked="+BASELINE+" WHERE id IN ("+ids(blogs)+")");c.commit();
            }catch(Exception error){c.rollback();throw error;}finally{c.setAutoCommit(true);}
            for(long blog:blogs)redis.call("DEL",prefix(blog)+"meta:"+blog,prefix(blog)+"users:"+blog,prefix(blog)+"warm:"+blog,prefix(blog)+"pending:"+blog);
        }else if("seckill".equals(kind)) {
            List<Long> vouchers=longs(fixture.get("voucherIds"));
            try(Statement st=c.createStatement()){st.executeUpdate("DELETE FROM tb_voucher_order WHERE voucher_id IN ("+ids(vouchers)+")");st.executeUpdate("UPDATE tb_seckill_voucher SET stock="+fixture.get("initialStock")+" WHERE voucher_id IN ("+ids(vouchers)+")");}
            for(long voucher:vouchers){redis.call("DEL","seckill:order:"+voucher);redis.call("SET","seckill:stock:"+voucher,fixture.get("initialStock").toString());}
        }else throw new IllegalArgumentException("Unknown fixture reset kind");
        System.out.println(JSON.writeValueAsString(Map.of("reset",kind,"status","complete")));
    }
    @SuppressWarnings("unchecked") static Map<String,Object> auditOnce(Connection c,Redis redis,Map<String,Object> load)throws Exception {
        boolean likes="likes".equals(load.get("kind"));
        Map<String,Object> audit=new LinkedHashMap<>();List<Map<String,Object>> streams=new ArrayList<>();
        audit.put("outboxDrained",drained(redis,likes,!likes,streams));audit.put("streams",streams);
        if(likes) {
            List<Long> blogs=longs(fixture.get("blogIds"));
            Map<String,Boolean> canonical=new HashMap<>(),persisted=new HashMap<>();
            for(Map<String,Object> row:query(c,"SELECT blog_id,user_id FROM tb_blog_like WHERE blog_id IN ("+ids(blogs)+")"))canonical.put(row.get("blog_id")+":"+row.get("user_id"),true);
            for(int p=0;p<16;p++) {
                String table=String.format(Locale.ROOT,"tb_blog_like_state_%02d",p);
                if(exists(c,table))for(Map<String,Object> row:query(c,"SELECT blog_id,user_id,liked FROM "+table+" WHERE blog_id IN ("+ids(blogs)+")")) {
                    Object liked=row.get("liked");persisted.put(row.get("blog_id")+":"+row.get("user_id"),liked instanceof Boolean?(Boolean)liked:((Number)liked).intValue()!=0);
                }
            }
            long wrongState=0,unknownResponses=0;List<Map<String,Object>> expected=(List<Map<String,Object>>)load.get("expectedStates");
            for(Map<String,Object> row:expected) {
                if(Boolean.TRUE.equals(row.get("uncertain"))){unknownResponses++;continue;}
                String key=row.get("blogId")+":"+row.get("userId");boolean liked=Boolean.TRUE.equals(row.get("liked"));
                long acknowledged=((Number)row.getOrDefault("acknowledged",0)).longValue();
                if(canonical.getOrDefault(key,false)!=liked||persisted.getOrDefault(key,false)!=liked||(acknowledged>0&&!persisted.containsKey(key)))wrongState++;
            }
            List<Map<String,Object>> counters=query(c,"SELECT b.id,b.liked,COALESCE(h.anonymous_likes,0) AS baseline,(SELECT COUNT(*) FROM tb_blog_like l WHERE l.blog_id=b.id) AS relations FROM tb_blog b LEFT JOIN tb_blog_like_baseline h ON h.blog_id=b.id WHERE b.id IN ("+ids(blogs)+") ORDER BY b.id");
            long mismatches=0,acceptedTransitions=0;boolean redisMatches=true;
            for(Map<String,Object> row:counters) {
                long blog=((Number)row.get("id")).longValue(),total=((Number)row.get("liked")).longValue(),relations=((Number)row.get("relations")).longValue();
                if(total!=((Number)row.get("baseline")).longValue()+relations)mismatches++;
                Object redisTotal=redis.call("HGET",prefix(blog)+"meta:"+blog,"total");
                Object version=redis.call("HGET",prefix(blog)+"meta:"+blog,"version");
                if(version!=null)acceptedTransitions+=Long.parseLong(version.toString());
                long redisRelations=((Number)redis.call("ZCARD",prefix(blog)+"users:"+blog)).longValue();
                row.put("redisTotal",redisTotal);row.put("redisRelations",redisRelations);
                if(redisTotal!=null&&(Long.parseLong(redisTotal.toString())!=total||redisRelations!=relations))redisMatches=false;
            }
            boolean transitionsMatch=acceptedTransitions==((Number)load.get("success")).longValue();
            audit.put("canonicalRelations",canonical.size());audit.put("persistedStateRows",persisted.size());audit.put("wrongUserStates",wrongState);audit.put("unknownResponseUsers",unknownResponses);audit.put("counterMismatches",mismatches);audit.put("redisMatches",redisMatches);audit.put("blogs",counters);
            audit.put("redisAcceptedTransitions",acceptedTransitions);audit.put("transitionsMatchAcknowledged",transitionsMatch);
            audit.put("consistent",wrongState==0&&unknownResponses==0&&mismatches==0&&redisMatches&&transitionsMatch);
        }else {
            List<Long> vouchers=longs(fixture.get("voucherIds"));
            Map<String,String> actual=new HashMap<>();
            for(Map<String,Object> row:query(c,"SELECT id,user_id,voucher_id FROM tb_voucher_order WHERE voucher_id IN ("+ids(vouchers)+")"))actual.put(row.get("user_id")+":"+row.get("voucher_id"),row.get("id").toString());
            long missing=0,wrongId=0;
            for(Map<String,Object> accepted:(List<Map<String,Object>>)load.get("acceptedOrders")) {
                String persisted=actual.get(accepted.get("userId")+":"+accepted.get("voucherId"));
                if(persisted==null)missing++;else if(!persisted.equals(accepted.get("orderId").toString()))wrongId++;
            }
            long mismatches=0,initial=((Number)fixture.get("initialStock")).longValue();
            List<Map<String,Object>> stocks=query(c,"SELECT s.voucher_id,s.stock,(SELECT COUNT(*) FROM tb_voucher_order o WHERE o.voucher_id=s.voucher_id) AS orders FROM tb_seckill_voucher s WHERE s.voucher_id IN ("+ids(vouchers)+") ORDER BY s.voucher_id");
            for(Map<String,Object> row:stocks) {
                long voucher=((Number)row.get("voucher_id")).longValue(),stock=((Number)row.get("stock")).longValue(),orders=((Number)row.get("orders")).longValue();
                Object redisStock=redis.call("GET","seckill:stock:"+voucher);long reservations=((Number)redis.call("SCARD","seckill:order:"+voucher)).longValue();
                row.put("redisStock",redisStock);row.put("redisReservations",reservations);
                if(stock<0||stock+orders!=initial||redisStock==null||Long.parseLong(redisStock.toString())!=stock||reservations!=orders)mismatches++;
            }
            long duplicatePairs=number(c,"SELECT COUNT(*) FROM (SELECT user_id,voucher_id FROM tb_voucher_order WHERE voucher_id IN ("+ids(vouchers)+") GROUP BY user_id,voucher_id HAVING COUNT(*)>1) d");
            audit.put("actualOrders",actual.size());audit.put("acknowledgedOrders",load.get("success"));audit.put("missingAcknowledgedOrders",missing);audit.put("wrongOrderIds",wrongId);audit.put("duplicateUserVoucherPairs",duplicatePairs);audit.put("stockMismatches",mismatches);audit.put("vouchers",stocks);
            audit.put("consistent",missing==0&&wrongId==0&&duplicatePairs==0&&mismatches==0&&actual.size()==((Number)load.get("success")).longValue());
            Map<String,Object> kafka=kafkaLag();audit.put("kafka",kafka);audit.put("kafkaDrained",((Number)kafka.get("totalLag")).longValue()==0);
        }
        return audit;
    }
    static void audit(Connection c,Redis redis,Path loadFile,int waitSeconds)throws Exception {
        Map<String,Object> load=JSON.readValue(loadFile.toFile(),new TypeReference<Map<String,Object>>(){});
        long start=System.nanoTime(),deadline=start+waitSeconds*1_000_000_000L;Map<String,Object> result;
        do {
            result=auditOnce(c,redis,load);
            if(Boolean.TRUE.equals(result.get("outboxDrained"))&&Boolean.TRUE.equals(result.get("consistent"))&&!Boolean.FALSE.equals(result.get("kafkaDrained")))break;
            if(System.nanoTime()>=deadline)break;
            Thread.sleep(500);
        }while(true);
        result.put("auditWaitSeconds",(System.nanoTime()-start)/1_000_000_000.0);result.put("kind",load.get("kind"));result.put("loadReport",loadFile.toString());
        if(load.get("finishedAt")!=null) {
            double afterHttp=Math.max(0,Duration.between(Instant.parse(load.get("finishedAt").toString()),Instant.now()).toNanos()/1_000_000_000.0);
            double total=((Number)load.get("elapsedSeconds")).doubleValue()+afterHttp;
            long verified="likes".equals(load.get("kind"))?((Number)result.getOrDefault("redisAcceptedTransitions",0)).longValue():((Number)result.getOrDefault("actualOrders",0)).longValue();
            result.put("httpFinishedToAuditDrainSeconds",afterHttp);result.put("injectionStartToAuditDrainSeconds",total);result.put("verifiedAcceptedChanges",verified);result.put("conservativeEndToEndVerifiedRps",verified/total);
            result.put("drainTimingNote","Includes audit process startup, final reconciliation and Kafka AdminClient checks; an upper bound on completion time, not a precisely sampled commit timestamp.");
        }
        result.put("status",Boolean.TRUE.equals(result.get("outboxDrained"))&&Boolean.TRUE.equals(result.get("consistent"))&&!Boolean.FALSE.equals(result.get("kafkaDrained"))?"passed":"failed");
        save(loadFile.getFileName().toString().replace(".json","-audit.json"),result);
        Map<String,Object> compact=new LinkedHashMap<>(result);compact.remove("blogs");compact.remove("vouchers");compact.remove("streams");System.out.println(JSON.writeValueAsString(compact));
        if(!"passed".equals(result.get("status")))throw new IllegalStateException("Async persistence did not reconcile within the bounded audit window");
    }
    static void cleanup(Connection c,Redis redis)throws Exception {
        List<Map<String,Object>> streams=new ArrayList<>();
        if(!drained(redis,true,true,streams))throw new IllegalStateException("Undrained outbox; keep fixture IDs for safe recovery");
        if(!longs(fixture.get("voucherIds")).isEmpty()&&((Number)kafkaLag().get("totalLag")).longValue()!=0)throw new IllegalStateException("Kafka committed lag is not zero; refuse fixture deletion");
        List<Long> blogs=longs(fixture.get("blogIds")),vouchers=longs(fixture.get("voucherIds")),users=longs(fixture.get("userIds"));
        c.setAutoCommit(false);
        try(Statement st=c.createStatement()) {
            if(!blogs.isEmpty()) {
                st.executeUpdate("DELETE FROM tb_blog_like WHERE blog_id IN ("+ids(blogs)+")");
                for(int p=0;p<16;p++){String table=String.format(Locale.ROOT,"tb_blog_like_state_%02d",p);if(exists(c,table))st.executeUpdate("DELETE FROM "+table+" WHERE blog_id IN ("+ids(blogs)+")");}
                st.executeUpdate("DELETE FROM tb_blog_like_baseline WHERE blog_id IN ("+ids(blogs)+")");st.executeUpdate("DELETE FROM tb_blog WHERE id IN ("+ids(blogs)+")");
            }
            if(!vouchers.isEmpty()) {st.executeUpdate("DELETE FROM tb_voucher_order WHERE voucher_id IN ("+ids(vouchers)+")");st.executeUpdate("DELETE FROM tb_seckill_voucher WHERE voucher_id IN ("+ids(vouchers)+")");st.executeUpdate("DELETE FROM tb_voucher WHERE id IN ("+ids(vouchers)+")");}
            if(!users.isEmpty())st.executeUpdate("DELETE FROM tb_user WHERE id IN ("+ids(users)+")");c.commit();
        }catch(Exception error){c.rollback();throw error;}finally{c.setAutoCommit(true);}
        for(Map<String,Object> client:clients())redis.call("DEL","login:token:"+client.get("token"));
        for(long blog:blogs)redis.call("DEL",prefix(blog)+"meta:"+blog,prefix(blog)+"users:"+blog,prefix(blog)+"warm:"+blog,prefix(blog)+"pending:"+blog);
        for(long voucher:vouchers)redis.call("DEL","seckill:stock:"+voucher,"seckill:order:"+voucher);
        // This stream name is generated per fixture; shared business outboxes are preserved.
        redis.call("DEL",fixture.get("seckillStream").toString());
        boolean intact=JSON.writeValueAsString(fixture.get("originalBlogs")).equals(JSON.writeValueAsString(query(c,"SELECT id,liked FROM tb_blog ORDER BY id")));
        boolean usersIntact=number(c,"SELECT COUNT(*) FROM tb_user")==((Number)fixture.get("originalUserCount")).longValue();
        boolean vouchersIntact=JSON.writeValueAsString(fixture.get("originalVouchers")).equals(JSON.writeValueAsString(query(c,"SELECT voucher_id,stock FROM tb_seckill_voucher ORDER BY voucher_id")));
        boolean ordersIntact=number(c,"SELECT COUNT(*) FROM tb_voucher_order")==((Number)fixture.get("originalOrderCount")).longValue();
        boolean fullRowsIntact=true;
        Path originalSnapshot=directory.resolve("schema-original-data.json");
        if(Files.exists(originalSnapshot)) {
            Map<String,Object> original=JSON.readValue(originalSnapshot.toFile(),new TypeReference<Map<String,Object>>(){});
            for(String table:List.of("tb_blog","tb_blog_like","tb_blog_like_baseline","tb_voucher","tb_seckill_voucher","tb_voucher_order")) {
                if(original.containsKey(table)) {
                    String beforeRows=JSON.writeValueAsString(original.get(table));
                    String afterRows=JSON.writeValueAsString(query(c,"SELECT * FROM "+table));
                    if(!beforeRows.equals(afterRows))fullRowsIntact=false;
                }
            }
        }
        save("cleanup-report.json",Map.of("originalBlogsUnchanged",intact,"originalUserCountRestored",usersIntact,"originalVoucherStocksUnchanged",vouchersIntact,"originalOrderCountRestored",ordersIntact,"fullOriginalBusinessRowsPreserved",fullRowsIntact,"ownedUsersRemoved",users.size(),"ownedBlogsRemoved",blogs.size(),"ownedVouchersRemoved",vouchers.size()));
        // Invalidate tokens in the persisted manifest as well as Redis, retaining exact cleanup IDs.
        for(Map<String,Object> client:clients())client.remove("token");fixture.put("cleaned",true);save("fixture.json",fixture);
        if(!intact||!usersIntact||!vouchersIntact||!ordersIntact||!fullRowsIntact)throw new IllegalStateException("Original-data preservation assertion failed");
        System.out.println("CLEANUP_COMPLETE original data verified; owned fixtures and tokens removed");
    }
    public static void main(String[]args)throws Exception {
        if(args.length<3)throw new IllegalArgumentException("action project fixtureDirectory [extra]");
        String action=args[0];Path project=Paths.get(args[1]);directory=Paths.get(args[2]);Files.createDirectories(directory);
        Map<String,Object> config=readConfiguration(project.resolve("src/main/resources/application.yaml"));
        Map<String,Object> spring=section(config,"spring"),db=section(spring,"datasource"),redisCfg=section(spring,"redis");kafkaConfig=section(spring,"kafka");
        if(!"setup".equals(action)&&!"install-schema".equals(action))fixture=JSON.readValue(directory.resolve("fixture.json").toFile(),new TypeReference<Map<String,Object>>(){});
        Class.forName("com.mysql.jdbc.Driver");
        try(Connection c=DriverManager.getConnection(db.get("url").toString(),db.get("username").toString(),String.valueOf(db.getOrDefault("password","")));Redis redis=new Redis(redisCfg)) {
            verifyDatabase(c);
            switch(action) {
                case "setup":setup(c,redis,args.length>3?Integer.parseInt(args[3]):4000,args.length>4?Integer.parseInt(args[4]):64);break;
                case "install-schema":installSchema(c,project);break;
                case "reset":reset(c,redis,args[3]);break;
                case "audit":audit(c,redis,Paths.get(args[3]),args.length>4?Integer.parseInt(args[4]):180);break;
                case "kafka-lag":System.out.println(JSON.writeValueAsString(kafkaLag()));break;
                case "bind-kafka":fixture.put("kafkaTopic",args[3]);fixture.put("kafkaGroup",args[4]);if(args.length>5)fixture.put("kafkaBootstrap",args[5]);save("fixture.json",fixture);System.out.println("Kafka audit binding saved");break;
                case "cleanup":cleanup(c,redis);break;
                default:throw new IllegalArgumentException("Unsupported fixture action");
            }
        }
    }
}
