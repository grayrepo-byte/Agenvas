-- Constraint comments do not document their backing pg_class index objects.
COMMENT ON INDEX custom_costume_reference_pkey IS 'Unique permanent costume identity';
COMMENT ON INDEX custom_costume_save_command_pkey IS 'Unique accepted save and archive identity';
COMMENT ON INDEX uq_custom_costume_save_command IS 'One save intention per authenticated source project and command key';
