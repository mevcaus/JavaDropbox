-- The SHA-256 of a file's live content and of each previous version, as 64 lowercase hex digits,
-- computed while an upload is written. Null where the app never read the content as it stored it:
-- files and versions from before this, and files copied in by hand and imported. A hint only:
-- content changed outside the app since doesn't match it, so it is checked before anything is
-- keyed by it.
ALTER TABLE file_metadata ADD COLUMN content_hash character varying(64);
ALTER TABLE file_versions ADD COLUMN content_hash character varying(64);
