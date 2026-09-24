package dev.agenvas.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Agenvas 单体应用的启动入口。 */
@SpringBootApplication(scanBasePackages = "dev.agenvas")
@ConfigurationPropertiesScan(basePackages = "dev.agenvas")
public class AgenvasApplication {

    /** 启动 HTTP 服务及应用所需的后台基础设施。 */
    public static void main(String[] args) {
        SpringApplication.run(AgenvasApplication.class, args);
    }
}
