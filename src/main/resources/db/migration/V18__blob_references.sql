-- The blob a file's live content or a previous version is kept as (#316), or null where the
-- content is still at its own path: the file's in its account's folder, the version's under
-- .versions/. Nothing writes it yet, so every row starts and stays null until uploads switch to
-- blobs; existing files move over in #319.
--
-- The foreign keys keep a blob's row from going while anything points at it, whatever its
-- reference count says: V2's cascades, folder deletes and the demo reset change these rows in
-- bulk, without the app counting each one. The indexes let a blob's references be counted and
-- found without a scan, and let deleting a blob row check them.
ALTER TABLE file_metadata ADD COLUMN blob_hash character varying(64);
ALTER TABLE file_versions ADD COLUMN blob_hash character varying(64);

ALTER TABLE file_metadata
    ADD CONSTRAINT fk_file_metadata_blob FOREIGN KEY (blob_hash) REFERENCES blobs (hash),
    -- A folder has no content to keep.
    ADD CONSTRAINT ck_file_metadata_folder_has_no_blob
        CHECK (blob_hash IS NULL OR is_directory IS NOT TRUE);
ALTER TABLE file_versions
    ADD CONSTRAINT fk_file_versions_blob FOREIGN KEY (blob_hash) REFERENCES blobs (hash);

CREATE INDEX idx_file_metadata_blob_hash ON file_metadata (blob_hash);
CREATE INDEX idx_file_versions_blob_hash ON file_versions (blob_hash);
