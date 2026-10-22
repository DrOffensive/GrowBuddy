"""/api/v1 reference data: strains, nutrient lines, feeding schedules, phase ranges."""

from __future__ import annotations

import json
import secrets

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel

from growbuddy.api.deps import DbDep, require_token
from growbuddy.api.schemas import (
    FeedingSchedule,
    FeedingScheduleBody,
    FeedingScheduleSummary,
    FeedingWeek,
    NutrientLine,
    NutrientProduct,
    PhaseRange,
    PhaseRangeBody,
    Strain,
    StrainCreate,
    StrainPage,
    StrainPatch,
)
from growbuddy.db.queries import reference as ref_q
from growbuddy.errors import Conflict, NotFound

router = APIRouter(tags=["reference"], dependencies=[Depends(require_token)])


# ---------------------------------------------------------------- strains


@router.get("/strains")
async def search_strains(
    db: DbDep,
    q: str | None = Query(default=None, max_length=100),
    category: str | None = None,
    vendor: str | None = None,
    shop_category: str | None = None,
    flowering_days: str | None = None,
    thc: str | None = None,
    limit: int = Query(default=500, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
) -> StrainPage:
    """Case-insensitive ``q`` matches name or vendor; other filters are exact facet values."""
    filters = {
        "category": category,
        "vendor": vendor,
        "shop_category": shop_category,
        "flowering_days": flowering_days,
        "thc": thc,
    }
    rows, total = await ref_q.search_strains(db, q=q, filters=filters, limit=limit, offset=offset)
    return StrainPage(items=[Strain.of(r) for r in rows], total=total)


@router.get("/strains/facets")
async def strain_facets(db: DbDep) -> dict[str, list[str]]:
    return await ref_q.strain_facets(db)


async def _strain_or_404(db: DbDep, strain_id: int) -> Strain:
    row = await ref_q.get_strain(db, strain_id)
    if row is None:
        raise NotFound(f"Strain {strain_id} not found")
    return Strain.of(row)


@router.get("/strains/{strain_id}")
async def get_strain(strain_id: int, db: DbDep) -> Strain:
    return await _strain_or_404(db, strain_id)


@router.post("/strains", status_code=201)
async def create_strain(body: StrainCreate, db: DbDep) -> Strain:
    if await ref_q.strain_exists_named(db, body.name, body.vendor):
        raise Conflict(
            f"Strain '{body.name}' from {body.vendor or 'no vendor'} exists",
            code="duplicate_strain",
        )
    strain_id = await ref_q.insert_strain(db, body.model_dump())
    return await _strain_or_404(db, strain_id)


@router.patch("/strains/{strain_id}")
async def patch_strain(strain_id: int, body: StrainPatch, db: DbDep) -> Strain:
    current = await _strain_or_404(db, strain_id)
    changes = body.model_dump(exclude_unset=True)
    name, vendor = changes.get("name", current.name), changes.get("vendor", current.vendor)
    if await ref_q.strain_exists_named(db, name, vendor, exclude_id=strain_id):
        raise Conflict(
            f"Strain '{name}' from {vendor or 'no vendor'} exists", code="duplicate_strain"
        )
    await ref_q.update_strain(db, strain_id, changes)
    return await _strain_or_404(db, strain_id)


@router.delete("/strains/{strain_id}", status_code=204)
async def delete_strain(strain_id: int, db: DbDep) -> None:
    strain = await _strain_or_404(db, strain_id)
    if not strain.custom:
        raise Conflict("Catalog strains can't be deleted", code="builtin_strain")
    if await ref_q.strain_in_use(db, strain_id):
        raise Conflict(f"'{strain.name}' is used by a plant", code="strain_in_use")
    await ref_q.delete_strain(db, strain_id)


# ------------------------------------------------------- nutrients/feeding


@router.get("/nutrient-lines")
async def nutrient_lines(db: DbDep) -> list[NutrientLine]:
    return [
        NutrientLine(
            id=line["id"],
            brand=line["brand"],
            description=line["description"],
            products=[
                NutrientProduct(
                    id=p["id"], name=p["name"], description=p["description"], unit=p["unit"]
                )
                for p in products
            ],
        )
        for line, products in await ref_q.nutrient_lines(db)
    ]


@router.get("/feeding-schedules")
async def feeding_schedules(db: DbDep) -> list[FeedingScheduleSummary]:
    return [
        FeedingScheduleSummary(
            id=r["id"],
            profile_key=r["profile_key"],
            display_name=r["display_name"],
            substrate=r["substrate"],
            custom=bool(r["custom"]),
            weeks=r["weeks"],
        )
        for r in await ref_q.feeding_schedules(db)
    ]


@router.get("/feeding-schedules/{schedule_id}")
async def feeding_schedule(schedule_id: int, db: DbDep) -> FeedingSchedule:
    found = await ref_q.feeding_schedule(db, schedule_id)
    if found is None:
        raise NotFound(f"Feeding schedule {schedule_id} not found")
    header, weeks = found
    return FeedingSchedule(
        id=header["id"],
        profile_key=header["profile_key"],
        display_name=header["display_name"],
        substrate=header["substrate"],
        custom=bool(header["custom"]),
        weeks=[FeedingWeek.of(w) for w in weeks],
    )


def _week_rows(body: FeedingScheduleBody) -> list[tuple[object, ...]]:
    def dump(part: BaseModel | None) -> str | None:
        return json.dumps(part.model_dump(exclude_none=True)) if part is not None else None

    return [
        (i, w.phase, json.dumps(w.nutrients), dump(w.ph), dump(w.ec), dump(w.environment), w.notes)
        for i, w in enumerate(body.weeks, start=1)
    ]


async def _custom_schedule_or_error(db: DbDep, schedule_id: int) -> str:
    """The custom schedule's profile key; NotFound, or Conflict for a built-in one."""
    found = await ref_q.feeding_schedule(db, schedule_id)
    if found is None:
        raise NotFound(f"Feeding schedule {schedule_id} not found")
    if not found[0]["custom"]:
        raise Conflict(
            "Built-in schedules can't be changed; duplicate it instead", code="builtin_schedule"
        )
    return str(found[0]["profile_key"])


@router.post("/feeding-schedules", status_code=201)
async def create_feeding_schedule(body: FeedingScheduleBody, db: DbDep) -> FeedingSchedule:
    schedule_id = await ref_q.save_custom_schedule(
        db, None, f"custom-{secrets.token_hex(6)}", body.display_name, body.substrate,
        _week_rows(body),
    )  # fmt: skip
    return await feeding_schedule(schedule_id, db)


@router.put("/feeding-schedules/{schedule_id}")
async def put_feeding_schedule(
    schedule_id: int, body: FeedingScheduleBody, db: DbDep
) -> FeedingSchedule:
    key = await _custom_schedule_or_error(db, schedule_id)
    await ref_q.save_custom_schedule(
        db, schedule_id, key, body.display_name, body.substrate, _week_rows(body)
    )
    return await feeding_schedule(schedule_id, db)


@router.delete("/feeding-schedules/{schedule_id}", status_code=204)
async def delete_feeding_schedule(schedule_id: int, db: DbDep) -> None:
    await _custom_schedule_or_error(db, schedule_id)
    await ref_q.delete_schedule(db, schedule_id)


# ------------------------------------------------------------ phase ranges


@router.get("/phase-ranges")
async def phase_ranges(db: DbDep) -> list[PhaseRange]:
    return [PhaseRange.of(r) for r in await ref_q.phase_ranges(db)]


@router.put("/phase-ranges/{phase}")
async def put_phase_range(phase: str, body: PhaseRangeBody, db: DbDep) -> PhaseRange:
    if await ref_q.get_phase_range(db, phase) is None:
        raise NotFound(f"Phase '{phase}' not found")
    await ref_q.put_phase_range(db, phase, body.model_dump())
    row = await ref_q.get_phase_range(db, phase)
    assert row is not None
    return PhaseRange.of(row)
