import kafka.server.KafkaConfig;
import kafka.server.KafkaServerStartable;
import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.ServerCnxnFactory;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.Level;

/** Local acceptance only. One broker is deliberately not a production HA deployment. */
public class LocalKafkaMain {
    public static void main(String[] args) throws Exception {
        LoggerContext logs=(LoggerContext)LoggerFactory.getILoggerFactory();
        logs.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(Level.WARN);
        Path runtime=Paths.get(args[0]).toAbsolutePath();
        Files.createDirectories(runtime);
        String topic=args.length>1 ? args[1] : "seckill-voucher-order";
        int port=args.length>2 ? Integer.parseInt(args[2]) : 9092;
        Path zkPath=runtime.resolve("zookeeper"); Files.createDirectories(zkPath);
        ZooKeeperServer zk=new ZooKeeperServer(zkPath.toFile(),zkPath.toFile(),2000);
        ServerCnxnFactory zkFactory=ServerCnxnFactory.createFactory(new InetSocketAddress("127.0.0.1",0),100);
        zkFactory.startup(zk);
        // After a crash ZooKeeper restores the old broker's ephemeral session from disk.
        // Wait for its normal expiry; never delete a possibly live broker registration.
        java.util.concurrent.CountDownLatch connected=new java.util.concurrent.CountDownLatch(1);
        try(org.apache.zookeeper.ZooKeeper probe=new org.apache.zookeeper.ZooKeeper("127.0.0.1:"+zkFactory.getLocalPort(),6000,event->{
            if(event.getState()==org.apache.zookeeper.Watcher.Event.KeeperState.SyncConnected)connected.countDown();
        })) {
            if(!connected.await(10,TimeUnit.SECONDS))throw new IllegalStateException("本地ZooKeeper未就绪");
            long expiryDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
            while(probe.exists("/brokers/ids/0",false)!=null){if(System.nanoTime()>expiryDeadline)throw new IllegalStateException("旧broker会话未过期，保留数据停止启动");Thread.sleep(250);}
        }
        String address="127.0.0.1:"+port;
        Properties props=new Properties();
        props.setProperty("broker.id","0");
        props.setProperty("listeners","PLAINTEXT://"+address);
        props.setProperty("advertised.listeners","PLAINTEXT://"+address);
        props.setProperty("zookeeper.connect","127.0.0.1:"+zkFactory.getLocalPort());
        props.setProperty("zookeeper.session.timeout.ms","6000");
        props.setProperty("log.dirs",runtime.resolve("data").toString());
        props.setProperty("offsets.topic.replication.factor","1");
        props.setProperty("transaction.state.log.replication.factor","1");
        props.setProperty("transaction.state.log.min.isr","1");
        props.setProperty("auto.create.topics.enable","false");
        props.setProperty("num.partitions","16");
        props.setProperty("group.initial.rebalance.delay.ms","0");
        KafkaServerStartable broker=new KafkaServerStartable(new KafkaConfig(props));
        broker.startup();
        AtomicBoolean stopped=new AtomicBoolean();
        Runnable stop=()-> {
            if(stopped.compareAndSet(false,true)) {
                broker.shutdown(); broker.awaitShutdown(); zkFactory.shutdown(); zk.shutdown();
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(stop));
        try {
        Properties adminProps=new Properties(); adminProps.setProperty("bootstrap.servers",address);
        try(AdminClient admin=AdminClient.create(adminProps)) {
            try { admin.createTopics(Collections.singletonList(new NewTopic(topic,16,(short)1))).all().get(30,TimeUnit.SECONDS); }
            catch(ExecutionException e) { if(!(e.getCause() instanceof TopicExistsException)) throw e; }
            int partitions=-1;
            long metadataDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
            while(partitions<0) {
                try {
                    partitions=admin.describeTopics(Collections.singletonList(topic)).all().get(5,TimeUnit.SECONDS).get(topic).partitions().size();
                } catch(ExecutionException | java.util.concurrent.TimeoutException e) {
                    if(System.nanoTime()>=metadataDeadline) throw e;
                    Thread.sleep(250);
                }
            }
            if(partitions!=16) throw new IllegalStateException("本地验收主题必须有16个分区");
        }
        Files.write(runtime.resolve("ready.txt"),(address+"\n"+topic+"\n16 partitions\nLOCAL SINGLE BROKER ONLY\n").getBytes(StandardCharsets.UTF_8));
        Path stopSignal=runtime.resolve("stop.signal");
        while(!Files.exists(stopSignal)) Thread.sleep(1000);
        Files.deleteIfExists(stopSignal);
        } finally {
            stop.run();
            Files.deleteIfExists(runtime.resolve("ready.txt"));
        }
    }
}
