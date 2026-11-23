# -*- coding: utf-8 -*-
"""Phương pháp vào tiền: đánh xen kẽ Banker/Player mỗi tay (B-P-B-P…) + gấp thếp 8 bậc.

Quy tắc của người dùng:
1. Vào bàn chỉ cần bàn đã đi đủ số tay tối thiểu (mặc định 10-50) để có dữ liệu; KHÔNG xét
   bên mạnh, KHÔNG xét cầu 3-3/3-2, KHÔNG xét bệt — cầu thực tế đang thế nào cũng không đổi
   cách đánh.
2. Tay 1 đánh Banker, tay 2 đánh Player, tay 3 đánh Banker… cứ thế xen kẽ tới hết phiên.
3. Thắng thì quay về bậc 1. Thua thì sang bậc kế tiếp trong bảng vốn (mặc định 8 bậc).
4. Cầu hoà (Tie) là hoàn tiền: không đổi bậc, không đổi cửa, tay kế tiếp vẫn đánh cửa đó.
"""

import math

from bcr.models import engine as E
from bcr.models import chips
from bcr.models import money as M

METHOD_NAME = "1-1"
METHOD_LABEL = "1-1 · xen kẽ B/P · gấp thếp 8 bậc"
# Bảng vốn mặc định (đơn vị hiển thị trên sàn - VN2): 20 · 40 · 80 · 160 · 320 · 640 · 1.280 · 2.560
# Tổng 5.100 VN2 = 5.100.000đ cho 8 tay thua liên tiếp.
DEFAULT_LADDER = [20, 40, 80, 160, 320, 640, 1280, 2560]
LADDER_LOWER = 1
LADDER_UPPER = 20
# Người dùng chỉnh khoảng tay đã đi trong Cài đặt; hai mốc dưới là biên hợp lệ.
MIN_HANDS = 10
MAX_HANDS = 50
HANDS_LOWER = 10
HANDS_UPPER = 50
# Khi đang chơi: bệt chạm ngưỡng này thì rời bàn (không phải điều kiện chọn bàn).
DEFAULT_LIVE_EXIT_STREAK = 7
STREAK_LOWER = 1
STREAK_UPPER = 12


def normalize_hand_range(min_hands=MIN_HANDS, max_hands=MAX_HANDS):
    """Chuẩn hoá khoảng tay đã đi: kẹp trong HANDS_LOWER..HANDS_UPPER và bảo đảm min<=max."""
    def clamp(value, fallback):
        try:
            number = int(value)
        except (TypeError, ValueError):
            number = fallback
        return max(HANDS_LOWER, min(HANDS_UPPER, number))
    low = clamp(min_hands, MIN_HANDS)
    high = clamp(max_hands, MAX_HANDS)
    if low > high:
        low, high = high, low
    return low, high


def multiply_ladder(base):
    """Sinh bảng vốn gấp đôi từ bậc 1: base · 2×base · 4×base … đúng 8 bậc mặc định."""
    base = float(base)
    return [base * (2 ** index) for index in range(len(DEFAULT_LADDER))]


def normalize_streak(value, fallback=None):
    """Kẹp ngưỡng bệt trong STREAK_LOWER..STREAK_UPPER; giá trị lạ thì về mặc định."""
    try:
        number = int(float(value))
    except (TypeError, ValueError):
        number = STREAK_LOWER if fallback is None else int(fallback)
    return max(STREAK_LOWER, min(STREAK_UPPER, number))


def ladder_stakes(ladder=None):
    """Chuẩn hoá bảng vốn: mỗi bậc là số dương, số bậc trong biên cho phép."""
    stakes = list(DEFAULT_LADDER if ladder is None else ladder)
    if not LADDER_LOWER <= len(stakes) <= LADDER_UPPER:
        raise ValueError("Bảng vốn cần từ %d đến %d bậc, đang có %d bậc." % (
            LADDER_LOWER, LADDER_UPPER, len(stakes)))
    if not all(math.isfinite(float(s)) and float(s) > 0 for s in stakes):
        raise ValueError("Mỗi bậc cược phải là một số lớn hơn 0.")
    return [float(s) for s in stakes]


def parse_road(text):
    """Đọc cầu dạng chữ: B=Banker, P=Player, T=Hoà. Mọi ký tự khác bị bỏ qua."""
    mapping = {"B": E.BANKER, "P": E.PLAYER, "T": E.TIE}
    return [mapping[ch] for ch in text.upper() if ch in mapping]


def opposite(side):
    return E.PLAYER if side == E.BANKER else E.BANKER


def check_table(results, min_hands=MIN_HANDS, max_hands=MAX_HANDS):
    """Bàn có đủ dữ liệu để bắt đầu đánh 1-1 hay chưa.

    Phương pháp không phụ thuộc cầu nên chỉ còn một điều kiện: số tay đã đi nằm trong
    khoảng người dùng đặt. Số tay B/P vẫn trả về để UI hiển thị cho dễ đọc.
    """
    min_hands, max_hands = normalize_hand_range(min_hands, max_hands)
    hands = len(results)
    dec = E.decisions_only(results)
    banker, player = dec.count(E.BANKER), dec.count(E.PLAYER)
    checks = [{
        "name": "Số tay đã đi %d-%d" % (min_hands, max_hands),
        "ok": min_hands <= hands <= max_hands,
        "detail": "ván này đã đi %d tay" % hands,
    }]
    return {
        "eligible": all(c["ok"] for c in checks),
        "hands": hands,
        "checks": checks,
        "banker": banker,
        "player": player,
    }


def plan(results, stakes, ladder_step=0, side=E.BANKER, started=False,
         min_hands=MIN_HANDS, max_hands=MAX_HANDS):
    """Tay tiếp theo nên làm gì: cửa xen kẽ, số tiền theo bậc gấp thếp hiện tại."""
    stakes = ladder_stakes(stakes)
    info = check_table(results, min_hands, max_hands)
    side = side if side in (E.BANKER, E.PLAYER) else E.BANKER

    if not started:
        if not info["eligible"]:
            return {"action": "wait", "side": None, "stake": 0, "reason": next(
                c["detail"] for c in info["checks"] if not c["ok"]), "checks": info["checks"],
                "ladder_step": 0, "next_side": side}
        return {"action": "start", "side": E.BANKER, "stake": stakes[0],
                "reason": "Bàn đủ %d tay. Bắt đầu 1-1: tay 1 đánh Banker, bậc 1/%d (%s)." % (
                    info["hands"], len(stakes), M.fmt_money(stakes[0])),
                "checks": info["checks"], "ladder_step": 0, "next_side": E.BANKER}

    if ladder_step >= len(stakes):
        return {"action": "halt", "side": None, "stake": 0,
                "reason": "Đã thua hết %d bậc gấp thếp (mất %s). Dừng, quay về bậc 1 %s." % (
                    len(stakes), M.fmt_money(sum(stakes)), M.fmt_money(stakes[0])),
                "checks": info["checks"], "ladder_step": ladder_step, "next_side": side}

    stake = stakes[ladder_step]
    return {"action": "bet", "side": side, "stake": stake,
            "reason": "1-1 xen kẽ: tay kế tiếp đánh %s · bậc %d/%d (%s)." % (
                E.SIDE_NAME[side], ladder_step + 1, len(stakes), M.fmt_money(stake)),
            "checks": info["checks"], "ladder_step": ladder_step, "next_side": side}


def compare_chips(plan_side, plan_stake, live, unit=None):
    """So chip thật đang đặt trên bàn với kế hoạch của tay tiếp theo.

    plan_side/plan_stake: cửa và số tiền (VNĐ) kế hoạch đã chốt cho tay tiếp theo.
    live: dict từ table_bets.read(); ``spots[side]["amount_vnd"]`` đã là VNĐ nên so trực tiếp
    với kế hoạch. ``suggested_chips`` cho biết cần bấm những chip nào để đạt số tiền đó.

    Trả dict(status, side, expected, actual, extra, suggested_chips, note):
      status: "match"        chip đúng cửa, đúng tiền
              "no_bet"       chưa đặt gì
              "wrong_side"   đặt sang cửa khác
              "wrong_amount" đúng cửa nhưng khác tiền
              "extra_bets"   đúng cửa nhưng đặt thêm cửa khác
              "ungroupable"  số tiền không ghép được bằng mệnh giá chip đang có
              "unknown"      chưa đọc được bàn
    """
    expected = float(plan_stake or 0)
    if not isinstance(live, dict) or not live.get("ok"):
        split = chips.decompose(expected, unit or chips.DEFAULT_CHIP_UNIT)
        return {"status": "unknown", "side": plan_side, "expected": expected, "actual": 0.0,
                "extra": {}, "suggested_chips": split["chips"],
                "note": "Chưa đọc được chip trên bàn."}

    chip_unit = int(live.get("unit") or unit or chips.DEFAULT_CHIP_UNIT)
    spots = live.get("spots") or {}
    amounts = {side: float((spots.get(side) or {}).get("amount_vnd") or 0)
               for side in (E.BANKER, E.PLAYER, E.TIE)}
    total = sum(amounts.values())
    actual = amounts.get(plan_side, 0.0)
    extra = {side: value for side, value in amounts.items() if side != plan_side and value > 0}
    split = chips.decompose(expected, chip_unit, live.get("denominations") or None)

    if not split["exact"]:
        return {"status": "ungroupable", "side": plan_side, "expected": expected,
                "actual": actual, "extra": extra, "suggested_chips": split["chips"],
                "note": "%s không ghép được bằng mệnh giá chip đang có (thiếu %s)." % (
                    M.fmt_money(expected), M.fmt_money(split["remainder_vnd"]))}
    if total <= 0:
        return {"status": "no_bet", "side": plan_side, "expected": expected, "actual": 0.0,
                "extra": {}, "suggested_chips": split["chips"],
                "note": "Chưa đặt chip nào trên bàn."}
    if actual <= 0 and extra:
        placed = next(iter(extra))
        return {"status": "wrong_side", "side": plan_side, "expected": expected, "actual": 0.0,
                "extra": extra, "suggested_chips": split["chips"],
                "note": "Đang đặt %s ở %s — kế hoạch là %s." % (
                    M.fmt_money(sum(extra.values())), E.SIDE_NAME[placed], E.SIDE_NAME[plan_side])}
    if abs(actual - expected) >= 1:
        return {"status": "wrong_amount", "side": plan_side, "expected": expected, "actual": actual,
                "extra": extra, "suggested_chips": split["chips"],
                "note": "Đặt %s nhưng kế hoạch là %s ở %s." % (
                    M.fmt_money(actual), M.fmt_money(expected), E.SIDE_NAME[plan_side])}
    if extra:
        return {"status": "extra_bets", "side": plan_side, "expected": expected, "actual": actual,
                "extra": extra, "suggested_chips": split["chips"],
                "note": "Đúng %s %s nhưng còn đặt thêm %s ở cửa khác." % (
                    E.SIDE_NAME[plan_side], M.fmt_money(actual),
                    M.fmt_money(sum(extra.values())))}
    return {"status": "match", "side": plan_side, "expected": expected, "actual": actual,
            "extra": {}, "suggested_chips": split["chips"],
            "note": "Chip khớp kế hoạch: %s %s." % (
                E.SIDE_NAME[plan_side], M.fmt_money(actual))}


CHIP_STATUS_LABEL = {
    "match": "KHỚP",
    "no_bet": "CHƯA ĐẶT",
    "wrong_side": "SAI CỬA",
    "wrong_amount": "SAI TIỀN",
    "extra_bets": "ĐẶT THÊM",
    "ungroupable": "KHÔNG GHÉP ĐƯỢC",
    "unknown": "CHƯA ĐỌC",
}


def settle(bet_side, result, stake, commission=0.95):
    """Trả về (delta tiền, kết quả, bước gấp thếp kế tiếp: 'reset'|'hold'|'up')."""
    if result == E.TIE:
        return 0.0, "push", "hold"
    if bet_side == result:
        rate = commission if bet_side == E.BANKER else 1.0
        return stake * rate, "win", "reset"
    return -stake, "loss", "up"


def advance(state, result, bet_side, stake):
    """Cập nhật trạng thái sau một tay. state: dict(ladder_step, side, started).

    Tay hoà không đổi bậc và không đổi cửa: tay kế tiếp vẫn đánh cửa cũ.
    """
    delta, outcome, step_action = settle(bet_side, result, stake)
    state = dict(state)
    if result != E.TIE:
        state["side"] = opposite(bet_side)
    if step_action == "up":
        state["ladder_step"] = state.get("ladder_step", 0) + 1
    elif step_action == "reset":
        state["ladder_step"] = 0
    state["started"] = True
    return state, {"delta": delta, "outcome": outcome}
