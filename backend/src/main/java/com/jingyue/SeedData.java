package com.jingyue;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.util.*;

/** Idempotent development seeds. Existing attractions/SKUs are never overwritten. */
@Component
@ConditionalOnProperty(name="jingyue.development",havingValue="true")
public class SeedData implements ApplicationRunner {
    private final Db db;
    @Value("${spring.datasource.url}") private String url;
    public SeedData(Db db) { this.db=db; }
    @Override public void run(ApplicationArguments args) {
        if(!url.matches("jdbc:mysql://[^/]+/jingyue_[a-z0-9_]+\\?.*")) throw new IllegalStateException("演示种子只允许独立 jingyue_ 数据库");
        String[] names={"林间旅人","景约运营","青岚谷核销员","云水古镇核销员"};
        String[] roles={"USER","ADMIN","REDEEMER","REDEEMER"};
        db.tx(()->{
            for(int i=0;i<4;i++) db.update("INSERT IGNORE INTO tb_user(id,phone,nick_name,role) VALUES(?,?,?,?)",i+1,"1390000000"+(i+1),names[i],roles[i]);
            db.update("INSERT IGNORE INTO tb_redeemer_scope(user_id,attraction_id) VALUES(3,1),(4,2)");
            String[] attractions={"青岚谷","云水古镇","听潮湾","光影艺术馆","栖霞山径","望海灯塔","湖畔花园","溪桥民俗馆","白鹭湿地","松风书院","岛屿记忆馆","青石老街"};
            String[] cities={"岚溪","栖川","临海"};
            String[] categories={"山水自然","古镇园林","山水自然","人文博物"};
            String[] images={"valley","town","coast","gallery"};
            for(int i=0;i<12;i++) {
                long id=i+1; String city=cities[i%3];
                db.update("INSERT IGNORE INTO tb_shop(id,name,type_id,address,x,y,images,open_hours) VALUES(?,?,?,?,?,?,?,?)",
                    id,city+attractions[i],i%4+1,city+"市景约演示路"+(i+1)+"号",120.0+i*.03,30.0+i*.02,"/assets/"+images[i%4]+".webp","09:00–18:00");
                db.update("INSERT IGNORE INTO tb_attraction_profile(shop_id,city_code,city_name,category,introduction,admission_notice,published) VALUES(?,?,?,?,?,?,1)",
                    id,"DEMO"+(i%3+1),city,categories[i%4],"在"+attractions[i]+"放慢脚步，发现沿途的自然与人文。此处为虚构景点，仅用于演示购票流程。",
                    "每单一张成人票，按所选日期及票券时间入园。模拟支付不扣真实资金；已支付票不支持退款、改期。请勿前往演示地址。");
                db.update("INSERT IGNORE INTO tb_ticket_product(id,attraction_id,name,description,status) VALUES(?,?,?,?,'ACTIVE')",1000+id,id,"成人单人票","指定日期使用，每人同一日期票最多一份有效资格。");
                for(int day=0;day<7;day++) {
                    LocalDate date=LocalDate.now(Values.ZONE).plusDays(day);
                    long start=date.atStartOfDay(Values.ZONE).toInstant().toEpochMilli();
                    long end=date.plusDays(1).atStartOfDay(Values.ZONE).toInstant().toEpochMilli();
                    db.update("INSERT INTO tb_ticket_sku(product_id,visit_date,valid_from,valid_until,sale_start,sale_end,price_cent,total_stock,available_stock,epoch) SELECT ?,?,?,?,?,?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM tb_ticket_sku WHERE product_id=? AND visit_date=?)",
                        1000+id,date.toString(),start,end-1,System.currentTimeMillis()-60000,end-900001,3800+(i%4)*1000,100,100,UUID.randomUUID().toString(),1000+id,date.toString());
                }
                db.update("INSERT IGNORE INTO tb_blog(id,shop_id,user_id,title,content,images) VALUES(?,?,1,?,?,?)",id,id,
                    "在"+attractions[i]+"，走一段不赶时间的路","留一个下午给眼前的风景。先看清指定日期、开放时间与入园须知，再安排自己的行程。这是一篇演示游记。","/assets/"+images[i%4]+".webp");
                db.update("INSERT IGNORE INTO tb_blog_like_baseline(blog_id,anonymous_likes) VALUES(?,0)",id);
            }
            return null;
        });
    }
}
