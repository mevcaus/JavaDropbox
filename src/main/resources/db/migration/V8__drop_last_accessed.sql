-- Downloads and previews wrote the time to every file's row, share links and range requests
-- included, but nothing ever read it.
--
-- A release from before V8 still maps the column and will not start without it. To go back to
-- one, first run: ALTER TABLE file_metadata ADD COLUMN last_accessed timestamptz;
ALTER TABLE file_metadata DROP COLUMN last_accessed;
