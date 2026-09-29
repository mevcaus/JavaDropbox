-- Constraints the application relies on but V1 (a copy of what Hibernate's
-- ddl-auto generated) never had: one metadata row per path, one user per
-- username, and foreign keys that say what happens when a file's row is
-- deleted. Without them, deleting a file failed on its history rows and
-- left the row behind, and re-creating the same path added a second row that
-- made every later lookup of that path fail.
--
-- Existing databases can already hold the duplicates those bugs produced, so
-- clean them up before adding the constraints.

-- 1. file_metadata: keep the newest row per path (the one describing what is
--    on disk now) and drop older duplicates and rows without a path. Their
--    history stays, detached; their versions belong to a file that no longer
--    exists.
CREATE TEMPORARY TABLE doomed_file_metadata AS
SELECT m.id
FROM file_metadata m
WHERE m.path IS NULL
   OR EXISTS (SELECT 1 FROM file_metadata n WHERE n.path = m.path AND n.id > m.id);

UPDATE file_history SET file_id = NULL
WHERE file_id IN (SELECT id FROM doomed_file_metadata);

DELETE FROM file_versions
WHERE file_id IN (SELECT id FROM doomed_file_metadata);

DELETE FROM file_metadata
WHERE id IN (SELECT id FROM doomed_file_metadata);

DROP TABLE doomed_file_metadata;

UPDATE file_metadata SET filename = regexp_replace(path, '^.*/', '') WHERE filename IS NULL;
UPDATE file_metadata SET is_directory = false WHERE is_directory IS NULL;

ALTER TABLE file_metadata
    ALTER COLUMN path TYPE character varying(4096),
    ALTER COLUMN path SET NOT NULL,
    ALTER COLUMN filename SET NOT NULL,
    ALTER COLUMN is_directory SET NOT NULL,
    ADD CONSTRAINT uk_file_metadata_path UNIQUE (path);

-- 2. Deleting a file's row clears the link from its history (which keeps its
--    own copy of the path) and removes its versions.
ALTER TABLE file_history
    DROP CONSTRAINT fkny1qisdo63yhqlibj91d8udfc,
    ADD CONSTRAINT fk_file_history_file
        FOREIGN KEY (file_id) REFERENCES file_metadata (id) ON DELETE SET NULL;

ALTER TABLE file_versions
    DROP CONSTRAINT fkoipk7el4suwg1l3kmcgo4dk19,
    ADD CONSTRAINT fk_file_versions_file
        FOREIGN KEY (file_id) REFERENCES file_metadata (id) ON DELETE CASCADE;

CREATE INDEX idx_file_versions_file ON file_versions (file_id);

-- 3. file_history: room for long paths and messages, a RESTORE change type,
--    and a details column so informational text stops living in
--    error_message. Restores used to be logged as successful uploads with the
--    note in error_message; move those over.
ALTER TABLE file_history
    ALTER COLUMN file_path TYPE character varying(4096),
    ALTER COLUMN error_message TYPE character varying(1024),
    ADD COLUMN details character varying(1024),
    DROP CONSTRAINT file_history_change_type_check,
    ADD CONSTRAINT file_history_change_type_check
        CHECK (change_type IN ('UPLOAD', 'DELETE', 'CREATE_FOLDER', 'RESTORE'));

UPDATE file_history
SET change_type = 'RESTORE', details = error_message, error_message = NULL
WHERE success AND change_type = 'UPLOAD' AND error_message LIKE 'Restored from%';

CREATE INDEX idx_file_history_timestamp ON file_history ("timestamp");

-- 4. users: one account per username. Setup had no guard against two
--    concurrent submissions, so merge any duplicates into the oldest account.
CREATE TEMPORARY TABLE merged_users AS
SELECT u.id AS old_id, keeper.id AS new_id
FROM users u
JOIN LATERAL (
    SELECT min(k.id) AS id FROM users k WHERE k.username = u.username
) keeper ON true
WHERE u.id <> keeper.id;

UPDATE file_metadata f SET user_id = m.new_id FROM merged_users m WHERE f.user_id = m.old_id;
UPDATE file_versions f SET user_id = m.new_id FROM merged_users m WHERE f.user_id = m.old_id;
UPDATE file_history f SET user_id = m.new_id FROM merged_users m WHERE f.user_id = m.old_id;
DELETE FROM users WHERE id IN (SELECT old_id FROM merged_users);

DROP TABLE merged_users;

ALTER TABLE users
    ALTER COLUMN username SET NOT NULL,
    ADD CONSTRAINT uk_users_username UNIQUE (username);
