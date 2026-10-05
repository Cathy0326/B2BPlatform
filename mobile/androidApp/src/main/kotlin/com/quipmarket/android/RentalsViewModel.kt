package com.quipmarket.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quipmarket.shared.QuipMarketApi
import com.quipmarket.shared.RentalListing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ListState {
    data object Loading : ListState
    data class Loaded(val listings: List<RentalListing>) : ListState
    data class Failed(val message: String) : ListState
}

/** Loads the rental listings once and survives screen rotation. All pricing happens in the shared module. */
class RentalsViewModel(private val api: QuipMarketApi = QuipMarketApi(BuildConfig.GRAPHQL_URL)) : ViewModel() {
    private val _state = MutableStateFlow<ListState>(ListState.Loading)
    val state: StateFlow<ListState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = ListState.Loading
        viewModelScope.launch {
            _state.value = try {
                ListState.Loaded(api.rentalListings())
            } catch (e: Exception) {
                ListState.Failed(e.message ?: "Something went wrong")
            }
        }
    }

    override fun onCleared() = api.close()
}
