package com.industri.smartpos

data class PosTransaction(
    val transactionId: String,
    val barcode: String,
    val productName: String,
    val quantity: Int,
    val totalAmount: Double,
    val status: String,
    val timestamp: Long = System.currentTimeMillis()
)
