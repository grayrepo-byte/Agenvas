package dev.agenvas.referencecatalog;

import static org.assertj.core.api.Assertions.*;
import dev.agenvas.referencecatalog.domain.*;
import dev.agenvas.referencecatalog.domain.ReferenceCatalog.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReferenceCatalogPrivatePolicyTest {
    Source source() {
        return new Source("source", "Known metadata", "hongye007", "open-pose-costume", "a".repeat(40),
                "public/data/catalog.json", Format.OPEN_POSE_COSTUME, true, false, 0, null, "NEVER", null, null, null,
                SourceType.GITHUB);
    }
    Entry entry(Kind kind, String host, String collection) {
        return new Entry("synthetic", kind, "Original synthetic", Map.of("en", "Original"), "P1", "standing", Map.of(),
                Map.of(), List.of(), Map.of(), Map.of(), Map.of(), collection, false, "https://" + host + "/",
                List.of());
    }
    @Test
    void individualBaseImagePermissionIsIndependentOfDistributionWhileOtherScopesRemainSpecific() {
        assertThat(ReferenceCatalogPrivatePolicy.disabledReason(source(),
                entry(Kind.POSE, "ai-pose-gallery.pages.dev", "builtin"))).isNull();
        assertThat(ReferenceCatalogPrivatePolicy.disabledReason(source(),
                entry(Kind.COSTUME, "ai-costume-gallery.pages.dev", "builtin"))).isNull();
        assertThat(ReferenceCatalogPrivatePolicy.disabledReason(source(),
                entry(Kind.COSTUME, "ai-costume-anime-gallery.pages.dev", "builtin")))
                .isEqualTo("PRIVATE_IMAGE_ANIME_USE_SCOPE_UNVERIFIED");
        assertThat(ReferenceCatalogPrivatePolicy.disabledReason(source(), entry(Kind.POSE, "github.com", "custom")))
                .isEqualTo("PRIVATE_IMAGE_CUSTOM_USE_SCOPE_UNVERIFIED");
        assertThat(ReferenceCatalogPrivatePolicy.disabledReason(source(),
                entry(Kind.POSE, "ai-pose-depth-gallery.pages.dev", "builtin")))
                .isEqualTo("PRIVATE_IMAGE_ARCHIVE_PATH_UNVERIFIED");
    }
}
