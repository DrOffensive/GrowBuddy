"""ntfy publisher (JSON publish, so titles may contain UTF-8; bearer access token).

When the GrowBuddy app has registered a UnifiedPush endpoint (a private ``up…``
topic on the same ntfy server), alerts go there instead: the ntfy app hands
them to GrowBuddy, which shows them with its own icon. If that publish fails,
the alert falls back to the shared topic, so it is never lost.
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field
from typing import Protocol

import httpx

from growbuddy.config import NtfySettings
from growbuddy.db.connection import Database
from growbuddy.db.queries.settings import get_setting
from growbuddy.errors import Invalid

log = logging.getLogger(__name__)

PUSH_ENDPOINT_KEY = "push_endpoint"  # settings table
_PUSH_TOPIC = re.compile(r"up[A-Za-z0-9_-]{1,64}")


class NotifyError(RuntimeError):
    pass


@dataclass(frozen=True, slots=True)
class Notification:
    title: str
    message: str
    priority: int  # ntfy 1..5
    tags: list[str] = field(default_factory=list)
    created_ts: int = 0


class Notifier(Protocol):
    async def send(self, notification: Notification) -> None:
        """Deliver or raise NotifyError."""
        ...


def push_topic(settings: NtfySettings, endpoint: str) -> str:
    """The ntfy topic of an app's UnifiedPush endpoint; raises Invalid if it isn't
    an ``up…`` topic on this server's public URL (``ntfy.public_url``)."""
    base = settings.public_url.rstrip("/") + "/"
    topic = endpoint.removeprefix(base).split("?", 1)[0] if endpoint.startswith(base) else ""
    if not _PUSH_TOPIC.fullmatch(topic):
        raise Invalid(
            f"Push endpoint must be an up… topic on {settings.public_url}. In the ntfy app, "
            f"set Settings > Default server to {settings.public_url} and try again.",
            code="invalid_push_endpoint",
        )
    return topic


async def load_push_topic(db: Database, settings: NtfySettings) -> str | None:
    """The stored app push topic, or None (also when it no longer matches the config)."""
    endpoint = await get_setting(db, PUSH_ENDPOINT_KEY)
    if not endpoint:
        return None
    try:
        return push_topic(settings, endpoint)
    except Invalid:
        log.warning("Ignoring stored push endpoint %s (not on %s)", endpoint, settings.public_url)
        return None


class NtfyNotifier:
    def __init__(
        self,
        settings: NtfySettings,
        timeout_s: float = 10.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._s = settings
        self._client = httpx.AsyncClient(timeout=timeout_s, transport=transport)
        self.push_topic: str | None = None  # the app's UnifiedPush topic, if registered

    async def send(self, notification: Notification) -> None:
        if self.push_topic is not None:
            try:
                await self._post(
                    f"/{self.push_topic}", content=json.dumps(self._app_payload(notification))
                )
                return
            except NotifyError as exc:
                log.warning("App push failed, using the shared topic: %s", exc)
        shared = {k: v for k, v in self._app_payload(notification).items() if k != "type"}
        await self._post("", json={"topic": self._s.topic, **shared})

    async def push_to_app(self, payload: dict[str, object]) -> None:
        """Publish a non-alert message (e.g. an environment snapshot) to the app only."""
        if self.push_topic is None:
            raise NotifyError("no app push endpoint registered")
        await self._post(f"/{self.push_topic}", content=json.dumps(payload))

    def _app_payload(self, notification: Notification) -> dict[str, object]:
        return {
            "type": "alert",
            "title": notification.title,
            "message": notification.message,
            "priority": notification.priority,
            "tags": notification.tags,
            "click": self._s.click_url,
        }

    async def _post(self, path: str, **body: object) -> None:
        headers = {"Authorization": f"Bearer {self._s.token}"} if self._s.token else {}
        try:
            response = await self._client.post(
                self._s.url.rstrip("/") + path,
                headers=headers,
                **body,  # type: ignore[arg-type]
            )
        except httpx.HTTPError as exc:
            raise NotifyError(f"ntfy unreachable: {exc}") from exc
        if response.status_code >= 300:
            raise NotifyError(f"ntfy returned {response.status_code}: {response.text[:200]}")

    async def close(self) -> None:
        await self._client.aclose()
