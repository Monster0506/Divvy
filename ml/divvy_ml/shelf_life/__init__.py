"""Typical shelf life of perishable groceries and a mapping from item names to it."""

from divvy_ml.shelf_life.mapping import category_for, estimate_for
from divvy_ml.shelf_life.table import CATEGORIES, SOURCES, ShelfLife

__all__ = ["CATEGORIES", "SOURCES", "ShelfLife", "category_for", "estimate_for"]
