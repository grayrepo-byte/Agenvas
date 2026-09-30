package dev.agenvas.bootstrap;

import dev.agenvas.settings.application.SystemLogBuffer;
import dev.agenvas.settings.application.SystemLogCapture;
import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;

/** Agenvas 单体应用的启动入口。 */
@SpringBootApplication(scanBasePackages = "dev.agenvas")
@ConfigurationPropertiesScan(basePackages = "dev.agenvas")
public class AgenvasApplication {

    /** 启动 HTTP 服务及应用所需的后台基础设施。 */
    public static void main(String[] args) {
        SystemLogBuffer logs = new SystemLogBuffer(Clock.systemUTC());
        SystemLogCapture capture = SystemLogCapture.install(logs);
        SpringApplication application = new SpringApplication(AgenvasApplication.class);
        application.addInitializers(context ->
                context.getBeanFactory().registerSingleton("systemLogBuffer", logs));
        application.addListeners(new ApplicationListener<ContextClosedEvent>() {
            @Override public void onApplicationEvent(ContextClosedEvent event) { capture.close(); }
        });
        try {
            application.run(args);
        } catch (RuntimeException | Error failure) {
            capture.close();
            throw failure;
        }
    }
}
