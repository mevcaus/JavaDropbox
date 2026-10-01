-- V2 made file_history.file_id an ON DELETE SET NULL foreign key, but
-- PostgreSQL does not index referencing columns by itself: every deleted
-- file_metadata row had to scan the whole history table to find the entries
-- to detach, so deleting a folder cost one full scan per item in it.
CREATE INDEX idx_file_history_file ON file_history (file_id);
