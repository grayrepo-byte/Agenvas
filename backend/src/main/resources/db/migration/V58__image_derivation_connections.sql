ALTER TABLE canvas_connection
    DROP CONSTRAINT ck_canvas_connection_type,
    ADD CONSTRAINT ck_canvas_connection_type
        CHECK (relation_type IN
            ('MEDIA_INPUT', 'AGENT_IMAGE_INPUT', 'IMAGE_DERIVATION'));

COMMENT ON COLUMN canvas_connection.relation_type IS
    'MEDIA_INPUT and AGENT_IMAGE_INPUT are editable inputs; IMAGE_DERIVATION is immutable result lineage.';
