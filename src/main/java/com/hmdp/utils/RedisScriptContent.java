package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Classpath scripts are immutable within a deployment; reload by restarting the app. */
public final class RedisScriptContent {
    private RedisScriptContent() { }
    public static String read(String name) {
        try (InputStream in=new ClassPathResource(name).getInputStream()) {
            return StreamUtils.copyToString(in,StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("无法加载Redis脚本："+name,e); }
    }
}
