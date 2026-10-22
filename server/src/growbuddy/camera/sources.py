"""Frame sources: blocking open/read/close producing JPEG bytes.

Called from worker threads by the camera service; never touched elsewhere.
"""

from __future__ import annotations

import logging
import time
from datetime import datetime
from typing import Protocol

import cv2
import numpy as np

from growbuddy.config import CameraSettings

log = logging.getLogger(__name__)

_JPEG_SOI = b"\xff\xd8"
_JPEG_EOI = b"\xff\xd9"


class CameraError(RuntimeError):
    """Device could not be opened or stopped delivering frames."""


class FrameSource(Protocol):
    def open(self) -> None: ...
    def read(self) -> bytes:
        """Next frame as JPEG; raises CameraError on failure."""
        ...

    def close(self) -> None: ...


def encode_jpeg(img: np.ndarray, quality: int) -> bytes:
    ok, buf = cv2.imencode(".jpg", img, [cv2.IMWRITE_JPEG_QUALITY, quality])
    if not ok:
        raise CameraError("JPEG encoding failed")
    return buf.tobytes()


class OpenCvSource:
    """V4L2 webcam. Requests MJPEG and, where the backend allows it, relays the
    camera's own JPEG frames (CAP_PROP_CONVERT_RGB=0) instead of re-encoding."""

    def __init__(self, settings: CameraSettings) -> None:
        self._s = settings
        self._cap: cv2.VideoCapture | None = None
        self.passthrough = False

    def open(self) -> None:
        cap = cv2.VideoCapture(self._s.device, cv2.CAP_V4L2)
        if not cap.isOpened():
            raise CameraError(f"cannot open {self._s.device}")
        cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter.fourcc(*"MJPG"))
        cap.set(cv2.CAP_PROP_FRAME_WIDTH, self._s.width)
        cap.set(cv2.CAP_PROP_FRAME_HEIGHT, self._s.height)
        cap.set(cv2.CAP_PROP_FPS, self._s.stream_fps)
        cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        self._cap = cap
        self.passthrough = self._try_passthrough(cap)
        log.info(
            "Camera %s opened at %dx%d (%s)",
            self._s.device,
            int(cap.get(cv2.CAP_PROP_FRAME_WIDTH)),
            int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT)),
            "MJPEG passthrough" if self.passthrough else "re-encoding",
        )

    @staticmethod
    def _try_passthrough(cap: cv2.VideoCapture) -> bool:
        if not cap.set(cv2.CAP_PROP_CONVERT_RGB, 0):
            return False
        ok, buf = cap.read()
        if ok and buf is not None and buf.ndim <= 2 and buf.tobytes()[:2] == _JPEG_SOI:
            return True
        cap.set(cv2.CAP_PROP_CONVERT_RGB, 1)
        return False

    def read(self) -> bytes:
        if self._cap is None:
            raise CameraError("camera not open")
        ok, frame = self._cap.read()
        if not ok or frame is None:
            raise CameraError("frame read failed")
        if self.passthrough:
            data = frame.tobytes()
            end = data.rfind(_JPEG_EOI)  # drop V4L2 buffer padding
            return data[: end + 2] if end > 0 else data
        return encode_jpeg(frame, self._s.jpeg_quality)

    def close(self) -> None:
        if self._cap is not None:
            self._cap.release()
            self._cap = None


class SimulatedSource:
    """Generated frames (moving gradient + timestamp) paced to ``stream_fps``."""

    def __init__(self, settings: CameraSettings) -> None:
        self._s = settings
        self._next = 0.0
        self._w, self._h = min(settings.width, 1280), min(settings.height, 720)
        y, x = np.mgrid[0 : self._h, 0 : self._w]
        self._base = ((x + y) % 256).astype(np.uint8)

    def open(self) -> None:
        self._next = time.monotonic()

    def read(self) -> bytes:
        self._next += 1 / self._s.stream_fps
        delay = self._next - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        shift = int(time.time() * 40) % 256
        green = (self._base + shift).astype(np.uint8)
        img = cv2.merge([green // 3, green, green // 4])
        stamp = datetime.now().strftime("SIM %Y-%m-%d %H:%M:%S")
        cv2.putText(img, stamp, (24, 56), cv2.FONT_HERSHEY_SIMPLEX, 1.4, (255, 255, 255), 3)
        return encode_jpeg(img, self._s.jpeg_quality)

    def close(self) -> None:
        pass


def make_source(settings: CameraSettings) -> FrameSource:
    return SimulatedSource(settings) if settings.simulate else OpenCvSource(settings)
