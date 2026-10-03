from datetime import date

from fastapi import APIRouter

from divvy_ml.receipts.reconcile import with_reconciliation
from divvy_ml.receipts.schemas import Discount, ExtractedItem, ExtractedReceipt, ExtractRequest

router = APIRouter(prefix="/receipts", tags=["receipts"])

# Fixed response until a real extraction approach lands (#72).
SAMPLE_RECEIPT = ExtractedReceipt(
    store="Sample Grocer",
    purchased_on=date(2026, 1, 15),
    items=[
        ExtractedItem(name="WHOLE MILK 1GAL", quantity=1, unit_price_cents=429, total_cents=429),
        ExtractedItem(name="BANANAS", quantity=2.5, unit_price_cents=59, total_cents=148),
        ExtractedItem(name="SOURDOUGH LOAF", quantity=1, unit_price_cents=550, total_cents=550),
    ],
    discounts=[Discount(description="MEMBER SAVINGS", amount_cents=100)],
    subtotal_cents=1027,
    tax_cents=72,
    total_cents=1099,
    warnings=["stub response: extraction not yet implemented"],
)


@router.post("/extract", response_model=ExtractedReceipt)
def extract(request: ExtractRequest) -> ExtractedReceipt:
    return with_reconciliation(SAMPLE_RECEIPT)
