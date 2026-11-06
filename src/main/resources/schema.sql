-- CFP schema. Runs on every start-up (spring.sql.init.mode=always), so everything must be IF NOT EXISTS.
-- Don't edit old statements, add new ones at the bottom.

-- 2019-03 initial version
CREATE TABLE IF NOT EXISTS speaker (
    id         SERIAL PRIMARY KEY,
    name       TEXT NOT NULL,
    email      TEXT NOT NULL,
    bio        TEXT,
    created    TEXT          -- dd.MM.yyyy, the old admin UI wrote it like that
);

-- 2019-04 talks
CREATE TABLE IF NOT EXISTS talk (
    id           SERIAL PRIMARY KEY,
    title        TEXT NOT NULL,
    abstract     TEXT,                              -- never exposed, the web form was dropped
    format       TEXT NOT NULL,
    duration     INTEGER,                           -- derived from format, the reporting tool reads it
    level        TEXT,
    status       TEXT NOT NULL DEFAULT 'SUBMITTED',
    speaker_id   INTEGER NOT NULL,
    submitted_at BIGINT                             -- epoch millis (UTC)
);

-- 2019-10 voting, replaced by rating in 2020. Kept for the 2019 statistics.
CREATE TABLE IF NOT EXISTS vote (
    talk_id INTEGER,
    voter   TEXT,
    stars   INTEGER
);

-- 2020-02 ratings
CREATE TABLE IF NOT EXISTS rating (
    talk_id     INTEGER NOT NULL,
    reviewer_id INTEGER NOT NULL,
    score       INTEGER NOT NULL,
    rated_at    TEXT,                               -- yyyy-MM-dd HH:mm:ss (UTC)
    PRIMARY KEY (talk_id, reviewer_id)
);

-- 2020-06 programme planning (never finished)
-- ALTER TABLE talk ADD COLUMN room TEXT;
-- ALTER TABLE talk ADD COLUMN slot TEXT;

-- 2021-09 after the CSV import created duplicate speakers
CREATE UNIQUE INDEX IF NOT EXISTS ux_speaker_email ON speaker (lower(email));

-- 2022-01 GET /speakers/{id}/talks was slow
CREATE INDEX IF NOT EXISTS ix_talk_speaker ON talk (speaker_id);
