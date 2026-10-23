-- Character consistency adds metadata and explicit draft application; no Task or result is rewritten.
CREATE TABLE public.project_character (
    id uuid NOT NULL,
    project_id uuid NOT NULL,
    content_json jsonb NOT NULL,
    version bigint NOT NULL,
    archived boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT project_character_pkey PRIMARY KEY (id),
    CONSTRAINT uq_project_character_project UNIQUE (project_id,id),
    CONSTRAINT fk_project_character_project FOREIGN KEY (project_id) REFERENCES public.project(id),
    CONSTRAINT ck_project_character_version CHECK (version > 0),
    CONSTRAINT ck_project_character_content CHECK (jsonb_typeof(content_json) = 'object' AND content_json->>'schemaVersion' IS NOT DISTINCT FROM '1' AND jsonb_typeof(content_json->'content') IS NOT DISTINCT FROM 'object')
);
COMMENT ON TABLE public.project_character IS 'Stable project characters with independently versioned immutable content.';
COMMENT ON COLUMN public.project_character.id IS 'Stable project character identity.';
COMMENT ON COLUMN public.project_character.project_id IS 'Owned project authorization boundary.';
COMMENT ON COLUMN public.project_character.content_json IS 'Current profile content; all exact media references belong to this project.';
COMMENT ON COLUMN public.project_character.version IS 'Profile CAS version; first saved revision is one.';
COMMENT ON COLUMN public.project_character.archived IS 'Archived identities retain existing immutable shot snapshots.';
COMMENT ON COLUMN public.project_character.created_at IS 'Identity creation time.';
COMMENT ON COLUMN public.project_character.updated_at IS 'Last profile change time.';
COMMENT ON CONSTRAINT project_character_pkey ON public.project_character IS 'Stable character identity.';
COMMENT ON INDEX public.project_character_pkey IS 'Stable character identity.';
COMMENT ON CONSTRAINT uq_project_character_project ON public.project_character IS 'Same project references to character identity.';
COMMENT ON INDEX public.uq_project_character_project IS 'Same project references to character identity.';
COMMENT ON CONSTRAINT fk_project_character_project ON public.project_character IS 'Character belongs to an existing project.';
COMMENT ON CONSTRAINT ck_project_character_version ON public.project_character IS 'Persisted profiles start at version one.';
COMMENT ON CONSTRAINT ck_project_character_content ON public.project_character IS 'Profile content is a structured object.';

CREATE TABLE public.project_character_revision (
    project_id uuid NOT NULL,
    character_id uuid NOT NULL,
    version bigint NOT NULL,
    content_json jsonb NOT NULL,
    archived boolean NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT project_character_revision_pkey PRIMARY KEY (project_id,character_id,version),
    CONSTRAINT fk_character_revision_character FOREIGN KEY (project_id,character_id) REFERENCES public.project_character(project_id,id),
    CONSTRAINT ck_character_revision_version CHECK (version > 0),
    CONSTRAINT ck_character_revision_content CHECK (jsonb_typeof(content_json) = 'object' AND content_json->>'schemaVersion' IS NOT DISTINCT FROM '1' AND jsonb_typeof(content_json->'content') IS NOT DISTINCT FROM 'object')
);
COMMENT ON TABLE public.project_character_revision IS 'Immutable revisions used by fixed character shot snapshots.';
COMMENT ON COLUMN public.project_character_revision.project_id IS 'Same project scope as the stable identity.';
COMMENT ON COLUMN public.project_character_revision.character_id IS 'Stable character identity.';
COMMENT ON COLUMN public.project_character_revision.version IS 'Exact immutable profile revision.';
COMMENT ON COLUMN public.project_character_revision.content_json IS 'Fixed name, description, looks, image views and voice reference.';
COMMENT ON COLUMN public.project_character_revision.archived IS 'Archive state at this revision.';
COMMENT ON COLUMN public.project_character_revision.created_at IS 'Revision creation time.';
COMMENT ON CONSTRAINT project_character_revision_pkey ON public.project_character_revision IS 'Immutable content revision identity.';
COMMENT ON INDEX public.project_character_revision_pkey IS 'Immutable content revision identity.';
COMMENT ON CONSTRAINT fk_character_revision_character ON public.project_character_revision IS 'Revision belongs to the same project character.';
COMMENT ON CONSTRAINT ck_character_revision_version ON public.project_character_revision IS 'Revision is positive.';
COMMENT ON CONSTRAINT ck_character_revision_content ON public.project_character_revision IS 'Revision content is a structured object.';

CREATE TABLE public.canvas_character_binding (
    project_id uuid NOT NULL,
    canvas_item_id uuid NOT NULL,
    version bigint NOT NULL,
    draft_version bigint NOT NULL,
    snapshots_json jsonb NOT NULL,
    draft_state_json jsonb NOT NULL,
    needs_review boolean NOT NULL,
    reviewed_draft_version bigint,
    reviewed_selected_version_id uuid,
    reviewed_at timestamptz,
    updated_at timestamptz NOT NULL,
    CONSTRAINT canvas_character_binding_pkey PRIMARY KEY (project_id,canvas_item_id),
    CONSTRAINT fk_character_binding_canvas FOREIGN KEY (project_id,canvas_item_id) REFERENCES public.canvas_item(project_id,id) ON DELETE CASCADE,
    CONSTRAINT ck_character_binding_version CHECK (version > 0 AND draft_version >= 0),
    CONSTRAINT ck_character_binding_snapshots CHECK (jsonb_typeof(snapshots_json) = 'object' AND snapshots_json->>'schemaVersion' IS NOT DISTINCT FROM '1' AND jsonb_typeof(snapshots_json->'content') IS NOT DISTINCT FROM 'array' AND jsonb_typeof(draft_state_json) = 'object' AND draft_state_json->>'schemaVersion' IS NOT DISTINCT FROM '1' AND jsonb_typeof(draft_state_json->'content') IS NOT DISTINCT FROM 'object')
);
COMMENT ON TABLE public.canvas_character_binding IS 'Applied character snapshots and independent shot review state.';
COMMENT ON COLUMN public.canvas_character_binding.project_id IS 'Project authorization and event lock scope.';
COMMENT ON COLUMN public.canvas_character_binding.canvas_item_id IS 'Media shot whose draft receives the explicit character inputs.';
COMMENT ON COLUMN public.canvas_character_binding.version IS 'Whole binding aggregate CAS version.';
COMMENT ON COLUMN public.canvas_character_binding.draft_version IS 'Draft version saved by the last explicit apply.';
COMMENT ON COLUMN public.canvas_character_binding.snapshots_json IS 'All applied character snapshots, fixed at user selected profile revisions.';
COMMENT ON COLUMN public.canvas_character_binding.draft_state_json IS 'Owned input source IDs, prompt contribution and speaker contribution used for safe removal.';
COMMENT ON COLUMN public.canvas_character_binding.needs_review IS 'Independent review state; apply never marks a shot reviewed.';
COMMENT ON COLUMN public.canvas_character_binding.reviewed_draft_version IS 'Draft CAS version explicitly reviewed.';
COMMENT ON COLUMN public.canvas_character_binding.reviewed_selected_version_id IS 'Exact selected result explicitly reviewed, nullable for empty media.';
COMMENT ON COLUMN public.canvas_character_binding.reviewed_at IS 'Last explicit review time.';
COMMENT ON COLUMN public.canvas_character_binding.updated_at IS 'Last aggregate application or review time.';
COMMENT ON CONSTRAINT canvas_character_binding_pkey ON public.canvas_character_binding IS 'One CAS aggregate per media shot.';
COMMENT ON INDEX public.canvas_character_binding_pkey IS 'One CAS aggregate per media shot.';
COMMENT ON CONSTRAINT fk_character_binding_canvas ON public.canvas_character_binding IS 'Binding belongs to a shot in the same project.';
COMMENT ON CONSTRAINT ck_character_binding_version ON public.canvas_character_binding IS 'Binding and draft versions are valid.';
COMMENT ON CONSTRAINT ck_character_binding_snapshots ON public.canvas_character_binding IS 'Fixed snapshots and contribution tracking retain structured JSON.';

CREATE TABLE public.canvas_character_binding_member (
    project_id uuid NOT NULL,
    canvas_item_id uuid NOT NULL,
    character_id uuid NOT NULL,
    character_version bigint NOT NULL,
    CONSTRAINT canvas_character_binding_member_pkey PRIMARY KEY (project_id,canvas_item_id,character_id),
    CONSTRAINT fk_character_member_binding FOREIGN KEY (project_id,canvas_item_id) REFERENCES public.canvas_character_binding(project_id,canvas_item_id) ON DELETE CASCADE,
    CONSTRAINT fk_character_member_revision FOREIGN KEY (project_id,character_id,character_version) REFERENCES public.project_character_revision(project_id,character_id,version)
);
COMMENT ON TABLE public.canvas_character_binding_member IS 'Relational same project revision references and impact lookup for applied snapshots.';
COMMENT ON COLUMN public.canvas_character_binding_member.project_id IS 'Same project as both the shot and character.';
COMMENT ON COLUMN public.canvas_character_binding_member.canvas_item_id IS 'Binding aggregate shot identity.';
COMMENT ON COLUMN public.canvas_character_binding_member.character_id IS 'Applied stable character identity.';
COMMENT ON COLUMN public.canvas_character_binding_member.character_version IS 'Applied immutable profile revision.';
COMMENT ON CONSTRAINT canvas_character_binding_member_pkey ON public.canvas_character_binding_member IS 'A character occurs once per shot binding.';
COMMENT ON INDEX public.canvas_character_binding_member_pkey IS 'A character occurs once per shot binding.';
COMMENT ON CONSTRAINT fk_character_member_binding ON public.canvas_character_binding_member IS 'Membership follows its complete binding aggregate.';
COMMENT ON CONSTRAINT fk_character_member_revision ON public.canvas_character_binding_member IS 'Applied profile revision is exact and belongs to the same project.';

CREATE TABLE public.project_character_command (
    project_id uuid NOT NULL,
    command_id uuid NOT NULL,
    request_hash varchar(64) NOT NULL,
    result_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT project_character_command_pkey PRIMARY KEY (project_id,command_id),
    CONSTRAINT fk_character_command_project FOREIGN KEY (project_id) REFERENCES public.project(id),
    CONSTRAINT ck_character_command_result CHECK (jsonb_typeof(result_json) = 'object' AND result_json->>'schemaVersion' IS NOT DISTINCT FROM '1' AND jsonb_typeof(result_json->'content') IS NOT DISTINCT FROM 'object')
);
COMMENT ON TABLE public.project_character_command IS 'Durable character and binding command replay without repeating draft mutations.';
COMMENT ON COLUMN public.project_character_command.project_id IS 'Owned project scope for command replay.';
COMMENT ON COLUMN public.project_character_command.command_id IS 'Client supplied idempotent command identity.';
COMMENT ON COLUMN public.project_character_command.request_hash IS 'Canonical command kind, target and request digest.';
COMMENT ON COLUMN public.project_character_command.result_json IS 'Original successful response returned on exact command replay.';
COMMENT ON COLUMN public.project_character_command.created_at IS 'Command completion time.';
COMMENT ON CONSTRAINT project_character_command_pkey ON public.project_character_command IS 'One result for a client command in a project.';
COMMENT ON INDEX public.project_character_command_pkey IS 'One result for a client command in a project.';
COMMENT ON CONSTRAINT fk_character_command_project ON public.project_character_command IS 'Command belongs to a real project.';
COMMENT ON CONSTRAINT ck_character_command_result ON public.project_character_command IS 'Successful replay response is an object.';

CREATE INDEX ix_character_member_impact ON public.canvas_character_binding_member(project_id,character_id,canvas_item_id);
COMMENT ON INDEX public.ix_character_member_impact IS 'Find each affected shot before editing or archiving a character.';
CREATE TRIGGER character_revision_immutable BEFORE UPDATE OR DELETE ON public.project_character_revision FOR EACH ROW EXECUTE FUNCTION public.reject_artifact_version_mutation();
COMMENT ON TRIGGER character_revision_immutable ON public.project_character_revision IS 'Reject changes to profile revisions already used by shot snapshots.';
