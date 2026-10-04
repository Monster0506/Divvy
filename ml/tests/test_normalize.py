import pytest

from divvy_ml.normalize import ABBREVIATIONS, normalize_product_name

EXAMPLES = [
    ("ORG BNCH BNNAS", "organic bunch bananas"),
    ("BNCH GRN ONN", "bunch green onion"),
    ("ORG WHL MLK 1GAL", "organic whole milk"),
    ("2% MLK 64 OZ", "2% milk"),
    ("CHKN BRST BNLS SKNLS 2.31 LB", "chicken breast boneless skinless"),
    ("GRD BF 80/20 1LB", "ground beef 80 20"),
    ("BCN SMKD 12OZ", "bacon smoked"),
    ("YOG GRK VNLA 32 OZ", "yogurt grk vanilla"),
    ("CHDR CHS SHRD 8OZ", "cheddar cheese shrd"),
    ("MOZZ CHS BLOCK", "mozzarella cheese block"),
    ("PNUT BTR CRMY 16OZ", "peanut butter crmy"),
    ("WW BRD 20OZ", "whole wheat bread"),
    ("BGL PLN 6CT", "bagel pln"),
    ("TORTLA FLR 10CT", "tortilla flour"),
    ("SPAG PASTA 16 OZ", "spaghetti pasta"),
    ("TMTO SCE MARINARA 24OZ", "tomato sauce marinara"),
    ("MAYO LITE 30 OZ", "mayonnaise light"),
    ("KTCHP SQZ", "ketchup sqz"),
    ("ORG EGGS LG 12CT", "organic eggs large"),
    ("AVO HASS 4PK", "avocado hass"),
    ("LMN 3 LB BAG", "lemon bag"),
    ("STRWB 1LB", "strawberries"),
    ("BLUBRYS 6OZ", "blueberries"),
    ("MSHRMS BABY BELLA 8 OZ", "mushrooms baby bella"),
    ("PPRS BELL", "peppers bell"),
    ("OJ NO PULP 52 FL OZ", "orange juice no pulp"),
    ("SPRKL WTR LIME 12PK", "sparkling water lime"),
    ("CFE DCF GRND 12OZ", "coffee decaf grnd"),
    ("DTRGNT LIQ 100OZ", "detergent liq"),
    ("TTHPST FLUORIDE 4.7OZ", "toothpaste fluoride"),
    ("PAPR TWLS 6CT", "paper towels"),
    ("0004011 BANANAS", "bananas"),
    ("BANANAS @ 0.59/LB", "bananas"),
    ("  Almnd   Milk  UNSWT ", "almond milk unsweetened"),
    ("EVOO 16.9 FL OZ", "extra virgin olive oil"),
    ("GF PASTA PENNE 12OZ $3.49", "gluten free pasta penne"),
    ("FRZN FROZEN PEAS", "frozen peas"),
    ("123456", ""),
]


@pytest.mark.parametrize(("raw", "expected"), EXAMPLES)
def test_normalizes_receipt_descriptions(raw: str, expected: str) -> None:
    assert normalize_product_name(raw) == expected


def test_covers_at_least_thirty_examples() -> None:
    assert len(EXAMPLES) >= 30


def test_is_idempotent() -> None:
    for raw, _ in EXAMPLES:
        once = normalize_product_name(raw)
        assert normalize_product_name(once) == once


def test_dictionary_keys_are_lowercase_single_tokens() -> None:
    assert all(k == k.lower() and k.isalnum() for k in ABBREVIATIONS)
