-- Pre-release reset for the CanvasItem-owned media work model. Project creative state is
-- intentionally discarded; installation identity and encrypted provider/model configuration stay.
TRUNCATE TABLE project CASCADE;
TRUNCATE TABLE idempotency_record;

-- Artifact now exposes a library default only. A CanvasItem's displayed result lives on that item.
ALTER TABLE artifact RENAME COLUMN current_version_id TO resource_default_version_id;
ALTER TABLE artifact RENAME CONSTRAINT fk_artifact_current_version
    TO fk_artifact_resource_default_version;

COMMENT ON COLUMN artifact.resource_default_version_id IS
    'Explicit library default used for new CanvasItems; card version selection never updates it.';
COMMENT ON TABLE artifact_version IS
    'Immutable content revisions. Resource-default and CanvasItem selections only move pointers.';

ALTER TABLE canvas_item ADD COLUMN selected_version_id uuid;
ALTER TABLE canvas_item ADD CONSTRAINT fk_canvas_item_selected_version
    FOREIGN KEY (artifact_id, selected_version_id)
    REFERENCES artifact_version (artifact_id, id);
ALTER TABLE canvas_item ADD CONSTRAINT ck_canvas_item_selected_version_subject CHECK (
    subject_type = 'ARTIFACT' OR selected_version_id IS NULL
);

COMMENT ON COLUMN canvas_item.selected_version_id IS
    'Version displayed by this card. Media cards own this independently of the Artifact default.';
COMMENT ON TABLE canvas_item IS
    'Spatial card plus card-local work context; Artifact content remains immutable and shared.';

-- Old rows were reset above, so replace the Artifact-keyed draft without a compatibility copy.
DROP TABLE media_draft;

CREATE TABLE media_draft (
    project_id uuid NOT NULL,
    canvas_item_id uuid NOT NULL,
    prompt text NOT NULL DEFAULT '',
    input_image_version_id uuid,
    duration_seconds integer,
    capability_id uuid,
    display_mode varchar(12) NOT NULL DEFAULT 'DRAFT',
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (project_id, canvas_item_id),
    CONSTRAINT fk_media_draft_canvas_item FOREIGN KEY (project_id, canvas_item_id)
        REFERENCES canvas_item (project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_media_draft_input FOREIGN KEY (project_id, input_image_version_id)
        REFERENCES artifact_version (project_id, id),
    CONSTRAINT fk_media_draft_capability FOREIGN KEY (capability_id)
        REFERENCES media_capability (id),
    CONSTRAINT ck_media_draft_prompt_length CHECK (length(prompt) <= 20000),
    CONSTRAINT ck_media_draft_duration CHECK
        (duration_seconds IS NULL OR duration_seconds BETWEEN 1 AND 30),
    CONSTRAINT ck_media_draft_display_mode CHECK (display_mode IN ('DRAFT', 'RESULT')),
    CONSTRAINT ck_media_draft_version CHECK (version >= 0)
);

COMMENT ON TABLE media_draft IS
    'Editable generation input owned by one IMAGE/VIDEO CanvasItem and protected by independent CAS.';
