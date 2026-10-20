package com.hmdp.config;

import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Boot 2.3 does not bind Tomcat keep-alive properties; configure the actual protocol. */
@Configuration
@ConditionalOnProperty(name="hmdp.like.async-enabled",havingValue="true")
public class HighConcurrencyTomcatConfig {
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> highConcurrencyKeepAlive(
            @Value("${hmdp.web.max-keep-alive-requests:10000}") int maxRequests,
            @Value("${hmdp.web.keep-alive-timeout-ms:60000}") int timeoutMs) {
        if(maxRequests<100||timeoutMs<1000) throw new IllegalArgumentException("长连接配置超出允许范围");
        return factory -> factory.addConnectorCustomizers(connector -> {
            if(connector.getProtocolHandler() instanceof AbstractHttp11Protocol) {
                AbstractHttp11Protocol<?> protocol=(AbstractHttp11Protocol<?>)connector.getProtocolHandler();
                protocol.setMaxKeepAliveRequests(maxRequests);
                protocol.setKeepAliveTimeout(timeoutMs);
            }
        });
    }
}
