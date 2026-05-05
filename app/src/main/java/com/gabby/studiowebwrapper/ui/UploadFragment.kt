package com.gabby.studiowebwrapper.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.gabby.studiowebwrapper.R
import com.gabby.studiowebwrapper.data.NativeRepository
import com.gabby.studiowebwrapper.databinding.FragmentUploadBinding
import com.gabby.studiowebwrapper.model.GradeRequest
import com.gabby.studiowebwrapper.model.SimilarProduct
import com.gabby.studiowebwrapper.model.SuggestMetadataOutput
import com.gabby.studiowebwrapper.model.YoloDetectionOutput
import com.gabby.studiowebwrapper.util.ImageUtils
import com.gabby.studiowebwrapper.util.ImageQualityReport
import com.gabby.studiowebwrapper.util.ModelPreferenceManager
import com.gabby.studiowebwrapper.util.StampOcr
import com.gabby.studiowebwrapper.util.StampOcrResult
import com.gabby.studiowebwrapper.util.YoloLabels
import com.gabby.studiowebwrapper.util.VanillaPipelineResult
import com.gabby.studiowebwrapper.util.VanillaVisionPipeline
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader

class UploadFragment : Fragment() {

    interface Callbacks {
        fun showGradeResult(result: com.gabby.studiowebwrapper.model.SuggestMetadataOutput, previewDataUri: String)
        fun navigateBack()
    }

    private var callbacks: Callbacks? = null
    private var binding: FragmentUploadBinding? = null

    private var selectedUri: Uri? = null
    private var selectedDataUri: String? = null
    private var selectedBitmap: Bitmap? = null
    private var selectedFileName: String = "jewelry.jpg"
    private var pendingCameraCaptureUri: Uri? = null
    private var pendingCameraCaptureFile: File? = null
    private var visionPipeline: VanillaVisionPipeline? = null

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            clearPendingCameraCapture()
            selectedUri = uri
            selectedDataUri = null
            selectedBitmap = null
            selectedFileName = resolveFileName(uri)
            binding?.previewImage?.load(uri)
            binding?.fileNameText?.text = selectedFileName
            binding?.fileNameText?.isVisible = true
            binding?.statusText?.isVisible = false
        }
    }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val captureUri = pendingCameraCaptureUri
        if (!success) {
            clearPendingCameraCapture()
            return@registerForActivityResult
        }

        if (success && captureUri != null) {
            val bitmap = runCatching { ImageUtils.decodeBitmap(requireContext(), captureUri) }.getOrNull()
            if (bitmap != null) {
                selectedUri = captureUri
                selectedDataUri = ImageUtils.bitmapToDataUri(bitmap)
                selectedBitmap = bitmap
                selectedFileName = pendingCameraCaptureFile?.name ?: "captured_${System.currentTimeMillis()}.jpg"
                binding?.previewImage?.load(bitmap)
                binding?.fileNameText?.text = selectedFileName
                binding?.fileNameText?.isVisible = true
                binding?.statusText?.isVisible = false
                return@registerForActivityResult
            }
        }

        clearPendingCameraCapture()
        binding?.statusText?.text = getString(R.string.generic_error)
        binding?.statusText?.isVisible = true
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            binding?.statusText?.text = "Camera permission is required to take a photo."
            binding?.statusText?.isVisible = true
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentUploadBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        OpenCVLoader.initDebug()
        visionPipeline = VanillaVisionPipeline(requireContext())

        binding?.apply {
            chooseImageButton.setOnClickListener {
                pickImageLauncher.launch("image/*")
            }
            takePhotoButton.setOnClickListener {
                launchCameraCapture()
            }
            gradeButton.setOnClickListener {
                submitForGrading()
            }
        }
    }

    private fun submitForGrading() {
        val currentBinding = binding ?: return
        val existingDataUri = selectedDataUri
        val uri = selectedUri

        if (existingDataUri == null && uri == null) {
            currentBinding.statusText.text = getString(R.string.select_image_error)
            currentBinding.statusText.isVisible = true
            return
        }

        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            if (selectedDataUri == null && uri != null) {
                val dataUriResult = withContext(Dispatchers.IO) {
                    runCatching { ImageUtils.toDataUri(requireContext(), uri) }
                }

                dataUriResult.onFailure {
                    setLoading(false)
                    currentBinding.statusText.text = it.message ?: getString(R.string.generic_error)
                    currentBinding.statusText.isVisible = true
                    return@launch
                }
                selectedDataUri = dataUriResult.getOrNull()
            }

            if (selectedBitmap == null) {
                val bitmapResult = withContext(Dispatchers.IO) {
                    runCatching {
                        when {
                            uri != null -> ImageUtils.decodeBitmap(requireContext(), uri)
                            selectedDataUri != null -> ImageUtils.decodeDataUri(selectedDataUri!!)
                            else -> throw IllegalStateException("No image selected")
                        }
                    }
                }
                bitmapResult.onFailure {
                    setLoading(false)
                    currentBinding.statusText.text = "Failed to read image for quality checks. ${it.message.orEmpty()}"
                    currentBinding.statusText.isVisible = true
                    return@launch
                }
                selectedBitmap = bitmapResult.getOrNull()
            }

            val qualityReport = withContext(Dispatchers.Default) {
                ImageUtils.analyzeImageQuality(selectedBitmap!!)
            }

            if (qualityReport.blockingIssues.isNotEmpty()) {
                setLoading(false)
                currentBinding.statusText.text = buildBlockingQualityMessage(qualityReport)
                currentBinding.statusText.isVisible = true
                return@launch
            }

            val pipelineAnalysis = withContext(Dispatchers.Default) {
                runCatching { visionPipeline?.analyze(selectedBitmap!!) }.getOrNull()
            }

            if (pipelineAnalysis == null || pipelineAnalysis.detections.isEmpty()) {
                setLoading(false)
                currentBinding.statusText.text = "No jewelry item was confidently detected. Please retake the photo with one item centered in frame."
                currentBinding.statusText.isVisible = true
                return@launch
            }

            val stampOcrResult = withContext(Dispatchers.Default) {
                val topDetection = pipelineAnalysis?.detections?.maxByOrNull { it.score }
                if (topDetection != null) {
                    runCatching { StampOcr.detectStampInRoi(selectedBitmap!!, topDetection) }.getOrNull()
                } else {
                    null
                }
            }

            val request = GradeRequest(
                fileDataUri = selectedDataUri!!,
                fileName = selectedFileName
            )

            val gradingResult = withContext(Dispatchers.IO) {
                NativeRepository.grade(requireContext(), request)
            }

            setLoading(false)
            gradingResult.onSuccess { response ->
                if (response.error != null) {
                    currentBinding.statusText.text = response.error
                    currentBinding.statusText.isVisible = true
                    return@onSuccess
                }
                val payload = response.data
                if (payload == null) {
                    currentBinding.statusText.text = getString(R.string.generic_error)
                    currentBinding.statusText.isVisible = true
                    return@onSuccess
                }
                val enhancedPayload = enrichResult(payload, qualityReport, pipelineAnalysis, stampOcrResult)
                if (enhancedPayload.totalComputedScore < 30) {
                    currentBinding.statusText.text = "Confidence is too low to save this result. Please retake the photo."
                    currentBinding.statusText.isVisible = true
                    return@onSuccess
                }
                if (pipelineAnalysis?.yoloStatus?.contains("fallback", ignoreCase = true) == true) {
                    Toast.makeText(
                        requireContext(),
                        "YOLOv8s failed to load, so the app used YOLOv8n instead.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                Toast.makeText(requireContext(), getString(R.string.grading_success), Toast.LENGTH_SHORT).show()
                callbacks?.showGradeResult(enhancedPayload, selectedDataUri!!)
            }.onFailure {
                currentBinding.statusText.text = it.message ?: getString(R.string.generic_error)
                currentBinding.statusText.isVisible = true
            }
        }
    }

    private fun launchCameraCapture() {
        val context = context ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            val captureUri = createCameraCaptureUri(context)
            if (captureUri == null) {
                binding?.statusText?.text = getString(R.string.generic_error)
                binding?.statusText?.isVisible = true
                return
            }
            pendingCameraCaptureUri = captureUri
            takePhotoLauncher.launch(captureUri)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun createCameraCaptureUri(context: Context): Uri? {
        val captureDir = File(context.cacheDir, "camera_captures")
        if (!captureDir.exists() && !captureDir.mkdirs()) {
            return null
        }

        val captureFile = File.createTempFile("capture_", ".jpg", captureDir)
        pendingCameraCaptureFile = captureFile
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            captureFile
        )
    }

    private fun clearPendingCameraCapture() {
        pendingCameraCaptureUri = null
        pendingCameraCaptureFile?.delete()
        pendingCameraCaptureFile = null
    }

    private fun resolveFileName(uri: Uri): String {
        val name = uri.lastPathSegment?.substringAfterLast('/')
        return if (name.isNullOrBlank()) "jewelry.jpg" else name
    }

    private fun setLoading(isLoading: Boolean) {
        binding?.apply {
            progressBar.isVisible = isLoading
            chooseImageButton.isEnabled = !isLoading
            takePhotoButton.isEnabled = !isLoading
            gradeButton.isEnabled = !isLoading
            statusText.isVisible = false
        }
    }

    private fun buildBlockingQualityMessage(report: ImageQualityReport): String {
        val reasons = report.blockingIssues.joinToString(separator = "\n- ", prefix = "- ")
        return "Quality check failed. Please retake the photo:\n$reasons"
    }

    private fun verifyStampMaterialConsistency(
        stampPurity: String?,
        detectedMaterial: com.gabby.studiowebwrapper.util.MaterialClassifier.MaterialScore?
    ): Boolean? {
        if (stampPurity == null || detectedMaterial == null) return null
        
        return when (stampPurity) {
            "24K", "999", "916" -> detectedMaterial.goldScore > 80
            "18K", "750" -> detectedMaterial.goldScore >= 70 && detectedMaterial.goldScore <= 95
            "14K", "585" -> detectedMaterial.goldScore >= 60 && detectedMaterial.goldScore <= 90
            "925", "999S" -> detectedMaterial.silverScore > 80
            else -> null
        }
    }

    private fun enrichResult(
        payload: SuggestMetadataOutput,
        report: ImageQualityReport,
        pipelineAnalysis: VanillaPipelineResult?,
        stampResult: StampOcrResult?
    ): SuggestMetadataOutput {
        val topDetection = pipelineAnalysis?.detections?.maxByOrNull { it.score }
        val noDetectionOutcome = pipelineAnalysis != null && pipelineAnalysis.detections.isEmpty()
        val detectedType = topDetection?.let { YoloLabels.labelForClassId(it.classId) }
        val pipelineYolo = pipelineAnalysis?.detections?.maxOfOrNull { it.score }?.let { (it * 100f).toInt() }
        val pipelineLbp = pipelineAnalysis?.topScore?.lbpScore?.let { (it * 100f).toInt() }
        val pipelineOrb = pipelineAnalysis?.topScore?.orbScore?.let { (it * 100f).toInt() }
        val yoloDetections = pipelineAnalysis?.detections?.map {
            YoloDetectionOutput(
                classId = it.classId,
                score = it.score,
                x1 = it.x1,
                y1 = it.y1,
                x2 = it.x2,
                y2 = it.y2
            )
        }

        // Rename for clarity: scores represent visual similarity, not authenticity
        // Pipeline-first fusion with per-signal fallback.
        // This avoids dropping LBP/ORB to 0 when references are unavailable.
        val detectionStrength = if (noDetectionOutcome) 0 else pipelineYolo ?: payload.yoloScore
        val textureMatch = if (noDetectionOutcome) 0 else pipelineLbp ?: payload.lbpScore
        val keypointMatch = if (noDetectionOutcome) 0 else pipelineOrb ?: payload.orbScore
        val detectionClamped = detectionStrength.coerceIn(0, 100)
        val textureClamped = textureMatch.coerceIn(0, 100)
        val keypointClamped = keypointMatch.coerceIn(0, 100)
        val hasComponentScores = detectionClamped > 0 || textureClamped > 0 || keypointClamped > 0
        val visualLikelihood = if (noDetectionOutcome) {
            0
        } else if (hasComponentScores) {
            (detectionClamped + textureClamped + keypointClamped) / 3
        } else {
            payload.qualityScore.coerceIn(0, 100)
        }
        val expectedWeightEstimation = estimateExpectedWeightScore01(pipelineAnalysis?.detections.orEmpty())
        val stampPassesThreshold = stampResult?.detected == true && stampResult.confidence >= 70
        val stampMaterialVerified = verifyStampMaterialConsistency(
            stampResult?.normalizedStamp,
            pipelineAnalysis?.materialScore
        )
        val resolvedPurity = when {
            noDetectionOutcome -> "Unknown"
            stampPassesThreshold && stampMaterialVerified != false -> stampResult?.normalizedStamp ?: "Unknown"
            stampPassesThreshold && stampMaterialVerified == false -> "Unknown (Stamp-Material Mismatch)"
            else -> payload.purity?.ifBlank { "Unknown" } ?: "Unknown"
        }
        val sourceHash = sha256(selectedDataUri.orEmpty())
        val selectedModel = ModelPreferenceManager.getSelectedModel(requireContext())
        val usedModelLabel = if (pipelineAnalysis?.yoloStatus?.contains("fallback", ignoreCase = true) == true) {
            "YOLOv8n (fallback)"
        } else {
            selectedModel.label
        }

        val explainability = if (hasComponentScores) {
            val brightness = String.format("%.1f", report.brightnessMean)
            val contrast = String.format("%.1f", report.contrastStdDev)
            val sharpness = String.format("%.1f", report.sharpnessVariance)
            val weight = String.format("%.2f", expectedWeightEstimation)
            buildList {
                add("Model used: $usedModelLabel")
                add("Detection Strength: $detectionClamped/100 (how confidently the item was detected)")
                add("Texture Similarity: $textureClamped/100 (how much it resembles known references)")
                add("Pattern Similarity: $keypointClamped/100 (how well patterns match known items)")
                add("Visual Likelihood: $visualLikelihood% (average of the three scores above)")
                add("Capture quality: brightness $brightness, contrast $contrast, sharpness $sharpness")
                add("Expected weight estimation (0-1 scale): $weight")
                add("Important: This shows visual similarity only. It is NOT an authenticity verification or professional appraisal.")
                if (stampMaterialVerified == false) {
                    add("WARNING: Detected stamp does not match the detected material type. This may indicate a mismatch or counterfeit.")
                }

                if (pipelineAnalysis != null) {
                    add("Pipeline status: YOLO=${pipelineAnalysis.yoloStatus}; Grader=${pipelineAnalysis.graderStatus}")
                    add("Detections found: ${pipelineAnalysis.detections.size}")
                    if (pipelineAnalysis.laidDownRefinementApplied) {
                        add("Laid-down refinement: applied")
                    }
                    pipelineAnalysis.topScore?.let { top ->
                        add("Top reference match: ${top.referenceName} (score=${"%.3f".format(top.finalScore)})")
                    }
                    if (pipelineAnalysis.notes.isNotEmpty()) {
                        add("Pipeline notes: ${pipelineAnalysis.notes.joinToString(separator = " | ")}")
                    }
                }
            }
        } else {
            listOf(
                "Algorithm component scores are unavailable for this result.",
                "Final score uses overall quality estimate = $visualLikelihood/100",
                "Capture metrics: brightness ${"%.1f".format(report.brightnessMean)}, contrast ${"%.1f".format(report.contrastStdDev)}, sharpness ${"%.1f".format(report.sharpnessVariance)}",
                "Expected weight estimation (0-1): ${"%.2f".format(expectedWeightEstimation)}"
            )
        }

        val suggestions = mutableListOf<String>()
        if (detectionClamped in 1..59) suggestions += "Center the item and keep all edges visible before scanning."
        if (textureClamped in 1..59) suggestions += "Use even lighting to improve surface texture detail."
        if (keypointClamped in 1..59) suggestions += "Reduce motion blur by keeping your hand steady and tapping to focus."
        if (!hasComponentScores) suggestions += "Try retaking with a full, centered view so component detectors can run."
        if (noDetectionOutcome) {
            suggestions += "No jewelry detections were found, so please retake the photo."
            suggestions += "Place one jewelry item on a plain background and keep it centered."
        }
        if (visualLikelihood < 70) suggestions += "Retake from a straight top-down angle with a plain background."
        if (stampMaterialVerified == false) {
            suggestions += "The stamp you have does not match the material detected. Have a professional jeweler inspect this item."
        }
        suggestions += report.warnings
        
        // Material classification feedback
        pipelineAnalysis?.materialScore?.let { material ->
            when {
                material.goldScore > 75 && material.confidence > 60 -> {
                    suggestions += "Material classification: Likely GOLD (confidence: ${material.confidence.toInt()}%)"
                }
                material.silverScore > 75 && material.confidence > 60 -> {
                    suggestions += "Material classification: Likely SILVER (confidence: ${material.confidence.toInt()}%)"
                }
                material.confidence < 50 -> {
                    suggestions += "Material could not be clearly classified; ensure item is centered and well-lit."
                }
            }
        }
        
        if (pipelineAnalysis?.colorPreFilterPassed == false) {
            suggestions += "Warning: Image doesn't look like metallic jewelry. Verify the item is gold or silver and well-illuminated."
        }
        
        if (suggestions.isEmpty()) suggestions += "Capture quality looked good. No rescan needed."

        val urlType = (detectedType ?: payload.material)
            .trim()
            .replace(" ", "+")
            .lowercase()
        val productPurity = resolvedPurity
        val productNameType = detectedType ?: payload.material
        val updatedProducts = if (payload.similarProducts.isNullOrEmpty()) {
            listOf(
                SimilarProduct(
                    name = "$productPurity Yellow Gold $productNameType",
                    url = "https://www.google.com/search?q=yellow+gold+$urlType+$productPurity",
                    price = "N/A",
                    imageUrl = ""
                )
            )
        } else {
            payload.similarProducts.mapIndexed { index, product ->
                if (index == 0) {
                    product.copy(
                        name = "$productPurity Yellow Gold $productNameType",
                        url = "https://www.google.com/search?q=yellow+gold+$urlType+$productPurity"
                    )
                } else {
                    product
                }
            }
        }

        val analysisText = when {
            noDetectionOutcome -> {
                "No jewelry stamp was clearly isolated in this photo. Please retake with one item centered on a plain background."
            }
            stampPassesThreshold -> {
                val typeText = detectedType?.let { " Jewelry type: $it." } ?: ""
                "Predicted karat: $resolvedPurity based on OCR stamp evidence (${stampResult?.confidence}% confidence).$typeText"
            }
            detectedType != null -> {
                "Jewelry type detected: $detectedType. No reliable stamp evidence was visible, so the karat is predicted as Unknown. Likelihood score summarizes visual similarity only and is not a certification."
            }
            else -> {
                "No confident jewelry type was detected. No reliable stamp evidence was visible, so the karat is predicted as Unknown. Likelihood score summarizes available visual signals only and is not a certification."
            }
        }

        return payload.copy(
            material = if (noDetectionOutcome) "Unknown" else detectedType ?: payload.material,
            purity = resolvedPurity,
            stampText = if (stampPassesThreshold) stampResult?.normalizedStamp else null,
            stampConfidence = if (stampPassesThreshold) stampResult?.confidence ?: 0 else 0,
            stampDetected = stampPassesThreshold,
            qualityScore = visualLikelihood,
            analysis = analysisText,
            similarProducts = updatedProducts,
            yoloScore = detectionClamped,
            lbpScore = textureClamped,
            orbScore = keypointClamped,
            expectedWeightGrams = expectedWeightEstimation,
            totalComputedScore = visualLikelihood,
            explainability = explainability,
            yoloDetections = yoloDetections,
            captureWarnings = report.warnings,
            rescanSuggestions = suggestions.distinct(),
            sourceHash = sourceHash,
            yoloModelUsed = usedModelLabel
        )
    }

    private fun estimateExpectedWeightScore01(detections: List<com.gabby.studiowebwrapper.util.Detection>): Float {
        if (detections.isEmpty()) return 0f

        val top = detections.maxByOrNull { it.score } ?: return 0f
        val width = (top.x2 - top.x1).coerceAtLeast(0f)
        val height = (top.y2 - top.y1).coerceAtLeast(0f)
        if (width <= 0f || height <= 0f) return 0f

        val areaNorm = (width * height) / (640f * 640f)
        val confidence = top.score.coerceIn(0f, 1f)
        val score = (0.65f * areaNorm.coerceIn(0f, 1f)) + (0.35f * confidence)
        return score.coerceIn(0f, 1f)
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun onDestroyView() {
        clearPendingCameraCapture()
        binding = null
        super.onDestroyView()
    }

    override fun onDetach() {
        callbacks = null
        super.onDetach()
    }
}
