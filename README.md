# PixelPioneers Jewelry Grading App

Android app for jewelry image grading with a hybrid pipeline:

1. On-device vision validation and detection (OpenCV + YOLOv8 TorchScript)
2. Feature extraction (LBP/ORB)
3. Result enrichment and grading persistence
4. Optional backend-assisted grading/auth flows through Supabase endpoints

The app is designed to give fast visual similarity feedback while keeping the grading workflow usable on-device.

## Repository

https://github.com/gmfernandez-maker/main

## General Overview

The app helps a user capture or select a jewelry photo, validates image quality, runs local detection, extracts descriptors, and then builds a grading result shown in a rich results screen.

Main user journey:

1. User signs in or creates an account
2. User opens Capture/Upload
3. App validates image quality (blur, brightness, dimensions)
4. App runs YOLO detection and center-zone checks
5. App computes local feature signals (LBP/ORB/material cues)
6. App displays grade result and saves history/reports locally

## App Architecture

### 1) Activity and navigation shell

- [app/src/main/java/com/gabby/studiowebwrapper/MainActivity.kt](app/src/main/java/com/gabby/studiowebwrapper/MainActivity.kt)

What it does:

- Hosts fragment navigation
- Controls bottom nav visibility by auth state
- Routes to Home, Upload, History, Account, and result details

### 2) UI feature fragments

- [app/src/main/java/com/gabby/studiowebwrapper/ui/UploadFragment.kt](app/src/main/java/com/gabby/studiowebwrapper/ui/UploadFragment.kt)
- [app/src/main/java/com/gabby/studiowebwrapper/ui/GradeResultFragment.kt](app/src/main/java/com/gabby/studiowebwrapper/ui/GradeResultFragment.kt)
- [app/src/main/java/com/gabby/studiowebwrapper/ui/HistoryFragment.kt](app/src/main/java/com/gabby/studiowebwrapper/ui/HistoryFragment.kt)
- [app/src/main/java/com/gabby/studiowebwrapper/ui/ReportsFragment.kt](app/src/main/java/com/gabby/studiowebwrapper/ui/ReportsFragment.kt)
- [app/src/main/java/com/gabby/studiowebwrapper/ui/ModelSelectionDialogFragment.kt](app/src/main/java/com/gabby/studiowebwrapper/ui/ModelSelectionDialogFragment.kt)

What they do:

- UploadFragment: capture/pick image and kick off grading flow
- GradeResultFragment: render final score breakdown, detection overlays, and guidance
- History/Reports: list and review previous grading outputs
- ModelSelectionDialogFragment: choose YOLO variant (speed vs accuracy tradeoff)

### 3) Local vision and scoring pipeline

- [app/src/main/java/com/gabby/studiowebwrapper/util/VanillaVisionPipeline.kt](app/src/main/java/com/gabby/studiowebwrapper/util/VanillaVisionPipeline.kt)
- [app/src/main/java/com/gabby/studiowebwrapper/util/YoloInference.kt](app/src/main/java/com/gabby/studiowebwrapper/util/YoloInference.kt)
- [app/src/main/java/com/gabby/studiowebwrapper/util/ImageUtils.kt](app/src/main/java/com/gabby/studiowebwrapper/util/ImageUtils.kt)
- [app/src/main/java/com/gabby/studiowebwrapper/util/DescriptorUtils.kt](app/src/main/java/com/gabby/studiowebwrapper/util/DescriptorUtils.kt)
- [app/src/main/java/com/gabby/studiowebwrapper/util/MaterialClassifier.kt](app/src/main/java/com/gabby/studiowebwrapper/util/MaterialClassifier.kt)
- [app/src/main/java/com/gabby/studiowebwrapper/util/Grader.kt](app/src/main/java/com/gabby/studiowebwrapper/util/Grader.kt)

What they do:

- VanillaVisionPipeline orchestrates the complete local flow
- YoloInference loads TorchScript model and parses detections
- ImageUtils handles bitmap/data URI conversion plus quality analysis
- DescriptorUtils + Grader compute and compare local features
- MaterialClassifier estimates metallic class cues (gold/silver)

### 4) Data and backend integration

- [app/src/main/java/com/gabby/studiowebwrapper/data/AppDatabase.kt](app/src/main/java/com/gabby/studiowebwrapper/data/AppDatabase.kt)
- [app/src/main/java/com/gabby/studiowebwrapper/data/ReportDao.kt](app/src/main/java/com/gabby/studiowebwrapper/data/ReportDao.kt)
- [app/src/main/java/com/gabby/studiowebwrapper/data/HistoryDao.kt](app/src/main/java/com/gabby/studiowebwrapper/data/HistoryDao.kt)
- [app/src/main/java/com/gabby/studiowebwrapper/data/NativeRepository.kt](app/src/main/java/com/gabby/studiowebwrapper/data/NativeRepository.kt)

What they do:

- Room database stores reports/history locally
- DAO layer provides retrieval/filter/delete operations
- NativeRepository handles auth, remote grading calls, and sync helpers

## Code Explanations by Flow

### Upload and grading trigger

In [app/src/main/java/com/gabby/studiowebwrapper/ui/UploadFragment.kt](app/src/main/java/com/gabby/studiowebwrapper/ui/UploadFragment.kt), the fragment:

1. Collects an image from camera/gallery
2. Decodes and caches bitmap/data URI
3. Runs quality checks and local pipeline
4. Builds final grading request/result
5. Navigates to result screen

Conceptually:

```kotlin
onGradeButtonClick() {
	validateInputImage()
	quality = analyzeImageQuality(bitmap)
	if (quality.hasBlockingIssues()) return

	local = vanillaPipeline.analyze(bitmap)
	if (local.detections.isEmpty()) return

	result = combineLocalSignalsAndBackendResponse(local)
	saveToHistory(result)
	navigateToGradeResult(result)
}
```

### Detection and parsing

In [app/src/main/java/com/gabby/studiowebwrapper/util/YoloInference.kt](app/src/main/java/com/gabby/studiowebwrapper/util/YoloInference.kt), the detector:

1. Copies model asset to app files dir
2. Loads TorchScript module
3. Converts bitmap to normalized tensor
4. Executes forward pass
5. Parses output candidates and applies NMS-style filtering

This keeps runtime inference local and avoids a network dependency for the detection stage.

### Pipeline orchestration

In [app/src/main/java/com/gabby/studiowebwrapper/util/VanillaVisionPipeline.kt](app/src/main/java/com/gabby/studiowebwrapper/util/VanillaVisionPipeline.kt), the orchestrator handles:

- model selection/fallback
- center-window gating for usable detections
- ROI extraction and laid-down refinement
- descriptor extraction and optional reference matching
- status reporting for UI diagnostics

### Quality gate

In [app/src/main/java/com/gabby/studiowebwrapper/util/ImageUtils.kt](app/src/main/java/com/gabby/studiowebwrapper/util/ImageUtils.kt), image quality checks compute:

- brightness mean
- contrast spread
- Laplacian-based sharpness variance
- blocking issues and warnings

This prevents low-quality images from polluting grading outputs.

### Result presentation and explanation

In [app/src/main/java/com/gabby/studiowebwrapper/ui/GradeResultFragment.kt](app/src/main/java/com/gabby/studiowebwrapper/ui/GradeResultFragment.kt), the app:

- clamps and combines component scores
- maps score bands to user-readable labels
- renders overlays and detailed insights
- keeps messaging explicit that this is a visual estimate, not legal appraisal

## Models and Assets

YOLO TorchScript assets are in:

- [app/src/main/assets/yolov8n_ts.pt](app/src/main/assets/yolov8n_ts.pt)
- [app/src/main/assets/yolov8s_ts.pt](app/src/main/assets/yolov8s_ts.pt)

Support docs:

- [README_CORE_LOGIC.md](README_CORE_LOGIC.md)
- [docs/MATERIAL_CLASSIFICATION.md](docs/MATERIAL_CLASSIFICATION.md)

## Build and Run

### Android Studio

1. Open the repository folder
2. Sync Gradle
3. Run app module on emulator/device

### From terminal

```bash
./gradlew assembleDebug
./gradlew installDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

## Important Notes

- This app provides AI-assisted visual grading and similarity cues.
- Results are not a certified authenticity report.
- For high-value transactions, use professional gemstone/gold testing.
