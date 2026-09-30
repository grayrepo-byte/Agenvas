package dev.agenvas.asset.storage;

import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.nio.file.Files;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Large uploads spool to the private persistent working volume instead of the small container /tmp. */
@Configuration(proxyBeanMethods = false)
public class MultipartWorkspaceConfiguration {
    @Bean MultipartConfigElement multipartConfigElement(LocalAssetStorage local, MultipartProperties properties) throws IOException {
        var directory = local.checkedPath(".multipart/work").getParent();
        Files.createDirectories(directory);
        var factory = new MultipartConfigFactory();
        factory.setLocation(directory.toString());
        factory.setMaxFileSize(properties.getMaxFileSize());
        factory.setMaxRequestSize(properties.getMaxRequestSize());
        factory.setFileSizeThreshold(properties.getFileSizeThreshold());
        return factory.createMultipartConfig();
    }
}
