-- 0001: initial schema. All *_ts / ts columns are Unix epoch milliseconds, UTC.
-- Not yet deployed: may still be edited until the first Pi install.

------------------------------------------------------------------ environment

-- Adopted sensors only. Discovered-but-unadopted devices live in memory (sensors/scanner.py).
CREATE TABLE sensors (
    id              INTEGER PRIMARY KEY,
    mac             TEXT    NOT NULL UNIQUE,  -- upper-case AA:BB:CC:DD:EE:FF
    name            TEXT    NOT NULL,
    status          TEXT    NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'retired')),
    alerts_enabled  INTEGER NOT NULL DEFAULT 1,
    adopted_ts      INTEGER NOT NULL,
    last_seen       INTEGER,
    last_battery    INTEGER,
    last_rssi       INTEGER
);

-- Per-sensor alarm bounds (docs/DOMAIN.md). Missing row or NULL bound = not checked.
CREATE TABLE sensor_ranges (
    sensor_id       INTEGER NOT NULL REFERENCES sensors(id) ON DELETE CASCADE,
    metric          TEXT    NOT NULL CHECK (metric IN ('temperature', 'humidity', 'vpd')),
    warn_min        REAL,
    warn_max        REAL,
    crit_min        REAL,
    crit_max        REAL,
    PRIMARY KEY (sensor_id, metric)
) WITHOUT ROWID;

CREATE TABLE readings (
    id              INTEGER PRIMARY KEY,
    sensor_id       INTEGER NOT NULL REFERENCES sensors(id),
    ts              INTEGER NOT NULL,
    temperature_c   REAL,
    humidity_pct    REAL,
    battery_pct     INTEGER,
    voltage_v       REAL,
    rssi            INTEGER
);
CREATE INDEX idx_readings_sensor_ts ON readings(sensor_id, ts);

CREATE TABLE readings_hourly (
    sensor_id       INTEGER NOT NULL REFERENCES sensors(id),
    hour_ts         INTEGER NOT NULL,  -- start of hour
    samples         INTEGER NOT NULL,
    temp_min        REAL, temp_avg REAL, temp_max REAL,
    hum_min         REAL, hum_avg  REAL, hum_max  REAL,
    PRIMARY KEY (sensor_id, hour_ts)
) WITHOUT ROWID;

CREATE TABLE snapshots (
    id              INTEGER PRIMARY KEY,
    ts              INTEGER NOT NULL,
    path            TEXT    NOT NULL UNIQUE,  -- relative to data_dir
    thumb_path      TEXT    NOT NULL,
    width           INTEGER NOT NULL,
    height          INTEGER NOT NULL,
    size_bytes      INTEGER NOT NULL,
    kind            TEXT    NOT NULL CHECK (kind IN ('scheduled', 'manual'))
);
CREATE INDEX idx_snapshots_ts ON snapshots(ts);

---------------------------------------------------------------------- reference

CREATE TABLE strains (
    id              INTEGER PRIMARY KEY,
    name            TEXT    NOT NULL,
    vendor          TEXT,
    category        TEXT,              -- Feminized | Autoflower | ...
    shop_category   TEXT,              -- source shop's label, informational
    thc             TEXT,
    cbd             TEXT,
    flowering_days  TEXT,              -- free text from source, e.g. "60-65"
    yield_indoor    TEXT,
    height          TEXT,
    url             TEXT,
    image_url       TEXT,
    description     TEXT,
    UNIQUE (name, vendor)
);

CREATE TABLE nutrient_lines (
    id              INTEGER PRIMARY KEY,
    brand           TEXT    NOT NULL UNIQUE,
    description     TEXT
);

CREATE TABLE nutrient_products (
    id              INTEGER PRIMARY KEY,
    line_id         INTEGER NOT NULL REFERENCES nutrient_lines(id) ON DELETE CASCADE,
    name            TEXT    NOT NULL,
    description     TEXT,
    unit            TEXT    NOT NULL DEFAULT 'ml/L',
    UNIQUE (line_id, name)
);

CREATE TABLE feeding_schedules (
    id              INTEGER PRIMARY KEY,
    profile_key     TEXT    NOT NULL UNIQUE,  -- e.g. biobizz
    display_name    TEXT    NOT NULL,
    substrate       TEXT
);

CREATE TABLE feeding_schedule_weeks (
    schedule_id     INTEGER NOT NULL REFERENCES feeding_schedules(id) ON DELETE CASCADE,
    week            INTEGER NOT NULL,
    phase           TEXT    NOT NULL,         -- Veg | Flower | Flush
    nutrients       TEXT    NOT NULL,         -- JSON {product: amount}
    ph              TEXT,                     -- JSON {min,max,target}
    ec              TEXT,                     -- JSON {min,max,target}
    environment     TEXT,                     -- JSON {temp_day,temp_night,humidity,light_hours}
    notes           TEXT,
    PRIMARY KEY (schedule_id, week)
) WITHOUT ROWID;

-- Phase templates for sensor range presets; never evaluated directly.
CREATE TABLE phase_ranges (
    phase           TEXT    PRIMARY KEY,
    enforce_limits  INTEGER NOT NULL DEFAULT 0,
    temp_min        REAL, temp_max     REAL,
    humidity_min    REAL, humidity_max REAL,
    vpd_min         REAL, vpd_max      REAL
) WITHOUT ROWID;

------------------------------------------------------------------------ journal

CREATE TABLE photos (
    id              TEXT    PRIMARY KEY,      -- client UUID
    ts              INTEGER NOT NULL,         -- taken (or uploaded) time
    path            TEXT    NOT NULL UNIQUE,
    thumb_path      TEXT    NOT NULL,
    width           INTEGER NOT NULL,
    height          INTEGER NOT NULL,
    size_bytes      INTEGER NOT NULL,
    created_ts      INTEGER NOT NULL          -- upload time (orphan cleanup grace period)
);

CREATE TABLE plants (
    id                  TEXT    PRIMARY KEY,  -- client UUID
    nickname            TEXT    NOT NULL,
    strain_id           INTEGER REFERENCES strains(id) ON DELETE SET NULL,
    is_autoflower       INTEGER NOT NULL DEFAULT 0,
    sensor_id           INTEGER REFERENCES sensors(id) ON DELETE SET NULL,  -- location
    thumbnail_photo_id  TEXT    REFERENCES photos(id) ON DELETE SET NULL,
    archived            INTEGER NOT NULL DEFAULT 0,
    created_ts          INTEGER NOT NULL
);

CREATE TABLE logs (
    id              TEXT    PRIMARY KEY,      -- client UUID
    ts              INTEGER NOT NULL,
    type            TEXT    NOT NULL CHECK (type IN (
                        'Germination', 'Sprout', 'Inspection', 'LightAdjust', 'HumidityAdjust',
                        'TemperatureAdjust', 'Feeding', 'Topping', 'LST', 'Pruning',
                        'FlowerFlip', 'Harvest', 'Trim', 'Cure', 'TerminatedEarly')),
    comment         TEXT,
    data            TEXT    NOT NULL DEFAULT '{}',  -- JSON, per-type (docs/DOMAIN.md)
    sensor_snapshot TEXT,                           -- JSON reading nearest ts
    created_ts      INTEGER NOT NULL,
    updated_ts      INTEGER NOT NULL
);
CREATE INDEX idx_logs_ts ON logs(ts);
CREATE INDEX idx_logs_type_ts ON logs(type, ts);

CREATE TABLE log_plants (
    log_id          TEXT    NOT NULL REFERENCES logs(id) ON DELETE CASCADE,
    plant_id        TEXT    NOT NULL REFERENCES plants(id) ON DELETE CASCADE,
    PRIMARY KEY (log_id, plant_id)
) WITHOUT ROWID;
CREATE INDEX idx_log_plants_plant ON log_plants(plant_id);

CREATE TABLE log_photos (
    log_id          TEXT    NOT NULL REFERENCES logs(id) ON DELETE CASCADE,
    photo_id        TEXT    NOT NULL REFERENCES photos(id),
    position        INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (log_id, photo_id)
) WITHOUT ROWID;

-- Milestones are derived, never stored: earliest log of each milestone type per plant.
CREATE VIEW plant_milestones AS
SELECT lp.plant_id, l.type, MIN(l.ts) AS ts
FROM logs l JOIN log_plants lp ON lp.log_id = l.id
WHERE l.type IN ('Germination', 'Sprout', 'FlowerFlip', 'Harvest', 'Trim', 'TerminatedEarly')
GROUP BY lp.plant_id, l.type;

------------------------------------------------------------------------- system

CREATE TABLE alerts (
    id              INTEGER PRIMARY KEY,
    sensor_id       INTEGER REFERENCES sensors(id) ON DELETE CASCADE,  -- NULL = system alert (camera)
    metric          TEXT    NOT NULL,  -- temperature | humidity | vpd | sensor_offline | battery_low | camera
    level           TEXT    NOT NULL CHECK (level IN ('warning', 'critical')),
    started_ts      INTEGER NOT NULL,
    ended_ts        INTEGER,           -- NULL = still active
    value           REAL,
    band_min        REAL,
    band_max        REAL,
    notified_ts     INTEGER
);
CREATE INDEX idx_alerts_active ON alerts(sensor_id, metric) WHERE ended_ts IS NULL;
CREATE INDEX idx_alerts_started ON alerts(started_ts);

CREATE TABLE settings (
    key             TEXT    PRIMARY KEY,
    value           TEXT    NOT NULL   -- JSON
) WITHOUT ROWID;

CREATE TABLE events (
    id              INTEGER PRIMARY KEY,
    ts              INTEGER NOT NULL,
    level           TEXT    NOT NULL CHECK (level IN ('debug', 'info', 'warning', 'error')),
    source          TEXT    NOT NULL,  -- ble | camera | alerts | backup | system
    code            TEXT    NOT NULL,  -- machine-readable, e.g. sensor_offline
    message         TEXT    NOT NULL,
    data            TEXT               -- JSON
);
CREATE INDEX idx_events_ts ON events(ts);
