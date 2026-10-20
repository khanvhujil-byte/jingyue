package com.jingyue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.context.annotation.Import;
import com.hmdp.service.like.async.*;

@SpringBootApplication
@EnableScheduling
@EnableKafka
@Import({AsyncBlogLikeService.class, LikeBatchProjector.class, LikeShardRouter.class})
public class JingyueApplication {
    public static void main(String[] args) { SpringApplication.run(JingyueApplication.class, args); }
}
