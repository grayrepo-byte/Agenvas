-- Preserve complete native operation / interaction identities for recovery and audit.
alter table task alter column provider_request_id type varchar(512);
alter table provider_attempt alter column provider_request_id type varchar(512);
alter table call_log alter column provider_request_id type varchar(512);
