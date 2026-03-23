// SyncSpendLikeApp.kt
// Single-file sample: Jetpack Compose UI + Room schema for Android 16+ (Compose + Material3).
package com.example.syncspendlite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.*

// Design tokens
private val ColorBackground = Color(0xFFFFFFFF)
private val ColorSecondaryBg = Color(0xFFF2F2F7)
private val ColorPrimaryAction = Color(0xFF007AFF)
private val ColorTextPrimary = Color(0xFF000000)
private val ColorTextSecondary = Color(0xFF8E8E93)
private val ColorPositive = Color(0xFF0A8A3A)
private val SheetCornerRadius = 16.dp
private val InsetCardRadius = 18.dp
private val DragPillColor = Color(0xFFE5E5EA)

// Room Entities and DAOs
@Entity(tableName = "entities")
data class EntityPerson(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val avatarUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String? = null,
    val last4: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String? = null
)

@Entity(tableName = "payment_sources")
data class PaymentSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long?,
    val gateway: String?,
    val displayName: String
)

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amountCents: Long,
    val currency: String = "INR",
    val title: String?,
    val dateEpoch: Long,
    val categoryId: Long?,
    val entityId: Long?,
    val paymentSourceId: Long?,
    val type: String,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface EntityDao {
    @Insert fun insert(entity: EntityPerson): Long
    @Query("SELECT * FROM entities ORDER BY name ASC") fun allEntities(): Flow<List<EntityPerson>>
}

@Dao
interface TransactionDao {
    @Insert fun insert(tx: TransactionEntity): Long
    @Query("SELECT * FROM transactions WHERE entityId = :entityId ORDER BY dateEpoch DESC")
    fun transactionsForEntity(entityId: Long): Flow<List<TransactionEntity>>

    @Query("""
      SELECT dateEpoch, id, amountCents, title, type, entityId, paymentSourceId, categoryId, notes
      FROM transactions
      ORDER BY dateEpoch DESC
    """)
    fun allTransactions(): Flow<List<TransactionEntity>>

    @Query("""
      SELECT
        COALESCE(SUM(CASE WHEN type = 'lend' THEN amountCents ELSE 0 END),0) -
        COALESCE(SUM(CASE WHEN type = 'repayment' THEN amountCents ELSE 0 END),0)
      FROM transactions
      WHERE entityId = :entityId
    """)
    fun outstandingForEntity(entityId: Long): Flow<Long>
}

@Database(entities = [EntityPerson::class, Account::class, Category::class, PaymentSource::class, TransactionEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun entityDao(): EntityDao
    abstract fun transactionDao(): TransactionDao
}

// Sample repository
class SampleRepository(private val db: AppDatabase) {
    val entities = db.entityDao().allEntities()
    val allTransactions = db.transactionDao().allTransactions()

    suspend fun seedIfEmpty() {
        val eDao = db.entityDao()
        val tDao = db.transactionDao()
        val a1 = eDao.insert(EntityPerson(name = "Anandhu"))
        val a2 = eDao.insert(EntityPerson(name = "Balan"))
        val now = System.currentTimeMillis()
        tDao.insert(TransactionEntity(amountCents = 100000, title = "Lent to Anandhu", dateEpoch = now - 86400000L * 2, entityId = a1, type = "lend"))
        tDao.insert(TransactionEntity(amountCents = 150000, title = "Lent to Anandhu", dateEpoch = now - 86400000L * 5, entityId = a1, type = "lend"))
        tDao.insert(TransactionEntity(amountCents = 60000, title = "Repayment from Anandhu", dateEpoch = now - 86400000L, entityId = a1, type = "repayment"))
        tDao.insert(TransactionEntity(amountCents = 10000, title = "Petrol", dateEpoch = now - 86400000L, entityId = null, type = "expense"))
    }

    fun transactionsForEntity(entityId: Long) = db.transactionDao().transactionsForEntity(entityId)
    fun outstandingForEntity(entityId: Long) = db.transactionDao().outstandingForEntity(entityId)
    suspend fun addTransaction(tx: TransactionEntity) = db.transactionDao().insert(tx)
}

// ViewModel
class MainViewModel(private val repo: SampleRepository) : ViewModel() {
    val entities = repo.entities.asLiveData()
    val allTransactions = repo.allTransactions.asLiveData()

    private val _selectedEntityId = MutableLiveData<Long?>(null)
    val selectedEntityId: LiveData<Long?> = _selectedEntityId

    val filteredTransactions: LiveData<List<TransactionEntity>> = MediatorLiveData<List<TransactionEntity>>().apply {
        var allTx: List<TransactionEntity>? = null
        var sel: Long? = null
        fun update() {
            val list = allTx ?: emptyList()
            value = if (sel == null) list else list.filter { it.entityId == sel }
        }
        addSource(allTransactions) { allTx = it; update() }
        addSource(_selectedEntityId) { sel = it; update() }
    }

    val outstandingForSelected: LiveData<Long?> = _selectedEntityId.switchMap { id ->
        if (id == null) liveData { emit(null) } else repo.outstandingForEntity(id).asLiveData()
    }

    init {
        viewModelScope.launch(Dispatchers.IO) { repo.seedIfEmpty() }
    }

    fun selectEntity(id: Long?) { _selectedEntityId.value = id }
    fun addTransaction(tx: TransactionEntity) {
        viewModelScope.launch(Dispatchers.IO) { repo.addTransaction(tx) }
    }
}

class MainViewModelFactory(private val repo: SampleRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(repo) as T
    }
}

// Helpers
private fun formatCurrency(cents: Long): String {
    val nf = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    val value = cents / 100.0
    return nf.format(value)
}

// Main Activity
class MainActivity : ComponentActivity() {
    private lateinit var db: AppDatabase
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Room.inMemoryDatabaseBuilder(applicationContext, AppDatabase::class.java).build()
        val repo = SampleRepository(db)
        val vmFactory = MainViewModelFactory(repo)
        val viewModel = ViewModelProvider(this, vmFactory).get(MainViewModel::class.java)

        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                AppRoot(viewModel)
            }
        }
    }
}

// UI Composables (Dashboard, Add sheet, Entity picker)
@Composable
fun AppRoot(viewModel: MainViewModel) {
    val entities by viewModel.entities.observeAsState(emptyList())
    val transactions by viewModel.filteredTransactions.observeAsState(emptyList())
    val selectedEntityId by viewModel.selectedEntityId.observeAsState()
    val outstanding by viewModel.outstandingForSelected.observeAsState()

    var showAdd by remember { mutableStateOf(false) }
    var showEntityPicker by remember { mutableStateOf(false) }

    val grouped = remember(transactions) {
        transactions.groupBy { tx ->
            val cal = Calendar.getInstance().apply { timeInMillis = tx.dateEpoch }
            val today = Calendar.getInstance()
            val diff = (today.timeInMillis - tx.dateEpoch) / 86400000L
            when {
                diff == 0L -> "Today"
                diff == 1L -> "Yesterday"
                else -> {
                    val dayName = java.text.SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(tx.dateEpoch))
                    dayName
                }
            }
        }
    }

    Scaffold(
        containerColor = ColorBackground,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAdd = true },
                containerColor = ColorTextPrimary,
                contentColor = ColorBackground,
                shape = CircleShape
            ) { Icon(Icons.Default.Add, contentDescription = "Add") }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TopBar(
                accounts = entities.map { it.name },
                selectedAccount = entities.find { it.id == selectedEntityId }?.name ?: "All Accounts",
                onSelectAccount = { showEntityPicker = true }
            )
            HeaderBalance(
                totalBalanceText = outstanding?.let { formatCurrency(it) } ?: "₹1,900.00",
                chartComposable = { MinimalBarChart() }
            )
            TransactionFeed(grouped = grouped)
        }

        if (showAdd) {
            AddTransactionModal(
                onDismiss = { showAdd = false },
                onSave = { title, amountText, dateEpoch, category, payment, entityName ->
                    val cents = (amountText.toDoubleOrNull() ?: 0.0) * 100.0
                    val tx = TransactionEntity(
                        amountCents = cents.toLong(),
                        title = title,
                        dateEpoch = dateEpoch,
                        categoryId = null,
                        entityId = null,
                        paymentSourceId = null,
                        type = "lend"
                    )
                    viewModel.addTransaction(tx)
                    showAdd = false
                }
            )
        }

        if (showEntityPicker) {
            EntityPickerModal(
                entities = entities,
                onSelect = { entity ->
                    viewModel.selectEntity(entity?.id)
                    showEntityPicker = false
                },
                onDismiss = { showEntityPicker = false }
            )
        }
    }
}

@Composable
fun TopBar(accounts: List<String>, selectedAccount: String, onSelectAccount: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.clickable { onSelectAccount() }, verticalAlignment = Alignment.CenterVertically) {
            Text(text = selectedAccount, fontWeight = FontWeight.Medium, color = ColorTextPrimary, fontSize = 16.sp)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = ColorTextPrimary)
        }
        Row {
            IconButton(onClick = { }) { Icon(Icons.Default.Search, contentDescription = null, tint = ColorTextPrimary) }
            IconButton(onClick = { }) { Icon(Icons.Default.List, contentDescription = null, tint = ColorTextPrimary) }
            IconButton(onClick = { }) { Icon(Icons.Default.Settings, contentDescription = null, tint = ColorTextPrimary) }
        }
    }
}

@Composable
fun HeaderBalance(totalBalanceText: String, chartComposable: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text = totalBalanceText, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = ColorTextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            chartComposable()
        }
    }
}

@Composable
fun MinimalBarChart() {
    Row(modifier = Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(8) { i ->
            val height = (20 + i * 6).dp
            Box(modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(vertical = 8.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(modifier = Modifier
                    .width(8.dp)
                    .height(height)
                    .clip(RoundedCornerShape(4.dp))
                    .background(ColorTextPrimary)
                )
            }
        }
    }
}

@Composable
fun TransactionFeed(grouped: Map<String, List<TransactionEntity>>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorSecondaryBg)
            .padding(12.dp)
    ) {
        grouped.forEach { (day, items) ->
            item {
                Text(day, color = ColorTextSecondary, modifier = Modifier.padding(vertical = 8.dp))
            }
            items(items) { tx ->
                InsetGroupedItem(tx)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun InsetGroupedItem(tx: TransactionEntity) {
    Card(
        shape = RoundedCornerShape(InsetCardRadius),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ColorBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(ColorSecondaryBg, shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Category, contentDescription = null, tint = ColorTextSecondary)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(tx.title ?: "Untitled", color = ColorTextPrimary, fontSize = 16.sp)
                val dateText = java.text.SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(tx.dateEpoch))
                Text("$dateText • ${tx.paymentSourceId?.let { "Slice via Cred" } ?: "—"}", color = ColorTextSecondary, fontSize = 13.sp)
            }
            val isRepayment = tx.type == "repayment"
            val amountText = if (isRepayment) "+${formatCurrency(tx.amountCents)}" else formatCurrency(tx.amountCents)
            Text(
                text = amountText,
                color = if (isRepayment) ColorPositive else ColorTextPrimary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionModal(onDismiss: () -> Unit, onSave: (String, String, Long, String, String, String) -> Unit) {
    val cfg = LocalConfiguration.current
    val screenHeight = with(LocalDensity.current) { cfg.screenHeightDp.dp }
    val targetHeight = screenHeight * 0.9f
    Box(modifier = Modifier.fillMaxSize().background(Color(0x80000000)).clickable { onDismiss() }) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(targetHeight)
                .clip(RoundedCornerShape(topStart = SheetCornerRadius, topEnd = SheetCornerRadius))
                .background(ColorBackground)
                .padding(16.dp)
        ) {
            Box(modifier = Modifier
                .size(width = 36.dp, height = 6.dp)
                .background(DragPillColor, shape = RoundedCornerShape(3.dp))
                .align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.ArrowBack, contentDescription = null, tint = ColorTextPrimary) }
                Text("Add Transaction", fontWeight = FontWeight.Medium, color = ColorTextPrimary)
                TextButton(onClick = { }) {
                    Text("Save", color = ColorPrimaryAction, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            GroupedCard {
                InputRow(icon = Icons.Default.TextFields, label = "Title", hint = "Petrol")
                ThinDivider()
                InputRow(icon = Icons.Default.AttachMoney, label = "Amount", hint = "1000", keyboardType = KeyboardType.Number)
                ThinDivider()
                InputRow(icon = Icons.Default.CalendarToday, label = "Date", hint = "22/03/2026", readOnly = true)
            }
            Spacer(modifier = Modifier.height(12.dp))
            GroupedCard {
                SelectRow(icon = Icons.Default.Label, label = "Category", value = "Personal")
                ThinDivider()
                SelectRow(icon = Icons.Default.CreditCard, label = "Payment", value = "Slice via Cred")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = {
                    onSave("Petrol", "1000", System.currentTimeMillis(), "Personal", "Slice via Cred", "Anandhu")
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ColorTextPrimary, contentColor = ColorBackground)
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
fun GroupedCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(InsetCardRadius),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ColorBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), content = content)
    }
}

@Composable
fun InputRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, hint: String = "", keyboardType: KeyboardType = KeyboardType.Text, readOnly: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = ColorTextSecondary)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = ColorTextPrimary)
        }
        Text(hint, color = ColorTextSecondary)
    }
}

@Composable
fun SelectRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(modifier = Modifier
        .fillMaxWidth()
        .clickable(enabled = onClick != null) { onClick?.invoke() }
        .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = ColorTextSecondary)
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, color = ColorTextPrimary, modifier = Modifier.weight(1f))
        Text(value, color = ColorTextSecondary)
    }
}

@Composable
fun ThinDivider() {
    Divider(color = Color(0xFFE6E6EA), thickness = 0.5.dp)
}

@Composable
fun EntityPickerModal(entities: List<EntityPerson>, onSelect: (EntityPerson?) -> Unit, onDismiss: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0x80000000)).clickable { onDismiss() }) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 420.dp)
                .clip(RoundedCornerShape(topStart = SheetCornerRadius, topEnd = SheetCornerRadius))
                .background(ColorBackground)
                .padding(12.dp)
        ) {
            Box(modifier = Modifier
                .size(width = 36.dp, height = 6.dp)
                .background(DragPillColor, shape = RoundedCornerShape(3.dp))
                .align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Select Entity", fontWeight = FontWeight.Medium, color = ColorTextPrimary, modifier = Modifier.padding(8.dp))
            Divider()
            LazyColumn {
                item {
                    Row(modifier = Modifier.fillMaxWidth().clickable { onSelect(null) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("All Accounts", color = ColorTextPrimary, modifier = Modifier.weight(1f))
                    }
                }
                items(entities) { e ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { onSelect(e) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(36.dp).background(ColorSecondaryBg, shape = CircleShape), contentAlignment = Alignment.Center) {
                            Text(e.name.firstOrNull()?.toString() ?: "?", color = ColorTextPrimary)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(e.name, color = ColorTextPrimary)
                    }
                }
            }
        }
    }
}
