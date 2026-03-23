
package com.syncspend.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Transaction(val title: String, val amount: Double)

class MainViewModel : ViewModel() {

    private val _transactions = MutableStateFlow(listOf<Transaction>())
    val transactions = _transactions.asStateFlow()

    fun addDummy() {
        _transactions.value = _transactions.value + Transaction("Sample", 100.0)
    }
}
