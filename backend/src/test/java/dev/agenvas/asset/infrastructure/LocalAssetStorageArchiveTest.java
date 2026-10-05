package dev.agenvas.asset.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import dev.agenvas.asset.application.AssetProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import tools.jackson.databind.ObjectMapper;

/** Real files verify that neither installation nor result metadata failures leave partial archives. */
class LocalAssetStorageArchiveTest {

    private static final int IMAGE_WIDTH = 16;
    private static final int IMAGE_HEIGHT = 8;
    private static final int VIDEO_DURATION_MS = 1_250;
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ASSET = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final byte[] VIDEO_BYTES = {0, 1, 2, 3};

    @TempDir Path root;
    private LocalAssetStorage storage;
    private byte[] imageBytes;

    @BeforeEach
    void prepareRealImageAndSyntheticVideoTools() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB),
                "png", output);
        imageBytes = output.toByteArray();
        MediaToolRunner mediaTools = mock(MediaToolRunner.class);
        when(mediaTools.ffprobe(anyList())).thenReturn("""
                {"streams":[{"codec_type":"video","width":16,"height":8}],
                 "format":{"format_name":"mp4","duration":"1.25"}}
                """);
        doAnswer(invocation -> {
            List<String> arguments = invocation.getArgument(0);
            Files.write(Path.of(arguments.getLast()), imageBytes);
            return null;
        }).when(mediaTools).ffmpeg(anyList());
        storage = new LocalAssetStorage(new AssetProperties(root), mediaTools, new ObjectMapper());
    }

    @ParameterizedTest(name = "{0} rolls back after {1}")
    @MethodSource("archiveFailures")
    void removesBothStableAndTemporaryFilesWhenArchiveCannotComplete(
            VisualKind kind, ArchiveFault fault) throws Exception {
        Path original = originalPath(kind);
        Path preview = previewPath();
        IOException failure = fault == ArchiveFault.UNSUPPORTED_ATOMIC_MOVE
                ? new AtomicMoveNotSupportedException("synthetic-original", "synthetic-preview",
                        "Synthetic archive failure")
                : new IOException("Synthetic archive failure");
        Throwable thrown;
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            switch (fault) {
                case SECOND_MOVE, UNSUPPORTED_ATOMIC_MOVE ->
                    files.when(() -> Files.move(any(Path.class), eq(preview),
                            eq(StandardCopyOption.ATOMIC_MOVE))).thenAnswer(invocation -> {
                        assertThat(Files.exists(original)).isTrue();
                        throw failure;
                    });
                case PREVIEW_SIZE -> files.when(() -> Files.size(preview)).thenAnswer(invocation -> {
                    assertThat(Files.exists(original)).isTrue();
                    assertThat(Files.exists(preview)).isTrue();
                    throw failure;
                });
                case PREVIEW_HASH -> files.when(() -> Files.newInputStream(preview))
                        .thenAnswer(invocation -> {
                            assertThat(Files.exists(original)).isTrue();
                            assertThat(Files.exists(preview)).isTrue();
                            throw failure;
                        });
            }
            thrown = catchThrowable(() -> store(kind));
        }

        String expectedMessage = kind == VisualKind.IMAGE
                ? fault == ArchiveFault.UNSUPPORTED_ATOMIC_MOVE
                        ? "Asset volume must support atomic file moves"
                        : "Private asset archive failed"
                : "Private video archive failed";
        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessage(expectedMessage).hasCause(failure);
        try (var files = Files.list(root.resolve(PROJECT.toString()))) {
            assertThat(files).isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(VisualKind.class)
    void keepsBothStableFilesAndReportsTheirMetadataOnSuccess(VisualKind kind) throws Exception {
        Object result = store(kind);
        byte[] originalBytes = Files.readAllBytes(originalPath(kind));
        byte[] previewBytes = Files.readAllBytes(previewPath());
        String key = PROJECT + "/" + ASSET;
        if (result instanceof LocalAssetStorage.StoredImage image) {
            assertThat(image).isEqualTo(new LocalAssetStorage.StoredImage(key + ".png", "image/png",
                    imageBytes.length, hash(imageBytes), IMAGE_WIDTH, IMAGE_HEIGHT,
                    key + ".thumb.png", previewBytes.length, hash(previewBytes)));
            assertThat(originalBytes).isEqualTo(imageBytes);
        } else {
            assertThat(result).isEqualTo(new LocalAssetStorage.StoredVideo(key + ".mp4",
                    VIDEO_BYTES.length, hash(VIDEO_BYTES), IMAGE_WIDTH, IMAGE_HEIGHT, VIDEO_DURATION_MS,
                    key + ".thumb.png", previewBytes.length, hash(previewBytes)));
            assertThat(originalBytes).isEqualTo(VIDEO_BYTES);
        }
        assertThat(ImageIO.read(previewPath().toFile()).getWidth()).isEqualTo(IMAGE_WIDTH);
        try (var files = Files.list(root.resolve(PROJECT.toString()))) {
            assertThat(files).containsExactlyInAnyOrder(originalPath(kind), previewPath());
        }
    }

    private Object store(VisualKind kind) {
        return kind == VisualKind.IMAGE
                ? storage.storeImage(PROJECT, ASSET, new ByteArrayInputStream(imageBytes))
                : storage.storeVideo(PROJECT, ASSET, new ByteArrayInputStream(VIDEO_BYTES));
    }

    private Path originalPath(VisualKind kind) {
        return root.resolve(PROJECT.toString()).resolve(ASSET + kind.extension);
    }

    private Path previewPath() {
        return root.resolve(PROJECT.toString()).resolve(ASSET + ".thumb.png");
    }

    private String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Stream<Arguments> archiveFailures() {
        return Stream.of(VisualKind.values()).flatMap(kind -> Stream.of(ArchiveFault.values())
                .map(fault -> Arguments.of(kind, fault)));
    }

    private enum VisualKind {
        IMAGE(".png"), VIDEO(".mp4");

        private final String extension;

        VisualKind(String extension) {
            this.extension = extension;
        }
    }

    private enum ArchiveFault {
        SECOND_MOVE, PREVIEW_SIZE, PREVIEW_HASH, UNSUPPORTED_ATOMIC_MOVE
    }
}
