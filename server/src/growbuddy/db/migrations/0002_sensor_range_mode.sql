-- 0002: per-sensor range mode. 'auto' derives ranges from the phase of the oldest plant
-- located at the sensor (docs/DOMAIN.md); 'manual' uses sensor_ranges.
ALTER TABLE sensors ADD COLUMN range_mode TEXT NOT NULL DEFAULT 'auto'
    CHECK (range_mode IN ('auto', 'manual'));

-- Sensors whose ranges were already set by hand keep them.
UPDATE sensors SET range_mode = 'manual'
WHERE id IN (SELECT DISTINCT sensor_id FROM sensor_ranges);
