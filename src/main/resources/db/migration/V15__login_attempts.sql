-- Failed sign-ins and setup codes, per client address, kept in the database rather than in each
-- server's memory, so that several servers share one count: running two mustn't allow twice the
-- guesses. A row is deleted once its failures can no longer lead to or extend a lockout.
CREATE TABLE login_attempts (
    -- What is being guessed: sign-in or setup-code, each counted on its own.
    kind character varying(16) NOT NULL,
    client character varying(255) NOT NULL,
    failures integer NOT NULL,
    first_failure timestamp with time zone,
    locked_until timestamp with time zone,
    -- Attempts reserved and not yet ended, which count towards the limit, so that a burst of
    -- parallel requests gets no more password checks than sequential ones would. A server that
    -- stops mid-check never ends its reservation, so they stop counting a while after the last.
    in_progress integer NOT NULL,
    reserved_at timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT login_attempts_pkey PRIMARY KEY (kind, client),
    CONSTRAINT login_attempts_kind CHECK (kind IN ('sign-in', 'setup-code')),
    CONSTRAINT login_attempts_counts CHECK (failures >= 0 AND in_progress >= 0)
);

CREATE INDEX login_attempts_updated_at ON login_attempts (updated_at);
