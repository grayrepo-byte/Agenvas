-- v64/v65 audit summaries were objects; adding a schema tag preserves their content.
UPDATE library_entry SET source_json = jsonb_set(source_json, '{schemaVersion}', '1') WHERE NOT source_json ? 'schemaVersion';
UPDATE library_import SET source_json = jsonb_set(source_json, '{schemaVersion}', '1') WHERE NOT source_json ? 'schemaVersion';
UPDATE library_command SET input_json = jsonb_set(input_json, '{source,schemaVersion}', '1') WHERE input_json ? 'source' AND NOT (input_json->'source' ? 'schemaVersion');
UPDATE library_cleanup SET metadata_json = jsonb_build_object('schemaVersion', 1, 'id', id::text, 'owner', owner_id::text,
    'objectKey', metadata_json->'objectKey', 'thumbnailKey', metadata_json->'thumbnailKey', 'preparedImport', NULL)
    WHERE metadata_json ? 'kind';
UPDATE library_cleanup SET metadata_json = jsonb_set(metadata_json, '{schemaVersion}', '1') WHERE NOT metadata_json ? 'schemaVersion';
ALTER TABLE library_entry ADD CONSTRAINT library_entry_source_schema CHECK (source_json ? 'schemaVersion' AND source_json->>'schemaVersion' = '1');
ALTER TABLE library_import ADD CONSTRAINT library_import_source_schema CHECK (source_json ? 'schemaVersion' AND source_json->>'schemaVersion' = '1');
ALTER TABLE library_command ADD CONSTRAINT library_command_input_schema CHECK (input_json ? 'schemaVersion' AND input_json->>'schemaVersion' = '1');
ALTER TABLE library_cleanup ADD CONSTRAINT library_cleanup_schema CHECK (metadata_json ? 'schemaVersion' AND metadata_json->>'schemaVersion' = '1');
