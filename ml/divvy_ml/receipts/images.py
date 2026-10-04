"""Gets receipt image bytes from a request and builds the data URL the vision model takes."""

import base64
import binascii
import os
import re
from dataclasses import dataclass
from pathlib import Path

MAX_IMAGE_BYTES = 10 * 1024 * 1024
_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


class ImageError(ValueError):
    """The image is missing, too large, or not a JPEG, PNG or WebP."""


@dataclass(frozen=True)
class ReceiptImage:
    data: bytes
    mime_type: str

    @property
    def data_url(self) -> str:
        return f"data:{self.mime_type};base64,{base64.b64encode(self.data).decode('ascii')}"


def sniff_mime_type(data: bytes) -> str | None:
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return "image/webp"
    return None


def from_bytes(data: bytes) -> ReceiptImage:
    if not data:
        raise ImageError("the image is empty")
    if len(data) > MAX_IMAGE_BYTES:
        raise ImageError(f"the image is larger than {MAX_IMAGE_BYTES // (1024 * 1024)} MB")
    mime_type = sniff_mime_type(data)
    if mime_type is None:
        raise ImageError("the image is not a JPEG, PNG or WebP")
    return ReceiptImage(data, mime_type)


def from_base64(encoded: str) -> ReceiptImage:
    """Accepts plain base64 or a data URL."""
    payload = (
        encoded.split(",", 1)[1] if encoded.startswith("data:") and "," in encoded else encoded
    )
    try:
        data = base64.b64decode(payload, validate=True)
    except (binascii.Error, ValueError):
        raise ImageError("image_base64 is not valid base64") from None
    return from_bytes(data)


def from_stored_id(image_id: str, directory: Path | None) -> ReceiptImage:
    """Reads an image the backend stored, named by its canonical lowercase UUID."""
    if directory is None:
        raise ImageError("image_id is not supported: DIVVY_IMAGE_DIR is not set")
    if not _UUID.match(image_id):
        raise ImageError("image_id is not a valid image id")
    path = directory.resolve() / image_id
    if not path.is_file():
        raise ImageError("no image with that id")
    return from_bytes(path.read_bytes())


def image_dir() -> Path | None:
    """Where uploaded receipt images are kept, shared with the backend, or None if not set."""
    raw = os.environ.get("DIVVY_IMAGE_DIR")
    return Path(raw) if raw else None
