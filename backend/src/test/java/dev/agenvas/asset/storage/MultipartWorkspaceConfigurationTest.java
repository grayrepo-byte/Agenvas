package dev.agenvas.asset.storage;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.asset.application.AssetProperties;
import dev.agenvas.asset.infrastructure.LocalAssetStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.util.unit.DataSize;

class MultipartWorkspaceConfigurationTest {
    @TempDir Path root;
    @Test void multipartSpoolsOnPrivateWorkingVolumeAndPreservesConfiguredLimits() throws Exception {
        var properties = new MultipartProperties();
        properties.setMaxFileSize(DataSize.ofMegabytes(500));
        properties.setMaxRequestSize(DataSize.ofMegabytes(501));
        var local = new LocalAssetStorage(new AssetProperties(root), null, null);
        var config = new MultipartWorkspaceConfiguration().multipartConfigElement(local, properties);
        assertThat(config.getLocation()).isEqualTo(root.resolve(".multipart").toString());
        assertThat(config.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(500).toBytes());
        assertThat(config.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(501).toBytes());
        assertThat(Files.isDirectory(root.resolve(".multipart"))).isTrue();
    }
    @Test void multipartNeverFollowsAnExistingWorkDirectorySymlink() throws Exception {
        Path outside = Files.createDirectory(root.resolve("outside"));
        Files.createSymbolicLink(root.resolve(".multipart"), outside);
        var local = new LocalAssetStorage(new AssetProperties(root), null, null);
        assertThatThrownBy(() -> new MultipartWorkspaceConfiguration().multipartConfigElement(local, new MultipartProperties()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("symbolic link");
    }
}
