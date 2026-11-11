-- Work the app does once, such as taking in an upgraded install's files, recorded once it is done
-- so that no later start does it again.
CREATE TABLE one_time_steps (
    name character varying(100) NOT NULL,
    completed_at timestamp with time zone NOT NULL,
    CONSTRAINT one_time_steps_pkey PRIMARY KEY (name)
);
