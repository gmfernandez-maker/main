# Jewelry Grading App - Core Logic & Architecture

## Overview

This Android application uses YOLOv8 object detection, local feature extraction (LBP/ORB), and backend ML grading to automatically classify and grade jewelry items. Users capture images, the app validates quality and detects jewelry, extracts visual features locally, and sends results to a backend for material/purity/gemstone analysis.

---

## Program Flow

```
1. User captures/selects image
   ↓
2. Quality Analysis (brightness, blur, resolution)
   ↓
3. YOLO Detection (local inference)
   ↓
4. Center-zone validation & feature extraction (LBP/ORB)
   ↓
5. Backend Grading Request (material/purity/gemstones)
   ↓
6. Result enrichment & display
```

---

## Core Components & File References

### 1. **Entry Point & Navigation**
- **File**: [`MainActivity.kt`](app/src/main/java/com/gabby/studiowebwrapper/MainActivity.kt)
- **Role**: Fragment navigation, auth state management, bottom navigation
- **Key Methods**:
  - `onCreate()` — Initialize navigation, check login status
  - `navigateToUpload()` — Route to image capture/selection
  - `showGradeResult()` — Display grading results

---

### 2. **Vision Pipeline (Local ML)**
- **File**: [`VanillaVisionPipeline.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/VanillaVisionPipeline.kt)
- **Role**: Orchestrates local detection + feature extraction
- **Key Methods**:

#### `analyze(bitmap: Bitmap): VanillaPipelineResult`
Main entry point for local processing:

```
Step 1: detect(bitmap) 
   → Runs YOLO model → parseDetections() → applies confidence threshold (0.40/0.45)
   → center-zone filter (must be in middle 55% of frame)
   
Step 2: pickRoi(bitmap, detections)
   → Extracts bounding box of highest-confidence detection
   
Step 3: isLikelyLaidDown(bitmap, detections)
   → Checks if jewelry is horizontal vs. vertical orientation
   → Adjusts ROI crop if needed (0.86f ratio)
   
Step 4: Material Classification
   → ColorPreFilter() checks if image looks like metallic jewelry
   → MaterialClassifier.classify() detects gold vs. silver
   
Step 5: Feature Extraction
   → LBP (Local Binary Pattern) histogram
   → ORB (Oriented FAST and Rotated BRIEF) descriptors
   
Step 6: Reference Matching
   → Grade against stored reference database
   → Returns top matching reference with score
   
Return: VanillaPipelineResult with all results
```

- **Related Files**:
  - [`YoloInference.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/YoloInference.kt) — YOLO model inference
  - [`MaterialClassifier.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/MaterialClassifier.kt) — Gold/silver detection
  - [`DescriptorUtils.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/DescriptorUtils.kt) — LBP/ORB extraction
  - [`Grader.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/Grader.kt) — Reference matching

---

### 3. **YOLO Detection**
- **File**: [`YoloInference.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/YoloInference.kt)
- **Model Assets**:
  - [`yolov8n_ts.pt`](app/src/main/assets/yolov8n_ts.pt) — Nano model (12.4 MB)
  - [`yolov8s_ts.pt`](app/src/main/assets/yolov8s_ts.pt) — Small model (44.9 MB)
- **Key Methods**:

#### `run(bitmap: Bitmap): FloatArray`
Executes TorchScript model inference:
- Resizes image to 640×640
- Converts to NCHW tensor (RGB normalized)
- Returns raw model output

#### `parseDetections(output: FloatArray, confThreshold: Float): List<Detection>`
Parses YOLOv8 output (shape: [1, 8, 8400]):

```
1. Try channel-major layout: [4+nc, numPredictions]
2. Try row-major layout: [numPredictions, 4+nc]
3. Score both parsing strategies
4. Apply Non-Max Suppression (IOU threshold 0.45)
5. Validate detection set (score range, box bounds, count)
6. Return filtered detections or empty list
```

- **Key Thresholds**:
  - Nano model confidence: 0.40
  - Small model confidence: 0.45
  - Box area ratio: 0.03% to 95% of image
  - Min score for strong detection: 0.45
  - Max detections: 10 per image

---

### 4. **Center-Zone Enforcement**
- **File**: [`VanillaVisionPipeline.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/VanillaVisionPipeline.kt#L331) (`isDetectionCentered()`)
- **Purpose**: Force users to center items in frame
- **Logic**: Detection center must fall within `centerKeepRatio` (0.55) window of image
  - Margin: (1 - 0.55) / 2 = 22.5% on each side
  - Detections outside this zone are filtered out

---

### 5. **Image Quality Analysis**
- **File**: [`ImageUtils.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/ImageUtils.kt)
- **Key Method**: `analyzeImageQuality(bitmap: Bitmap): ImageQualityReport`

**Blocking Issues** (image rejected):
- Resolution < 240px on shortest side
- Laplacian sharpness < 8.0 (too blurry)
- Brightness < 18 or > 220 luma (too dark/bright)

**Warnings** (user advised to retake):
- Modest resolution (240-360px)
- Slight blur (sharpness 8-40)
- Dim lighting (luma 45-80)
- Low contrast (std dev < 20)
- Narrow framing (aspect ratio > 2.4)

---

### 6. **Image Upload & Grading**
- **File**: [`UploadFragment.kt`](app/src/main/java/com/gabby/studiowebwrapper/ui/UploadFragment.kt)
- **Key Method**: `submitForGrading()`

```kotlin
// 1. Quality check → reject if blocking issues
// 2. Local vision pipeline → reject if no detection
// 3. Stamp OCR detection (optional)
// 4. Backend grade request
// 5. Enrich results with local scores
// 6. Final score gate (≥30 to save)
// 7. Display results
```

- **Related Files**:
  - [`NativeRepository.kt`](app/src/main/java/com/gabby/studiowebwrapper/data/NativeRepository.kt) — Backend API calls
  - [`StampOcr.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/StampOcr.kt) — OCR detection

---

### 7. **Backend Integration**
- **File**: [`NativeRepository.kt`](app/src/main/java/com/gabby/studiowebwrapper/data/NativeRepository.kt)
- **Role**: HTTP client for Supabase backend + auth
- **Key Methods**:
  - `grade(request: GradeRequest): Result<GradeResponse>` — Submit image for grading
  - `signup()` / `login()` — User authentication
  - `syncHistoryFromSupabase()` — Fetch grading history

**Backend Response Structure**:
```json
{
  "material": "Gold",
  "purity": "18K",
  "stampText": "750",
  "stampDetected": true,
  "gemstones": [{"type": "Diamond", "cut": "Round", "clarity": "VS1"}],
  "qualityScore": 85,
  "analysis": "...",
  "similarProducts": [...]
}
```

---

### 8. **Result Display & Enrichment**
- **File**: [`GradeResultFragment.kt`](app/src/main/java/com/gabby/studiowebwrapper/ui/GradeResultFragment.kt)
- **Role**: Display grading results with detection overlay
- **Enhancements**:
  - YOLO detection overlay (bounding boxes, scores)
  - Material classification results (gold/silver %)
  - Stamp OCR results
  - Similarity score breakdown (YOLO, LBP, ORB)
  - Actionable rescan suggestions

---

## Data Models

### Detection (from YOLO)
**File**: [`YoloInference.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/YoloInference.kt#L15)
```kotlin
data class Detection(
    val x1: Float, val y1: Float,      // Top-left corner (pixels)
    val x2: Float, val y2: Float,      // Bottom-right corner (pixels)
    val score: Float,                   // Confidence [0, 1]
    val classId: Int                    // 0=Bracelet, 1=Earring, 2=Necklace, 3=Ring
)
```

### Pipeline Result
**File**: [`VanillaVisionPipeline.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/VanillaVisionPipeline.kt#L8)
```kotlin
data class VanillaPipelineResult(
    val detections: List<Detection>,                    // Raw YOLO output
    val topScore: ScoreResult?,                         // Best reference match
    val queryLbpBins: Int,                              // LBP histogram size
    val queryOrbRows: Int,                              // ORB descriptor rows
    val materialScore: MaterialClassifier.MaterialScore?, // Gold/silver %
    val yoloAvailable: Boolean,
    val yoloStatus: String,
    val notes: List<String>                             // Debug notes
)
```

### Grading Result
**File**: [`Models.kt`](app/src/main/java/com/gabby/studiowebwrapper/model/Models.kt)
```kotlin
data class SuggestMetadataOutput(
    val material: String,                               // "Gold", "Silver", etc.
    val purity: String?,                                // "18K", "925", etc.
    val stampText: String?,                             // Detected hallmark
    val stampConfidence: Int,                           // 0-100
    val gemstones: List<Gemstone>?,
    val qualityScore: Int,                              // Image quality (0-100)
    val yoloScore: Int,                                 // Detection confidence (0-100)
    val lbpScore: Int,                                  // Reference LBP match (0-100)
    val orbScore: Int,                                  // Reference ORB match (0-100)
    val totalComputedScore: Int,                        // Average of YOLO/LBP/ORB
    val yoloModelUsed: String?,                         // "YOLOv8n" or "YOLOv8s"
    val yoloDetections: List<YoloDetectionOutput>?,     // For visualization
    val captureWarnings: List<String>?,
    val rescanSuggestions: List<String>?
)
```

---

## Model Selection

**File**: [`ModelPreferenceManager.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/ModelPreferenceManager.kt)

Users can switch between models via settings:
- **YOLOv8n (Nano)**: 12.4 MB, 0.40 confidence threshold
- **YOLOv8s (Small)**: 44.9 MB, 0.45 confidence threshold

Both are TorchScript exports (.pt files) for Android inference.

---

## Critical Thresholds & Gates

| Gate | Threshold | File | Impact |
|------|-----------|------|--------|
| Image resolution | ≥240px min | [`ImageUtils.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/ImageUtils.kt#L65) | Blocks low-res captures |
| Image brightness | 18-220 luma | [`ImageUtils.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/ImageUtils.kt#L85) | Blocks too dark/bright |
| Sharpness (Laplacian) | ≥8.0 variance | [`ImageUtils.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/ImageUtils.kt#L75) | Blocks motion blur |
| YOLO nano confidence | 0.40 | [`VanillaVisionPipeline.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/VanillaVisionPipeline.kt#L301) | Min detection strength |
| YOLO small confidence | 0.45 | [`VanillaVisionPipeline.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/VanillaVisionPipeline.kt#L302) | Min detection strength |
| Center zone | Middle 55% of frame | [`VanillaVisionPipeline.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/VanillaVisionPipeline.kt#L328) | Forces centering |
| Box area ratio | 0.03% - 95% of image | [`YoloInference.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/YoloInference.kt#L385) | Rejects tiny/huge boxes |
| Detection count | ≤10 total | [`YoloInference.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/YoloInference.kt#L374) | Rejects cluttered scenes |
| Final composite score | ≥30 | [`UploadFragment.kt`](app/src/main/java/com/gabby/studiowebwrapper/ui/UploadFragment.kt#L233) | Rejects low confidence |

---

## Key Utility Files

| File | Purpose |
|------|---------|
| [`DescriptorUtils.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/DescriptorUtils.kt) | LBP & ORB feature extraction (OpenCV native) |
| [`Grader.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/Grader.kt) | Reference database matching |
| [`ReferenceManager.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/ReferenceManager.kt) | Load reference JSON files from assets |
| [`YoloLabels.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/YoloLabels.kt) | Class names mapping (Bracelet, Earring, etc.) |
| [`MaterialClassifier.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/MaterialClassifier.kt) | Texture-based gold/silver classification |
| [`StampOcr.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/StampOcr.kt) | Hallmark text detection (OpenCV) |

---

## Database & Persistence

**File**: [`AppDatabase.kt`](app/src/main/java/com/gabby/studiowebwrapper/data/AppDatabase.kt)

Local SQLite tables:
- **Report** — Grading history (scores, metadata, timestamps)
- **HistoryEntry** — User submission log

---

## Building & Running

### Prerequisites
- Android SDK 30+
- Gradle 8.13
- PyTorch Mobile runtime (included in gradle dependencies)
- OpenCV 4.5.5

### Build Debug APK
```bash
./gradlew assembleDebug
```

### Install to Device
```bash
./gradlew installDebug
```

### Model Format
Both models are PyTorch TorchScript exports (.pt files) for PyTorch Mobile inference on Android. Original training likely used YOLOv8 with Ultralytics library.

---

## Feature Extraction Pipeline

**Reference Matching** uses two local descriptors:

### 1. LBP (Local Binary Pattern)
- **Purpose**: Texture fingerprint
- **Method**: 8-neighbor LBP with 256 histogram bins
- **File**: [`DescriptorUtils.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/DescriptorUtils.kt)
- **Spatial**: 4×4 grid of LBP histograms for fine-grained matching

### 2. ORB (Oriented FAST and Rotated BRIEF)
- **Purpose**: Keypoint-based feature matching
- **Method**: OpenCV ORB detector (max 500 features)
- **File**: [`DescriptorUtils.kt`](app/src/main/java/com/gabby/studiowebwrapper/util/DescriptorUtils.kt)
- **Matching**: Brute-force descriptor distance

Both descriptors are stored in reference JSON files and compared with query image features.

---

## Debugging & Logs

**Debug Logs** are tagged with class names:
- `YoloInference` — YOLO parsing details (tensor shapes, scores)
- `VanillaVisionPipeline` — Detection counts, feature extraction status
- `Grader` — Reference matching scores
- `MaterialClassifier` — Gold/silver classification confidence

Use `logcat` to view:
```bash
adb logcat | grep -E "YoloInference|VanillaVisionPipeline|Grader"
```

---

## Performance Notes

| Component | Latency | Notes |
|-----------|---------|-------|
| YOLO inference | ~100-200ms | TorchScript on CPU |
| LBP extraction | ~50ms | Single-threaded |
| ORB extraction | ~100ms | OpenCV native |
| Reference matching | ~50-150ms | Depends on reference count |
| Total local pipeline | ~300-500ms | Parallelized where possible |

---

## Known Limitations & Future Work

1. **Backend Availability**: Grading requires internet connection (Supabase)
2. **Reference Database**: Limited to pre-loaded reference JSON files
3. **Orientation**: Works best with jewelry photographed from top-down
4. **Material**: Currently optimized for gold/silver; other metals need tuning
5. **Gemstone Detection**: Crude classification; requires backend ML for precision

---

## Contact & Support

For issues or feature requests, refer to:
- Model training logs: [`tools/train_material_classifier.py`](tools/train_material_classifier.py)
- Conversion scripts: [`tools/convert_to_tflite.py`](tools/convert_to_tflite.py)
- Dataset config: [`dataset/data.yaml`](dataset/data.yaml)
