package com.gabby.studiowebwrapper.data

import android.content.Context
import com.gabby.studiowebwrapper.BuildConfig
import com.gabby.studiowebwrapper.model.AuthResponse
import com.gabby.studiowebwrapper.model.GradeRequest
import com.gabby.studiowebwrapper.model.GradeResponse
import com.gabby.studiowebwrapper.model.SimilarProduct
import com.gabby.studiowebwrapper.model.SuggestMetadataOutput
import com.gabby.studiowebwrapper.model.UserDto
import com.gabby.studiowebwrapper.util.ImageUtils
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

object NativeRepository {
    private const val PREFS_NAME = "native_repo"
    private const val KEY_ACCESS_TOKEN = "supabase_access_token"
    private const val KEY_REFRESH_TOKEN = "supabase_refresh_token"
    private const val KEY_CURRENT_USER_ID = "supabase_current_user_id"
    private const val KEY_CURRENT_USER_EMAIL = "supabase_current_user_email"
    private const val KEY_CURRENT_USER_FULL_NAME = "supabase_current_user_full_name"
    private const val KEY_SYNCED_HISTORY_KEYS = "synced_history_keys"
    private const val KEY_LAST_HISTORY_SYNC_AT = "last_history_sync_at"
    private const val KEY_FEEDBACK_SHARING = "feedback_sharing_enabled"

    private val gson = Gson()
    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun signup(context: Context, fullName: String, email: String, password: String): Result<AuthResponse> {
        return try {
            val normalizedEmail = email.trim().lowercase(Locale.ROOT)
            val request = Request.Builder()
                .url(supabaseAuthUrl("signup"))
                .supabaseHeaders()
                .post(
                    gson.toJson(
                        SupabaseSignupRequest(
                            email = normalizedEmail,
                            password = password,
                            data = mapOf("full_name" to fullName.trim())
                        )
                    ).toRequestBody(jsonMediaType)
                )
                .build()

            val response = httpClient.newCall(request).execute()
            response.use {
                val bodyText = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    error(mapSupabaseAuthMessage(extractSupabaseError(bodyText), isSignup = true))
                }

                val session = parseSessionResponse(bodyText)
                val user = session.user

                if (user == null) {
                    val confirmationMessage = if (isEmailConfirmationLikelyEnabled(bodyText)) {
                        "Signup created. Check your email to confirm your account before logging in."
                    } else {
                        "Supabase signup completed, but no user object was returned. Check your Auth settings and response payload."
                    }
                    throw IllegalStateException(confirmationMessage)
                }

                val userDto = user.toUserDto(normalizedEmail, fullName)

                saveCurrentUser(context, userDto)
                if (!session.accessToken.isNullOrBlank() || !session.refreshToken.isNullOrBlank()) {
                    saveSessionTokens(context, session)
                }
                syncHistoryFromSupabase(context, userDto.id)

                Result.success(
                    AuthResponse(
                    message = if (session.accessToken.isNullOrBlank()) {
                        "Signup successful. Check your email to confirm your account."
                    } else {
                        "Signup successful"
                    },
                    user = userDto
                    )
                )
            }
        } catch (t: Throwable) {
            Result.failure(IllegalStateException(mapAuthThrowable(t, isSignup = true), t))
        }
    }

    fun login(context: Context, email: String, password: String): Result<AuthResponse> {
        return try {
            val normalizedEmail = email.trim().lowercase(Locale.ROOT)
            val request = Request.Builder()
                .url(supabaseAuthUrl("token?grant_type=password"))
                .supabaseHeaders()
                .post(
                    gson.toJson(
                        SupabasePasswordLoginRequest(
                            email = normalizedEmail,
                            password = password
                        )
                    ).toRequestBody(jsonMediaType)
                )
                .build()

            val response = httpClient.newCall(request).execute()
            response.use {
                val bodyText = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    error(mapSupabaseAuthMessage(extractSupabaseError(bodyText), isSignup = false))
                }

                val session = parseSessionResponse(bodyText)
                val user = session.user ?: error("Supabase login did not return a user.")
                val userDto = user.toUserDto(normalizedEmail, null)

                saveCurrentUser(context, userDto)
                saveSessionTokens(context, session)
                syncHistoryFromSupabase(context, userDto.id)

                Result.success(AuthResponse(message = "Login successful", user = userDto))
            }
        } catch (t: Throwable) {
            Result.failure(IllegalStateException(mapAuthThrowable(t, isSignup = false), t))
        }
    }

    fun getCurrentUser(context: Context): UserDto? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userId = prefs.getString(KEY_CURRENT_USER_ID, null) ?: return null
        val email = prefs.getString(KEY_CURRENT_USER_EMAIL, null) ?: return null
        val fullName = prefs.getString(KEY_CURRENT_USER_FULL_NAME, null)
        return UserDto(id = userId, email = email, fullName = fullName)
    }

    fun isLoggedIn(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_ACCESS_TOKEN, null).isNullOrBlank() ||
            !prefs.getString(KEY_REFRESH_TOKEN, null).isNullOrBlank()
    }

    fun logout(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null).orEmpty()
        if (accessToken.isNotBlank() && supabaseEnabled()) {
            runCatching {
                val request = Request.Builder()
                    .url(supabaseAuthUrl("logout"))
                    .supabaseHeaders(accessToken)
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                httpClient.newCall(request).execute().close()
            }
        }

        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_CURRENT_USER_ID)
            .remove(KEY_CURRENT_USER_EMAIL)
            .remove(KEY_CURRENT_USER_FULL_NAME)
            .apply()
    }

    fun grade(context: android.content.Context, request: GradeRequest): Result<GradeResponse> {
        // Fast on-device grader path
        runCatching {
            val local = com.gabby.studiowebwrapper.inference.LocalGrader.grade(context, request)
            if (local != null) return Result.success(local)
        }

        // Fallback heuristic (legacy) if LocalGrader couldn't run or failed
        return runCatching {
            val material = "Yellow Gold"
            val purity = "Unknown"

            val bitmap = runCatching { ImageUtils.decodeDataUri(request.fileDataUri) }.getOrNull()
            val quality = bitmap?.let { ImageUtils.analyzeImageQuality(it) }

            val brightnessScore = quality?.brightnessMean?.let { mean ->
                val distanceFromIdeal = kotlin.math.abs(mean - 128.0)
                (100.0 - (distanceFromIdeal / 1.28)).roundToInt().coerceIn(0, 100)
            } ?: 0
            val contrastScore = quality?.contrastStdDev?.let { stdDev ->
                (stdDev * 2.0).roundToInt().coerceIn(0, 100)
            } ?: 0
            val sharpnessScore = quality?.sharpnessVariance?.let { variance ->
                (variance * 1.5).roundToInt().coerceIn(0, 100)
            } ?: 0

            val yoloScore = ((contrastScore * 0.35f) + (sharpnessScore * 0.65f)).roundToInt().coerceIn(0, 100)
            val lbpScore = ((contrastScore * 0.55f) + (brightnessScore * 0.45f)).roundToInt().coerceIn(0, 100)
            val orbScore = ((sharpnessScore * 0.75f) + (brightnessScore * 0.25f)).roundToInt().coerceIn(0, 100)
            val qualityScore = ((yoloScore + lbpScore + orbScore) / 3).coerceIn(0, 100)

            val captureSummary = if (quality != null) {
                "Fallback grading based on image quality (brightness=${"%.1f".format(quality.brightnessMean)}, contrast=${"%.1f".format(quality.contrastStdDev)}, sharpness=${"%.1f".format(quality.sharpnessVariance)})."
            } else {
                "Fallback grading used because the image could not be decoded for quality analysis."
            }

            val similarProducts = listOf(
                SimilarProduct(
                    name = "$purity Yellow Gold Ring",
                    url = "https://www.google.com/search?q=yellow+gold+${purity.lowercase(Locale.ROOT)}+ring",
                    price = "N/A",
                    imageUrl = ""
                )
            )

            GradeResponse(
                data = SuggestMetadataOutput(
                    material = material,
                    purity = purity,
                    gemstones = null,
                    qualityScore = qualityScore,
                    analysis = captureSummary,
                    similarProducts = similarProducts,
                    yoloScore = yoloScore,
                    lbpScore = lbpScore,
                    orbScore = orbScore,
                    explainability = listOf(
                        "YOLO detection proxy: $yoloScore/100",
                        "LBP texture proxy: $lbpScore/100",
                        "ORB keypoint proxy: $orbScore/100",
                        "Final score uses equal weights: 33.3% YOLO + 33.3% LBP + 33.3% ORB"
                    )
                )
            )
        }
    }

    fun getLastHistorySyncAt(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_HISTORY_SYNC_AT, 0L)
    }

    private data class StoredCurrentUser(
        val id: String,
        val email: String,
        val fullName: String?
    )

    private data class SupabaseSignupRequest(
        val email: String,
        val password: String,
        val data: Map<String, String>
    )

    private data class SupabasePasswordLoginRequest(
        val email: String,
        val password: String
    )

    private data class SupabaseSessionResponse(
        @SerializedName("access_token") val accessToken: String? = null,
        @SerializedName("refresh_token") val refreshToken: String? = null,
        val user: SupabaseUserResponse? = null
    )

    private data class SupabaseUserResponse(
        val id: String,
        val email: String? = null,
        @SerializedName("user_metadata") val userMetadata: Map<String, Any?>? = null
    )

    private data class SupabaseErrorResponse(
        val msg: String? = null,
        val message: String? = null,
        val error: String? = null,
        @SerializedName("error_description") val errorDescription: String? = null
    )

    private data class SupabaseSignupHint(
        val user: SupabaseUserResponse? = null,
        val session: SupabaseSessionResponse? = null,
        val data: SupabaseSessionResponse? = null
    )

    private data class SupabaseHistoryRow(
        @SerializedName("result_json") val resultJson: String,
        @SerializedName("preview_uri") val previewUri: String,
        @SerializedName("created_at") val createdAt: Long
    )

    private fun supabaseEnabled(): Boolean {
        return BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    }

    private fun supabaseBaseUrl(): String {
        return BuildConfig.SUPABASE_URL.trim().trimEnd('/')
    }

    private fun supabaseAuthUrl(path: String): String {
        return "${supabaseBaseUrl()}/auth/v1/${path.trimStart('/')}"
    }

    private fun supabaseRestUrl(path: String): String {
        return "${supabaseBaseUrl()}/rest/v1/${path.trimStart('/')}"
    }

    private fun Request.Builder.supabaseHeaders(accessToken: String? = null): Request.Builder {
        header("apikey", BuildConfig.SUPABASE_ANON_KEY)
        header("Authorization", "Bearer ${accessToken ?: BuildConfig.SUPABASE_ANON_KEY}")
        header("Content-Type", "application/json")
        header("Accept", "application/json")
        return this
    }

    private fun parseSessionResponse(bodyText: String): SupabaseSessionResponse {
        val parsed = runCatching { gson.fromJson(bodyText, SupabaseSessionResponse::class.java) }.getOrNull()
        if (parsed?.user != null || parsed?.accessToken != null || parsed?.refreshToken != null) {
            return parsed
        }

        val hint = runCatching { gson.fromJson(bodyText, SupabaseSignupHint::class.java) }.getOrNull()
        return hint?.session ?: hint?.data ?: parsed ?: SupabaseSessionResponse()
    }

    private fun isEmailConfirmationLikelyEnabled(bodyText: String): Boolean {
        val normalized = bodyText.lowercase(Locale.ROOT)
        return normalized.contains("confirm") || normalized.contains("verify") || normalized.contains("email")
    }

    private fun extractSupabaseError(bodyText: String): String {
        if (bodyText.isBlank()) return "Supabase request failed."
        val parsed = runCatching { gson.fromJson(bodyText, SupabaseErrorResponse::class.java) }.getOrNull()
        return parsed?.errorDescription ?: parsed?.message ?: parsed?.msg ?: parsed?.error ?: bodyText
    }

    private fun mapAuthThrowable(throwable: Throwable, isSignup: Boolean): String {
        val raw = throwable.message.orEmpty()
        if (throwable is IOException) {
            return "Network error. Please check your internet connection and try again."
        }
        return mapSupabaseAuthMessage(raw, isSignup)
    }

    private fun mapSupabaseAuthMessage(raw: String, isSignup: Boolean): String {
        val normalized = raw.lowercase(Locale.ROOT)

        return when {
            normalized.contains("email rate limit exceeded") ||
                normalized.contains("rate limit") -> {
                "Too many email attempts. Please wait a bit and try again."
            }

            normalized.contains("user already registered") ||
                normalized.contains("already registered") -> {
                "An account with this email already exists. Try logging in instead."
            }

            normalized.contains("invalid login credentials") ||
                normalized.contains("invalid email or password") -> {
                "Invalid email or password."
            }

            normalized.contains("email not confirmed") ||
                normalized.contains("confirm your email") ||
                normalized.contains("verify your email") -> {
                "Please confirm your email before logging in."
            }

            normalized.contains("supabase signup did not return a user") -> {
                "Signup created. Check your email to confirm your account before logging in."
            }

            normalized.contains("signup completed, but no user object") -> {
                "Signup created. Check your email to confirm your account before logging in."
            }

            isSignup && (normalized.contains("signup") && normalized.contains("confirm")) -> {
                "Signup created. Check your email to confirm your account before logging in."
            }

            raw.isBlank() -> {
                if (isSignup) "Signup failed. Please try again." else "Login failed. Please try again."
            }

            else -> raw
        }
    }

    private fun SupabaseUserResponse.toUserDto(fallbackEmail: String, fallbackFullName: String?): UserDto {
        val metadataFullName = userMetadata?.get("full_name")?.toString()?.takeIf { it.isNotBlank() }
            ?: userMetadata?.get("fullName")?.toString()?.takeIf { it.isNotBlank() }
        return UserDto(
            id = id,
            email = email ?: fallbackEmail,
            fullName = metadataFullName ?: fallbackFullName?.takeIf { it.isNotBlank() }
        )
    }

    private fun saveCurrentUser(context: Context, user: UserDto) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CURRENT_USER_ID, user.id)
            .putString(KEY_CURRENT_USER_EMAIL, user.email)
            .putString(KEY_CURRENT_USER_FULL_NAME, user.fullName)
            .apply()
    }

    private fun saveSessionTokens(context: Context, session: SupabaseSessionResponse) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .apply()
    }

    fun syncHistoryEntryToSupabase(context: Context, entry: HistoryEntry) {
        if (!supabaseEnabled()) return
        if (entry.userId.isBlank()) return

        thread(start = true, isDaemon = true) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null).orEmpty()
            if (accessToken.isBlank()) return@thread

            val syncKey = historySyncKey(entry.userId, entry.timestamp, entry.previewUri)
            if (isHistorySynced(context, syncKey)) return@thread

            if (pushHistoryRowToSupabase(accessToken, entry.userId, entry.resultJson, entry.previewUri, entry.timestamp)) {
                markHistorySynced(context, syncKey)
            }
        }
    }

    private fun syncHistoryFromSupabase(context: Context, userId: String) {
        if (!supabaseEnabled()) return
        if (userId.isBlank()) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null).orEmpty()
        if (accessToken.isBlank()) return

        val db = AppDatabase.getInstance(context)
        val dao = db.historyDao()

        // Push pending local entries first so remote has latest data.
        val localHistory = runCatching { dao.getAllForUserOnce(userId) }.getOrDefault(emptyList())
        localHistory.forEach { localEntry ->
            val syncKey = historySyncKey(localEntry.userId, localEntry.timestamp, localEntry.previewUri)
            if (!isHistorySynced(context, syncKey)) {
                if (pushHistoryRowToSupabase(accessToken, localEntry.userId, localEntry.resultJson, localEntry.previewUri, localEntry.timestamp)) {
                    markHistorySynced(context, syncKey)
                }
            }
        }

        // Pull remote history and merge into local Room without duplicating known rows.
        val remoteHistory = fetchRemoteHistoryRows(accessToken, userId)
        remoteHistory.forEach { remote ->
            val exists = dao.countByUserAndTimestampAndPreview(userId, remote.createdAt, remote.previewUri) > 0
            if (!exists) {
                val entry = HistoryEntry(
                    userId = userId,
                    resultJson = remote.resultJson,
                    previewUri = remote.previewUri,
                    timestamp = remote.createdAt
                )
                dao.insert(entry)
            }
            markHistorySynced(context, historySyncKey(userId, remote.createdAt, remote.previewUri))
        }

        markHistorySyncNow(context)
    }

    private fun fetchRemoteHistoryRows(accessToken: String, userId: String): List<SupabaseHistoryRow> {
        val base = supabaseRestUrl("history_entries").toHttpUrlOrNull() ?: return emptyList()
        val url = base.newBuilder()
            .addQueryParameter("select", "result_json,preview_uri,created_at")
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("order", "created_at.desc")
            .build()

        val request = Request.Builder()
            .url(url)
            .supabaseHeaders(accessToken)
            .get()
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string().orEmpty()
                if (body.isBlank() || body == "null") return emptyList()

                val listType = object : TypeToken<List<SupabaseHistoryRow>>() {}.type
                gson.fromJson<List<SupabaseHistoryRow>>(body, listType) ?: emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun pushHistoryRowToSupabase(
        accessToken: String,
        userId: String,
        resultJson: String,
        previewUri: String,
        timestamp: Long
    ): Boolean {
        val payload = mapOf(
            "user_id" to userId,
            "result_json" to resultJson,
            "preview_uri" to previewUri,
            "created_at" to timestamp
        )

        val request = Request.Builder()
            .url(supabaseRestUrl("history_entries"))
            .supabaseHeaders(accessToken)
            .header("Prefer", "return=minimal")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        return runCatching {
            httpClient.newCall(request).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun isFeedbackSharingEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FEEDBACK_SHARING, false)
    }

    fun setFeedbackSharingEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FEEDBACK_SHARING, enabled).apply()
    }

    fun syncFeedbackEntryToSupabase(context: Context, entry: com.gabby.studiowebwrapper.data.FeedbackEntry) {
        if (!supabaseEnabled()) return
        if (!isFeedbackSharingEnabled(context)) return

        thread(start = true, isDaemon = true) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val accessToken = prefs.getString(KEY_ACCESS_TOKEN, null).orEmpty()
            if (accessToken.isBlank()) return@thread

            // Send anonymized payload: omit previewUri and userId to avoid PII leakage
            val sanitizedResultJson = scrubResultJsonForUpload(entry.resultJson)
            val commentSafe = entry.comment?.takeIf { it.length <= 1000 } ?: ""
            val payload = mapOf(
                "selection" to entry.selection,
                "comment" to commentSafe,
                "model_confidence" to entry.modelConfidence,
                "routed_to" to (entry.routedTo ?: ""),
                "result_json" to sanitizedResultJson,
                "created_at" to entry.timestamp
            )

            runCatching {
                val request = Request.Builder()
                    .url(supabaseRestUrl("feedback_entries"))
                    .supabaseHeaders(accessToken)
                    .header("Prefer", "return=minimal")
                    .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                    .build()

                httpClient.newCall(request).execute().use { }
            }
        }
    }

    // Public for testing - produce a sanitized JSON suitable for server upload
    fun scrubResultJsonForUpload(resultJson: String): String {
        if (resultJson.isBlank()) return "{}"
        try {
            // Fields that must always be removed or redacted
            val redactList = listOf("stampText", "sourceHash", "analysis", "similarProducts", "yoloDetections", "previewUri", "userId")

            val jsonElement = runCatching { com.google.gson.JsonParser.parseString(resultJson) }.getOrNull()
            if (jsonElement != null && jsonElement.isJsonObject) {
                val obj = jsonElement.asJsonObject

                // Remove redact fields if present
                redactList.forEach { key -> if (obj.has(key)) obj.remove(key) }

                // Additionally build a reduced object keeping only numeric signals and safe lists
                val reduced = com.google.gson.JsonObject()
                if (obj.has("material")) reduced.add("material", obj.get("material"))
                if (obj.has("purity")) reduced.add("purity", obj.get("purity"))
                if (obj.has("stampDetected")) reduced.add("stampDetected", obj.get("stampDetected"))
                if (obj.has("stampConfidence")) reduced.add("stampConfidence", obj.get("stampConfidence"))
                if (obj.has("qualityScore")) reduced.add("qualityScore", obj.get("qualityScore"))
                if (obj.has("yoloScore")) reduced.add("yoloScore", obj.get("yoloScore"))
                if (obj.has("lbpScore")) reduced.add("lbpScore", obj.get("lbpScore"))
                if (obj.has("orbScore")) reduced.add("orbScore", obj.get("orbScore"))
                if (obj.has("totalComputedScore")) reduced.add("totalComputedScore", obj.get("totalComputedScore"))
                if (obj.has("captureWarnings")) reduced.add("captureWarnings", obj.get("captureWarnings"))
                if (obj.has("rescanSuggestions")) reduced.add("rescanSuggestions", obj.get("rescanSuggestions"))

                return gson.toJson(reduced)
            }
        } catch (t: Throwable) {
            // fallthrough to fallback
        }

        // Fallback: perform simple regex redaction for known sensitive keys
        return resultJson.replace(Regex("\"stampText\"\\s*:\\s*\".*?\""), "\"stampText\":\"[redacted]\"")
            .replace(Regex("\"sourceHash\"\\s*:\\s*\".*?\""), "\"sourceHash\":\"[redacted]\"")
            .let { s -> s }
    }

    private fun historySyncKey(userId: String, timestamp: Long, previewUri: String): String {
        return "$userId|$timestamp|$previewUri"
    }

    private fun isHistorySynced(context: Context, key: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val synced = prefs.getStringSet(KEY_SYNCED_HISTORY_KEYS, emptySet()) ?: emptySet()
        return synced.contains(key)
    }

    private fun markHistorySynced(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY_SYNCED_HISTORY_KEYS, emptySet()) ?: emptySet()
        val updated = current.toMutableSet().apply { add(key) }
        prefs.edit().putStringSet(KEY_SYNCED_HISTORY_KEYS, updated).apply()
        markHistorySyncNow(context)
    }

    private fun markHistorySyncNow(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_LAST_HISTORY_SYNC_AT, System.currentTimeMillis()).apply()
    }
}
