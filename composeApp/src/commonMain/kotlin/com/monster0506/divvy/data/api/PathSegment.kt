package com.monster0506.divvy.data.api

import io.ktor.http.encodeURLPathPart

/** A household's name doubles as its id, so it must be escaped before it goes into a URL path. */
internal fun String.seg(): String = encodeURLPathPart()
