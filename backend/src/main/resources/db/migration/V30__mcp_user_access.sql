-- MCP is an additional user-authorized entry point; existing Agent state remains unchanged.
CREATE TABLE public.mcp_access_token (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    name varchar(100) NOT NULL,
    secret_hash varchar(64) NOT NULL,
    token_hint varchar(32) NOT NULL,
    scopes jsonb NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    last_used_at timestamptz,
    CONSTRAINT mcp_access_token_pkey PRIMARY KEY (id),
    CONSTRAINT fk_mcp_access_token_user FOREIGN KEY (user_id) REFERENCES public.app_user(id),
    CONSTRAINT ck_mcp_access_token_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_mcp_access_token_hash CHECK (secret_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_mcp_access_token_scopes CHECK (jsonb_typeof(scopes) = 'array'
        AND scopes @> '["READ"]'::jsonb
        AND scopes <@ '["READ","CONTENT_WRITE","MEDIA_GENERATE"]'::jsonb),
    CONSTRAINT ck_mcp_access_token_version CHECK (version >= 0),
    CONSTRAINT ck_mcp_access_token_expiry CHECK (expires_at > created_at)
);
COMMENT ON TABLE public.mcp_access_token IS 'User-owned revocable MCP credentials; only the secret hash is stored, never a project-specific grant.';
COMMENT ON COLUMN public.mcp_access_token.id IS 'Public credential identity used to locate its secret hash.';
COMMENT ON COLUMN public.mcp_access_token.user_id IS 'User whose current project permissions authorize every request.';
COMMENT ON COLUMN public.mcp_access_token.name IS 'User-supplied label for this external client credential.';
COMMENT ON COLUMN public.mcp_access_token.secret_hash IS 'SHA-256 of a high-entropy credential secret; never the bearer token.';
COMMENT ON COLUMN public.mcp_access_token.token_hint IS 'Non-secret prefix for identifying a credential in account settings.';
COMMENT ON COLUMN public.mcp_access_token.scopes IS 'Explicit READ, CONTENT_WRITE and MEDIA_GENERATE permissions.';
COMMENT ON COLUMN public.mcp_access_token.version IS 'CAS version for revocation and security-sensitive changes.';
COMMENT ON COLUMN public.mcp_access_token.created_at IS 'Credential creation time.';
COMMENT ON COLUMN public.mcp_access_token.expires_at IS 'Absolute credential expiry.';
COMMENT ON COLUMN public.mcp_access_token.revoked_at IS 'Revocation time; null while not explicitly revoked.';
COMMENT ON COLUMN public.mcp_access_token.last_used_at IS 'Last authenticated use without request or bearer contents.';
COMMENT ON CONSTRAINT mcp_access_token_pkey ON public.mcp_access_token IS 'Primary credential identity.';
COMMENT ON CONSTRAINT fk_mcp_access_token_user ON public.mcp_access_token IS 'Credential ownership belongs to an existing user.';
COMMENT ON CONSTRAINT ck_mcp_access_token_name ON public.mcp_access_token IS 'Credential labels cannot be blank.';
COMMENT ON CONSTRAINT ck_mcp_access_token_hash ON public.mcp_access_token IS 'Only a hexadecimal SHA-256 digest is persisted.';
COMMENT ON CONSTRAINT ck_mcp_access_token_scopes ON public.mcp_access_token IS 'Require READ and reject unknown credential permissions.';
COMMENT ON CONSTRAINT ck_mcp_access_token_version ON public.mcp_access_token IS 'Credential CAS versions are nonnegative.';
COMMENT ON CONSTRAINT ck_mcp_access_token_expiry ON public.mcp_access_token IS 'Credentials must expire after creation.';
COMMENT ON INDEX public.mcp_access_token_pkey IS 'Credential lookup by public identity.';
CREATE INDEX ix_mcp_access_token_user ON public.mcp_access_token (user_id, created_at DESC, id);
COMMENT ON INDEX public.ix_mcp_access_token_user IS 'List a user account credentials without exposing secrets.';

CREATE TABLE public.mcp_operation (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    token_id uuid NOT NULL,
    project_id uuid NOT NULL,
    command_id varchar(200) NOT NULL,
    tool_name varchar(64) NOT NULL,
    request_hash varchar(64) NOT NULL,
    result jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT mcp_operation_pkey PRIMARY KEY (id),
    CONSTRAINT uq_mcp_operation_command UNIQUE (user_id, command_id),
    CONSTRAINT fk_mcp_operation_user FOREIGN KEY (user_id) REFERENCES public.app_user(id),
    CONSTRAINT fk_mcp_operation_token FOREIGN KEY (token_id) REFERENCES public.mcp_access_token(id),
    CONSTRAINT fk_mcp_operation_project FOREIGN KEY (project_id) REFERENCES public.project(id),
    CONSTRAINT ck_mcp_operation_command CHECK (length(btrim(command_id)) > 0),
    CONSTRAINT ck_mcp_operation_tool CHECK (length(btrim(tool_name)) > 0),
    CONSTRAINT ck_mcp_operation_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_mcp_operation_result CHECK (jsonb_typeof(result) = 'object')
);
COMMENT ON TABLE public.mcp_operation IS 'Committed external write commands; results, business changes and project events share one transaction.';
COMMENT ON COLUMN public.mcp_operation.id IS 'Stable external operation identity, independent of AgentRun and LlmTurn.';
COMMENT ON COLUMN public.mcp_operation.user_id IS 'Authenticated user and command-key namespace.';
COMMENT ON COLUMN public.mcp_operation.token_id IS 'Credential that originally authorized the command; historical attribution only.';
COMMENT ON COLUMN public.mcp_operation.project_id IS 'Project authorized and changed by this command.';
COMMENT ON COLUMN public.mcp_operation.command_id IS 'Client-generated stable command key reused after a lost response.';
COMMENT ON COLUMN public.mcp_operation.tool_name IS 'Explicitly registered MCP tool name.';
COMMENT ON COLUMN public.mcp_operation.request_hash IS 'Digest of the canonical tool, project and complete arguments.';
COMMENT ON COLUMN public.mcp_operation.result IS 'Safe committed result for idempotent replay; no credentials or private model state.';
COMMENT ON COLUMN public.mcp_operation.created_at IS 'Business command commit preparation time.';
COMMENT ON CONSTRAINT mcp_operation_pkey ON public.mcp_operation IS 'Stable operation identity.';
COMMENT ON CONSTRAINT uq_mcp_operation_command ON public.mcp_operation IS 'A user command key cannot commit twice or move to another project.';
COMMENT ON CONSTRAINT fk_mcp_operation_user ON public.mcp_operation IS 'Operation references its authenticated user.';
COMMENT ON CONSTRAINT fk_mcp_operation_token ON public.mcp_operation IS 'Retain the original credential attribution after revocation.';
COMMENT ON CONSTRAINT fk_mcp_operation_project ON public.mcp_operation IS 'Operation belongs to an existing project.';
COMMENT ON CONSTRAINT ck_mcp_operation_command ON public.mcp_operation IS 'Stable command keys cannot be blank.';
COMMENT ON CONSTRAINT ck_mcp_operation_tool ON public.mcp_operation IS 'Tool names cannot be blank.';
COMMENT ON CONSTRAINT ck_mcp_operation_hash ON public.mcp_operation IS 'Canonical request digests use hexadecimal SHA-256.';
COMMENT ON CONSTRAINT ck_mcp_operation_result ON public.mcp_operation IS 'Committed tool results are structured objects.';
COMMENT ON INDEX public.mcp_operation_pkey IS 'Operation lookup by identity.';
COMMENT ON INDEX public.uq_mcp_operation_command IS 'Idempotent user-command lookup.';
CREATE INDEX ix_mcp_operation_project ON public.mcp_operation (project_id, created_at DESC, id);
COMMENT ON INDEX public.ix_mcp_operation_project IS 'Project-scoped external operation audit lookup.';

CREATE TABLE public.mcp_operation_task (
    operation_id uuid NOT NULL,
    task_id uuid NOT NULL,
    output_index integer NOT NULL,
    CONSTRAINT mcp_operation_task_pkey PRIMARY KEY (operation_id, task_id),
    CONSTRAINT uq_mcp_operation_task_id UNIQUE (task_id),
    CONSTRAINT uq_mcp_operation_task_output UNIQUE (operation_id, output_index),
    CONSTRAINT fk_mcp_operation_task_operation FOREIGN KEY (operation_id) REFERENCES public.mcp_operation(id),
    CONSTRAINT fk_mcp_operation_task_task FOREIGN KEY (task_id) REFERENCES public.task(id),
    CONSTRAINT ck_mcp_operation_task_index CHECK (output_index >= 0)
);
COMMENT ON TABLE public.mcp_operation_task IS 'All media tasks accepted by an external command, including every batch output.';
COMMENT ON COLUMN public.mcp_operation_task.operation_id IS 'Committed external command that accepted this task.';
COMMENT ON COLUMN public.mcp_operation_task.task_id IS 'Existing durable media Task identity.';
COMMENT ON COLUMN public.mcp_operation_task.output_index IS 'Stable zero-based batch output order.';
COMMENT ON CONSTRAINT mcp_operation_task_pkey ON public.mcp_operation_task IS 'Unique operation-task association.';
COMMENT ON CONSTRAINT uq_mcp_operation_task_id ON public.mcp_operation_task IS 'Each task has one originating external command.';
COMMENT ON CONSTRAINT uq_mcp_operation_task_output ON public.mcp_operation_task IS 'Batch output positions are unique within a command.';
COMMENT ON CONSTRAINT fk_mcp_operation_task_operation ON public.mcp_operation_task IS 'Only committed commands can own task associations.';
COMMENT ON CONSTRAINT fk_mcp_operation_task_task ON public.mcp_operation_task IS 'External task associations reference real durable tasks.';
COMMENT ON CONSTRAINT ck_mcp_operation_task_index ON public.mcp_operation_task IS 'Batch output order cannot be negative.';
COMMENT ON INDEX public.mcp_operation_task_pkey IS 'List all tasks from an operation.';
COMMENT ON INDEX public.uq_mcp_operation_task_id IS 'Find external provenance for a Task.';
COMMENT ON INDEX public.uq_mcp_operation_task_output IS 'Retain exact batch ordering.';

CREATE TABLE public.mcp_operation_version (
    operation_id uuid NOT NULL,
    artifact_version_id uuid NOT NULL,
    CONSTRAINT mcp_operation_version_pkey PRIMARY KEY (operation_id, artifact_version_id),
    CONSTRAINT uq_mcp_operation_version_id UNIQUE (artifact_version_id),
    CONSTRAINT fk_mcp_operation_version_operation FOREIGN KEY (operation_id) REFERENCES public.mcp_operation(id),
    CONSTRAINT fk_mcp_operation_version_version FOREIGN KEY (artifact_version_id) REFERENCES public.artifact_version(id)
);
COMMENT ON TABLE public.mcp_operation_version IS 'Typed provenance for externally created text and archived media versions, without fake Agent runs.';
COMMENT ON COLUMN public.mcp_operation_version.operation_id IS 'Originating external write command.';
COMMENT ON COLUMN public.mcp_operation_version.artifact_version_id IS 'Immutable content version created by the command or its Task.';
COMMENT ON CONSTRAINT mcp_operation_version_pkey ON public.mcp_operation_version IS 'Unique operation-version association.';
COMMENT ON CONSTRAINT uq_mcp_operation_version_id ON public.mcp_operation_version IS 'A content version has one external origin.';
COMMENT ON CONSTRAINT fk_mcp_operation_version_operation ON public.mcp_operation_version IS 'Version provenance references a committed external command.';
COMMENT ON CONSTRAINT fk_mcp_operation_version_version ON public.mcp_operation_version IS 'External provenance references a real immutable content version.';
COMMENT ON INDEX public.mcp_operation_version_pkey IS 'List immutable outputs of an external command.';
COMMENT ON INDEX public.uq_mcp_operation_version_id IS 'Resolve external provenance for a content version.';

ALTER TABLE public.artifact_version DROP CONSTRAINT ck_artifact_created_by_kind;
ALTER TABLE public.artifact_version ADD CONSTRAINT ck_artifact_created_by_kind
    CHECK (created_by_kind IN ('USER', 'AGENT', 'TASK', 'EXTERNAL_TOOL'));
COMMENT ON CONSTRAINT ck_artifact_created_by_kind ON public.artifact_version IS 'Immutable content source: user, built-in Agent, durable task or external MCP tool.';
