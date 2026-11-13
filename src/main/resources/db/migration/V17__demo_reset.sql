-- When the public demo's files were last reset (the demo profile only). Kept here rather than on
-- one server's disk, so that several servers reset them once a day between them, not once each.
-- At most one row.
CREATE TABLE demo_reset (
    id smallint NOT NULL DEFAULT 1,
    reset_at timestamp with time zone NOT NULL,
    CONSTRAINT demo_reset_pkey PRIMARY KEY (id),
    CONSTRAINT demo_reset_one_row CHECK (id = 1)
);
