import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Local maintenance helper. Credentials are read in memory, never printed or passed as arguments. */
public class LiveLikeMigration {
    static final ObjectMapper JSON = new ObjectMapper();
    @SuppressWarnings("unchecked")
    static Map<String,Object> readConfiguration(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return (Map<String,Object>) resolveEnvironment(new Yaml().load(in));
        }
    }
    @SuppressWarnings("unchecked")
    static Object resolveEnvironment(Object value) {
        if (value instanceof Map) {
            Map<String,Object> result = new LinkedHashMap<>();
            ((Map<String,Object>) value).forEach((key,item) -> result.put(key,resolveEnvironment(item)));
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) result.add(resolveEnvironment(item));
            return result;
        }
        if (value instanceof String) {
            java.util.regex.Matcher match = java.util.regex.Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*):([^}]*)}").matcher((String)value);
            if (match.matches()) {
                String configured = System.getenv(match.group(1));
                return configured == null ? match.group(2) : configured;
            }
        }
        return value;
    }
    static void verifyDatabase(Connection connection) throws SQLException {
        String expected = System.getenv().getOrDefault("HMDP_FIXTURE_DATABASE","shanhai_ticket");
        if (!expected.equals(connection.getCatalog())) throw new IllegalStateException("Unexpected database");
    }
    static List<Map<String,Object>> query(Connection c, String sql) throws SQLException {
        List<Map<String,Object>> rows = new ArrayList<>();
        try (Statement st=c.createStatement(); ResultSet rs=st.executeQuery(sql)) {
            ResultSetMetaData meta=rs.getMetaData();
            while(rs.next()) {
                Map<String,Object> row=new LinkedHashMap<>();
                for(int i=1;i<=meta.getColumnCount();i++) row.put(meta.getColumnLabel(i),rs.getObject(i));
                rows.add(row);
            }
        }
        return rows;
    }
    static boolean exists(Connection c,String name) throws SQLException {
        try(PreparedStatement st=c.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?")) {
            st.setString(1,name); try(ResultSet rs=st.executeQuery()){rs.next();return rs.getInt(1)>0;}
        }
    }
    @SuppressWarnings("unchecked") static Map<String,Object> section(Map<String,Object> map,String key) {
        Object value=map.get(key); return value instanceof Map?(Map<String,Object>)value:new HashMap<>();
    }
    static class Redis implements AutoCloseable {
        final Socket socket=new Socket(); final InputStream in; final OutputStream out;
        Redis(Map<String,Object> cfg) throws Exception {
            socket.connect(new InetSocketAddress(String.valueOf(cfg.getOrDefault("host","localhost")),Integer.parseInt(String.valueOf(cfg.getOrDefault("port",6379)))),5000);
            socket.setSoTimeout(10000);in=new BufferedInputStream(socket.getInputStream());out=socket.getOutputStream();
            Object password=cfg.get("password");if(password!=null&&!password.toString().isEmpty())call("AUTH",password.toString());
            call("SELECT",String.valueOf(cfg.getOrDefault("database",0)));
        }
        String line() throws IOException {
            ByteArrayOutputStream b=new ByteArrayOutputStream();int v;
            while((v=in.read())!=-1){if(v==13){if(in.read()!=10)throw new IOException("Invalid Redis response");return b.toString("UTF-8");}b.write(v);}
            throw new EOFException();
        }
        Object read() throws Exception {
            int type=in.read();String s=line();
            if(type=='+')return s;if(type=='-')throw new IOException("Redis command rejected");if(type==':')return Long.valueOf(s);
            int n=Integer.parseInt(s);if(n<0)return null;
            if(type=='*'){List<Object>a=new ArrayList<>();for(int i=0;i<n;i++)a.add(read());return a;}
            if(type=='$'){byte[] b=new byte[n];int at=0;while(at<n){int got=in.read(b,at,n-at);if(got<0)throw new EOFException();at+=got;}if(in.read()!=13||in.read()!=10)throw new IOException("Invalid Redis response");return new String(b,StandardCharsets.UTF_8);}
            throw new IOException("Unsupported Redis response");
        }
        Object call(String...args)throws Exception{
            out.write(("*"+args.length+"\r\n").getBytes(StandardCharsets.UTF_8));
            for(String arg:args){byte[]b=arg.getBytes(StandardCharsets.UTF_8);out.write(("$"+b.length+"\r\n").getBytes(StandardCharsets.UTF_8));out.write(b);out.write(new byte[]{13,10});}out.flush();return read();
        }
        @SuppressWarnings("unchecked") List<Map<String,Object>> snapshot()throws Exception{
            Map<String,Map<String,Object>> pairs=new TreeMap<>();String cursor="0";
            do{
                List<Object>scan=(List<Object>)call("SCAN",cursor,"MATCH","blog:liked:*","COUNT","200");cursor=scan.get(0).toString();
                for(Object raw:(List<Object>)scan.get(1)){
                    String key=raw.toString();long blog=Long.parseLong(key.substring("blog:liked:".length()));
                    if(!"zset".equals(call("TYPE",key)))throw new IllegalStateException("Legacy like key has unexpected type");
                    List<Object>values=(List<Object>)call("ZRANGE",key,"0","-1","WITHSCORES");
                    for(int i=0;i<values.size();i+=2){
                        long user=Long.parseLong(values.get(i).toString());long time=new java.math.BigDecimal(values.get(i+1).toString()).longValueExact();
                        if(user<=0||blog<=0||time<0)throw new IllegalStateException("Invalid legacy like record");
                        Map<String,Object>r=new LinkedHashMap<>();r.put("user_id",user);r.put("blog_id",blog);r.put("created_at",time);pairs.put(user+":"+blog,r);
                    }
                }
            }while(!"0".equals(cursor));return new ArrayList<>(pairs.values());
        }
        public void close()throws IOException{socket.close();}
    }
    @SuppressWarnings("unchecked")
    static void migrate(Connection c, Path project, Path backup, Map<String,Object> redisCfg) throws Exception {
        try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT GET_LOCK('hmdp_blog_like_migration', 0)")){
            rs.next();if(rs.getInt(1)!=1)throw new IllegalStateException("Maintenance already running");
        }
        try {
            List<Map<String,Object>> legacy;
            try(Redis redis=new Redis(redisCfg)){legacy=redis.snapshot();}
            Map<String,Object> before=new LinkedHashMap<>();
            before.put("blogs",query(c,"SELECT * FROM tb_blog ORDER BY id"));
            before.put("relations",exists(c,"tb_blog_like")?query(c,"SELECT * FROM tb_blog_like ORDER BY id"):Collections.emptyList());
            before.put("baselines",exists(c,"tb_blog_like_baseline")?query(c,"SELECT * FROM tb_blog_like_baseline ORDER BY blog_id"):Collections.emptyList());
            before.put("legacyRedisRelations",legacy);
            before.put("blogSchema",query(c,"SHOW CREATE TABLE tb_blog"));
            if(exists(c,"tb_blog_like"))before.put("relationSchema",query(c,"SHOW CREATE TABLE tb_blog_like"));
            if(exists(c,"tb_blog_like_baseline"))before.put("baselineSchema",query(c,"SHOW CREATE TABLE tb_blog_like_baseline"));
            Path snapshot=backup.resolve("migration-before.json");
            if(Files.exists(snapshot))throw new IllegalStateException("Backup already exists; choose a fresh directory");
            JSON.writerWithDefaultPrettyPrinter().writeValue(snapshot.toFile(),before);
            String sql=new String(Files.readAllBytes(project.resolve("src/main/resources/db/blog_like.sql")),StandardCharsets.UTF_8);
            for(String part:sql.split(";")){
                String cleaned=part.replaceAll("(?m)^--.*$", "").trim();
                if(cleaned.startsWith("CREATE TABLE"))try(Statement st=c.createStatement()){st.execute(cleaned);}
            }
            c.setAutoCommit(false);
            int imported=0;
            try {
                List<Map<String,Object>> blogs=query(c,"SELECT id,COALESCE(liked,0) AS liked FROM tb_blog ORDER BY id FOR UPDATE");
                Set<Long> blogIds=new HashSet<>(),userIds=new HashSet<>();
                for(Map<String,Object>b:blogs)blogIds.add(((Number)b.get("id")).longValue());
                for(Map<String,Object>u:query(c,"SELECT id FROM tb_user"))userIds.add(((Number)u.get("id")).longValue());
                for(Map<String,Object> r:legacy){
                    long blog=((Number)r.get("blog_id")).longValue(),user=((Number)r.get("user_id")).longValue();
                    if(!blogIds.contains(blog)||!userIds.contains(user))throw new IllegalStateException("Orphaned legacy relationship");
                    try(PreparedStatement find=c.prepareStatement("SELECT id FROM tb_blog_like WHERE user_id=? AND blog_id=? FOR UPDATE")){
                        find.setLong(1,user);find.setLong(2,blog);
                        try(ResultSet rs=find.executeQuery()){
                            if(!rs.next())try(PreparedStatement insert=c.prepareStatement("INSERT INTO tb_blog_like(user_id,blog_id,created_at) VALUES(?,?,?)")){
                                insert.setLong(1,user);insert.setLong(2,blog);insert.setLong(3,((Number)r.get("created_at")).longValue());imported+=insert.executeUpdate();
                            }
                        }
                    }
                }
                for(Map<String,Object>b:blogs){
                    long id=((Number)b.get("id")).longValue(),stored=((Number)b.get("liked")).longValue();
                    long known=((Number)query(c,"SELECT COUNT(*) AS n FROM tb_blog_like WHERE blog_id="+id).get(0).get("n")).longValue();
                    long baseline=stored-known;if(baseline<0)throw new IllegalStateException("Known relationships exceed stored count");
                    List<Map<String,Object>> old=query(c,"SELECT anonymous_likes FROM tb_blog_like_baseline WHERE blog_id="+id+" FOR UPDATE");
                    if(old.isEmpty())try(PreparedStatement insert=c.prepareStatement("INSERT INTO tb_blog_like_baseline(blog_id,anonymous_likes) VALUES(?,?)")){
                        insert.setLong(1,id);insert.setLong(2,baseline);insert.executeUpdate();
                    } else if(((Number)old.get(0).get("anonymous_likes")).longValue()!=baseline)throw new IllegalStateException("Existing historical baseline does not reconcile");
                }
                try(Redis redis=new Redis(redisCfg)){if(!legacy.equals(redis.snapshot()))throw new IllegalStateException("Legacy likes changed during migration");}
                if(!query(c,"SELECT l.id FROM tb_blog_like l LEFT JOIN tb_user u ON u.id=l.user_id LEFT JOIN tb_blog b ON b.id=l.blog_id WHERE u.id IS NULL OR b.id IS NULL").isEmpty())throw new IllegalStateException("Orphaned relationships");
                if(!query(c,"SELECT user_id,blog_id FROM tb_blog_like GROUP BY user_id,blog_id HAVING COUNT(*)>1").isEmpty())throw new IllegalStateException("Duplicate relationships");
                c.commit();
            }catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}
            Map<String,Object> report=new LinkedHashMap<>();report.put("status","迁移完成");report.put("database",c.getCatalog());
            report.put("completedAt",java.time.OffsetDateTime.now().toString());report.put("importedRelationships",imported);
            report.put("blogs",query(c,"SELECT b.id,b.liked,COALESCE(h.anonymous_likes,0) AS historical_likes,(SELECT COUNT(*) FROM tb_blog_like l WHERE l.blog_id=b.id) AS known_likes FROM tb_blog b LEFT JOIN tb_blog_like_baseline h ON h.blog_id=b.id ORDER BY b.id"));
            report.put("relations",query(c,"SELECT user_id,blog_id,created_at FROM tb_blog_like ORDER BY blog_id,user_id"));
            report.put("note","没有用户身份的历史计数保留为基数；未改动原计数，未删除旧Redis数据。");
            for(Map<String,Object>b:(List<Map<String,Object>>)report.get("blogs"))if(((Number)b.get("liked")).longValue()!=((Number)b.get("historical_likes")).longValue()+((Number)b.get("known_likes")).longValue())throw new IllegalStateException("Post-commit reconciliation failed");
            JSON.writerWithDefaultPrettyPrinter().writeValue(backup.resolve("migration-report.json").toFile(),report);
            System.out.println(JSON.writeValueAsString(report));
        }finally{try(Statement st=c.createStatement()){st.execute("SELECT RELEASE_LOCK('hmdp_blog_like_migration')");}}
    }
    public static void main(String[]args)throws Exception{
        Path project=Paths.get(args[1]);Path backup=Paths.get(args[2]);Files.createDirectories(backup);
        Map<String,Object>root=readConfiguration(project.resolve("src/main/resources/application.yaml"));
        Map<String,Object>spring=section(root,"spring"),db=section(spring,"datasource"),redisCfg=section(spring,"redis");
        if(System.getenv("HMDP_DB_CONFIG")!=null){
            Map<String,Object>other=readConfiguration(Paths.get(System.getenv("HMDP_DB_CONFIG")));
            Map<String,Object>otherDb=section(section(other,"spring"),"datasource");
            if(!db.get("url").toString().split("\\?")[0].equals(otherDb.get("url").toString().split("\\?")[0]))throw new IllegalStateException("Credential source targets a different database");
            db.put("username",otherDb.get("username"));db.put("password",otherDb.get("password"));
        }
        Class.forName("com.mysql.jdbc.Driver");
        try(Connection c=DriverManager.getConnection(db.get("url").toString(),db.get("username").toString(),String.valueOf(db.getOrDefault("password","")))){
            verifyDatabase(c);
            if("migrate".equals(args[0])){migrate(c,project,backup,redisCfg);return;}
            if("inspect".equals(args[0])){
                Map<String,Object>snapshot=new LinkedHashMap<>();snapshot.put("database",c.getCatalog());snapshot.put("server",c.getMetaData().getDatabaseProductVersion());snapshot.put("mysqlEndpoint",c.getMetaData().getURL().split("\\?")[0]);
                snapshot.put("blogs",query(c,"SELECT id,COALESCE(liked,0) AS liked FROM tb_blog ORDER BY id"));snapshot.put("users",query(c,"SELECT id FROM tb_user ORDER BY id"));
                boolean table=exists(c,"tb_blog_like");snapshot.put("likeTableExists",table);
                snapshot.put("relations",table?query(c,"SELECT user_id,blog_id,created_at FROM tb_blog_like ORDER BY blog_id,user_id"):Collections.emptyList());
                snapshot.put("redisHost",redisCfg.getOrDefault("host","localhost"));snapshot.put("redisPort",redisCfg.getOrDefault("port",6379));snapshot.put("redisDatabase",redisCfg.getOrDefault("database",0));
                try(Redis redis=new Redis(redisCfg)){
                    snapshot.put("legacyRedisRelations",redis.snapshot());snapshot.put("redisAvailable",true);
                    snapshot.put("redisKeyspace",redis.call("INFO","keyspace"));
                    List<Map<String,Object>>databases=new ArrayList<>();
                    for(int database=0;database<16;database++){
                        redis.call("SELECT",Integer.toString(database));List<Map<String,Object>>legacy=redis.snapshot();
                        if(!legacy.isEmpty()){Map<String,Object>item=new LinkedHashMap<>();item.put("database",database);item.put("relations",legacy);databases.add(item);}
                    }
                    snapshot.put("otherDatabaseLikes",databases);
                }catch(IOException e){snapshot.put("redisAvailable",false);snapshot.put("redisConnectionError",e.getClass().getSimpleName());}
                JSON.writerWithDefaultPrettyPrinter().writeValue(backup.resolve("before.json").toFile(),snapshot);
                Map<String,Object>summary=new LinkedHashMap<>(snapshot);summary.remove("users");summary.remove("relations");summary.remove("legacyRedisRelations");
                summary.put("userCount",((List<?>)snapshot.get("users")).size());summary.put("databaseRelations",((List<?>)snapshot.get("relations")).size());summary.put("redisRelations",((List<?>)snapshot.getOrDefault("legacyRedisRelations",Collections.emptyList())).size());
                System.out.println(JSON.writeValueAsString(summary));return;
            }
            throw new IllegalArgumentException("Unsupported maintenance action");
        }
    }
}
