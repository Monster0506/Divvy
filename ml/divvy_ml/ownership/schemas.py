"""Request/response contract for item ownership prediction.

Independent of the receipts module: items arrive as plain descriptions so this
module never depends on how receipts are extracted.
"""

from enum import StrEnum
from typing import Annotated, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator

Confidence = Annotated[
    float, Field(ge=0, le=1, description="Probability the owner set is correct, 0 to 1")
]


class Person(BaseModel):
    id: str = Field(min_length=1)
    name: str = Field(min_length=1)


class PredictItem(BaseModel):
    item_id: str = Field(min_length=1)
    description: str = Field(min_length=1, description="Item name, raw or normalised")


class PredictRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    household_id: str = Field(min_length=1)
    people: list[Person] = Field(min_length=1)
    items: list[PredictItem]

    @model_validator(mode="after")
    def ids_are_unique(self) -> Self:
        if len({p.id for p in self.people}) != len(self.people):
            raise ValueError("person ids must be unique")
        if len({i.item_id for i in self.items}) != len(self.items):
            raise ValueError("item ids must be unique")
        return self


class PredictionStatus(StrEnum):
    PREDICTED = "predicted"
    UNKNOWN = "unknown"


class Alternative(BaseModel):
    owners: list[str] = Field(min_length=1, description="Person ids")
    confidence: Confidence


class ItemPrediction(BaseModel):
    item_id: str
    status: PredictionStatus
    owners: list[str] = Field(
        default_factory=list, description="Predicted person ids; empty when unknown"
    )
    confidence: Confidence
    alternatives: list[Alternative] = Field(default_factory=list)

    @model_validator(mode="after")
    def owners_match_status(self) -> Self:
        if self.status is PredictionStatus.UNKNOWN and self.owners:
            raise ValueError("unknown predictions must not name owners")
        if self.status is PredictionStatus.PREDICTED and not self.owners:
            raise ValueError("predicted items must name at least one owner")
        return self


class PredictResponse(BaseModel):
    model_version: str
    predictions: list[ItemPrediction]
