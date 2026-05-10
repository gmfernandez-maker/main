#!/usr/bin/env python3
"""Evaluate the exported YOLO TorchScript models on a labeled dataset split.

This script loads the TorchScript models directly, parses their raw outputs,
and computes detection metrics from the dataset labels without relying on
Ultralytics' high-level validation wrapper.

Example:
    python tools/evaluate_yolo_torchscript.py --data dataset/data.yaml --split test
"""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np
import torch
import yaml
from PIL import Image
from ultralytics.utils.metrics import ConfusionMatrix, ap_per_class, box_iou


@dataclass
class ModelEvaluation:
    name: str
    weights: Path
    precision: float
    recall: float
    f1: float
    map50: float
    map50_95: float
    confusion_matrix: list[list[float]]


def resolve_weights(project_root: Path) -> list[tuple[str, Path]]:
    assets = project_root / "app" / "src" / "main" / "assets"
    candidates = [
        ("yolov8n", assets / "yolov8n_ts.pt"),
        ("yolov8s", assets / "yolov8s_ts.pt"),
    ]
    missing = [str(path) for _, path in candidates if not path.exists()]
    if missing:
        raise FileNotFoundError("Missing model weights: " + ", ".join(missing))
    return candidates


def mean(values: Iterable[float]) -> float:
    values = list(values)
    return sum(values) / len(values) if values else 0.0


def safe_float(value: object) -> float:
    try:
        return float(value)
    except Exception:
        return 0.0


def extract_scalar(metric_source: object, attr: str) -> float:
    if metric_source is None:
        return 0.0
    value = getattr(metric_source, attr, 0.0)
    if isinstance(value, (list, tuple)):
        return mean(safe_float(v) for v in value)
    return safe_float(value)


def load_data_config(data_path: Path) -> dict:
    with data_path.open("r", encoding="utf-8") as handle:
        return yaml.safe_load(handle)


def resolve_split_dir(project_root: Path, data_cfg: dict, split: str) -> Path:
    base = project_root / str(data_cfg["path"])
    split_key = "val" if split == "val" else split
    return (base / str(data_cfg[split_key])).resolve()


def image_paths_for_split(split_dir: Path) -> list[Path]:
    if not split_dir.exists():
        raise FileNotFoundError(f"Split directory not found: {split_dir}")
    return sorted(
        [
            *split_dir.rglob("*.jpg"),
            *split_dir.rglob("*.jpeg"),
            *split_dir.rglob("*.png"),
            *split_dir.rglob("*.bmp"),
        ]
    )


def corresponding_label_path(image_path: Path) -> Path:
    parts = list(image_path.parts)
    if "images" in parts:
        idx = parts.index("images")
        parts[idx] = "labels"
    label_path = Path(*parts).with_suffix(".txt")
    return label_path


def letterbox_params(image_size: tuple[int, int], input_size: int) -> tuple[float, float, float]:
    width, height = image_size
    ratio = min(input_size / float(width), input_size / float(height))
    new_width = int(round(width * ratio))
    new_height = int(round(height * ratio))
    pad_x = (input_size - new_width) / 2.0
    pad_y = (input_size - new_height) / 2.0
    return ratio, pad_x, pad_y


def load_labels(label_path: Path, image_size: tuple[int, int], input_size: int) -> np.ndarray:
    if not label_path.exists():
        return np.zeros((0, 5), dtype=np.float32)

    width, height = image_size
    ratio, pad_x, pad_y = letterbox_params(image_size, input_size)
    labels: list[list[float]] = []

    for line in label_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        cls, x_center, y_center, box_width, box_height = map(float, line.split())
        x1 = (x_center - box_width / 2.0) * width * ratio + pad_x
        y1 = (y_center - box_height / 2.0) * height * ratio + pad_y
        x2 = (x_center + box_width / 2.0) * width * ratio + pad_x
        y2 = (y_center + box_height / 2.0) * height * ratio + pad_y
        labels.append([cls, x1, y1, x2, y2])

    return np.asarray(labels, dtype=np.float32)


def load_image_tensor(image_path: Path, input_size: int) -> torch.Tensor:
    image = Image.open(image_path).convert("RGB")
    width, height = image.size
    ratio, pad_x, pad_y = letterbox_params((width, height), input_size)
    new_width = int(round(width * ratio))
    new_height = int(round(height * ratio))
    resized = image.resize((new_width, new_height))
    canvas = Image.new("RGB", (input_size, input_size), (114, 114, 114))
    canvas.paste(resized, (int(round(pad_x)), int(round(pad_y))))
    array = np.asarray(canvas, dtype=np.float32) / 255.0
    array = np.transpose(array, (2, 0, 1))[None, ...]
    return torch.from_numpy(array)


def class_names_from_data(data_cfg: dict) -> dict[int, str]:
    names = data_cfg.get("names", {})
    if isinstance(names, list):
        return {index: str(name) for index, name in enumerate(names)}
    return {int(index): str(name) for index, name in names.items()}


def parse_detections(output: torch.Tensor, conf_threshold: float) -> np.ndarray:
    tensor = output.squeeze(0)
    if tensor.ndim != 2 or tensor.shape[0] < 5:
        return np.zeros((0, 6), dtype=np.float32)

    if tensor.shape[0] <= tensor.shape[1]:
        # Channel-major layout: [channels, predictions]
        num_predictions = tensor.shape[1]
        class_count = tensor.shape[0] - 4
        detections: list[list[float]] = []
        for index in range(num_predictions):
            cx = float(tensor[0, index])
            cy = float(tensor[1, index])
            width = float(tensor[2, index])
            height = float(tensor[3, index])
            scores = torch.sigmoid(tensor[4:, index])
            best_score, best_class = torch.max(scores, dim=0)
            score = float(best_score)
            class_id = int(best_class)
            if score < conf_threshold or class_id < 0 or class_id >= class_count:
                continue
            x1 = max(0.0, cx - width / 2.0)
            y1 = max(0.0, cy - height / 2.0)
            x2 = min(640.0, cx + width / 2.0)
            y2 = min(640.0, cy + height / 2.0)
            if x2 <= x1 or y2 <= y1:
                continue
            detections.append([x1, y1, x2, y2, score, class_id])
        return np.asarray(detections, dtype=np.float32)

    # Row-major fallback: [predictions, channels]
    detections = []
    for row in tensor:
        if row.shape[0] < 5:
            continue
        cx, cy, width, height = [float(v) for v in row[:4]]
        scores = torch.sigmoid(row[4:])
        best_score, best_class = torch.max(scores, dim=0)
        score = float(best_score)
        class_id = int(best_class)
        if score < conf_threshold:
            continue
        x1 = max(0.0, cx - width / 2.0)
        y1 = max(0.0, cy - height / 2.0)
        x2 = min(640.0, cx + width / 2.0)
        y2 = min(640.0, cy + height / 2.0)
        if x2 <= x1 or y2 <= y1:
            continue
        detections.append([x1, y1, x2, y2, score, class_id])
    return np.asarray(detections, dtype=np.float32)


def greedy_match_tp(predictions: np.ndarray, labels: np.ndarray, iou_thresholds: np.ndarray) -> np.ndarray:
    if len(predictions) == 0:
        return np.zeros((0, len(iou_thresholds)), dtype=bool)
    if len(labels) == 0:
        return np.zeros((len(predictions), len(iou_thresholds)), dtype=bool)

    pred_boxes = torch.from_numpy(predictions[:, :4])
    pred_classes = predictions[:, 5].astype(int)
    label_boxes = torch.from_numpy(labels[:, 1:5])
    label_classes = labels[:, 0].astype(int)
    ious = box_iou(pred_boxes, label_boxes, xywh=False).numpy()

    correct = np.zeros((len(predictions), len(iou_thresholds)), dtype=bool)
    for threshold_index, threshold in enumerate(iou_thresholds):
        matched_labels: set[int] = set()
        for pred_index in np.argsort(-predictions[:, 4]):
            same_class = np.where(label_classes == pred_classes[pred_index])[0]
            if same_class.size == 0:
                continue
            best_label_index = same_class[np.argmax(ious[pred_index, same_class])]
            if ious[pred_index, best_label_index] >= threshold and best_label_index not in matched_labels:
                correct[pred_index, threshold_index] = True
                matched_labels.add(best_label_index)
    return correct


def evaluate_model(weights: Path, data_cfg: dict, project_root: Path, split: str, imgsz: int, conf: float) -> ModelEvaluation:
    input_size = imgsz
    class_names = class_names_from_data(data_cfg)
    split_dir = resolve_split_dir(project_root, data_cfg, split)
    image_paths = image_paths_for_split(split_dir)
    if not image_paths:
        raise FileNotFoundError(f"No images found in {split_dir}")

    model = torch.jit.load(str(weights), map_location="cpu")
    model.eval()

    confusion_matrix = ConfusionMatrix(names=class_names, task="detect")
    stats_tp: list[np.ndarray] = []
    stats_conf: list[np.ndarray] = []
    stats_pred_cls: list[np.ndarray] = []
    stats_target_cls: list[np.ndarray] = []

    iou_thresholds = np.linspace(0.5, 0.95, 10)

    for image_path in image_paths:
        label_path = corresponding_label_path(image_path)
        with Image.open(image_path) as image:
            original_size = image.size
        labels = load_labels(label_path, original_size, input_size)
        stats_target_cls.append(labels[:, 0].astype(np.int64) if len(labels) else np.zeros((0,), dtype=np.int64))

        tensor = load_image_tensor(image_path, input_size)
        output = model(tensor)
        predictions = parse_detections(output, conf)

        if len(predictions):
            detections_for_cm = {
                "cls": torch.from_numpy(predictions[:, 5].astype(np.float32)),
                "conf": torch.from_numpy(predictions[:, 4].astype(np.float32)),
                "bboxes": torch.from_numpy(predictions[:, :4].astype(np.float32)),
            }
        else:
            detections_for_cm = {
                "cls": torch.zeros((0,), dtype=torch.float32),
                "conf": torch.zeros((0,), dtype=torch.float32),
                "bboxes": torch.zeros((0, 4), dtype=torch.float32),
            }

        if len(labels):
            batch_for_cm = {
                "cls": torch.from_numpy(labels[:, 0].astype(np.float32)),
                "bboxes": torch.from_numpy(labels[:, 1:5].astype(np.float32)),
            }
        else:
            batch_for_cm = {
                "cls": torch.zeros((0,), dtype=torch.float32),
                "bboxes": torch.zeros((0, 4), dtype=torch.float32),
            }

        confusion_matrix.process_batch(detections_for_cm, batch_for_cm, conf=conf, iou_thres=0.45)

        correct = greedy_match_tp(predictions, labels, iou_thresholds)
        if len(predictions):
            stats_tp.append(correct)
            stats_conf.append(predictions[:, 4])
            stats_pred_cls.append(predictions[:, 5].astype(np.int64))

    tp = np.concatenate(stats_tp, axis=0) if stats_tp else np.zeros((0, len(iou_thresholds)), dtype=bool)
    confs = np.concatenate(stats_conf, axis=0) if stats_conf else np.zeros((0,), dtype=np.float32)
    pred_cls = np.concatenate(stats_pred_cls, axis=0) if stats_pred_cls else np.zeros((0,), dtype=np.int64)
    target_cls = np.concatenate(stats_target_cls, axis=0) if stats_target_cls else np.zeros((0,), dtype=np.int64)

    if len(tp) and len(target_cls):
        tp_out, fp_out, precision, recall, f1, ap, unique_classes, *_ = ap_per_class(
            tp,
            confs,
            pred_cls,
            target_cls,
            plot=False,
            names=class_names,
        )
        precision_value = float(np.mean(precision)) if len(precision) else 0.0
        recall_value = float(np.mean(recall)) if len(recall) else 0.0
        f1_value = float(np.mean(f1)) if len(f1) else 0.0
        map50_value = float(np.mean(ap[:, 0])) if ap.size else 0.0
        map50_95_value = float(np.mean(ap)) if ap.size else 0.0
    else:
        precision_value = 0.0
        recall_value = 0.0
        f1_value = 0.0
        map50_value = 0.0
        map50_95_value = 0.0

    return ModelEvaluation(
        name=weights.stem,
        weights=weights,
        precision=precision_value,
        recall=recall_value,
        f1=f1_value,
        map50=map50_value,
        map50_95=map50_95_value,
        confusion_matrix=confusion_matrix.matrix.tolist(),
    )


def write_confusion_matrix_csv(output_dir: Path, evaluation: ModelEvaluation) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    csv_path = output_dir / f"{evaluation.name}_confusion_matrix.csv"
    matrix = evaluation.confusion_matrix

    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        if not matrix:
            writer.writerow(["confusion_matrix_unavailable"])
        else:
            for row in matrix:
                writer.writerow(row)

    return csv_path


def print_summary(evaluation: ModelEvaluation) -> None:
    print(f"\n== {evaluation.name} ==")
    print(f"Weights: {evaluation.weights}")
    print(f"Precision:  {evaluation.precision:.4f}")
    print(f"Recall:     {evaluation.recall:.4f}")
    print(f"F1 score:   {evaluation.f1:.4f}")
    print(f"mAP@50:     {evaluation.map50:.4f}")
    print(f"mAP@50-95:  {evaluation.map50_95:.4f}")
    if evaluation.confusion_matrix:
        print(f"Confusion matrix size: {len(evaluation.confusion_matrix)} x {len(evaluation.confusion_matrix[0])}")
    else:
        print("Confusion matrix: unavailable from this Ultralytics run")


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate both YOLO TorchScript models on a labeled split.")
    parser.add_argument("--data", default="dataset/data.yaml", help="Path to the YOLO data.yaml file.")
    parser.add_argument("--split", default="test", choices=["train", "val", "test"], help="Dataset split to evaluate.")
    parser.add_argument("--imgsz", type=int, default=640, help="Evaluation image size.")
    parser.add_argument("--conf", type=float, default=0.25, help="Confidence threshold.")
    parser.add_argument("--output-dir", default="evaluation/torchscript_metrics", help="Directory for CSV outputs.")
    args = parser.parse_args()

    project_root = Path(__file__).resolve().parents[1]
    data_path = (project_root / args.data).resolve()
    if not data_path.exists():
        raise FileNotFoundError(f"Data config not found: {data_path}")

    data_cfg = load_data_config(data_path)

    output_dir = (project_root / args.output_dir).resolve()
    evaluations: list[ModelEvaluation] = []

    for name, weights in resolve_weights(project_root):
        print(f"Evaluating {name} from {weights.name}...")
        evaluation = evaluate_model(
            weights=weights,
            data_cfg=data_cfg,
            project_root=project_root,
            split=args.split,
            imgsz=args.imgsz,
            conf=args.conf,
        )
        evaluations.append(evaluation)
        csv_path = write_confusion_matrix_csv(output_dir, evaluation)
        print_summary(evaluation)
        print(f"Confusion matrix CSV: {csv_path}")

    print("\nDone. Compare the two summaries above for your report or thesis.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())