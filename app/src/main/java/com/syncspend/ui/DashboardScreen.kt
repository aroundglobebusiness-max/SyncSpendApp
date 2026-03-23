
package com.syncspend.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syncspend.viewmodel.MainViewModel

@Composable
fun DashboardScreen(vm: MainViewModel) {

    val list by vm.transactions.collectAsState()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.addDummy() }) {
                Text("+")
            }
        }
    ) { padding ->

        Column(Modifier.padding(padding)) {

            Text("SyncSpend", style = MaterialTheme.typography.titleLarge)

            LazyColumn {
                items(list) {
                    Text(it.title + " ₹" + it.amount)
                }
            }
        }
    }
}
