-- What each account stores, previous versions included, as a running total the app keeps up to
-- date in the transaction of every change, instead of walking the account's folder. A quota check
-- charges the total and compares it to the quota in one statement, so two uploads at once can't
-- both squeeze in. Started from the rows there are: the files each account owns and the versions
-- of those files.
ALTER TABLE users ADD COLUMN used_bytes bigint NOT NULL DEFAULT 0;

UPDATE users u
SET used_bytes =
    coalesce((SELECT sum(m.size) FROM file_metadata m
              WHERE m.user_id = u.id AND m.is_directory = false), 0)
    + coalesce((SELECT sum(v.size) FROM file_versions v
                JOIN file_metadata m ON m.id = v.file_id
                WHERE m.user_id = u.id), 0);
