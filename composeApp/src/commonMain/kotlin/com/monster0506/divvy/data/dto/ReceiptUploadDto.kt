package com.monster0506.divvy.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class ReceiptUploadDto(val id: String, val status: String)
