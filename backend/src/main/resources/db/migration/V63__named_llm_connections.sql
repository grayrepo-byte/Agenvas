-- Editable connection heads are distinct from immutable snapshots pinned by accepted work.
CREATE TABLE public.llm_connection (
    id uuid NOT NULL,
    name varchar(120) NOT NULL,
    current_config_version integer NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT llm_connection_pkey PRIMARY KEY (id),
    CONSTRAINT llm_connection_name_check CHECK (char_length(btrim(name)) BETWEEN 1 AND 120),
    CONSTRAINT llm_connection_current_config_version_key UNIQUE (current_config_version),
    CONSTRAINT llm_connection_current_config_version_fkey FOREIGN KEY (current_config_version) REFERENCES public.llm_provider_config(version)
);
COMMENT ON TABLE public.llm_connection IS 'Stable named LLM connections pointing to their latest immutable runtime snapshot';
COMMENT ON COLUMN public.llm_connection.id IS 'Stable administrator-selected connection identity';
COMMENT ON COLUMN public.llm_connection.name IS 'Administrator-visible connection name';
COMMENT ON COLUMN public.llm_connection.current_config_version IS 'Latest immutable snapshot; used as the connection edit CAS version';
COMMENT ON COLUMN public.llm_connection.created_at IS 'Connection creation time (UTC)';
COMMENT ON COLUMN public.llm_connection.updated_at IS 'Latest connection edit or activation time (UTC)';
COMMENT ON CONSTRAINT llm_connection_pkey ON public.llm_connection IS 'Unique stable connection identity';
COMMENT ON CONSTRAINT llm_connection_name_check ON public.llm_connection IS 'Connection names must contain 1 to 120 characters';
COMMENT ON CONSTRAINT llm_connection_current_config_version_key ON public.llm_connection IS 'Each current snapshot belongs to one named connection';
COMMENT ON CONSTRAINT llm_connection_current_config_version_fkey ON public.llm_connection IS 'Connection head must reference a retained runtime snapshot';
COMMENT ON INDEX public.llm_connection_pkey IS 'Supports stable connection identity lookup';
COMMENT ON INDEX public.llm_connection_current_config_version_key IS 'Supports active snapshot to connection lookup';

-- Preserve credentials, active selection, verification and all historical snapshot identities verbatim.
INSERT INTO public.llm_connection (id, name, current_config_version, created_at, updated_at)
SELECT id, left(model_id, 120), version, created_at, created_at
FROM public.llm_provider_config WHERE active;
