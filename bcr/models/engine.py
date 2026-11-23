# -*- coding: utf-8 -*-
"""Hằng số + tiện ích đường cầu Baccarat. Không phụ thuộc giao diện.

Phương pháp đang dùng là 1-1 thuần (xen kẽ B/P, gấp thếp 8 bậc), nên ở đây chỉ
còn dữ liệu trung tính: tên cửa, tỷ lệ trả thưởng, độ dài vệt và thống kê bàn.
Không còn hàm "soi cầu" nào để tránh mọi phụ thuộc vào cầu thực tế.
"""

BANKER, PLAYER, TIE = "B", "P", "T"

SIDE_NAME = {BANKER: "Banker", PLAYER: "Player", TIE: "Tie (Hoà)"}

PAYOUT = {BANKER: 0.95, PLAYER: 1.00, TIE: 8.00}


def decisions_only(results):
    """Bỏ Tie: chỉ giữ các tay phân thắng bại."""
    return [r for r in results if r in (BANKER, PLAYER)]


def run_lengths(results):
    """Độ dài các vệt liên tiếp, bỏ qua Tie: [(side, length), ...]"""
    runs = []
    for d in decisions_only(results):
        if runs and runs[-1][0] == d:
            runs[-1] = (d, runs[-1][1] + 1)
        else:
            runs.append((d, 1))
    return runs


def stats(results):
    dec = decisions_only(results)
    total = len(results)
    runs = run_lengths(results)
    longest = {BANKER: 0, PLAYER: 0}
    for side, length in runs:
        longest[side] = max(longest[side], length)
    return {
        "hands": total,
        "banker": dec.count(BANKER),
        "player": dec.count(PLAYER),
        "tie": results.count(TIE),
        "banker_pct": (dec.count(BANKER) / len(dec) * 100) if dec else 0.0,
        "player_pct": (dec.count(PLAYER) / len(dec) * 100) if dec else 0.0,
        "tie_pct": (results.count(TIE) / total * 100) if total else 0.0,
        "longest": longest,
    }
