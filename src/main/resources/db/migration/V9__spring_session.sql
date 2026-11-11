-- Sessions, kept in the database by Spring Session JDBC rather than in the
-- memory of one server, so a restart doesn't sign everyone out. Spring
-- Session's own schema script is not run (spring.session.jdbc.initialize-schema
-- is never): this is its schema-postgresql.sql from spring-session-jdbc 3.5,
-- except that principal_name is as long as a username can be (255, not 100),
-- or an account with a longer name could not sign in.
--
-- Sessions from before this migration were only in the old server's memory
-- and are gone already: everyone signs in once more after the upgrade.
--
-- A release from before V9 ignores these tables. To go back to one, they can
-- be left in place or dropped with:
-- DROP TABLE spring_session_attributes; DROP TABLE spring_session;
CREATE TABLE spring_session (
    primary_id character(36) NOT NULL,
    session_id character(36) NOT NULL,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name character varying(255),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session (session_id);
-- The scheduled cleanup deletes expired sessions by this.
CREATE INDEX spring_session_ix2 ON spring_session (expiry_time);
-- Finding an account's sessions, to end them.
CREATE INDEX spring_session_ix3 ON spring_session (principal_name);

CREATE TABLE spring_session_attributes (
    session_primary_id character(36) NOT NULL,
    attribute_name character varying(200) NOT NULL,
    attribute_bytes bytea NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES spring_session (primary_id) ON DELETE CASCADE
);
