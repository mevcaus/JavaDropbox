-- Uploads before folders got rows of their own made the folders on the way without one: a row
-- for "Work/report.txt" with none for "Work". The file tree now lists rows, so every such
-- folder gets a row, owned by the account whose rows are below it, dated from what it holds.
--
-- Each row hands its dates up to every folder above it, and the folders are then grouped, so the
-- work grows with the rows and their depth rather than with rows times folders. Paths the app
-- wouldn't accept today, left by older releases (an empty segment, or a hidden name the tree
-- leaves out), imply no folders.
WITH RECURSIVE ancestors (user_id, path, created_at, updated_at) AS (
    SELECT user_id, regexp_replace(path, '/[^/]*$', ''), created_at, updated_at
    FROM file_metadata
    WHERE path LIKE '%/%'
      AND path !~ '(^|/)(/|$)'
      AND path !~ '(^|/)\.'
    UNION ALL
    SELECT user_id, regexp_replace(path, '/[^/]*$', ''), created_at, updated_at
    FROM ancestors
    WHERE path LIKE '%/%'
)
INSERT INTO file_metadata
    (user_id, path, filename, size, is_directory, current_version, created_at, updated_at)
SELECT a.user_id,
       a.path,
       regexp_replace(a.path, '^.*/', ''),
       0,
       true,
       1,
       coalesce(min(a.created_at), now()),
       coalesce(max(a.updated_at), now())
FROM ancestors a
WHERE NOT EXISTS (
    SELECT 1 FROM file_metadata e WHERE e.user_id = a.user_id AND e.path = a.path
)
GROUP BY a.user_id, a.path;
