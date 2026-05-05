#!/usr/bin/env python3
"""
Compute LBP histograms and ORB descriptors for image references.
Writes per-image JSON files in the assets/references folder matching
the schema consumed by ReferenceManager (name, lbp_hist, orb_desc).

Run from project root (where this script lives):
  .venv\Scripts\python tools/precompute_references.py

The script requires OpenCV and NumPy.
"""
import os
import json
import cv2
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REF_DIR = os.path.join(ROOT, 'app', 'src', 'main', 'assets', 'references')

def compute_lbp_hist(gray):
    # basic 8-neighbor LBP (0-255) histogram, ignore 1px border
    h, w = gray.shape
    if h < 3 or w < 3:
        return [0.0]*256
    lbp = np.zeros((h-2, w-2), dtype=np.uint8)
    neighbors = [(-1,-1), ( -1,0), (-1,1), (0,1), (1,1), (1,0), (1,-1), (0,-1)]
    center = gray[1:h-1, 1:w-1]
    for i, (dy, dx) in enumerate(neighbors):
        n = gray[1+dy:h-1+dy, 1+dx:w-1+dx]
        lbp |= ((n >= center) << (7-i)).astype(np.uint8)
    hist, _ = np.histogram(lbp.ravel(), bins=256, range=(0,255))
    hist = hist.astype(float)
    s = hist.sum()
    if s > 0:
        hist /= s
    return hist.tolist()

def compute_orb_descriptors(gray, max_features=500):
    orb = cv2.ORB_create(nfeatures=max_features)
    kps, desc = orb.detectAndCompute(gray, None)
    if desc is None:
        return []
    # desc is uint8 array (N x 32). Convert to nested list of ints.
    return desc.astype(int).tolist()

def main():
    if not os.path.isdir(REF_DIR):
        print('Reference folder not found:', REF_DIR)
        return

    files = sorted(os.listdir(REF_DIR))
    images = [f for f in files if f.lower().endswith(('.jpg', '.jpeg', '.png', '.bmp', '.webp'))]
    if not images:
        print('No reference images found in', REF_DIR)
        return

    for img in images:
        base = os.path.splitext(img)[0]
        img_path = os.path.join(REF_DIR, img)
        out_json = os.path.join(REF_DIR, f"{base}.json")
        try:
            bgr = cv2.imdecode(np.fromfile(img_path, dtype=np.uint8), cv2.IMREAD_COLOR)
            if bgr is None:
                print('Failed to read', img_path)
                continue
            gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)

            lbp_hist = compute_lbp_hist(gray)
            orb_desc = compute_orb_descriptors(gray)

            payload = {
                'name': base,
                'lbp_hist': lbp_hist,
                'orb_desc': orb_desc
            }

            with open(out_json, 'w', encoding='utf-8') as f:
                json.dump(payload, f, indent=2)

            print('Wrote', out_json, 'LBP bins =', len(lbp_hist), 'ORB rows =', len(orb_desc))
        except Exception as e:
            print('Error processing', img, e)

if __name__ == '__main__':
    main()
