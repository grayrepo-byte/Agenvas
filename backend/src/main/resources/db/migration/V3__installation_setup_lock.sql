CREATE TABLE installation_lock (
    id smallint PRIMARY KEY,
    purpose varchar(64) NOT NULL,
    CONSTRAINT ck_installation_lock_singleton CHECK (id = 1)
);

INSERT INTO installation_lock (id, purpose) VALUES (1, 'administrator-setup');

COMMENT ON TABLE installation_lock IS
    'Singleton row used to serialize installation-wide bootstrap decisions.';
