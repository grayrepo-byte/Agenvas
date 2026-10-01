-- A temporarily unwritable object must not starve cleanup for other accounts.
ALTER TABLE library_cleanup ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX library_cleanup_due_idx ON library_cleanup(next_attempt_at, created_at, id);
