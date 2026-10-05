package com.industri.smartpos

import java.io.IOException

class ProductBarcodeNotFoundException(val barcode: String) :
    Exception("Barcode produk '$barcode' tidak terdaftar di sistem kasir.")

class PaymentGatewayTimeoutException(val gatewayName: String) :
    IOException("Koneksi ke payment gateway '$gatewayName' mengalami timeout. Silakan coba kembali.")

class CashierLimitExceededException(val maxLimit: Double) :
    Exception("Total transaksi melebihi batas otorisasi kasir (Rp${maxLimit.toInt()}). Memerlukan Otorisasi Supervisor.")
