package io.github.mobdev

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var displayView: TextView

    private var currentInput = "0"
    private var leftOperand: Double? = null
    private var pendingOperator: Char? = null
    private var resetInputOnNextDigit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        displayView = findViewById(R.id.display)

        if (savedInstanceState != null) {
            currentInput = savedInstanceState.getString(KEY_CURRENT_INPUT, "0")
            if (savedInstanceState.getBoolean(KEY_HAS_LEFT_OPERAND, false)) {
                leftOperand = savedInstanceState.getDouble(KEY_LEFT_OPERAND)
            }
            savedInstanceState.getString(KEY_PENDING_OPERATOR)?.let {
                pendingOperator = it.firstOrNull()
            }
            resetInputOnNextDigit = savedInstanceState.getBoolean(KEY_RESET_ON_NEXT_DIGIT, false)
        }

        bindButtons()
        updateDisplay(currentInput)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_CURRENT_INPUT, currentInput)
        outState.putBoolean(KEY_HAS_LEFT_OPERAND, leftOperand != null)
        leftOperand?.let { outState.putDouble(KEY_LEFT_OPERAND, it) }
        pendingOperator?.let { outState.putString(KEY_PENDING_OPERATOR, it.toString()) }
        outState.putBoolean(KEY_RESET_ON_NEXT_DIGIT, resetInputOnNextDigit)
        super.onSaveInstanceState(outState)
    }

    private fun bindButtons() {
        val digitMap = mapOf(
            R.id.btn0 to "0",
            R.id.btn1 to "1",
            R.id.btn2 to "2",
            R.id.btn3 to "3",
            R.id.btn4 to "4",
            R.id.btn5 to "5",
            R.id.btn6 to "6",
            R.id.btn7 to "7",
            R.id.btn8 to "8",
            R.id.btn9 to "9"
        )

        digitMap.forEach { (id, digit) ->
            findViewById<Button>(id).setOnClickListener { appendDigit(digit) }
        }

        findViewById<Button>(R.id.btnDot).setOnClickListener { appendDot() }
        findViewById<Button>(R.id.btnAdd).setOnClickListener { setOperator('+') }
        findViewById<Button>(R.id.btnSub).setOnClickListener { setOperator('-') }
        findViewById<Button>(R.id.btnMul).setOnClickListener { setOperator('*') }
        findViewById<Button>(R.id.btnDiv).setOnClickListener { setOperator('/') }
        findViewById<Button>(R.id.btnEq).setOnClickListener { evaluate() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { clearAll() }
    }

    private fun appendDigit(digit: String) {
        if (resetInputOnNextDigit) {
            currentInput = "0"
            resetInputOnNextDigit = false
        }

        currentInput = when (currentInput) {
            "0" -> digit
            "-0" -> "-$digit"
            else -> currentInput + digit
        }
        updateDisplay(currentInput)
    }

    private fun appendDot() {
        if (resetInputOnNextDigit) {
            currentInput = "0"
            resetInputOnNextDigit = false
        }

        if (!currentInput.contains('.')) {
            currentInput += "."
            updateDisplay(currentInput)
        }
    }

    private fun setOperator(operator: Char) {
        val currentValue = currentInput.toDoubleOrNull()
        if (currentValue == null) {
            clearAll()
            return
        }

        if (leftOperand == null) {
            leftOperand = currentValue
        } else if (pendingOperator != null && !resetInputOnNextDigit) {
            val result = calculate(leftOperand!!, currentValue, pendingOperator!!)
            if (result == null) {
                showErrorAndReset()
                return
            }
            leftOperand = result
            currentInput = formatDouble(result)
            updateDisplay(currentInput)
        }

        pendingOperator = operator
        resetInputOnNextDigit = true
    }

    private fun evaluate() {
        val left = leftOperand
        val operator = pendingOperator
        val right = currentInput.toDoubleOrNull()

        if (left == null || operator == null || right == null) {
            updateDisplay(currentInput)
            return
        }

        val result = calculate(left, right, operator)
        if (result == null) {
            showErrorAndReset()
            return
        }

        currentInput = formatDouble(result)
        leftOperand = null
        pendingOperator = null
        resetInputOnNextDigit = true
        updateDisplay(currentInput)
    }

    private fun clearAll() {
        currentInput = "0"
        leftOperand = null
        pendingOperator = null
        resetInputOnNextDigit = false
        updateDisplay(currentInput)
    }

    private fun calculate(left: Double, right: Double, operator: Char): Double? {
        val value = when (operator) {
            '+' -> left + right
            '-' -> left - right
            '*' -> left * right
            '/' -> left / right
            else -> return null
        }
        return value.takeIf { it.isFinite() }
    }

    private fun formatDouble(value: Double): String {
        if (value % 1.0 == 0.0) {
            return value.toLong().toString()
        }
        return value.toString()
    }

    private fun showErrorAndReset() {
        updateDisplay(getString(R.string.error_value))
        currentInput = "0"
        leftOperand = null
        pendingOperator = null
        resetInputOnNextDigit = true
    }

    private fun updateDisplay(text: String) {
        displayView.text = text
    }

    companion object {
        private const val KEY_CURRENT_INPUT = "key_current_input"
        private const val KEY_LEFT_OPERAND = "key_left_operand"
        private const val KEY_HAS_LEFT_OPERAND = "key_has_left_operand"
        private const val KEY_PENDING_OPERATOR = "key_pending_operator"
        private const val KEY_RESET_ON_NEXT_DIGIT = "key_reset_on_next_digit"
    }
}