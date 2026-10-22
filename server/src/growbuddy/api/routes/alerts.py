"""/api/v1 alerts, alert settings and test notification."""

from __future__ import annotations

from fastapi import APIRouter, Depends, Query

from growbuddy.alerts.messages import available_languages
from growbuddy.alerts.ntfy import PUSH_ENDPOINT_KEY, NotifyError, push_topic
from growbuddy.alerts.settings import load_alert_settings, save_alert_settings
from growbuddy.api.deps import (
    AlertsDep,
    DbDep,
    EnvironmentPusherDep,
    NotifierDep,
    SettingsDep,
    require_token,
)
from growbuddy.api.pagination import Limit, split_page
from growbuddy.api.schemas import (
    Alert,
    AlertPage,
    AlertSettingsBody,
    NotificationLanguage,
    PushEndpointBody,
)
from growbuddy.config import AlertSettings
from growbuddy.db.queries import alerts as alert_q
from growbuddy.db.queries.settings import get_setting, put_setting
from growbuddy.errors import AppError, Conflict, Invalid
from growbuddy.services.environment_push import PUSH_ENVIRONMENT_KEY

router = APIRouter(tags=["alerts"], dependencies=[Depends(require_token)])


@router.get("/alerts/active")
async def active_alerts(db: DbDep, sensor_id: int | None = None) -> list[Alert]:
    return [Alert.of(r) for r in await alert_q.active_alerts(db, sensor_id)]


@router.get("/alerts")
async def alert_history(
    db: DbDep,
    limit: Limit = 100,
    before: int | None = None,
    sensor_id: int | None = None,
    from_ts: int | None = Query(default=None, alias="from"),
    to_ts: int | None = Query(default=None, alias="to"),
) -> AlertPage:
    rows = await alert_q.list_alerts(
        db, limit=limit + 1, before=before, sensor_id=sensor_id, from_ts=from_ts, to_ts=to_ts
    )
    items, next_before = split_page(rows, limit, lambda r: int(r["started_ts"]))
    return AlertPage(items=[Alert.of(r) for r in items], next_before=next_before)


@router.get("/alert-settings")
async def get_alert_settings(db: DbDep, settings: SettingsDep) -> AlertSettingsBody:
    current = await load_alert_settings(db, settings.alerts)
    return AlertSettingsBody(**current.model_dump(exclude={"evaluate_every_s"}))


@router.put("/alert-settings")
async def put_alert_settings(
    body: AlertSettingsBody, db: DbDep, settings: SettingsDep
) -> AlertSettingsBody:
    if body.language not in available_languages():
        raise Invalid(
            f"No notification texts for language '{body.language}'", code="unknown_language"
        )
    # Fields an older app doesn't know (warning_debounce_s, language) keep their current value.
    current = await load_alert_settings(db, settings.alerts)
    merged = AlertSettings(**{**current.model_dump(), **body.model_dump(exclude_unset=True)})
    await save_alert_settings(db, merged)
    return await get_alert_settings(db, settings)


@router.get("/notification-languages")
async def notification_languages() -> list[NotificationLanguage]:
    return [NotificationLanguage(code=c, name=n) for c, n in available_languages().items()]


@router.post("/alerts/test", status_code=204)
async def test_notification(alerts: AlertsDep) -> None:
    try:
        await alerts.send_test()
    except RuntimeError as exc:  # NotifyError is a RuntimeError too
        code = "notify_failed" if isinstance(exc, NotifyError) else "ntfy_disabled"
        raise AppError(str(exc), code=code, status=502 if code == "notify_failed" else 409) from exc


# ------------------------------------------------- app push (UnifiedPush endpoint)


@router.get("/push-endpoint")
async def get_push_endpoint(db: DbDep) -> PushEndpointBody:
    return PushEndpointBody(
        endpoint=await get_setting(db, PUSH_ENDPOINT_KEY),
        environment=bool(await get_setting(db, PUSH_ENVIRONMENT_KEY, False)),
    )


@router.put("/push-endpoint")
async def put_push_endpoint(
    body: PushEndpointBody,
    db: DbDep,
    settings: SettingsDep,
    notifier: NotifierDep,
    pusher: EnvironmentPusherDep,
) -> PushEndpointBody:
    if notifier is None:
        raise Conflict("ntfy is disabled", code="ntfy_disabled")
    if body.endpoint is None:
        raise AppError("endpoint is required (DELETE to unregister)", code="invalid")
    topic = push_topic(settings.ntfy, body.endpoint)
    await put_setting(db, PUSH_ENDPOINT_KEY, body.endpoint)
    await put_setting(db, PUSH_ENVIRONMENT_KEY, body.environment)
    notifier.push_topic = topic
    if body.environment:
        pusher.kick()  # the widget gets its first values right away
    return body


@router.delete("/push-endpoint", status_code=204)
async def delete_push_endpoint(db: DbDep, notifier: NotifierDep) -> None:
    await put_setting(db, PUSH_ENDPOINT_KEY, None)
    await put_setting(db, PUSH_ENVIRONMENT_KEY, False)
    if notifier is not None:
        notifier.push_topic = None
