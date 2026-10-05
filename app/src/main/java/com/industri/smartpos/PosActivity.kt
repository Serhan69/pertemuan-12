package com.industri.smartpos

import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.industri.smartpos.util.CurrencyUtils
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class PosActivity : AppCompatActivity() {

    private lateinit var etBarcode: TextInputEditText
    private lateinit var etNominal: TextInputEditText
    private lateinit var etQty: TextInputEditText
    private lateinit var rgSimulation: RadioGroup
    private lateinit var btnProcessPayment: MaterialButton
    private lateinit var pbPosLoading: ProgressBar
    private lateinit var cardPosResult: MaterialCardView
    private lateinit var tvPosStatus: TextView
    private lateinit var tvPosDetails: TextView
    private lateinit var coordinatorLayout: View

    private var consecutiveTimeoutCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_pos)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.coordinatorLayout)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        setupListeners()
        Timber.i("PosActivity created.")
    }

    private fun initViews() {
        etBarcode = findViewById(R.id.etBarcode)
        etNominal = findViewById(R.id.etNominal)
        etQty = findViewById(R.id.etQty)
        rgSimulation = findViewById(R.id.rgSimulation)
        btnProcessPayment = findViewById(R.id.btnProcessPayment)
        pbPosLoading = findViewById(R.id.pbPosLoading)
        cardPosResult = findViewById(R.id.cardPosResult)
        tvPosStatus = findViewById(R.id.tvPosStatus)
        tvPosDetails = findViewById(R.id.tvPosDetails)
        coordinatorLayout = findViewById(R.id.coordinatorLayout)
    }

    private fun setupListeners() {
        btnProcessPayment.setOnClickListener {
            // [DEBUGGER PRACTICE POINT]: Set breakpoint here to inspect input values before processing
            processPaymentFlow()
        }
    }

    private fun processPaymentFlow() {
        val barcode = etBarcode.text.toString().trim()
        val nominalStr = etNominal.text.toString().trim()
        val qtyStr = etQty.text.toString().trim()

        // 1. Validate inputs on Main Thread before launching coroutine
        val validationResult = runCatching {
            if (barcode.isEmpty()) throw IllegalArgumentException("Barcode tidak boleh kosong.")
            if (nominalStr.isEmpty()) throw IllegalArgumentException("Nominal tidak boleh kosong.")
            if (qtyStr.isEmpty()) throw IllegalArgumentException("Kuantitas tidak boleh kosong.")

            val nominal = nominalStr.toDoubleOrNull() ?: throw IllegalArgumentException("Nominal tidak valid.")
            val qty = qtyStr.toIntOrNull() ?: throw IllegalArgumentException("Kuantitas tidak valid.")

            if (nominal <= 0) throw IllegalArgumentException("Nominal harus lebih dari 0.")
            if (qty <= 0) throw IllegalArgumentException("Kuantitas minimal 1.")

            val total = nominal * qty
            Triple(barcode, qty, total)
        }

        validationResult.onFailure { e ->
            Timber.w(e, "Input validation failed: ${e.localizedMessage}")
            renderState(UiState.Error(e.localizedMessage ?: "Validasi input gagal", canRetry = false))
            return
        }

        val (validBarcode, validQty, totalAmount) = validationResult.getOrThrow()

        // Check Cashier Limit Exceeded (> Rp 10.000.000)
        if (totalAmount > 10_000_000.0) {
            Timber.w("Cashier limit exceeded for total amount: $totalAmount")
            showSupervisorAuthDialog(validBarcode, validQty, totalAmount)
            return
        }

        val selectedId = rgSimulation.checkedRadioButtonId
        val simulationType = when (selectedId) {
            R.id.rbBarcodeError -> SimulationType.BARCODE_ERROR
            R.id.rbNetworkTimeout -> SimulationType.NETWORK_TIMEOUT
            else -> SimulationType.SUCCESS
        }

        executeTransactionSimulation(validBarcode, validQty, totalAmount, simulationType)
    }

    private enum class SimulationType {
        SUCCESS, BARCODE_ERROR, NETWORK_TIMEOUT
    }

    private fun executeTransactionSimulation(
        barcode: String,
        qty: Int,
        totalAmount: Double,
        simulationType: SimulationType
    ) {
        // CoroutineExceptionHandler as a safety net for unhandled coroutine exceptions
        val errorHandler = CoroutineExceptionHandler { _, throwable ->
            Timber.e(throwable, "Unhandled coroutine exception caught in safety net.")
            runOnUiThread {
                renderState(UiState.Error("Terjadi error sistem yang tidak terduga: ${throwable.localizedMessage}"))
            }
        }

        lifecycleScope.launch(errorHandler) {
            // Render Loading state
            withContext(Dispatchers.Main) {
                renderState(UiState.Loading)
                Timber.d("Starting transaction simulation: barcode=$barcode, qty=$qty, total=$totalAmount, type=$simulationType")
            }

            // Simulate background network/database work on IO Dispatcher (NO View access here)
            val result = runCatching {
                delay(1200L) // Simulating network delay

                when (simulationType) {
                    SimulationType.BARCODE_ERROR -> {
                        throw ProductBarcodeNotFoundException(barcode)
                    }
                    SimulationType.NETWORK_TIMEOUT -> {
                        throw PaymentGatewayTimeoutException("QRIS Bank Settlement")
                    }
                    SimulationType.SUCCESS -> {
                        PosTransaction(
                            transactionId = "TRX-2026-${(1000..9999).random()}",
                            barcode = barcode,
                            productName = if (barcode == "8991201") "Susu UHT Full Cream 1 Liter" else "Produk Retail Umum",
                            quantity = qty,
                            totalAmount = totalAmount,
                            status = "SETTLED_SUCCESS"
                        )
                    }
                }
            }

            // Switch back to Main dispatcher to update UI
            withContext(Dispatchers.Main) {
                result.onSuccess { transaction ->
                    consecutiveTimeoutCount = 0 // Reset retry counter on success
                    renderState(UiState.Success(transaction))
                    Timber.i("Transaction successful: ${transaction.transactionId}, Total: ${transaction.totalAmount}")
                }.onFailure { throwable ->
                    handleTransactionError(throwable, barcode, qty, totalAmount, simulationType)
                }
            }
        }
    }

    private fun handleTransactionError(
        throwable: Throwable,
        barcode: String,
        qty: Int,
        totalAmount: Double,
        simulationType: SimulationType
    ) {
        when (throwable) {
            is ProductBarcodeNotFoundException -> {
                consecutiveTimeoutCount = 0
                Timber.w(throwable, "Product barcode not found: ${throwable.barcode}")
                renderState(UiState.Error(throwable.localizedMessage ?: "Barcode tidak ditemukan", canRetry = false))
            }
            is PaymentGatewayTimeoutException -> {
                consecutiveTimeoutCount++
                Timber.e(throwable, "Payment gateway timeout. Consecutive count: $consecutiveTimeoutCount")

                if (consecutiveTimeoutCount >= 3) {
                    Timber.w("Max retry threshold (3) reached for network timeout. Triggering cash payment fallback.")
                    showCashFallbackDialog()
                } else {
                    renderState(
                        UiState.Error(
                            message = "${throwable.localizedMessage} (Percobaan gagal ke-$consecutiveTimeoutCount/3)",
                            canRetry = true
                        )
                    )
                    showRetrySnackbar("Payment Gateway Timeout. Coba lagi?") {
                        executeTransactionSimulation(barcode, qty, totalAmount, simulationType)
                    }
                }
            }
            else -> {
                consecutiveTimeoutCount = 0
                Timber.e(throwable, "General transaction error: ${throwable.localizedMessage}")
                renderState(UiState.Error(throwable.localizedMessage ?: "Terjadi kesalahan transaksi"))
            }
        }
    }

    private fun renderState(state: UiState<Any?>) {
        when (state) {
            is UiState.Idle -> {
                pbPosLoading.visibility = View.GONE
                cardPosResult.visibility = View.GONE
                btnProcessPayment.isEnabled = true
            }
            is UiState.Loading -> {
                pbPosLoading.visibility = View.VISIBLE
                cardPosResult.visibility = View.GONE
                btnProcessPayment.isEnabled = false
            }
            is UiState.Success<*> -> {
                pbPosLoading.visibility = View.GONE
                cardPosResult.visibility = View.VISIBLE
                btnProcessPayment.isEnabled = true

                val trx = state.data as PosTransaction
                tvPosStatus.text = "Status: ${trx.status}"
                tvPosDetails.text = buildString {
                    append("ID Transaksi : ${trx.transactionId}\n")
                    append("Produk       : ${trx.productName}\n")
                    append("Barcode      : ${trx.barcode}\n")
                    append("Kuantitas    : ${trx.quantity}\n")
                    append("Total        : ${CurrencyUtils.formatRupiah(trx.totalAmount)}\n")
                    append("Waktu        : ${DateFormat.format("dd/MM/yyyy HH:mm:ss", trx.timestamp)}")
                }
            }
            is UiState.Error -> {
                pbPosLoading.visibility = View.GONE
                cardPosResult.visibility = View.GONE
                btnProcessPayment.isEnabled = true

                if (!state.canRetry) {
                    Snackbar.make(coordinatorLayout, state.message, Snackbar.LENGTH_LONG)
                        .setBackgroundTint(resources.getColor(android.R.color.holo_red_dark, theme))
                        .show()
                }
            }
        }
    }

    private fun showRetrySnackbar(message: String, onRetry: () -> Unit) {
        Snackbar.make(coordinatorLayout, message, Snackbar.LENGTH_INDEFINITE)
            .setAction("COBA LAGI") {
                onRetry()
            }
            .setBackgroundTint(resources.getColor(android.R.color.holo_orange_dark, theme))
            .setActionTextColor(resources.getColor(android.R.color.white, theme))
            .show()
    }

    private fun showCashFallbackDialog() {
        consecutiveTimeoutCount = 0
        renderState(UiState.Idle)

        AlertDialog.Builder(this)
            .setTitle("Fallback: Pembayaran Tunai Manual")
            .setMessage("Batas maksimal 3 kali retry koneksi payment gateway telah tercapai. Disarankan untuk mengalihkan metode pembayaran ke Pembayaran Tunai Manual di kasir.\n\n(Catatan: Ini adalah simulasi instruksi kasir, pastikan fisik uang tunai diterima).")
            .setPositiveButton("Alihkan ke Tunai") { dialog, _ ->
                dialog.dismiss()
                tvPosStatus.text = "Status: DIALIHKAN KE TUNAI (MANUAL)"
                tvPosDetails.text = "Transaksi dilanjutkan secara manual dengan uang tunai fisik sesuai prosedur toko."
                cardPosResult.visibility = View.VISIBLE
                Timber.i("Transaction switched to manual cash payment after max retry timeout.")
            }
            .setNegativeButton("Tutup") { dialog, _ ->
                dialog.dismiss()
                Timber.i("Cash fallback dialog dismissed by user.")
            }
            .show()
    }

    private fun showSupervisorAuthDialog(barcode: String, qty: Int, totalAmount: Double) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_supervisor_auth, null)
        val etSupervisorPin = dialogView.findViewById<TextInputEditText>(R.id.etSupervisorPin)

        AlertDialog.Builder(this)
            .setTitle("Otorisasi Supervisor Diperlukan")
            .setMessage("Total transaksi ${CurrencyUtils.formatRupiah(totalAmount)} melebihi batas kasir (Rp10.000.000).\nMasukkan PIN Supervisor (Demo: bebas, e.g. 1234):")
            .setView(dialogView)
            .setPositiveButton("Otorisasi") { dialog, _ ->
                val pin = etSupervisorPin.text.toString().trim()
                if (pin.isNotEmpty()) {
                    Timber.i("Supervisor authorization demo successful with PIN length: ${pin.length}")
                    dialog.dismiss()
                    val selectedId = rgSimulation.checkedRadioButtonId
                    val simulationType = when (selectedId) {
                        R.id.rbBarcodeError -> SimulationType.BARCODE_ERROR
                        R.id.rbNetworkTimeout -> SimulationType.NETWORK_TIMEOUT
                        else -> SimulationType.SUCCESS
                    }
                    executeTransactionSimulation(barcode, qty, totalAmount, simulationType)
                } else {
                    Toast.makeText(this, "PIN tidak boleh kosong!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Batal") { dialog, _ ->
                dialog.dismiss()
                Timber.w("Supervisor authorization cancelled by user.")
                renderState(UiState.Error("Transaksi dibatalkan karena memerlukan otorisasi supervisor.", canRetry = false))
            }
            .show()
    }
}
