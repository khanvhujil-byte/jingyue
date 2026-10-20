package com.jingyue;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import com.hmdp.service.like.async.AsyncBlogLikeService;
import com.hmdp.entity.Blog;
import com.hmdp.dto.Result;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static com.jingyue.Values.*;

@RestController
@RequestMapping("/api/v1")
public class NotesController {
    final Db db;final AsyncBlogLikeService likes;final RedisOps ops;final Path uploads;
    public NotesController(Db db,AsyncBlogLikeService likes,RedisOps ops,@Value("${jingyue.upload-dir}")String dir){this.db=db;this.likes=likes;this.ops=ops;this.uploads=Paths.get(dir).toAbsolutePath().normalize();}
    static final String READ="SELECT b.*,u.nick_name,s.name AS attraction_name FROM tb_blog b JOIN tb_user u ON u.id=b.user_id JOIN tb_shop s ON s.id=b.shop_id JOIN tb_attraction_profile a ON a.shop_id=s.id WHERE a.published=1 ";
    Map<String,Object> note(Map<String,Object> row){Blog b=new Blog().setId(number(row,"id"));likes.decorate(b,Identity.current()==null?null:Identity.id());return map("travelNoteId",text(row,"id"),"attractionId",text(row,"shop_id"),"attractionName",row.get("attraction_name"),"authorId",text(row,"user_id"),"authorName",row.get("nick_name"),"title",row.get("title"),"content",row.get("content"),"images",text(row,"images").isEmpty()?new String[0]:text(row,"images").split(","),"likeCount",b.getLiked(),"liked",b.getIsLike(),"createdAt",row.get("create_time").toString());}
    @GetMapping("/travel-notes") public Object list(@RequestParam(defaultValue="0")long attractionId,@RequestParam(defaultValue="1")int page){if(page<1||page>100000)throw new ApiFault(400,"INVALID_PARAMETER","页码不正确");List<Map<String,Object>> out=new ArrayList<>();for(Map<String,Object> r:db.rows(READ+" AND (?=0 OR b.shop_id=?) ORDER BY b.id DESC LIMIT 12 OFFSET ?",attractionId,attractionId,(page-1)*12))out.add(note(r));return map("items",out,"page",page,"total",db.count("SELECT COUNT(*) FROM tb_blog b JOIN tb_attraction_profile a ON a.shop_id=b.shop_id WHERE a.published=1 AND (?=0 OR b.shop_id=?)",attractionId,attractionId));}
    @GetMapping("/travel-notes/{id}") public Object detail(@PathVariable long id){return note(db.need(READ+" AND b.id=?",id));}
    String images(Map<String,Object>b){Object v=b.get("images");if(!(v instanceof List)||((List<?>)v).size()>6)throw new ApiFault(400,"INVALID_IMAGE","最多上传6张图片");List<String> out=new ArrayList<>();for(Object value:(List<?>)v){String s=value.toString();if(s.matches("/assets/(valley|town|coast|gallery)\\.webp")){out.add(s);continue;}if(!s.matches("/api/v1/media/[A-Za-z0-9_-]{43}"))throw new ApiFault(400,"INVALID_IMAGE","图片地址不正确");db.need("SELECT id FROM tb_media WHERE id=? AND user_id=?",s.substring(s.lastIndexOf('/')+1),Identity.id());out.add(s);}return String.join(",",out);}
    @PostMapping("/travel-notes") public Object publish(@RequestBody Map<String,Object>b){ops.limit("note:"+Identity.id(),2);long attraction=positive(b,"attractionId");db.need(CatalogController.DETAIL+" AND s.id=?",attraction);String imgs=images(b);long user=Identity.id();return db.tx(()->{org.springframework.jdbc.support.GeneratedKeyHolder h=new org.springframework.jdbc.support.GeneratedKeyHolder();db.jdbc.update(c->{java.sql.PreparedStatement s=c.prepareStatement("INSERT INTO tb_blog(shop_id,user_id,title,content,images) VALUES(?,?,?,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS);s.setLong(1,attraction);s.setLong(2,user);s.setString(3,required(b,"title",120));s.setString(4,required(b,"content",20000));s.setString(5,imgs);return s;},h);long id=h.getKey().longValue();db.update("INSERT INTO tb_blog_like_baseline(blog_id,anonymous_likes) VALUES(?,0)",id);return map("travelNoteId",Long.toString(id));});}
    @PutMapping("/travel-notes/{id}") public Object edit(@PathVariable long id,@RequestBody Map<String,Object>b){String imgs=images(b);if(db.update("UPDATE tb_blog SET title=?,content=?,images=?,update_time=NOW() WHERE id=? AND user_id=?",required(b,"title",120),required(b,"content",20000),imgs,id,Identity.id())!=1)throw new ApiFault(404,"NOT_FOUND","游记不存在或无权编辑");return detail(id);}
    @PutMapping("/travel-notes/{id}/like") public Object like(@PathVariable long id,@RequestBody Map<String,Object>b){if(!(b.get("liked") instanceof Boolean))throw new ApiFault(400,"INVALID_PARAMETER","请明确点赞或取消点赞");db.need(READ+" AND b.id=?",id);Result r=likes.change(id,Identity.id(),(Boolean)b.get("liked"));if(!Boolean.TRUE.equals(r.getSuccess()))throw new ApiFault(409,"LIKE_UNAVAILABLE","点赞暂不可用，请查询原状态");Blog blog=new Blog().setId(id);likes.decorate(blog,Identity.id());return map("travelNoteId",Long.toString(id),"liked",blog.getIsLike(),"likeCount",blog.getLiked());}
    @PostMapping(value="/media",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public Object upload(@RequestParam("file")MultipartFile file) throws IOException {
        ops.limit("upload:"+Identity.id(),2);if(file.isEmpty()||file.getSize()>5*1024*1024)throw new ApiFault(400,"INVALID_IMAGE","请上传不超过5MB的图片");
        if(!Arrays.asList("image/png","image/jpeg").contains(file.getContentType()))throw new ApiFault(400,"INVALID_IMAGE","支持PNG或JPEG图片");
        BufferedImage decoded;try(ImageInputStream in=ImageIO.createImageInputStream(file.getInputStream())){Iterator<ImageReader> readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new ApiFault(400,"INVALID_IMAGE","图片内容无法识别");ImageReader reader=readers.next();try{reader.setInput(in);String format=reader.getFormatName().toLowerCase(Locale.ROOT);if(!Arrays.asList("png","jpeg","jpg").contains(format)||(long)reader.getWidth(0)*reader.getHeight(0)>24000000)throw new ApiFault(400,"INVALID_IMAGE","图片格式或尺寸超出限制");decoded=reader.read(0);}finally{reader.dispose();}}
        Files.createDirectories(uploads);String id=token();Path path=uploads.resolve(id+".png").normalize();if(!path.getParent().equals(uploads))throw new IllegalStateException();ImageIO.write(decoded,"png",path.toFile());try{db.update("INSERT INTO tb_media(id,user_id,suffix,created_at) VALUES(?,?,'png',?)",id,Identity.id(),System.currentTimeMillis());}catch(RuntimeException e){Files.deleteIfExists(path);throw e;}return map("mediaId",id,"url","/api/v1/media/"+id);
    }
    @GetMapping("/media/{id}") public ResponseEntity<byte[]> media(@PathVariable String id) throws IOException {if(!id.matches("[A-Za-z0-9_-]{43}"))throw new ApiFault(404,"NOT_FOUND","图片不存在");db.need("SELECT id FROM tb_media WHERE id=?",id);Path p=uploads.resolve(id+".png");if(!Files.isRegularFile(p))throw new ApiFault(404,"NOT_FOUND","图片不存在");return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).header("X-Content-Type-Options","nosniff").body(Files.readAllBytes(p));}
}
