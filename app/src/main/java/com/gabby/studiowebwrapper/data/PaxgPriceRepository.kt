package com.gabby.studiowebwrapper.data

import android.content.Context
import com.gabby.studiowebwrapper.network.CoinGeckoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PaxgPriceRepository {
    // Fetch latest price from CoinGecko and cache it. Returns price in PHP per token (1 PAXG ≈ 1 troy oz)
    suspend fun fetchAndCachePricePhp(context: Context, coinId: String = "pax-gold", vsCurrency: String = "php"): Double? {
        return withContext(Dispatchers.IO) {
            try {
                val apiKey = PaxgPrefs.getApiKey(context)
                val price = CoinGeckoClient.fetchPrice(coinId, vsCurrency, apiKey)
                PaxgPrefs.savePricePhp(context, price, System.currentTimeMillis())
                price
            } catch (e: Exception) {
                null
            }
        }
    }

    fun getCachedPricePhp(context: Context): Double? {
        return PaxgPrefs.getCachedPricePhp(context)
    }
}
