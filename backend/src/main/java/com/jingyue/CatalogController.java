package com.jingyue;

import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.jingyue.Values.*;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final Db db; private final RedisOps ops; private final CatalogCache cache;
    static final String DETAIL="SELECT s.*,p.city_code,p.city_name,p.category,p.introduction,p.admission_notice,p.content_version FROM tb_shop s JOIN tb_attraction_profile p ON p.shop_id=s.id WHERE p.published=1 ";
    public CatalogController(Db db,RedisOps ops,CatalogCache cache) { this.db=db; this.ops=ops;this.cache=cache; }
    @GetMapping("/health") public Map<String,Object> health() { return map("name","景约","status","UP","version","V1.0-development"); }
    @GetMapping("/attractions") public Map<String,Object> list(
        @RequestParam(defaultValue="") String city,@RequestParam(defaultValue="") String category,
        @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="12") int size,@RequestParam(required=false) Double latitude,@RequestParam(required=false) Double longitude) {
        if(page<1||page>100000||size<1||size>50||keyword.length()>100||city.length()>30||category.length()>40)
            throw new ApiFault(400,"INVALID_PARAMETER","请检查查询条件");
        if((latitude==null)!=(longitude==null)||latitude!=null&&(!Double.isFinite(latitude)||!Double.isFinite(longitude)||Math.abs(latitude)>90||Math.abs(longitude)>180))
            throw new ApiFault(400,"INVALID_LOCATION","定位参数不正确");
        String filter=" AND (?='' OR p.city_code=? OR p.city_name=?) AND (?='' OR p.category=?) AND (?='' OR s.name LIKE ?)";
        Object[] args={city,city,city,category,category,keyword,"%"+keyword+"%"};
        String sql=DETAIL+filter+" ORDER BY s.id LIMIT ? OFFSET ?";
        List<Object> query=new ArrayList<>(Arrays.asList(args)); query.add(size);query.add((page-1)*size);
        List<Map<String,Object>> out=new ArrayList<>();
        for(Map<String,Object> row:db.rows(sql,query.toArray())) out.add(attraction(row));
        if(latitude!=null) {
            // Distance sorting must precede pagination; fetch the filtered catalog, then page.
            out.clear();
            for(Map<String,Object> row:db.rows(DETAIL+filter,args)) {
                Map<String,Object> item=attraction(row);
                double dlat=Math.toRadians(((Number)row.get("y")).doubleValue()-latitude);
                double dlon=Math.toRadians(((Number)row.get("x")).doubleValue()-longitude);
                double a=Math.pow(Math.sin(dlat/2),2)+Math.cos(Math.toRadians(latitude))*Math.cos(Math.toRadians(((Number)row.get("y")).doubleValue()))*Math.pow(Math.sin(dlon/2),2);
                item.put("distanceMeters",Math.round(6371000*2*Math.atan2(Math.sqrt(a),Math.sqrt(Math.max(0,1-a))))); out.add(item);
            }
            out.sort(Comparator.comparingLong(m->number(m,"distanceMeters")));
            out=new ArrayList<>(out.subList(Math.min(out.size(),(page-1)*size),Math.min(out.size(),page*size)));
        }
        long total=db.count("SELECT COUNT(*) FROM tb_shop s JOIN tb_attraction_profile p ON p.shop_id=s.id WHERE p.published=1"+filter,args);
        return map("items",out,"page",page,"size",size,"total",total);
    }
    @GetMapping("/attractions/{id}") public Map<String,Object> detail(@PathVariable long id) { return cache.detail(id); }
    static Map<String,Object> attraction(Map<String,Object> row) {
        return map("attractionId",text(row,"id"),"name",row.get("name"),"cityCode",row.get("city_code"),"cityName",row.get("city_name"),"category",row.get("category"),
            "address",row.get("address"),"latitude",row.get("y"),"longitude",row.get("x"),"images",text(row,"images").split(","),"openHours",row.get("open_hours"),
            "introduction",row.get("introduction"),"admissionNotice",row.get("admission_notice"),"contentVersion",text(row,"content_version"));
    }
    @GetMapping("/attractions/{id}/ticket-calendar") public Map<String,Object> calendar(@PathVariable long id) {
        Map<String,Object> content=cache.detail(id);
        String today=java.time.LocalDate.now(ZONE).toString(),end=java.time.LocalDate.now(ZONE).plusDays(30).toString();
        List<Map<String,Object>> result=new ArrayList<>();
        long now=System.currentTimeMillis();
        for(Map<String,Object> row:db.rows("SELECT s.*,p.name,p.description FROM tb_ticket_sku s JOIN tb_ticket_product p ON p.id=s.product_id WHERE p.attraction_id=? AND p.status='ACTIVE' AND s.visit_date>=? AND s.visit_date<? ORDER BY s.visit_date,s.id",id,today,end)) {
            String state=text(row,"state"),sid=text(row,"id");
            List<Object> live=ops.redis.opsForHash().multiGet(RedisOps.STATE,Arrays.asList("stock:"+sid,"sale:"+sid,"meta:"+sid,"fence:"+sid));
            String ref=live.get(0)==null?"":live.get(0).toString();
            String availability=ref.isEmpty()?"待预热":Long.parseLong(ref)==0?"售罄":Long.parseLong(ref)<=10?"紧张":"充足";
            boolean sellable="ON_SALE".equals(state)&&"ON_SALE".equals(live.get(1))&&live.get(2)!=null&&(live.get(3)==null||live.get(3).toString().isEmpty())&&text(row,"control_state").isEmpty()&&now>=number(row,"sale_start")&&now<number(row,"sale_end")&&now<number(row,"valid_until")&&!ref.isEmpty()&&Long.parseLong(ref)>0;
            Object docs=content.get("ticketProducts");if(docs instanceof List)for(Object item:(List<?>)docs)if(item instanceof Map&&text((Map<?,?>)item,"productId").equals(text(row,"product_id"))){row.put("name",((Map<?,?>)item).get("name"));row.put("description",((Map<?,?>)item).get("description"));}
            result.add(map("skuId",text(row,"id"),"productId",text(row,"product_id"),"productName",row.get("name"),"description",row.get("description"),"visitDate",text(row,"visit_date"),
                "validFrom",instant(number(row,"valid_from")),"validUntil",instant(number(row,"valid_until")),"saleStart",instant(number(row,"sale_start")),"saleEnd",instant(number(row,"sale_end")),
                "priceCent",row.get("price_cent"),"state",state,"availability",availability,"sellable",sellable,"configVersion",text(row,"config_version")));
        }
        return map("items",result,"asOf",instant(now),"message","余票仅供参考，以提交结果为准");
    }
}
