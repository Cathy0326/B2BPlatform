package com.quipmarket.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.quipmarket.shared.RentalListing
import com.quipmarket.shared.RentalPricing
import com.quipmarket.shared.formatCents

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                QuipMarketApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuipMarketApp(vm: RentalsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = (state as? ListState.Loaded)?.listings?.find { it.id == selectedId }

    BackHandler(enabled = selected != null) { selectedId = null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected?.title ?: "QuipMarket rentals") },
                navigationIcon = {
                    if (selected != null) TextButton(onClick = { selectedId = null }) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                selected != null -> QuoteScreen(selected)
                else -> when (val s = state) {
                    ListState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    is ListState.Failed -> ErrorMessage(s.message, onRetry = vm::load)
                    is ListState.Loaded -> ListingList(s.listings, onSelect = { selectedId = it.id })
                }
            }
        }
    }
}

@Composable
private fun ListingList(listings: List<RentalListing>, onSelect: (RentalListing) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(listings, key = { it.id }) { listing ->
            Card(Modifier.fillMaxWidth().clickable(onClickLabel = "Get a rental quote") { onSelect(listing) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(listing.category.uppercase(), style = MaterialTheme.typography.labelSmall)
                    Text(listing.title, style = MaterialTheme.typography.titleMedium)
                    Text(listing.subtitle, style = MaterialTheme.typography.bodyMedium)
                    Text(listing.location, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("From ${formatCents(listing.rates.dailyCents)} / day", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun QuoteScreen(listing: RentalListing) {
    var days by rememberSaveable { mutableFloatStateOf(10f) }
    val rentalDays = days.toInt()
    // Computed on the device by the shared Kotlin module; contract tests guarantee it equals the server's quote.
    val quote = remember(listing.id, rentalDays) { RentalPricing.quote(rentalDays, listing.rates) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(listing.subtitle, style = MaterialTheme.typography.bodyMedium)
        Text("Rates", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        RateRow("Day", listing.rates.dailyCents)
        RateRow("Week (7 days)", listing.rates.weeklyCents)
        RateRow("Month (28 days)", listing.rates.monthlyCents)
        HorizontalDivider()

        Text("Rental length: $rentalDays days", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = days,
            onValueChange = { days = it },
            valueRange = 1f..120f,
            steps = 118,
        )
        Text(formatCents(quote.totalCents), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Cheapest mix: ${quote.describe()}")
        if (quote.savingsCents > 0) {
            Text("You save ${formatCents(quote.savingsCents)} compared with ${formatCents(quote.naiveDailyCents)} at the daily rate")
        }
    }
}

@Composable
private fun RateRow(label: String, cents: Long) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(formatCents(cents))
    }
}

@Composable
private fun ErrorMessage(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}
