package com.industri.smartpos

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.industri.smartpos.util.CurrencyUtils
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private lateinit var etCalcPrice: TextInputEditText
    private lateinit var etCalcQty: TextInputEditText
    private lateinit var btnCalculate: MaterialButton
    private lateinit var btnOpenPos: MaterialButton
    private lateinit var tvCalcResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.mainCoordinator)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        setupListeners()
        Timber.i("MainActivity created and initialized.")
    }

    private fun initViews() {
        etCalcPrice = findViewById(R.id.etCalcPrice)
        etCalcQty = findViewById(R.id.etCalcQty)
        btnCalculate = findViewById(R.id.btnCalculate)
        btnOpenPos = findViewById(R.id.btnOpenPos)
        tvCalcResult = findViewById(R.id.tvCalcResult)
    }

    private fun setupListeners() {
        btnOpenPos.setOnClickListener {
            Timber.d("Navigating to PosActivity")
            val intent = Intent(this, PosActivity::class.java)
            startActivity(intent)
        }

        btnCalculate.setOnClickListener {
            // [DEBUGGER PRACTICE POINT]: Set breakpoint here to inspect input parsing and runCatching calculation
            performCalculation()
        }
    }

    private fun performCalculation() {
        val priceStr = etCalcPrice.text.toString().trim()
        val qtyStr = etCalcQty.text.toString().trim()

        // Using runCatching for robust input parsing & calculation error handling
        val result = runCatching {
            if (priceStr.isEmpty() || qtyStr.isEmpty()) {
                throw IllegalArgumentException("Harga dan kuantitas tidak boleh kosong.")
            }
            val price = priceStr.toDoubleOrNull() ?: throw IllegalArgumentException("Format harga tidak valid.")
            val qty = qtyStr.toIntOrNull() ?: throw IllegalArgumentException("Format kuantitas tidak valid.")

            if (price <= 0) throw IllegalArgumentException("Harga harus lebih besar dari 0.")
            if (qty <= 0) throw IllegalArgumentException("Kuantitas minimal 1.")

            val total = price * qty
            if (total > 100_000_000) {
                throw ArithmeticException("Nilai kalkulasi terlalu besar.")
            }
            total
        }

        result.onSuccess { total ->
            val formatted = CurrencyUtils.formatRupiah(total)
            tvCalcResult.text = "Hasil Kalkulasi: $formatted"
            Timber.i("Calculation successful: price=$priceStr, qty=$qtyStr, total=$total")
        }.onFailure { throwable ->
            val errorMsg = throwable.localizedMessage ?: "Terjadi kesalahan kalkulasi"
            tvCalcResult.text = "Error: $errorMsg"
            Timber.w(throwable, "Calculation failed: $errorMsg")
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
        }
    }
}
