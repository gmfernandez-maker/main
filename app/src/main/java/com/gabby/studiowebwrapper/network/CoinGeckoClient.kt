package com.gabby.studiowebwrapper.network

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object CoinGeckoClient {
    // Simple fetcher for /simple/price endpoint
    // coinId example: "pax-gold"  vsCurrency example: "php"
    @Throws(Exception::class)
    fun fetchPrice(coinId: String, vsCurrency: String, apiKey: String? = null): Double {
        val base = "https://api.coingecko.com/api/v3/simple/price"
        val keyParam = if (!apiKey.isNullOrBlank()) "&x_cg_demo_api_key=${apiKey}" else ""
        val urlStr = "$base?ids=${coinId}&vs_currencies=${vsCurrency}$keyParam"
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.doInput = true

        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val reader = BufferedReader(InputStreamReader(stream))
            val sb = StringBuilder()
            var line: String? = reader.readLine()
            while (line != null) {
                sb.append(line)
                line = reader.readLine()
            }
            val body = sb.toString()
            val json = JSONObject(body)
            val obj = json.optJSONObject(coinId) ?: throw Exception("Missing coin in response")
            val price = obj.optDouble(vsCurrency)
            if (price.isNaN()) throw Exception("Price not found for $coinId/$vsCurrency")
            return price
        } finally {
            conn.disconnect()
        }
    }
}
