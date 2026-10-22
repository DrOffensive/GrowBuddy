-- 0003: user-made reference data. custom = 1 rows belong to the user: the app can edit and
-- delete them, and seeding never touches them. Built-in (custom = 0) feeding schedules and
-- nutrient lines are kept in sync with growbuddy/seed/ on startup.
ALTER TABLE feeding_schedules ADD COLUMN custom INTEGER NOT NULL DEFAULT 0;
ALTER TABLE strains ADD COLUMN custom INTEGER NOT NULL DEFAULT 0;

-- Every seeded catalog entry has a shop URL, so strains without one were added through the API.
UPDATE strains SET custom = 1 WHERE url IS NULL;
