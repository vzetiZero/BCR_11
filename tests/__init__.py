# -*- coding: utf-8 -*-
"""Bộ kiểm thử của BCR: kiến trúc, engine JSON-RPC, model phương pháp 1-1, adapter."""

import pathlib
import sys

# Cho phép chạy `python -m unittest discover -s tests -t .` từ thư mục gốc.
ROOT = pathlib.Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
