ALTER TABLE canvas_connection
    DROP CONSTRAINT ck_canvas_connection_type;

UPDATE canvas_connection
SET relation_type = 'MEDIA_DERIVATION'
WHERE relation_type = 'IMAGE_DERIVATION';

ALTER TABLE canvas_connection
    ADD CONSTRAINT ck_canvas_connection_type
        CHECK (relation_type IN
            ('MEDIA_INPUT', 'AGENT_IMAGE_INPUT', 'MEDIA_DERIVATION'));

COMMENT ON COLUMN canvas_connection.relation_type IS
    'MEDIA_INPUT and AGENT_IMAGE_INPUT are editable inputs; MEDIA_DERIVATION is removable media lineage.';
