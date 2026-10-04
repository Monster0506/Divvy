"""Request/response contract for item ownership prediction.

Independent of the receipts module: items arrive as plain descriptions so this
module never depends on how receipts are extracted.
"""

from datetime import date
from enum import StrEnum
from typing import Annotated, Self

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

Confidence = Annotated[
    float, Field(ge=0, le=1, description="Probability the owner set is correct, 0 to 1")
]


HOUSEHOLD_ID_PATTERN = r"^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$"
HouseholdId = Annotated[
    str, Field(pattern=HOUSEHOLD_ID_PATTERN, description="Letters, digits, - and _")
]
PersonId = Annotated[str, StringConstraints(min_length=1, max_length=128)]


class Person(BaseModel):
    id: str = Field(min_length=1)
    name: str = Field(min_length=1)


class PredictItem(BaseModel):
    item_id: str = Field(min_length=1)
    description: str = Field(min_length=1, description="Item name, raw or normalised")


class PredictRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    household_id: HouseholdId
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


MAX_UPSERT = 5000
MAX_DESCRIPTION = 512


class AssignmentSource(StrEnum):
    MANUAL = "manual"
    PREDICTED_ACCEPTED = "predicted_accepted"
    PREDICTED_CORRECTED = "predicted_corrected"


class ObservationIn(BaseModel):
    """One confirmed item and who owns it. Resending an observation_id updates it in place."""

    model_config = ConfigDict(extra="forbid")

    observation_id: str = Field(min_length=1, max_length=128)
    purchased_on: date = Field(description="Original purchase date; a correction keeps it")
    description: Annotated[
        str, StringConstraints(strip_whitespace=True, min_length=1, max_length=MAX_DESCRIPTION)
    ]
    member_ids: list[PersonId] = Field(
        min_length=1, description="Person ids in the household on that date"
    )
    owner_ids: list[PersonId] = Field(min_length=1, description="Person ids who own the item")
    source: AssignmentSource = AssignmentSource.MANUAL

    @model_validator(mode="after")
    def owners_are_members(self) -> Self:
        if len(set(self.member_ids)) != len(self.member_ids):
            raise ValueError("member ids must be unique")
        if len(set(self.owner_ids)) != len(self.owner_ids):
            raise ValueError("owner ids must be unique")
        if not set(self.owner_ids) <= set(self.member_ids):
            raise ValueError("every owner must be a member")
        return self


class UpsertObservationsRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    observations: list[ObservationIn] = Field(min_length=1, max_length=MAX_UPSERT)

    @model_validator(mode="after")
    def ids_are_unique(self) -> Self:
        ids = [o.observation_id for o in self.observations]
        if len(set(ids)) != len(ids):
            raise ValueError("observation ids must be unique within a request")
        return self


class UpsertObservationsResponse(BaseModel):
    household_id: str
    received: int
    history_size: int


class HistoryStatus(BaseModel):
    household_id: str
    history_size: int
    min_history: int = Field(description="Labelled items needed before predictions are made")
    ready: bool
    model_version: str | None = Field(
        default=None, description="Version of the model currently serving, if one is built"
    )
