"""Request/response contract for receipt extraction.

All money values are integers in cents. Strict mode rejects floats so a
fractional amount can never slip through as a silently truncated value.
"""

from datetime import date
from typing import Annotated, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator

Cents = Annotated[int, Field(strict=True)]
NonNegativeCents = Annotated[int, Field(strict=True, ge=0)]


class ExtractRequest(BaseModel):
    """Identifies the receipt image to extract. Exactly one source must be given."""

    model_config = ConfigDict(extra="forbid")

    image_id: str | None = Field(
        default=None, min_length=1, description="ID of an image already held in receipt storage"
    )
    image_base64: str | None = Field(
        default=None, min_length=1, description="Base64-encoded image bytes"
    )

    @model_validator(mode="after")
    def exactly_one_source(self) -> Self:
        if (self.image_id is None) == (self.image_base64 is None):
            raise ValueError("provide exactly one of image_id or image_base64")
        return self


class ExtractedItem(BaseModel):
    name: str = Field(min_length=1, description="Item name as printed on the receipt")
    quantity: float = Field(default=1, gt=0, description="Count or weight; may be fractional")
    unit_price_cents: NonNegativeCents | None = None
    total_cents: Cents = Field(description="Line total; negative for refunds or voids")


class Discount(BaseModel):
    description: str = Field(min_length=1)
    amount_cents: NonNegativeCents = Field(description="Discount size as a positive amount")


class ExtractedReceipt(BaseModel):
    store: str | None = None
    purchased_on: date | None = None
    items: list[ExtractedItem] = Field(default_factory=list)
    discounts: list[Discount] = Field(default_factory=list)
    subtotal_cents: Cents | None = Field(
        default=None, description="Pre-tax amount after discounts, as printed"
    )
    tax_cents: NonNegativeCents | None = None
    total_cents: Cents | None = None
    warnings: list[str] = Field(
        default_factory=list, description="Non-fatal issues found during extraction"
    )
