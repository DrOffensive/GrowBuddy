# Seed data

Reference data the server loads on startup (`growbuddy/db/seed.py`).

| File | Loaded | Notes |
|---|---|---|
| `strains.json` | into an empty table | Strain catalog |
| `phase_ranges.json` | into an empty table | Per-phase range templates |
| `nutrient_lines.json` | on every start | Missing lines (by `brand`) and products (by `name`) are added; nothing is removed or changed |
| `feeding_schedules.json` | on every start | Built-in schedules are inserted or updated by `profileId`; custom schedules made in the app are never touched |

So a new brand or schedule added here reaches existing installs with the next
update. Removing one from a file doesn't delete it from existing databases.

## Nutrient lines

```json
{"lines": [
  {"brand": "Biobizz", "description": "…",
   "products": [{"name": "Bio-Grow", "description": "…", "unit": "ml/L"}]}
]}
```

`brand` is unique. `unit` defaults to `ml/L`; schedule amounts are in it
(e.g. `g/L`, `tabs/plant`).

## Feeding schedules

```json
{"schedules": [
  {"profileId": "biobizz", "displayName": "BioBizz (Coco)", "substrate": "coco",
   "weekEntries": [
     {"week": 1, "phase": "Veg",
      "nutrients": {"BioGrow": 1.0, "CalMag": 1.0},
      "pH": {"min": 6.0, "max": 6.4, "target": 6.2},
      "EC": {"min": 1.0, "max": 1.4, "target": 1.2},
      "environment": {"tempDay": {"min": 22, "max": 28}, "tempNight": {"min": 18, "max": 22},
                      "humidity": {"min": 60, "max": 70}, "lightHours": 18},
      "notes": "Establish roots."}
   ]}
]}
```

- `profileId`: stable, unique key (lower case, e.g. `canna-terra`). Don't
  rename it later, or installs get the schedule twice. Never start it with
  `custom-` (reserved for schedules made in the app).
- `week`: 1, 2, 3, … counted from Sprout (docs/DOMAIN.md › Feeding schedules).
- `phase`: `Veg`, `Flower` or `Flush`.
- `nutrients`: product name → amount in the product's `unit` (0 = not this
  week; names that aren't a product count as ml/L). Use the product names
  from `nutrient_lines.json` and list every product of the line in each week
  (0 when unused): the app matches names on letters only (case, digits and
  punctuation are ignored) and takes the line with the most matching products,
  so "Cocos A" goes to the right brand and "Fill from schedule" picks the line
  and products.
- `pH`, `EC`, `environment` and `notes` are optional.
