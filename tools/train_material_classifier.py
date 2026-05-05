#!/usr/bin/env python3
"""
Lightweight Material Classifier Training Template
Trains a simple neural network to classify jewelry material (gold vs silver).

Usage:
1. Prepare training data: Create two folders
   - data/train/gold/*.jpg (50+ gold jewelry images)
   - data/train/silver/*.jpg (50+ silver jewelry images)

2. Install dependencies:
   pip install tensorflow opencv-python numpy

3. Run training:
   python tools/train_material_classifier.py --epochs 30 --output models/material_classifier.h5

4. Convert to TFLite for Android:
   python tools/convert_to_tflite.py models/material_classifier.h5 models/material_classifier.tflite

5. Copy to Android assets:
   cp models/material_classifier.tflite app/src/main/assets/
"""

import os
import argparse
import numpy as np
from pathlib import Path
from typing import Tuple, List

# Optional TensorFlow import (install with: pip install tensorflow)
try:
    import tensorflow as tf
    from tensorflow.keras import layers, models, callbacks
    TF_AVAILABLE = True
except ImportError:
    TF_AVAILABLE = False
    print("WARNING: TensorFlow not installed. Install with: pip install tensorflow")

import cv2


class MaterialClassifierTrainer:
    def __init__(self, input_size: int = 128, batch_size: int = 32):
        """
        Initialize trainer.
        
        Args:
            input_size: Square image size (128x128 by default for lightweight)
            batch_size: Training batch size
        """
        self.input_size = input_size
        self.batch_size = batch_size
        self.model = None

    def load_images_from_folder(self, folder_path: str, label: int, max_images: int = 500) -> Tuple[np.ndarray, np.ndarray]:
        """Load images from a folder and return X, y arrays."""
        images = []
        labels = []
        count = 0
        
        if not os.path.exists(folder_path):
            print(f"Folder not found: {folder_path}")
            return np.array([]), np.array([])
        
        for filename in sorted(os.listdir(folder_path)):
            if count >= max_images:
                break
            if filename.lower().endswith(('.jpg', '.jpeg', '.png')):
                try:
                    img_path = os.path.join(folder_path, filename)
                    img = cv2.imread(img_path)
                    if img is None:
                        continue
                    
                    # Resize to input_size
                    img = cv2.resize(img, (self.input_size, self.input_size))
                    # Convert BGR to RGB
                    img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
                    # Normalize to [0, 1]
                    img = img.astype(np.float32) / 255.0
                    
                    images.append(img)
                    labels.append(label)
                    count += 1
                    
                    if count % 50 == 0:
                        print(f"  Loaded {count} images from {folder_path}")
                except Exception as e:
                    print(f"  Error loading {filename}: {e}")
                    continue
        
        return np.array(images), np.array(labels)

    def build_model(self) -> models.Sequential:
        """
        Build lightweight CNN model.
        Architecture optimized for mobile deployment (~500K parameters).
        """
        model = models.Sequential([
            # Input layer
            layers.InputLayer(input_shape=(self.input_size, self.input_size, 3)),
            
            # Data augmentation
            layers.RandomFlip("horizontal"),
            layers.RandomRotation(0.1),
            layers.RandomZoom(0.1),
            
            # Block 1
            layers.Conv2D(16, 3, padding='same', activation='relu'),
            layers.MaxPooling2D(2),
            layers.Dropout(0.2),
            
            # Block 2
            layers.Conv2D(32, 3, padding='same', activation='relu'),
            layers.MaxPooling2D(2),
            layers.Dropout(0.2),
            
            # Block 3
            layers.Conv2D(64, 3, padding='same', activation='relu'),
            layers.MaxPooling2D(2),
            layers.Dropout(0.2),
            
            # Block 4
            layers.Conv2D(128, 3, padding='same', activation='relu'),
            layers.GlobalAveragePooling2D(),
            layers.Dropout(0.3),
            
            # Output layer (binary classification)
            layers.Dense(64, activation='relu'),
            layers.Dropout(0.3),
            layers.Dense(1, activation='sigmoid')  # Binary: 0=silver, 1=gold
        ])
        
        return model

    def train(self, train_data_dir: str, val_split: float = 0.2, epochs: int = 30, output_path: str = None):
        """
        Train the material classifier.
        
        Args:
            train_data_dir: Path containing gold/ and silver/ subdirectories
            val_split: Validation split ratio
            epochs: Number of training epochs
            output_path: Where to save trained model
        """
        if not TF_AVAILABLE:
            print("ERROR: TensorFlow is required for training. Install with: pip install tensorflow")
            return None
        
        print(f"Loading training data from {train_data_dir}...")
        
        # Load data
        gold_dir = os.path.join(train_data_dir, 'gold')
        silver_dir = os.path.join(train_data_dir, 'silver')
        
        X_gold, y_gold = self.load_images_from_folder(gold_dir, label=1)  # 1 = gold
        X_silver, y_silver = self.load_images_from_folder(silver_dir, label=0)  # 0 = silver
        
        if len(X_gold) == 0 or len(X_silver) == 0:
            print("ERROR: Could not load images. Ensure you have:")
            print(f"  {gold_dir}/*.jpg")
            print(f"  {silver_dir}/*.jpg")
            return None
        
        # Combine data
        X = np.vstack([X_gold, X_silver])
        y = np.hstack([y_gold, y_silver])
        
        print(f"\nDataset summary:")
        print(f"  Gold images: {len(X_gold)}")
        print(f"  Silver images: {len(X_silver)}")
        print(f"  Total: {len(X)}")
        
        # Build model
        print("\nBuilding model...")
        self.model = self.build_model()
        self.model.compile(
            optimizer='adam',
            loss='binary_crossentropy',
            metrics=['accuracy', tf.keras.metrics.AUC()]
        )
        
        print(f"Model parameters: {self.model.count_params():,}")
        
        # Train
        print(f"\nTraining for {epochs} epochs...")
        history = self.model.fit(
            X, y,
            batch_size=self.batch_size,
            epochs=epochs,
            validation_split=val_split,
            callbacks=[
                callbacks.EarlyStopping(monitor='val_loss', patience=5, restore_best_weights=True),
                callbacks.ReduceLROnPlateau(monitor='val_loss', factor=0.5, patience=3, min_lr=1e-6)
            ],
            verbose=1
        )
        
        # Save model
        if output_path:
            os.makedirs(os.path.dirname(output_path), exist_ok=True)
            self.model.save(output_path)
            print(f"\nModel saved to: {output_path}")
            
            # Print conversion instructions
            print("\nTo convert to TFLite for Android:")
            print(f"  python tools/convert_to_tflite.py {output_path} {output_path.replace('.h5', '.tflite')}")
        
        return history


def convert_to_tflite(h5_model_path: str, output_path: str):
    """Convert Keras model to TFLite format for Android deployment."""
    if not TF_AVAILABLE:
        print("ERROR: TensorFlow is required for conversion. Install with: pip install tensorflow")
        return
    
    print(f"Loading model from {h5_model_path}...")
    model = tf.keras.models.load_model(h5_model_path)
    
    print("Converting to TFLite...")
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    converter.target_spec.supported_ops = [
        tf.lite.OpsSet.TFLITE_BUILTINS,
        tf.lite.OpsSet.SELECT_TF_OPS
    ]
    
    tflite_model = converter.convert()
    
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    with open(output_path, 'wb') as f:
        f.write(tflite_model)
    
    print(f"TFLite model saved to: {output_path}")
    print(f"Model size: {os.path.getsize(output_path) / 1024 / 1024:.2f} MB")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Train lightweight material classifier')
    parser.add_argument('--train-dir', type=str, default='data/train',
                       help='Path to training data (should contain gold/ and silver/ subdirs)')
    parser.add_argument('--output', type=str, default='models/material_classifier.h5',
                       help='Output model path')
    parser.add_argument('--epochs', type=int, default=30,
                       help='Number of training epochs')
    parser.add_argument('--batch-size', type=int, default=32,
                       help='Batch size')
    parser.add_argument('--input-size', type=int, default=128,
                       help='Input image size (square)')
    parser.add_argument('--convert-tflite', action='store_true',
                       help='Also convert to TFLite after training')
    
    args = parser.parse_args()
    
    # Train
    trainer = MaterialClassifierTrainer(input_size=args.input_size, batch_size=args.batch_size)
    trainer.train(
        train_data_dir=args.train_dir,
        epochs=args.epochs,
        output_path=args.output
    )
    
    # Convert to TFLite if requested
    if args.convert_tflite and os.path.exists(args.output):
        tflite_path = args.output.replace('.h5', '.tflite')
        convert_to_tflite(args.output, tflite_path)
