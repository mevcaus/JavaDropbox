-- The blob a file's live content or a previous version is kept as (#316), or null where the
-- content is still at its own path: the file's in its account's folder, the version's under
-- .versions/. Nothing writes it yet, so every row starts and stays null until uploads switch to
-- blobs; existing files move over in #319.
--
-- The foreign keys keep a blob's row from going while anything points at it, whatever its
-- reference count says: V2's cascades, folder deletes and the demo reset change these rows in
-- bulk, without the app counting each one.
--
-- What it costs on a large install: it all runs in one transaction, which holds an exclusive lock
-- on file_metadata and file_versions from the first ALTER until it commits, so uploads and reads
-- wait meanwhile. Adding a column with no default writes no rows. The constraints are NOT VALID,
-- since every row is null and there is nothing to check; they hold for every row written or
-- changed from now on, and can be validated later under a weaker lock. Each CREATE INDEX reads its
-- whole table once (partial, so it stays small), which is most of the time the upgrade takes,
-- like V5's index. Stop the previous release before upgrading, as with any migration.
ALTER TABLE file_metadata ADD COLUMN blob_hash character varying(64);
ALTER TABLE file_versions ADD COLUMN blob_hash character varying(64);
-- The account whose file the version is of, for a version kept as a blob. An upload skips the
-- copy only when its own account already holds the content (decided on #316, as for sync in
-- #202), and asks that by blob and owner under the blob's lock. user_id is who made the version,
-- and can be null, so it won't do. Null for versions at their paths, which the question never
-- concerns; #319 fills it in as it moves them. Whatever sets blob_hash sets it from the version's
-- file's user_id in the same statement.
ALTER TABLE file_versions ADD COLUMN owner_id bigint;

ALTER TABLE file_metadata
    ADD CONSTRAINT fk_file_metadata_blob
        FOREIGN KEY (blob_hash) REFERENCES blobs (hash) NOT VALID,
    -- A folder has no content to keep.
    ADD CONSTRAINT ck_file_metadata_folder_has_no_blob
        CHECK (blob_hash IS NULL OR is_directory IS NOT TRUE) NOT VALID;
ALTER TABLE file_versions
    ADD CONSTRAINT fk_file_versions_blob
        FOREIGN KEY (blob_hash) REFERENCES blobs (hash) NOT VALID,
    -- Like every other key to users: an account that owns anything can't be deleted, so a
    -- version whose owner_id names a deleted account would be a bug to surface, not to cascade.
    ADD CONSTRAINT fk_file_versions_owner
        FOREIGN KEY (owner_id) REFERENCES users (id) NOT VALID,
    ADD CONSTRAINT ck_file_versions_blob_has_owner
        CHECK (blob_hash IS NULL OR owner_id IS NOT NULL) NOT VALID;

-- A blob's references, found without a scan: to count them, to let deleting a blob's row check
-- them, and to ask whether an account holds it.
CREATE INDEX idx_file_metadata_blob_hash ON file_metadata (blob_hash, user_id)
    WHERE blob_hash IS NOT NULL;
CREATE INDEX idx_file_versions_blob_hash ON file_versions (blob_hash, owner_id)
    WHERE blob_hash IS NOT NULL;
