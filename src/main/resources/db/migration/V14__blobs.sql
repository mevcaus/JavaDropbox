-- Each distinct content kept once, keyed by its SHA-256, at .blobs/<first 2 hex>/<hash> in the
-- store. Files and versions will point at a blob instead of owning a path, and the reference count
-- says how many do; nothing points at one yet.
CREATE TABLE blobs (
    hash character varying(64) NOT NULL,
    size bigint NOT NULL,
    reference_count integer NOT NULL DEFAULT 0,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT blobs_pkey PRIMARY KEY (hash),
    CONSTRAINT ck_blobs_hash CHECK (hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_blobs_size CHECK (size >= 0),
    CONSTRAINT ck_blobs_reference_count CHECK (reference_count >= 0)
);
