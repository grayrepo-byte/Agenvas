CREATE TABLE app_user (
    id uuid PRIMARY KEY,
    login_name varchar(100) NOT NULL,
    password_hash varchar(255) NOT NULL,
    status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_app_user_login_name UNIQUE (login_name),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE UNIQUE INDEX uq_app_user_single_active_admin
    ON app_user ((status))
    WHERE status = 'ACTIVE';

CREATE TABLE spring_session (
    primary_id char(36) NOT NULL,
    session_id char(36) NOT NULL,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id char(36) NOT NULL,
    attribute_name varchar(200) NOT NULL,
    attribute_bytes bytea NOT NULL,
    CONSTRAINT spring_session_attributes_pk
        PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk
        FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id)
        ON DELETE CASCADE
);
