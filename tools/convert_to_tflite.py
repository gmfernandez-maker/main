#!/usr/bin/env python3
"""
Convert Keras model to TensorFlow Lite format for Android deployment.

Usage:
    python tools/convert_to_tflite.py models/material_classifier.h5 models/material_classifier.tflite
"""

import os
import sys
import argparse

try:
    import tensorflow as tf
except ImportError:
    print("ERROR: TensorFlow is required. Install with: pip install tensorflow")
    sys.exit(1)


def convert_to_tflite(keras_model_path: str, output_tflite_path: str, quantize: bool = True):
    """
    Convert Keras/SavedModel to TFLite format.
    
    Args:
        keras_model_path: Path to .h5 or SavedModel directory
        output_tflite_path: Output .tflite file path
        quantize: Whether to apply quantization (smaller but slightly less accurate)
    """
    print(f"Loading model from: {keras_model_path}")
    
    # Load model
    if keras_model_path.endswith('.h5'):
        model = tf.keras.models.load_model(keras_model_path)
    else:
        model = tf.keras.models.load_model(keras_model_path)
    
    print(f"Model loaded. Parameters: {model.count_params():,}")
    print(f"Input shape: {model.input_shape}")
    print(f"Output shape: {model.output_shape}")
    
    # Create converter
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS
    ]
    
    if quantize:
        print("Applying quantization (dynamic range)...")
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
    
    # Convert
    print("Converting to TFLite...")
    tflite_model = converter.convert()
    
    # Save
    os.makedirs(os.path.dirname(output_tflite_path), exist_ok=True)
    with open(output_tflite_path, 'wb') as f:
        f.write(tflite_model)
    
    file_size_kb = os.path.getsize(output_tflite_path) / 1024
    file_size_mb = file_size_kb / 1024
    
    print(f"\n✓ TFLite model saved!")
    print(f"  Path: {output_tflite_path}")
    print(f"  Size: {file_size_mb:.2f} MB ({file_size_kb:.0f} KB)")
    print(f"\nNext steps:")
    print(f"  1. Copy to Android assets:")
    print(f"     cp {output_tflite_path} app/src/main/assets/")
    print(f"  2. Update MaterialClassifier.kt to load and use this model")
    print(f"  3. Rebuild Android app")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Convert Keras model to TFLite')
    parser.add_argument('input', type=str, help='Input model path (.h5 or SavedModel)')
    parser.add_argument('output', type=str, help='Output TFLite path (.tflite)')
    parser.add_argument('--no-quantize', action='store_true', help='Skip quantization')
    
    args = parser.parse_args()
    
    if not os.path.exists(args.input):
        print(f"ERROR: Input model not found: {args.input}")
        sys.exit(1)
    
    convert_to_tflite(args.input, args.output, quantize=not args.no_quantize)
