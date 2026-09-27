-- Pre-release reset for the versioned-input model. Project creative state is intentionally
-- discarded; installation identity and encrypted provider/model configuration stay.
TRUNCATE TABLE project CASCADE;
TRUNCATE TABLE idempotency_record;
UPDATE creative_data_reset_marker SET completed_at = NULL WHERE id = 1;

-- Replace the last singular media-input column with ordered, exact-version card-local inputs.
ALTER TABLE media_draft
    DROP CONSTRAINT fk_media_draft_input,
    DROP COLUMN input_image_version_id,
    ADD COLUMN parameters_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN video_input_mode varchar(24),
    ADD COLUMN mentions_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_media_draft_parameters_object
        CHECK (jsonb_typeof(parameters_json) = 'object'),
    ADD CONSTRAINT ck_media_draft_mentions_array
        CHECK (jsonb_typeof(mentions_json) = 'array'),
    ADD CONSTRAINT ck_media_draft_video_input_mode
        CHECK (video_input_mode IS NULL OR video_input_mode IN
            ('TEXT', 'START_END', 'GENERAL_REFERENCE'));

CREATE TABLE canvas_item_media_input (
    project_id uuid NOT NULL,
    canvas_item_id uuid NOT NULL,
    artifact_version_id uuid NOT NULL,
    input_role varchar(24) NOT NULL,
    input_order integer NOT NULL,
    color varchar(7) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (project_id, canvas_item_id, artifact_version_id),
    CONSTRAINT uq_canvas_item_media_input_order
        UNIQUE (project_id, canvas_item_id, input_order),
    CONSTRAINT fk_canvas_item_media_input_draft
        FOREIGN KEY (project_id, canvas_item_id)
        REFERENCES media_draft (project_id, canvas_item_id) ON DELETE CASCADE,
    CONSTRAINT fk_canvas_item_media_input_version
        FOREIGN KEY (project_id, artifact_version_id)
        REFERENCES artifact_version (project_id, id),
    CONSTRAINT ck_canvas_item_media_input_role
        CHECK (input_role IN ('REFERENCE', 'START_FRAME', 'END_FRAME')),
    CONSTRAINT ck_canvas_item_media_input_order CHECK (input_order >= 0),
    CONSTRAINT ck_canvas_item_media_input_color
        CHECK (color ~ '^#[0-9A-F]{6}$')
);

CREATE UNIQUE INDEX uq_canvas_item_media_input_start_frame
    ON canvas_item_media_input (project_id, canvas_item_id, input_role)
    WHERE input_role = 'START_FRAME';

CREATE UNIQUE INDEX uq_canvas_item_media_input_end_frame
    ON canvas_item_media_input (project_id, canvas_item_id, input_role)
    WHERE input_role = 'END_FRAME';

CREATE TABLE canvas_connection (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    source_canvas_item_id uuid NOT NULL,
    target_canvas_item_id uuid NOT NULL,
    relation_type varchar(32) NOT NULL,
    source_artifact_version_id uuid NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_canvas_connection_identity UNIQUE
        (project_id, source_canvas_item_id, target_canvas_item_id, relation_type),
    CONSTRAINT fk_canvas_connection_source
        FOREIGN KEY (project_id, source_canvas_item_id)
        REFERENCES canvas_item (project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_canvas_connection_target
        FOREIGN KEY (project_id, target_canvas_item_id)
        REFERENCES canvas_item (project_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_canvas_connection_version
        FOREIGN KEY (project_id, source_artifact_version_id)
        REFERENCES artifact_version (project_id, id),
    CONSTRAINT ck_canvas_connection_distinct_items
        CHECK (source_canvas_item_id <> target_canvas_item_id),
    CONSTRAINT ck_canvas_connection_type
        CHECK (relation_type IN ('MEDIA_INPUT', 'AGENT_IMAGE_INPUT')),
    CONSTRAINT ck_canvas_connection_version CHECK (version >= 0)
);

CREATE INDEX ix_canvas_connection_target
    ON canvas_connection (project_id, target_canvas_item_id);

CREATE TABLE canvas_item_media_input_source (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL,
    canvas_item_id uuid NOT NULL,
    artifact_version_id uuid NOT NULL,
    source_type varchar(16) NOT NULL,
    connection_id uuid,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_canvas_item_media_input_source_input
        FOREIGN KEY (project_id, canvas_item_id, artifact_version_id)
        REFERENCES canvas_item_media_input
            (project_id, canvas_item_id, artifact_version_id) ON DELETE CASCADE,
    CONSTRAINT fk_canvas_item_media_input_source_connection
        FOREIGN KEY (connection_id)
        REFERENCES canvas_connection (id) ON DELETE CASCADE,
    CONSTRAINT ck_canvas_item_media_input_source_type CHECK (
        (source_type = 'MANUAL' AND connection_id IS NULL)
        OR (source_type = 'CONNECTION' AND connection_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_canvas_item_media_input_manual_source
    ON canvas_item_media_input_source (project_id, canvas_item_id, artifact_version_id)
    WHERE source_type = 'MANUAL';

CREATE UNIQUE INDEX uq_canvas_item_media_input_connection_source
    ON canvas_item_media_input_source (connection_id)
    WHERE source_type = 'CONNECTION';

-- Immutable versions keep both their branch parent and the complete frozen generation input.
ALTER TABLE artifact_version
    ADD COLUMN base_version_id uuid,
    ADD COLUMN frozen_input_json jsonb,
    ADD CONSTRAINT fk_artifact_version_base
        FOREIGN KEY (artifact_id, base_version_id)
        REFERENCES artifact_version (artifact_id, id),
    ADD CONSTRAINT ck_artifact_version_frozen_input_object
        CHECK (frozen_input_json IS NULL OR jsonb_typeof(frozen_input_json) = 'object');

COMMENT ON TABLE canvas_item_media_input IS
    'Ordered exact image versions used by one CanvasItem media draft; identity and color are card-local.';
COMMENT ON TABLE canvas_item_media_input_source IS
    'Manual and connection reasons that keep a deduplicated media input alive.';
COMMENT ON TABLE canvas_connection IS
    'Persistent CanvasItem-to-CanvasItem topology with the exact source version captured at creation.';
COMMENT ON COLUMN artifact_version.base_version_id IS
    'Displayed parent version from the originating CanvasItem when this immutable version was created.';
COMMENT ON COLUMN artifact_version.frozen_input_json IS
    'Read-only generation input copied from the accepting Task; null for uploads and text edits.';
