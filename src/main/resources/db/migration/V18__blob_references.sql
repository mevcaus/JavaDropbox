-- The blob a file's live content or a previous version is kept as (#316), or null where the
-- content is still at its own path: the file's in its account's folder, the version's under
-- .versions/. Nothing writes it yet, so every row starts and stays null until uploads switch to
-- blobs; existing files move over in #319.
--
-- The foreign keys keep a blob's row from going while anything points at it, whatever its
-- reference count says: V2's cascades, folder deletes and the demo reset change these rows in
-- bulk, without the app counting each one.
--
-- Everything here is quick on a large install, so the tables are locked only for a moment:
-- adding a column with no default writes no rows, the constraints are NOT VALID since every row
-- is null and there is nothing to check (they hold for every row written from now on), and the
-- indexes are partial, so they start empty.
ALTER TABLE file_metadata ADD COLUMN blob_hash character varying(64);
ALTER TABLE file_versions ADD COLUMN blob_hash character varying(64);
-- The account whose file the version is of, for a version kept as a blob. An upload skips the
-- copy only when its own account already holds the content (decided on #316, as for sync in
-- #202), and asks that by blob and owner under the blob's lock. user_id is who made the version,
-- and can be null, so it won't do. Null for versions at their paths, which the question never
-- concerns; #319 fills it in as it moves them.
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
    ADD CONSTRAINT fk_file_versions_owner
        FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE NOT VALID,
    ADD CONSTRAINT ck_file_versions_blob_has_owner
        CHECK (blob_hash IS NULL OR owner_id IS NOT NULL) NOT VALID;

-- A blob's references, found without a scan: to count them, to let deleting a blob's row check
-- them, and to ask whether an account holds it.
CREATE INDEX idx_file_metadata_blob_hash ON file_metadata (blob_hash, user_id)
    WHERE blob_hash IS NOT NULL;
CREATE INDEX idx_file_versions_blob_hash ON file_versions (blob_hash, owner_id)
    WHERE blob_hash IS NOT NULL;
