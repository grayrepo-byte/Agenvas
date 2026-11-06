package dev.agenvas.referencecatalog;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.referencecatalog.application.ReferenceCatalogValidation;
import org.junit.jupiter.api.Test;

class ReferenceCatalogValidationTest {
    @Test void repositoryFilesRejectTraversalCredentialsQueriesAndEncodedSeparators() {
        for (String path : new String[]{"../private.png", "/a.png", "a//b.png", "a/./b.png", "a/%2e%2e/x", "a?token=secret", "a#fragment", "a\\b.png", "https://example.com/a.png"})
            assertThatThrownBy(() -> ReferenceCatalogValidation.path(path)).as(path).isInstanceOf(RuntimeException.class);
        assertThat(ReferenceCatalogValidation.path("images/original-pose_1.png")).isEqualTo("images/original-pose_1.png");
    }
    @Test void refsCannotInjectPathsOrApiQueries() {
        for (String ref : new String[]{"../main", "main?token=x", "main#x", "https://github.com/x", "a\\b", "a%2fb"})
            assertThatThrownBy(() -> ReferenceCatalogValidation.ref(ref)).as(ref).isInstanceOf(RuntimeException.class);
        assertThat(ReferenceCatalogValidation.ref("main")).isEqualTo("main");
        assertThat(ReferenceCatalogValidation.ref("feature/poses")).isEqualTo("feature/poses");
    }
    @Test void verifiesOriginalPackBytesAndRejectsMimeAndHashMismatch() throws Exception {
        byte[] image = java.nio.file.Files.readAllBytes(java.nio.file.Path.of("../examples/reference-pack/images/neutral-front.png"));
        String sha = ReferenceCatalogValidation.hash(image);
        assertThatCode(() -> ReferenceCatalogValidation.image(image, sha, image.length, "image/png")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ReferenceCatalogValidation.image(image, sha, image.length, "image/jpeg")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ReferenceCatalogValidation.image(image, "0".repeat(64), image.length, "image/png")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ReferenceCatalogValidation.image(image, sha, image.length + 1, "image/png")).isInstanceOf(RuntimeException.class);
    }
    @Test void rejectsInvalidAndOversizedImagesEvenWhenClaimedAsPng() {
        assertThatThrownBy(() -> ReferenceCatalogValidation.image("<html>bad</html>".getBytes(), ReferenceCatalogValidation.hash("<html>bad</html>".getBytes()), (long) "<html>bad</html>".getBytes().length, "image/png"))
                .isInstanceOf(RuntimeException.class);
    }
}
