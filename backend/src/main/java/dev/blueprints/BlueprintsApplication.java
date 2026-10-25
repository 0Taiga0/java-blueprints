package dev.blueprints;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.apache.coyote.http11.Http11Nio2Protocol;

@SpringBootApplication
public class BlueprintsApplication {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> windowsConnector() {
        return factory -> {
            // NIO2 использует Windows IOCP, не внутренний AF_UNIX wakeup pipe,
            // который в некоторых окружениях Windows завершается Invalid argument.
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                factory.setProtocol(Http11Nio2Protocol.class.getName());
            }
        };
    }

    public static void main(String[] args) {
        SpringApplication.run(BlueprintsApplication.class, args);
    }
}
