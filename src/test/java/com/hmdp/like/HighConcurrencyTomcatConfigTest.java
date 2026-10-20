package com.hmdp.like;

import com.hmdp.config.HighConcurrencyTomcatConfig;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import static org.junit.jupiter.api.Assertions.*;

class HighConcurrencyTomcatConfigTest {
    @Test void keepAliveSettingsReachTheActualTomcatProtocol() throws Exception {
        TomcatServletWebServerFactory factory=new TomcatServletWebServerFactory();
        new HighConcurrencyTomcatConfig().highConcurrencyKeepAlive(10000,60000).customize(factory);
        Connector connector=new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(0);
        try {
            factory.getTomcatConnectorCustomizers().forEach(c -> c.customize(connector));
            // Tomcat deliberately returns 1 while its endpoint has not yet bound.
            connector.init(); connector.start();
            AbstractHttp11Protocol<?> protocol=(AbstractHttp11Protocol<?>)connector.getProtocolHandler();
            assertEquals(10000,protocol.getMaxKeepAliveRequests());
            assertEquals(60000,protocol.getKeepAliveTimeout());
        } finally { connector.stop(); connector.destroy(); }
    }
}
