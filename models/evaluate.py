"""SparkShield 1D CNN Classifier Evaluation Script.

Evaluates trained checkpoint on held-out test set, computes:
  - Confusion matrix
  - Per-class precision, recall, F1
  - False-positive rate on NORMAL class
  - Overall accuracy and macro F1
Saves metrics to models/model_metadata.json.
"""

import argparse
import json
import logging
import os
import sys
from typing import Dict, Tuple

import numpy as np
import torch
from sklearn.metrics import confusion_matrix, precision_recall_fscore_support

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
from models.train import CLASS_NAMES, SparkShield1DCNN, generate_split

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("sparkshield.models.evaluate")


def evaluate_checkpoint(
    checkpoint_path: str = "models/sparkshield_1d_cnn.pt",
    test_data_path: str = "models/test_data.npz",
    output_metadata_path: str = "models/model_metadata.json",
) -> Dict:
    """Evaluates the model on held-out test set and saves metadata."""
    if not os.path.exists(checkpoint_path):
        raise FileNotFoundError(f"Checkpoint not found at: {checkpoint_path}")

    checkpoint = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    model = SparkShield1DCNN(num_classes=4)
    model.load_state_dict(checkpoint["model_state_dict"])
    model.eval()

    # Load test data or generate if missing
    if os.path.exists(test_data_path):
        data = np.load(test_data_path)
        x_test = data["x_test"]
        y_test = data["y_test"]
        logger.info("Loaded %d test samples from %s", len(y_test), test_data_path)
    else:
        logger.warning("Test data not found; generating on the fly with seed 345...")
        x_test, y_test = generate_split("test", 200, seed=345, held_out_ranges=True)

    with torch.no_grad():
        x_tensor = torch.from_numpy(x_test)
        logits = model(x_tensor)
        probabilities = torch.softmax(logits, dim=1).numpy()
        preds = torch.argmax(logits, dim=1).numpy()

    # Confusion matrix
    cm = confusion_matrix(y_test, preds, labels=[0, 1, 2, 3])

    # Per-class metrics
    precision, recall, f1, support = precision_recall_fscore_support(
        y_test, preds, labels=[0, 1, 2, 3], zero_division=0
    )

    # False-Positive Rate on NORMAL (Class 0):
    # False positive for NORMAL = any non-NORMAL sample (label in {1, 2, 3}) predicted as 0 (NORMAL)
    non_normal_mask = (y_test != 0)
    total_non_normal = int(np.sum(non_normal_mask))
    fp_normal = int(np.sum((preds == 0) & non_normal_mask))
    fpr_normal = float(fp_normal / total_non_normal) if total_non_normal > 0 else 0.0

    # Overall accuracy
    accuracy = float(np.mean(preds == y_test))
    macro_f1 = float(np.mean(f1))

    # Format per-class dictionary
    per_class_metrics = {}
    for idx, cname in enumerate(CLASS_NAMES):
        per_class_metrics[cname] = {
            "precision": float(precision[idx]),
            "recall": float(recall[idx]),
            "f1_score": float(f1[idx]),
            "support": int(support[idx]),
        }

    # Print evaluation summary
    logger.info("================ SPARKSHIELD EVALUATION REPORT ================")
    logger.info("Overall Accuracy: %.4f | Macro F1: %.4f", accuracy, macro_f1)
    logger.info("False-Positive Rate on NORMAL: %.6f (%d / %d)", fpr_normal, fp_normal, total_non_normal)
    logger.info("---------------------------------------------------------------")
    logger.info(f"{'Class':<10} | {'Precision':<10} | {'Recall':<10} | {'F1-Score':<10} | {'Support':<8}")
    logger.info("---------------------------------------------------------------")
    for cname in CLASS_NAMES:
        m = per_class_metrics[cname]
        logger.info(
            f"{cname:<10} | {m['precision']:<10.4f} | {m['recall']:<10.4f} | {m['f1_score']:<10.4f} | {m['support']:<8d}"
        )
    logger.info("---------------------------------------------------------------")
    logger.info("Confusion Matrix (Rows: Ground Truth, Cols: Predicted):")
    logger.info("          %s", "    ".join([f"{c[:4]:>4}" for c in CLASS_NAMES]))
    for idx, row in enumerate(cm):
        logger.info("  %4s:   [%s]", CLASS_NAMES[idx][:4], "  ".join(f"{val:4d}" for val in row))
    logger.info("===============================================================")

    # Prepare model metadata output
    model_metadata = {
        "model_name": "SparkShield-1D-CNN",
        "checkpoint_path": checkpoint_path,
        "input_shape": checkpoint.get("input_shape", [1, 1, 128]),
        "num_classes": len(CLASS_NAMES),
        "class_names": CLASS_NAMES,
        "overall_accuracy": accuracy,
        "macro_f1": macro_f1,
        "fpr_on_normal": fpr_normal,
        "per_class_metrics": per_class_metrics,
        "confusion_matrix": cm.tolist(),
        "normalization": checkpoint.get("normalization", {}),
        "training_config": checkpoint.get("training_config", {}),
        "confidence_threshold_policy": {
            "alert_threshold": 0.85,
            "description": "Alerts and haptics triggered only when classification confidence >= 0.85",
        },
    }

    os.makedirs(os.path.dirname(output_metadata_path) or ".", exist_ok=True)
    with open(output_metadata_path, "w") as f:
        json.dump(model_metadata, f, indent=2)
    logger.info("Saved complete model metadata to %s", output_metadata_path)

    return model_metadata


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Evaluate SparkShield Model Checkpoint")
    parser.add_argument("--checkpoint", type=str, default="models/sparkshield_1d_cnn.pt")
    parser.add_argument("--test-data", type=str, default="models/test_data.npz")
    parser.add_argument("--output", type=str, default="models/model_metadata.json")
    args = parser.parse_args()
    evaluate_checkpoint(args.checkpoint, args.test_data, args.output)
