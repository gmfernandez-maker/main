package com.gabby.studiowebwrapper

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.gabby.studiowebwrapper.databinding.ActivityMainBinding
import com.gabby.studiowebwrapper.data.NativeRepository
import com.gabby.studiowebwrapper.model.SuggestMetadataOutput
import com.gabby.studiowebwrapper.ui.AccountFragment
import com.gabby.studiowebwrapper.ui.AdminFeedbackFragment
import com.gabby.studiowebwrapper.ui.AdvancedMetricsFragment
import com.gabby.studiowebwrapper.ui.GradeResultFragment
import com.gabby.studiowebwrapper.ui.HistoryFragment
import com.gabby.studiowebwrapper.ui.HomeFragment
import com.gabby.studiowebwrapper.ui.LoginFragment
import com.gabby.studiowebwrapper.ui.SignupFragment
import com.gabby.studiowebwrapper.ui.UploadFragment
import com.gabby.studiowebwrapper.ui.WelcomeFragment
import com.gabby.studiowebwrapper.ui.SettingsPreferencesFragment
import com.gabby.studiowebwrapper.ui.EmailVerificationFragment
import com.gabby.studiowebwrapper.util.ThemeModeManager
import com.gabby.studiowebwrapper.data.AppDatabase
import com.gabby.studiowebwrapper.data.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.util.Base64
import android.graphics.BitmapFactory
import java.io.File
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.gson.Gson
import androidx.fragment.app.Fragment
import android.widget.Toast
import com.google.gson.reflect.TypeToken

class MainActivity : AppCompatActivity(),
    WelcomeFragment.Callbacks,
    LoginFragment.Callbacks,
    SignupFragment.Callbacks,
    EmailVerificationFragment.Callbacks,
    UploadFragment.Callbacks,
    GradeResultFragment.Callbacks,
    AdvancedMetricsFragment.Callbacks,
    HomeFragment.Callbacks,
    HistoryFragment.Callbacks,
    AccountFragment.Callbacks {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bottomNav: BottomNavigationView
    private val BOTTOM_VIS_KEY = "bottom_nav_visibility"
    private val STARTUP_DISCLAIMER_KEY = "startup_disclaimer_acknowledged"

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeModeManager.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showStartupDisclaimerIfNeeded()

        bottomNav = binding.bottomNavigation
        // Restore bottom nav visibility across recreation (theme switch)
        val restoredVis = savedInstanceState?.getInt(BOTTOM_VIS_KEY, android.view.View.GONE)
            ?: android.view.View.GONE
        bottomNav.visibility = restoredVis

        // Set up navigation
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    safeReplaceFragment(HomeFragment.newInstance(), addToBackStack = false)
                }
                R.id.nav_capture -> navigateToUploadForm()
                R.id.nav_history -> navigateToHistoryScreen()
                R.id.nav_account -> navigateToAccountScreen()
            }
            true
        }

        if (savedInstanceState == null) {
            if (NativeRepository.isLoggedIn(this)) {
                bottomNav.visibility = android.view.View.VISIBLE
                safeReplaceFragment(HomeFragment.newInstance(), addToBackStack = false)
            } else {
                // Default start: show the welcome/login flow instead of the YOLO demo
                safeReplaceFragment(WelcomeFragment(), addToBackStack = false)
            }
        }
    }

    override fun navigateToWelcome() {
        bottomNav.visibility = android.view.View.GONE
        safeReplaceFragment(WelcomeFragment(), addToBackStack = false)
    }

    override fun navigateToLogin() {
        bottomNav.visibility = android.view.View.GONE
        safeReplaceFragment(LoginFragment(), addToBackStack = true)
    }

    override fun navigateToSignup() {
        bottomNav.visibility = android.view.View.GONE
        safeReplaceFragment(SignupFragment(), addToBackStack = true)
    }

    override fun navigateToEmailVerification(email: String) {
        bottomNav.visibility = android.view.View.GONE
        safeReplaceFragment(EmailVerificationFragment.newInstance(email), addToBackStack = true)
    }

    override fun navigateToUpload() {
        bottomNav.visibility = android.view.View.VISIBLE
        safeReplaceFragment(HomeFragment.newInstance(), addToBackStack = false)
    }

    override fun navigateToUploadForm() {
        safeReplaceFragment(UploadFragment(), addToBackStack = true)
    }

    override fun navigateToHistoryScreen() {
        safeReplaceFragment(HistoryFragment(), addToBackStack = true)
    }

    override fun navigateToAccountScreen() {
        safeReplaceFragment(AccountFragment(), addToBackStack = true)
    }

    override fun navigateToAdminFeedback() {
        safeReplaceFragment(AdminFeedbackFragment(), addToBackStack = true)
    }

    override fun navigateToSettingsPreferences() {
        safeReplaceFragment(SettingsPreferencesFragment(), addToBackStack = true)
    }

    override fun navigateToGradeDetail(resultJson: String, previewUri: String) {
        safeReplaceFragment(GradeResultFragment.newInstance(resultJson, previewUri), addToBackStack = true)
    }

    override fun openAdvancedMetrics(resultJson: String, previewDataUri: String) {
        safeReplaceFragment(AdvancedMetricsFragment.newInstance(resultJson, previewDataUri), addToBackStack = true)
    }

    override fun showGradeResult(result: SuggestMetadataOutput, previewDataUri: String) {
        // Save a small thumbnail file and persist the graded result to Room DB, then navigate.
        try {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // previewDataUri is expected to be a data URI (data:<mime>;base64,AAA...)
                    val base64 = previewDataUri.substringAfter(",")
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val maxDim = 800
                    val (newW, newH) = if (bmp.width > maxDim || bmp.height > maxDim) {
                        val ratio = bmp.width.toFloat() / bmp.height.toFloat()
                        if (ratio >= 1f) Pair(maxDim, (maxDim / ratio).toInt()) else Pair((maxDim * ratio).toInt(), maxDim)
                    } else Pair(bmp.width, bmp.height)

                    val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, newW, newH, true)
                    val dir = File(filesDir, "thumbnails")
                    if (!dir.exists()) dir.mkdirs()
                    val outFile = File(dir, "thumb_${System.currentTimeMillis()}.jpg")
                    outFile.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
                    val previewPath = outFile.absolutePath

                    // insert into Room
                    val db = AppDatabase.getInstance(this@MainActivity)
                    val userId = NativeRepository.getCurrentUser(this@MainActivity)?.id ?: ""
                    val sourceHash = result.sourceHash.orEmpty()
                    val isDuplicate = sourceHash.isNotBlank() && db.historyDao().countByUserAndSourceHash(userId, sourceHash) > 0
                    val entry = HistoryEntry(
                        userId = userId,
                        sourceHash = sourceHash,
                        resultJson = Gson().toJson(result),
                        previewUri = previewPath,
                        timestamp = System.currentTimeMillis()
                    )
                    if (!isDuplicate) {
                        db.historyDao().insert(entry)
                        NativeRepository.syncHistoryEntryToSupabase(this@MainActivity, entry)
                    }

                    // Open the result screen on main thread
                    launch(Dispatchers.Main) {
                        if (isDuplicate) {
                            Toast.makeText(this@MainActivity, "Duplicate submission ignored.", Toast.LENGTH_SHORT).show()
                        }
                        safeReplaceFragment(
                            GradeResultFragment.newInstance(Gson().toJson(result), previewPath),
                            addToBackStack = false
                        )
                    }
                } catch (e: Exception) {
                    // Fallback: navigate with original data URI if thumbnail save failed.
                    launch(Dispatchers.Main) {
                        safeReplaceFragment(
                            GradeResultFragment.newInstance(Gson().toJson(result), previewDataUri),
                            addToBackStack = false
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // Final fallback.
            safeReplaceFragment(
                GradeResultFragment.newInstance(Gson().toJson(result), previewDataUri),
                addToBackStack = false
            )
        }
    }

    override fun navigateBack() {
        onBackPressedDispatcher.onBackPressed()
    }

    private fun safeReplaceFragment(fragment: Fragment, addToBackStack: Boolean = true) {
        try {
            val tx = supportFragmentManager.beginTransaction()
            tx.replace(R.id.fragmentContainer, fragment)
            if (addToBackStack) tx.addToBackStack(null)
            tx.commit()
        } catch (e: IllegalStateException) {
            // Fallback: commit allowing state loss to avoid crash (occurs if state saved)
            try {
                val tx2 = supportFragmentManager.beginTransaction()
                tx2.replace(R.id.fragmentContainer, fragment)
                if (addToBackStack) tx2.addToBackStack(null)
                tx2.commitAllowingStateLoss()
            } catch (ex: Exception) {
                Toast.makeText(this, "Navigation failed: ${ex.message}", Toast.LENGTH_SHORT).show()
            }
        } catch (ex: Exception) {
            Toast.makeText(this, "Navigation failed: ${ex.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(BOTTOM_VIS_KEY, bottomNav.visibility)
    }

    private fun showStartupDisclaimerIfNeeded() {
        val prefs = getSharedPreferences("app_ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean(STARTUP_DISCLAIMER_KEY, false)) return

        AlertDialog.Builder(this)
            .setTitle("Before you start")
            .setMessage(
                "This app gives a photo-based estimate of how a jewelry item compares to known examples. " +
                    "It can also look for a visible karat stamp. The result is helpful guidance, not a certified appraisal or purity guarantee."
            )
            .setCancelable(false)
            .setPositiveButton("I understand") { _, _ ->
                prefs.edit().putBoolean(STARTUP_DISCLAIMER_KEY, true).apply()
            }
            .show()
    }
}
