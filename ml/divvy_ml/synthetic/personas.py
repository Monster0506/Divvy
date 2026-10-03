"""Synthetic household: products, diets and each person's buying habits.

A habit is a product bought for fixed owners at a weekly rate; intro/until are timeline fractions.
"""

from dataclasses import dataclass
from enum import StrEnum
from typing import Any

PEOPLE = ("sumedh", "savar", "andrew", "tj")

ALL = "+".join(PEOPLE)
OWNER_SEPARATOR = "+"


class Tag(StrEnum):
    MEAT = "meat"
    RED_MEAT = "red_meat"
    FISH = "fish"
    EGG = "egg"


# Hard constraints: nobody co-owns food their diet excludes.
DIETS: dict[str, frozenset[Tag]] = {
    "sumedh": frozenset({Tag.MEAT, Tag.RED_MEAT, Tag.FISH, Tag.EGG}),  # lacto-vegetarian
    "savar": frozenset({Tag.RED_MEAT}),
    "andrew": frozenset(),
    "tj": frozenset(),
}

RED = (Tag.MEAT, Tag.RED_MEAT)
Variant = str | tuple[str, float]


@dataclass(frozen=True)
class Product:
    name: str
    category: str
    variants: tuple[Variant, ...]
    tags: frozenset[Tag] = frozenset()


@dataclass(frozen=True)
class Habit:
    product: str
    owners: str
    per_week: float
    intro: float = 0.0
    until: float = 1.0

    @property
    def label(self) -> str:
        return f"{self.owners}:{self.product}"

    @property
    def owner_set(self) -> frozenset[str]:
        return frozenset(self.owners.split(OWNER_SEPARATOR))


def _catalog(*groups: tuple[str, list[tuple[Any, ...]]]) -> dict[str, Product]:
    products = {}
    for category, entries in groups:
        for name, variants, *tags in entries:
            if name in products:
                raise ValueError(f"duplicate product {name}")
            products[name] = Product(
                name, category, tuple(variants), frozenset(tags[0] if tags else ())
            )

    return products


PRODUCTS = _catalog(
    (
        "soda",
        [
            (
                "coke_zero",
                [
                    "Coke Zero 12-pack",
                    "Coca-Cola Zero Sugar 12-pack",
                    "Coke Zero cans",
                    "Coca-Cola Zero Sugar bottles",
                    ("Coke Zero 24-pack", 0.8),
                ],
            ),
            (
                "sparkling_water",
                ["LaCroix sparkling water", "Sparkling water 12-pack", "Bubly lime"],
            ),
        ],
    ),
    (
        "energy_drinks",
        [
            (
                "monster",
                ["Monster Energy 4-pack", "Monster Zero Ultra", "Monster energy drink cans"],
            ),
            (
                "celsius",
                [
                    "Celsius sparkling orange",
                    "Celsius energy drink 12-pack",
                    ("Celsius peach vibe", 0.85),
                ],
            ),
            ("gatorade", ["Gatorade 8-pack", "Gatorade cool blue", "Gatorade Zero"]),
        ],
    ),
    (
        "dairy",
        [
            (
                "whole_milk",
                [
                    "Whole milk gallon",
                    "Vitamin D whole milk 1 gal",
                    "Whole milk half gallon",
                    "Organic whole milk",
                ],
            ),
            ("lactose_free_milk", ["Lactaid whole milk", "Lactose-free milk half gallon"]),
            (
                "greek_yogurt",
                [
                    "Greek yogurt",
                    "Chobani plain Greek yogurt",
                    "Fage total 2%",
                    ("Oikos triple zero yogurt", 0.82),
                ],
            ),
            ("dahi", ["Plain whole milk yogurt", "Dahi 2 lb tub"]),
            ("butter", ["Butter 4 sticks", "Salted butter", "Kerrygold butter"]),
            (
                "shredded_cheese",
                ["Shredded Mexican cheese blend", "Shredded cheddar", "Shredded mozzarella"],
            ),
            ("paneer", ["Paneer block", "Nanak paneer 14 oz", "Fresh paneer"]),
            ("cream_cheese", ["Cream cheese", "Philadelphia cream cheese"]),
            ("coffee_creamer", ["Coffee creamer", "Coffee mate french vanilla"]),
            ("orange_juice", ["Orange juice", "Tropicana no pulp 52 oz"]),
        ],
    ),
    (
        "eggs",
        [
            (
                "eggs",
                [
                    "Large eggs",
                    "Eggs dozen",
                    "18 count large brown eggs",
                    "Cage-free large eggs",
                    ("Eggland's Best eggs 12 ct", 0.8),
                ],
                [Tag.EGG],
            ),
            ("mayo", ["Mayonnaise", "Hellmann's real mayo"], [Tag.EGG]),
        ],
    ),
    (
        "bread",
        [
            (
                "white_bread",
                [
                    "Wonder bread classic white",
                    "White sandwich bread",
                    "Sandwich bread",
                    ("Great Value white bread", 0.7),
                ],
            ),
            (
                "wheat_bread",
                ["Whole wheat bread loaf", "Dave's Killer Bread 21 grain", "Sandwich bread"],
            ),
            ("tortillas", ["Flour tortillas", "Mission flour tortillas 10 ct"]),
            ("bagels", ["Plain bagels 6 ct", "Everything bagels", "Thomas' bagels"]),
            ("pita", ["Pita bread", "Whole wheat pita"]),
            ("naan", ["Naan bread", "Garlic naan 4 ct"]),
        ],
    ),
    (
        "spreads",
        [
            (
                "peanut_butter",
                ["Jif creamy peanut butter", "Peanut butter", "Skippy peanut butter 40 oz"],
            ),
            (
                "grape_jelly",
                ["Smucker's grape jelly", "Grape jelly", "Welch's concord grape jelly"],
            ),
            ("almond_butter", ["Almond butter", "Justin's almond butter"]),
            ("hummus", ["Hummus", "Sabra classic hummus"]),
            ("salsa", ["Salsa", "Tostitos chunky salsa", "Pace picante sauce"]),
        ],
    ),
    (
        "breakfast",
        [
            (
                "breakfast_sandwich",
                [
                    "Jimmy Dean breakfast sandwiches",
                    "Frozen sausage egg and cheese croissants",
                    "Frozen breakfast sandwiches",
                ],
                [*RED, Tag.EGG],
            ),
            (
                "breakfast_burrito",
                ["Frozen breakfast burritos", "El Monterey breakfast burritos"],
                [*RED, Tag.EGG],
            ),
            (
                "waffles",
                ["Eggo homestyle waffles", "Frozen waffles", ("Eggo blueberry waffles", 0.85)],
            ),
            ("cereal", ["Honey Nut Cheerios", "Cheerios family size", "Frosted Flakes"]),
            ("oats", ["Old fashioned oats", "Quaker rolled oats", "Steel cut oats"]),
        ],
    ),
    (
        "frozen",
        [
            (
                "cheese_pizza",
                [
                    "Frozen cheese pizza",
                    "DiGiorno four cheese pizza",
                    "Red Baron classic crust cheese pizza",
                ],
            ),
            (
                "pepperoni_pizza",
                ["DiGiorno pepperoni pizza", "Frozen pepperoni pizza", "Tombstone pepperoni pizza"],
                RED,
            ),
            (
                "veggie_burgers",
                ["Morningstar veggie burgers", "Frozen veggie patties", "Beyond Burger"],
            ),
            ("frozen_veg", ["Frozen mixed vegetables", "Frozen peas"]),
            (
                "frozen_berries",
                ["Frozen mixed berries", "Frozen strawberries", "Frozen blueberries 3 lb"],
            ),
            ("samosas", ["Frozen samosas", "Vegetable samosas 12 ct"]),
            ("ice_cream", ["Ben & Jerry's pint", "Haagen-Dazs vanilla", "Ice cream sandwiches"]),
        ],
    ),
    (
        "meat",
        [
            (
                "chicken_breast",
                [
                    "Boneless chicken breast",
                    "Fresh chicken breasts",
                    "Chicken breast fillets",
                    "Boneless skinless chicken breast family pack",
                    ("Organic chicken breast", 0.8),
                ],
                [Tag.MEAT],
            ),
            ("chicken_thighs", ["Chicken thighs", "Boneless skinless chicken thighs"], [Tag.MEAT]),
            ("ground_beef", ["Ground beef 80/20", "Lean ground beef 93%", "Ground chuck"], RED),
            ("ground_turkey", ["Ground turkey", "Lean ground turkey 93%"], [Tag.MEAT]),
            ("bacon", ["Bacon", "Thick cut bacon", "Oscar Mayer bacon"], RED),
            ("steak", ["Ribeye steak", "Sirloin steak 2-pack"], RED),
            ("deli_turkey", ["Sliced turkey breast", "Deli turkey"], [Tag.MEAT]),
            ("rotisserie_chicken", ["Rotisserie chicken", "Whole roasted chicken"], [Tag.MEAT]),
        ],
    ),
    (
        "seafood",
        [
            ("salmon", ["Salmon fillets", "Atlantic salmon", "Frozen salmon portions"], [Tag.FISH]),
            ("shrimp", ["Frozen shrimp", "Raw shrimp 1 lb"], [Tag.FISH]),
        ],
    ),
    (
        "protein",
        [
            ("whey", ["Whey protein powder chocolate", "Optimum Nutrition gold standard whey"]),
            ("protein_bars", ["Quest protein bars", "Protein bars variety pack"]),
            ("tofu", ["Extra firm tofu", "Firm tofu block"]),
        ],
    ),
    (
        "indian",
        [
            (
                "garam_masala",
                [
                    "Garam masala",
                    "MDH garam masala 100g",
                    "Everest garam masala",
                    ("Shan garam masala", 0.85),
                ],
            ),
            ("turmeric", ["Ground turmeric", "Turmeric powder", "Haldi powder"]),
            ("cumin", ["Cumin seeds", "Whole jeera", "Ground cumin"]),
            (
                "toor_dal",
                ["Toor dal", "Split pigeon peas", "Arhar dal 4 lb", ("Toovar dal oily", 0.9)],
            ),
            ("chana_dal", ["Chana dal", "Split chickpeas"]),
            ("atta", ["Whole wheat atta 20 lb", "Aashirvaad atta flour"]),
            ("ghee", ["Ghee jar", "Clarified butter ghee"]),
        ],
    ),
    (
        "produce",
        [
            (
                "spinach",
                ["Baby spinach", "Spinach bunch", "Organic spinach 16 oz", ("Palak leaves", 0.88)],
            ),
            ("cilantro", ["Cilantro bunch", "Fresh coriander leaves"]),
            ("tomatoes", ["Roma tomatoes", "Tomatoes on the vine", "Tomatoes"]),
            ("green_chilies", ["Green chilies", "Serrano peppers", "Thai green chili"]),
            ("bell_peppers", ["Bell peppers 3-pack", "Red bell pepper"]),
            ("onions", ["Yellow onions 3 lb bag", "Onions", "Red onions"]),
            ("garlic", ["Garlic bulbs", "Peeled garlic cloves", "Minced garlic jar"]),
            ("bananas", ["Bananas", "Banana bunch", "Organic bananas"]),
            ("apples", ["Honeycrisp apples", "Gala apples 3 lb bag"]),
            ("avocados", ["Avocados", "Hass avocados 4 ct"]),
            ("lemons", ["Lemons", "Limes"]),
        ],
    ),
    (
        "snacks",
        [
            ("takis", ["Takis Fuego", "Takis rolled tortilla chips", ("Takis Blue Heat", 0.8)]),
            ("hot_cheetos", ["Flamin' Hot Cheetos", "Hot Cheetos party size"]),
            ("beef_jerky", ["Beef jerky", "Jack Link's beef jerky"], RED),
            ("trail_mix", ["Trail mix", "Kirkland trail mix"]),
            ("popcorn", ["Microwave popcorn", "Skinny Pop"]),
            ("tortilla_chips", ["Tortilla chips", "Tostitos restaurant style chips"]),
        ],
    ),
    (
        "coffee_tea",
        [
            (
                "ground_coffee",
                ["Ground coffee", "Starbucks Pike Place ground coffee", "Folgers classic roast"],
            ),
            ("k_cups", ["K-cup pods", "Keurig coffee pods 24 ct"]),
            ("chai", ["Chai tea bags", "Wagh Bakri tea", "Masala chai tea"]),
            ("coffee_filters", ["Coffee filters", "Melitta #4 cone filters"]),
        ],
    ),
    (
        "alcohol",
        [
            ("beer", ["Modelo 12-pack", "Coors Light 12-pack", "White Claw variety pack"]),
        ],
    ),
    (
        "pantry",
        [
            ("basmati", ["Basmati rice", "Royal basmati rice 20 lb", "Long grain basmati rice"]),
            ("pasta", ["Spaghetti", "Penne pasta", "Barilla pasta"]),
            ("pasta_sauce", ["Marinara sauce", "Rao's marinara"]),
            ("cooking_oil", ["Vegetable oil", "Canola oil 48 oz", "Extra virgin olive oil"]),
            ("salt", ["Iodized salt", "Kosher salt"]),
            ("vegetable_broth", ["Vegetable broth", "Swanson vegetable broth"]),
            ("chicken_broth", ["Chicken broth", "Swanson chicken broth"], [Tag.MEAT]),
            ("canned_beans", ["Black beans can", "Chickpeas can", "Kidney beans"]),
            ("hot_sauce", ["Hot sauce", "Cholula hot sauce", "Sriracha"]),
            ("sushi_rice", ["Sushi rice", "Nishiki medium grain rice"]),
            ("nori", ["Nori sheets", "Roasted seaweed sheets"]),
        ],
    ),
    (
        "household",
        [
            (
                "paper_towels",
                ["Paper towels", "Bounty paper towels 6 rolls", ("Viva paper towels", 0.8)],
            ),
            ("toilet_paper", ["Toilet paper 12 rolls", "Charmin ultra soft toilet paper"]),
            ("dish_soap", ["Dish soap", "Dawn dish soap", ("Palmolive dish soap", 0.86)]),
            ("dishwasher_pods", ["Dishwasher pods", "Cascade platinum pods"]),
            ("trash_bags", ["Trash bags 13 gal", "Hefty kitchen trash bags"]),
            ("laundry", ["Laundry detergent pods", "Tide pods"]),
            ("cleaner", ["All-purpose cleaner", "Clorox disinfecting wipes", "Lysol spray"]),
            ("sponges", ["Sponges 6-pack", "Scotch-Brite sponges"]),
            ("hand_soap", ["Hand soap", "Softsoap refill"]),
        ],
    ),
    (
        "personal_care",
        [
            (
                "mens_shampoo",
                ["Men's shampoo", "Head & Shoulders shampoo", ("Pantene shampoo", 0.85)],
            ),
            ("body_wash", ["Dove Men+Care body wash", "Men's body wash"]),
            ("face_wash", ["CeraVe foaming facial cleanser", "Face wash"]),
            ("toothpaste", ["Toothpaste", "Colgate total toothpaste", "Crest toothpaste"]),
            ("deodorant", ["Deodorant", "Old Spice deodorant", "Degree antiperspirant"]),
            ("razors", ["Disposable razors", "Gillette razor refills"]),
        ],
    ),
    (
        "medicine",
        [
            ("ibuprofen", ["Ibuprofen 200 mg", "Advil"]),
            ("allergy", ["Allergy relief tablets", "Zyrtec"]),
            ("cough_drops", ["Cough drops", "Halls cough drops"]),
        ],
    ),
    (
        "party",
        [("party_cups", ["Solo cups 50 ct", "Red party cups"]), ("balloons", ["Party balloons"])],
    ),
    (
        "gardening",
        [("potting_soil", ["Potting soil"]), ("herb_plant", ["Basil plant", "Mint plant"])],
    ),
    (
        "electronics",
        [
            ("charging_cable", ["USB-C charging cable"]),
            ("screen_protector", ["Phone screen protector"]),
        ],
    ),
    ("stationery", [("printer_paper", ["Printer paper"]), ("sticky_notes", ["Sticky notes"])]),
    (
        "gaming",
        [
            ("gift_card", ["PlayStation gift card", "Xbox gift card"]),
            ("mouse_pad", ["Gaming mouse pad"]),
        ],
    ),
    (
        "baking",
        [
            ("baking_soda", ["Baking soda"]),
            ("vanilla", ["Vanilla extract"]),
            ("chocolate_chips", ["Chocolate chips"]),
        ],
    ),
    (
        "pet",
        [
            ("cat_food", ["Cat food wet 12 ct", "Friskies cat food", "Dry cat food"]),
            ("cat_litter", ["Cat litter", "Tidy Cats clumping litter"]),
        ],
    ),
    (
        "kombucha",
        [
            (
                "kombucha",
                ["GT's synergy kombucha", "Health-Ade kombucha ginger lemon", "Kombucha 16 oz"],
            )
        ],
    ),
    (
        "hardware",
        [
            ("batteries", ["AA batteries 8-pack", "Duracell AAA batteries"]),
            ("light_bulbs", ["LED light bulbs 4-pack", "60W equivalent soft white bulbs"]),
        ],
    ),
    (
        "car",
        [("washer_fluid", ["Windshield washer fluid"]), ("air_freshener", ["Car air freshener"])],
    ),
    (
        "outdoor",
        [
            ("sunscreen", ["Sunscreen SPF 50", "Banana Boat sport sunscreen"]),
            ("bug_spray", ["Bug spray", "OFF deep woods insect repellent"]),
        ],
    ),
    ("holiday", [("christmas_lights", ["Christmas lights"]), ("gift_wrap", ["Gift wrap"])]),
)


def _each(product: str, per_week: float) -> list[Habit]:
    """Everyone buys their own, e.g. toothpaste."""
    return [Habit(product, name, per_week) for name in PEOPLE]


H = Habit

HABITS: tuple[Habit, ...] = (
    # Andrew: PB&J every day (two loaves a week), lifts, frozen pizza
    H("white_bread", "andrew", 2.0),
    H("peanut_butter", "andrew", 0.5),
    H("grape_jelly", "andrew", 0.4),
    H("cheese_pizza", "andrew", 0.6),
    H("pepperoni_pizza", "andrew", 0.5),
    H("monster", "andrew", 1.2, until=0.6),
    H("celsius", "andrew", 1.2, intro=0.6),  # switches energy drinks
    H("gatorade", "andrew", 0.5),
    H("whey", "andrew", 0.2),
    H("protein_bars", "andrew", 0.3),
    H("chicken_breast", "andrew", 1.0),  # meal prep
    H("bananas", "andrew", 0.8),
    H("trail_mix", "andrew", 0.2),
    H("gift_card", "andrew", 0.03),
    H("mouse_pad", "andrew", 0.02),
    # TJ: Coke Zero habit, cereal and milk, frozen breakfast, bacon
    H("coke_zero", "tj", 2.0),
    H("whole_milk", "tj", 1.0, until=0.7),
    H("lactose_free_milk", "tj", 1.0, intro=0.7),  # goes lactose-free
    H("cereal", "tj", 0.6),
    H("breakfast_sandwich", "tj", 0.8),
    H("waffles", "tj", 0.4),
    H("breakfast_burrito", "tj", 0.4, intro=0.6),
    H("bacon", "tj", 0.3),
    H("beef_jerky", "tj", 0.2),
    H("k_cups", "tj", 0.4),
    H("deli_turkey", "tj", 0.4),
    H("printer_paper", "tj", 0.03),
    H("sticky_notes", "tj", 0.03),
    # Sumedh: lacto-vegetarian, no eggs, cooks Indian food, chai with milk
    H("chai", "sumedh", 0.3),
    H("whole_milk", "sumedh", 0.5),
    H("garam_masala", "sumedh", 0.12),
    H("turmeric", "sumedh", 0.1),
    H("cumin", "sumedh", 0.12),
    H("toor_dal", "sumedh", 0.3),
    H("chana_dal", "sumedh", 0.15),
    H("atta", "sumedh", 0.1),
    H("ghee", "sumedh", 0.1),
    H("paneer", "sumedh", 0.5),
    H("dahi", "sumedh", 0.6),
    H("tofu", "sumedh", 0.4),
    H("veggie_burgers", "sumedh", 0.3),
    H("frozen_veg", "sumedh", 0.3),
    # Savar: no red meat, spicy snacks, fish, skincare; adopts a cat late
    H("takis", "savar", 0.6),
    H("hot_cheetos", "savar", 0.5),
    H("sparkling_water", "savar", 0.8),
    H("greek_yogurt", "savar", 0.6),
    H("salmon", "savar", 0.4),
    H("ground_turkey", "savar", 0.3),
    H("wheat_bread", "savar", 0.4),
    H("apples", "savar", 0.4),
    H("avocados", "savar", 0.4),
    H("mens_shampoo", "savar", 0.1),
    H("body_wash", "savar", 0.12),
    H("face_wash", "savar", 0.08),
    H("cat_food", "savar", 1.0, intro=0.8),
    H("cat_litter", "savar", 0.3, intro=0.8),
    H("kombucha", "savar", 1.0, intro=0.82),
    # Shared by two or three
    H("eggs", "savar+andrew+tj", 1.2),
    H("ground_beef", "andrew+tj", 0.5),  # taco night
    H("tortillas", "andrew+tj", 0.3),
    H("shredded_cheese", "andrew+tj", 0.3),
    H("steak", "andrew+tj", 0.1),
    H("beer", "andrew+tj", 0.3),
    H("mayo", "andrew+tj", 0.1),
    H("chicken_thighs", "savar+andrew+tj", 0.3),  # group dinners
    H("chicken_breast", "savar+tj", 0.2),
    H("chicken_broth", "savar+andrew+tj", 0.1),
    H("shrimp", "savar+tj", 0.15),
    H("ice_cream", "savar+andrew", 0.25),
    H("tortilla_chips", "savar+andrew", 0.3),  # watch soccer together
    H("salsa", "savar+andrew", 0.25),
    H("hot_sauce", "savar+andrew", 0.1),
    H("sushi_rice", "savar+tj", 0.1),  # sushi nights
    H("nori", "savar+tj", 0.1),
    H("oats", "sumedh+andrew", 0.3, intro=0.3),  # smoothie and oatmeal pact
    H("frozen_berries", "sumedh+andrew", 0.5, intro=0.3),
    H("almond_butter", "sumedh+andrew", 0.15, intro=0.3),
    H("ground_coffee", "sumedh+tj", 0.3),
    H("coffee_creamer", "sumedh+tj", 0.3),
    H("coffee_filters", "sumedh+tj", 0.05),
    H("bagels", "sumedh+andrew+tj", 0.4),  # weekend brunch
    H("cream_cheese", "sumedh+andrew+tj", 0.25),
    H("orange_juice", "sumedh+andrew+tj", 0.4),
    H("naan", "sumedh+savar+andrew", 0.3),  # curry nights; TJ skips spicy food
    H("samosas", "sumedh+savar+andrew", 0.2),
    H("hummus", "sumedh+savar+tj", 0.35),  # mezze nights
    H("pita", "sumedh+savar+tj", 0.25),
    H("rotisserie_chicken", "savar+andrew+tj", 0.2),
    H("spinach", "sumedh+savar", 0.4),
    H("cilantro", "sumedh+savar", 0.4),
    H("tomatoes", "sumedh+savar", 0.5),
    H("green_chilies", "sumedh+savar", 0.3),
    H("bell_peppers", "sumedh+savar", 0.3),
    H("lemons", "sumedh+savar", 0.2),
    H("potting_soil", "sumedh+savar", 0.03),
    H("herb_plant", "sumedh+savar", 0.03),
    H("baking_soda", "sumedh+savar+tj", 0.03),
    H("vanilla", "sumedh+savar+tj", 0.03),
    H("chocolate_chips", "sumedh+savar+tj", 0.05),
    # Everyone
    H("basmati", ALL, 0.1),
    H("pasta", ALL, 0.25),
    H("pasta_sauce", ALL, 0.2),
    H("cooking_oil", ALL, 0.08),
    H("salt", ALL, 0.04),
    H("onions", ALL, 0.5),
    H("garlic", ALL, 0.3),
    H("vegetable_broth", ALL, 0.1),
    H("canned_beans", ALL, 0.2),
    H("butter", ALL, 0.25),
    H("bananas", ALL, 0.3),
    H("popcorn", ALL, 0.15),  # movie nights
    H("paper_towels", ALL, 0.4),
    H("toilet_paper", ALL, 0.4),
    H("dish_soap", ALL, 0.25),
    H("dishwasher_pods", ALL, 0.2),
    H("trash_bags", ALL, 0.25),
    H("laundry", ALL, 0.15),
    H("cleaner", ALL, 0.15),
    H("sponges", ALL, 0.1),
    H("hand_soap", ALL, 0.15),
    H("party_cups", ALL, 0.04),
    H("balloons", ALL, 0.02),
    H("batteries", ALL, 0.3, intro=0.84),
    H("light_bulbs", ALL, 0.3, intro=0.84),
    H("christmas_lights", ALL, 0.3, intro=0.9),
    H("gift_wrap", ALL, 0.3, intro=0.9),
    H("washer_fluid", "tj", 0.15, intro=0.86),
    H("air_freshener", "tj", 0.15, intro=0.86),
    H("sunscreen", "andrew+tj", 0.2, intro=0.88),
    H("bug_spray", "andrew+tj", 0.2, intro=0.88),
    # Everyone buys their own
    *_each("toothpaste", 0.08),
    *_each("deodorant", 0.1),
    *_each("razors", 0.06),
    *_each("ibuprofen", 0.02),
    *_each("cough_drops", 0.02),
    *_each("charging_cable", 0.02),
    *_each("screen_protector", 0.01),
    H("allergy", "savar", 0.05),
    H("allergy", "tj", 0.03),
)


def allowed_owners(product: Product) -> tuple[str, ...]:
    return tuple(name for name in PEOPLE if not DIETS[name] & product.tags)


def check(habits: tuple[Habit, ...], products: dict[str, Product]) -> None:
    """Reject habits naming unknown products/people or breaking a diet."""
    for habit in habits:
        if habit.product not in products:
            raise ValueError(f"{habit.label}: unknown product")

        unknown = habit.owner_set - set(PEOPLE)
        if unknown:
            raise ValueError(f"{habit.label}: unknown people {sorted(unknown)}")

        forbidden = habit.owner_set - set(allowed_owners(products[habit.product]))
        if forbidden:
            raise ValueError(f"{habit.label}: diet forbids {sorted(forbidden)}")


check(HABITS, PRODUCTS)
