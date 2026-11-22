-- When a blob's reference count last fell to zero, or when its row was made ahead of the first
-- reference; null while anything points at it (#316). A background job deletes a blob once it
-- has stayed at zero longer than javadropbox.blobs.release-grace: readers hold a blob's hash
-- outside any lock (a download that has looked it up, a folder zip streaming for minutes), so it
-- isn't deleted the moment its count reaches zero.
ALTER TABLE blobs ADD COLUMN released_at timestamp with time zone;

-- A blob nothing points at has been released, and only such a blob has: the job's queue is never
-- missing one, and never holds one in use. Nothing has written blobs yet, but a database a
-- development build used might hold some, released now so they fit.
UPDATE blobs SET released_at = now() WHERE reference_count = 0;
ALTER TABLE blobs
    ADD CONSTRAINT ck_blobs_released_when_unreferenced
        CHECK ((reference_count = 0) = (released_at IS NOT NULL));

-- The job's queue, without a scan of every blob.
CREATE INDEX idx_blobs_released ON blobs (released_at) WHERE reference_count = 0;
