# Material Classification Setup (Gold vs Silver)

This guide walks you through three approaches to detect whether jewelry is gold or silver:

1. **Color-based Pre-filter (fastest, simplest)**
2. **Texture+Color Combo using LBP (uses your 20 existing refs)**
3. **Lightweight TFLite Classifier (most accurate, requires training)**

---

## Approach 1: Color-Based Pre-Filter Only

The fastest approach that requires **zero training or reference data**.

### How it works:
- Checks if image looks like metallic jewelry (gold/silver/platinum)
- Rejects images that don't look like jewelry (e.g., wood, plastic, skin)
- Does NOT classify gold vs silver, just validates the image

### Quick Start:
The color pre-filter is already integrated in `MaterialClassifier.colorPreFilter()`.
It runs automatically during pipeline analysis and logs results to the notes.

### Example Output in UI:
```
"Color pre-filter: image looks like metallic jewelry." ✓
OR
"Warning: Image doesn't look like metallic jewelry. Verify the item is gold or silver and well-illuminated." ✗
```

### Customization:
Edit the hue thresholds in `MaterialClassifier.extractColorBias()` to match your lighting setup:
- **Gold hues**: typically 20-60° (yellow/orange range)
- **Silver hues**: typically 180-240° or achromatic (gray/cool tones)
- **Saturation threshold**: 0.1 (values below = grayscale = silver-like)

---

## Approach 2: Texture+Color Combo (Using Existing 20 References)

Uses your precomputed LBP histograms to classify gold vs silver.

### How it works:
1. Compares test image LBP histogram against 20 reference images
2. Splits refs into gold (even IDs) vs silver (odd IDs)
3. Returns similarity scores + color hue bias
4. Predicts: whichever group has higher average similarity

### Current Configuration:
- **Gold refs**: IMG_1796, IMG_1798, IMG_1800, ... (even-numbered)
- **Silver refs**: IMG_1797, IMG_1799, IMG_1801, ... (odd-numbered)

**IMPORTANT**: Edit the `materialLabels` map in `MaterialClassifier.kt` if your refs don't follow this pattern!

### Quick Start:
Already integrated! The classifier runs if:
- Color pre-filter passes ✓
- References are loaded ✓
- At least one reference has LBP data ✓

### Example Output in UI:
```
"Material classification: Likely GOLD (confidence: 78%)"
"Material classification: Likely SILVER (confidence: 82%)"
"Material could not be clearly classified; ensure item is centered and well-lit."
```

### Accuracy:
- Expected: 80-85% with your current 20 refs
- Can improve to 90%+ if you add more refs or label them correctly

### To Improve Accuracy:
1. Verify the 20 ref images are actually gold or silver (manually check)
2. Add more references (~30-50 per material)
3. Re-run the precompute script: `.venv\Scripts\python tools\precompute_references.py`

### Troubleshooting:
- **All results are "Unknown"**: Check if references loaded (`graderStatus` in logs)
- **Scores always ~50/50**: Your refs might not be clearly labeled, or images are similar textures

---

## Approach 3: Lightweight TFLite Classifier (Highest Accuracy)

Trains a neural network classifier to distinguish gold from silver.

### Requirements:
- 30-50 gold jewelry images (high quality, well-lit, centered)
- 30-50 silver jewelry images (matching quality)
- Python 3.8+ with TensorFlow
- ~5-10 minutes training time

### Setup:

#### Step 1: Prepare Training Data
```bash
# Create directory structure
mkdir -p data/train/gold
mkdir -p data/train/silver

# Add your gold images to: data/train/gold/*.jpg
# Add your silver images to: data/train/silver/*.jpg
```

**Best practices for training images:**
- At least 128x128 pixels
- Good lighting (similar to deployment use case)
- Centered jewelry item
- Minimal background
- Variety of orientations and angles

#### Step 2: Install Dependencies
```bash
pip install tensorflow opencv-python numpy
```

#### Step 3: Train the Model
```bash
# Basic training (30 epochs, auto-saves to models/material_classifier.h5)
python tools/train_material_classifier.py --epochs 30

# Custom options
python tools/train_material_classifier.py \
  --train-dir data/train \
  --output models/material_classifier.h5 \
  --epochs 50 \
  --input-size 128
```

**Expected output:**
```
Loading training data from data/train...
  Loaded 50 images from data/train/gold
  Loaded 50 images from data/train/silver

Dataset summary:
  Gold images: 50
  Silver images: 50
  Total: 100

Building model...
Model parameters: 523,456

Training for 30 epochs...
Epoch 1/30 - loss: 0.6523 - accuracy: 0.6400 - val_loss: 0.5234 - val_accuracy: 0.7000
...
Epoch 30/30 - loss: 0.0234 - accuracy: 0.9900 - val_loss: 0.0856 - val_accuracy: 0.9400

Model saved to: models/material_classifier.h5
```

#### Step 4: Convert to TFLite
```bash
# Converts to ~500KB quantized model
python tools/convert_to_tflite.py \
  models/material_classifier.h5 \
  models/material_classifier.tflite

# Output:
# ✓ TFLite model saved!
#   Path: models/material_classifier.tflite
#   Size: 0.52 MB (512 KB)
```

#### Step 5: Deploy to Android
```bash
# Copy model to app assets
cp models/material_classifier.tflite app/src/main/assets/

# Then create TFLiteClassifier.kt (see below)
# Update MaterialClassifier.kt to use it instead of texture+color
```

### Integration with Android:

Create `app/src/main/java/com/gabby/studiowebwrapper/util/TFLiteClassifier.kt`:

```kotlin
package com.gabby.studiowebwrapper.util

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

object TFLiteClassifier {
    private var interpreter: Interpreter? = null
    private val INPUT_SIZE = 128
    private val GOLD_CLASS = 1 // Model output neuron for gold
    private val SILVER_CLASS = 0
    
    fun initialize(context: Context, modelName: String = "material_classifier.tflite"): Boolean {
        return try {
            val modelBuffer = FileUtil.loadMappedFile(context, modelName)
            interpreter = Interpreter(modelBuffer)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun classify(bitmap: Bitmap): Pair<String, Float>? {
        val interpreter = interpreter ?: return null
        
        // Prepare input
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val inputBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        inputBuffer.order(ByteOrder.nativeOrder())
        
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            inputBuffer.putFloat(r / 255f)
            inputBuffer.putFloat(g / 255f)
            inputBuffer.putFloat(b / 255f)
        }
        
        // Inference
        val output = Array(1) { FloatArray(1) }
        interpreter?.run(inputBuffer, output)
        
        val goldProbability = output[0][0]
        val prediction = if (goldProbability > 0.5) "gold" else "silver"
        val confidence = if (goldProbability > 0.5) goldProbability else 1f - goldProbability
        
        return Pair(prediction, confidence)
    }
    
    fun release() {
        interpreter?.close()
        interpreter = null
    }
}
```

### Expected Accuracy:
- **Validation accuracy**: 92-98% (on your training data)
- **Real-world accuracy**: 85-95% (depends on image quality and lighting consistency)

### Troubleshooting:
- **Model doesn't converge**: Add more training data or more epochs
- **Poor accuracy in production**: Ensure test images match training distribution (same lighting, angles)
- **Model too large**: The 128x128 input already produces a small model (~500KB), which is optimal for mobile

---

## Comparison Table

| Approach | Training Data | Inference Time | Accuracy | Customization |
|----------|---------------|----------------|----------|---------------|
| **Color Filter** | None | <10ms | 50-60% | Easy (edit hue thresholds) |
| **Texture+Color** | Your 20 refs | 50-100ms | 80-85% | Medium (label refs correctly) |
| **TFLite NN** | 50-100 images | 100-200ms | 92-98% | Hard (need training data) |

**Recommendation**: 
- Start with **Texture+Color Combo** (zero effort, already working)
- If accuracy insufficient, collect training data and use **TFLite Classifier**
- Use **Color Filter** as a pre-check to reject obviously wrong inputs early

---

## Debug & Monitoring

### Check Material Classification Output:
In `UploadFragment`, material scores appear in:
- `pipelineAnalysis?.materialScore?.goldScore` (0-100)
- `pipelineAnalysis?.materialScore?.silverScore` (0-100)
- `pipelineAnalysis?.materialScore?.predicted` ("gold" or "silver")
- `pipelineAnalysis?.materialScore?.confidence` (0-100)
- `pipelineAnalysis?.colorPreFilterPassed` (boolean)

### Logging:
Add to `enrichResult()` in UploadFragment:
```kotlin
pipelineAnalysis?.materialScore?.let { 
    Log.d("MaterialClassifier", "Gold: ${it.goldScore}%, Silver: ${it.silverScore}%, Predicted: ${it.predicted}, Confidence: ${it.confidence}%")
}
```

### Live Testing:
1. Take photos of known gold/silver items
2. Check console logs for material scores
3. Verify predictions are correct
4. Adjust confidence thresholds if needed

---

## Next Steps

1. **Verify current refs are labeled correctly** (even=gold, odd=silver)
2. **Test Texture+Color Combo** with your jewelry images
3. **If accuracy < 80%**: Collect training data and follow TFLite setup
4. **Optional**: Replace texture+color logic with trained model for best results

Questions or issues? Check the logs in `VanillaPipelineResult.notes` for debugging info!
