package com.monster0506.divvy.server.repository

import kotlin.uuid.Uuid

/** Thrown when an item would be newly assigned to people who have left the household. */
class ArchivedPersonException(val personIds: List<Uuid>) :
    IllegalArgumentException("Cannot assign items to archived people: $personIds")