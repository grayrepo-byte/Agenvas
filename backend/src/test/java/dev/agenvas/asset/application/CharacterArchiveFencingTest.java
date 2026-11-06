package dev.agenvas.asset.application;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.asset.domain.Asset;
import dev.agenvas.testing.ImageAssetFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Actual filesystem locks verify that a terminal cleanup cannot race a delayed archive writer. */
class CharacterArchiveFencingTest {
    @TempDir Path root;
    @Test void terminalCleanupWaitsForWriterThenPreventsAnyLateRecreation() throws Exception {
        var archive=new PrivateMediaArchive(new AssetProperties(root),null,new ObjectMapper());
        UUID owner=UUID.randomUUID(),id=UUID.randomUUID();
        Path source=Files.write(root.resolve("synthetic.png"),ImageAssetFixture.encoded("png",3,3));
        var active=new AtomicBoolean(true); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        try (var threads=Executors.newFixedThreadPool(2)) {
            var writer=threads.submit(() -> archive.archive(owner,id,Asset.MediaKind.IMAGE,source,() -> {
                entered.countDown();
                try { if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("Test writer timed out"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return true;
            }));
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue(); active.set(false);
            var cleanup=threads.submit(() -> archive.cleanupUnregistered(owner,id,Asset.MediaKind.IMAGE,() -> !active.get()));
            release.countDown();
            var saved=writer.get(10,TimeUnit.SECONDS); cleanup.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(() -> archive.file(owner,saved,false)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> archive.archive(owner,id,Asset.MediaKind.IMAGE,source,active::get))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Archive lease superseded");
            assertThat(Files.exists(root.resolve("library").resolve(owner.toString()).resolve(id+".png"))).isFalse();
        }
    }
    @Test void registeredMediaIsPreservedAndAudioCleanupDoesNotDecodeOrCreateBytes() throws Exception {
        var archive=new PrivateMediaArchive(new AssetProperties(root),null,new ObjectMapper());
        UUID owner=UUID.randomUUID(),id=UUID.randomUUID();
        Path source=Files.write(root.resolve("synthetic.png"),ImageAssetFixture.encoded("png",3,3));
        var saved=archive.archive(owner,id,Asset.MediaKind.IMAGE,source);
        archive.cleanupUnregistered(owner,id,Asset.MediaKind.IMAGE,() -> false);
        assertThat(Files.readAllBytes(archive.file(owner,saved,false))).containsExactly(Files.readAllBytes(source));
        UUID audio=UUID.randomUUID(); Path partial=root.resolve("library").resolve(owner.toString()).resolve(audio+".wav");
        Files.writeString(partial,"synthetic partial archive");
        archive.cleanupUnregistered(owner,audio,Asset.MediaKind.AUDIO,() -> true);
        assertThat(Files.exists(partial)).isFalse();
    }
}
