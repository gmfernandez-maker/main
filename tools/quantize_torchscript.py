"""
Simple helper to attempt dynamic quantization of a TorchScript model and write
`weights_quant.torchscript` at the project root.

Notes:
- Dynamic quantization is most effective on `nn.Linear` / `nn.LSTM` style layers.
- Many detection models (YOLO) are conv-heavy and may not benefit from dynamic
  quantization; more advanced workflows (FX graph mode / static quantization)
  require access to the original Python model definition and calibration data.

Usage:
    python tools/quantize_torchscript.py --input weights.torchscript --output weights_quant.torchscript

Requirements:
    pip install torch

This script will attempt to load the TorchScript model, run `quantize_dynamic`
and save the resulting ScriptModule. If quantization fails, it will save a
fallback copy of the original model and exit with a non-zero code.
"""

import argparse
import sys
from pathlib import Path

try:
    import torch
except Exception as e:
    print("ERROR: torch is required to run this script. Install via 'pip install torch'.")
    raise


def quantize_dynamic_torchscript(input_path: Path, output_path: Path) -> int:
    if not input_path.exists():
        print(f"Input model not found: {input_path}")
        return 2

    print(f"Loading TorchScript model from: {input_path}")
    try:
        model = torch.jit.load(str(input_path), map_location="cpu")
    except Exception as e:
        print(f"Failed to load TorchScript model: {e}")
        return 3

    # Try dynamic quantization (works primarily for Linear/LSTM)
    modules_to_quantize = {torch.nn.Linear, torch.nn.LSTM}
    try:
        print("Attempting dynamic quantization (Linear, LSTM)")
        quantized = torch.quantization.quantize_dynamic(model, modules_to_quantize, dtype=torch.qint8)
        print("Quantization succeeded, saving quantized TorchScript...")
        # If quantize_dynamic returned a (possibly) ScriptModule, save it.
        torch.jit.save(quantized, str(output_path))
        print(f"Saved quantized model to: {output_path}")
        return 0
    except Exception as e:
        print(f"Dynamic quantization failed: {e}")
        print("Falling back to saving original model copy to the output path.")
        try:
            torch.jit.save(model, str(output_path))
            print(f"Saved original model to: {output_path}")
            return 1
        except Exception as e2:
            print(f"Failed to save fallback model: {e2}")
            return 4


if __name__ == '__main__':
    p = argparse.ArgumentParser(description="Quantize TorchScript model (dynamic quantization)")
    p.add_argument("--input", "-i", default="weights.torchscript", help="Path to input TorchScript model")
    p.add_argument("--output", "-o", default="weights_quant.torchscript", help="Path to write quantized TorchScript model")
    args = p.parse_args()

    input_path = Path(args.input)
    output_path = Path(args.output)

    rc = quantize_dynamic_torchscript(input_path, output_path)
    if rc == 0:
        print("Quantization completed successfully.")
        sys.exit(0)
    elif rc == 1:
        print("Quantization not supported; saved original model as fallback (non-quantized).")
        sys.exit(0)
    else:
        print("Quantization process failed.")
        sys.exit(rc)
