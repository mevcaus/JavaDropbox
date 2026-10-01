-- One row per version of a file. Two replaces of the same file at once used
-- to read the same current_version and both archive under that number: the
-- second move overwrote the first one's stored copy and left two rows
-- pointing at it. The application now locks the file's row while it
-- archives; this makes the database refuse a duplicate as well.
--
-- Existing databases can already hold such duplicates. They share one stored
-- file, which holds whatever was moved there last, so keep the newest row
-- and drop the others.
DELETE FROM file_versions v
WHERE EXISTS (
    SELECT 1 FROM file_versions n
    WHERE n.file_id = v.file_id AND n.version = v.version AND n.id > v.id
);

ALTER TABLE file_versions
    ADD CONSTRAINT uk_file_versions_file_version UNIQUE (file_id, version);

-- The unique index starts with file_id, so it serves the lookups by file
-- that V2's index was added for.
DROP INDEX idx_file_versions_file;
