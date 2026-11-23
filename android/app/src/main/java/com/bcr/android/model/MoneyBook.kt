package com.bcr.android.model

/**
 * Sổ vốn một phiên: số dư, lãi/lỗ, chặn khi hết vốn.
 *
 * Chốt lời mặc định TẮT (0 = không tự dừng): gấp thếp cần đánh đủ thang mới hồi vốn, tự
 * dừng giữa đường sẽ bỏ dở. Người dùng dừng bằng nút DỪNG.
 */
class MoneyBook(
    capital: Double = 6000.0,
    unit: Double = 20.0,
    takeProfitPct: Double = 0.0
) {
    var capital: Double = capital.coerceAtLeast(0.0)
        private set
    var startCapital: Double = this.capital
        private set
    var balance: Double = this.capital
        private set
    var unit: Double = unit.coerceAtLeast(1.0)
        private set
    var takeProfitPct: Double = takeProfitPct.coerceAtLeast(0.0)
        private set

    var handsPlayed: Int = 0; private set
    var wins: Int = 0; private set
    var losses: Int = 0; private set
    var pushes: Int = 0; private set
    var totalStaked: Double = 0.0; private set
    var peak: Double = this.capital; private set
    var maxDrawdown: Double = 0.0; private set
    var lossStreak: Int = 0; private set

    /** null = đang chơi được; khác null = (mã, lời nhắn) đã bị chặn. */
    var blocked: Pair<String, String>? = null
        private set

    val profit: Double get() = balance - startCapital

    fun setUnit(value: Double) {
        unit = value.coerceAtLeast(1.0)
        checkLimits()
    }

    fun setTakeProfit(value: Double) {
        takeProfitPct = value.coerceAtLeast(0.0)
        checkLimits()
    }

    /** Ghi một tay đã cược thật. Trả (tiền thay đổi, win|loss|push). */
    fun settle(betSide: String, result: String, stake: Double): Settlement {
        handsPlayed += 1
        totalStaked += stake
        if (stake <= 0) return Settlement(0.0, "skip", "hold")

        val delta: Double
        val outcome: String
        if (result == Method.TIE) {
            delta = 0.0
            pushes += 1
            outcome = "push"
        } else if (betSide == result) {
            delta = stake * if (betSide == Method.BANKER) 0.95 else 1.0
            wins += 1
            lossStreak = 0
            outcome = "win"
        } else {
            delta = -stake
            losses += 1
            lossStreak += 1
            outcome = "loss"
        }
        balance += delta
        peak = maxOf(peak, balance)
        maxDrawdown = maxOf(maxDrawdown, peak - balance)
        checkLimits()
        return Settlement(delta, outcome, "hold")
    }

    /** Đồng bộ vốn theo số dư thật khi phiên chưa có tay nào. */
    fun syncCapital(value: Double): Pair<Boolean, String> {
        if (!value.isFinite() || value <= 0) return false to "Số dư phải lớn hơn 0."
        if (handsPlayed > 0) {
            return false to ("Phiên đã có $handsPlayed tay; không đổi vốn giữa phiên để giữ đúng " +
                "lãi/lỗ. Tạo phiên mới nếu muốn dùng số dư thật.")
        }
        capital = value
        startCapital = value
        balance = value
        peak = value
        maxDrawdown = 0.0
        checkLimits()
        return true to "Đã đồng bộ vốn: ${Fmt.money(value)} VN2."
    }

    /** Chốt lời xong: vốn của bàn kế tiếp = số dư hiện tại. */
    fun rebaseToBalance() {
        capital = balance
        startCapital = balance
        checkLimits()
    }

    fun reset(capital: Double = startCapital) {
        this.capital = capital.coerceAtLeast(0.0)
        startCapital = this.capital
        balance = this.capital
        peak = this.capital
        maxDrawdown = 0.0
        handsPlayed = 0
        wins = 0
        losses = 0
        pushes = 0
        totalStaked = 0.0
        lossStreak = 0
        checkLimits()
    }

    fun checkLimits() {
        val wonPct = if (startCapital > 0) (balance - startCapital) / startCapital * 100.0 else 0.0
        blocked = when {
            takeProfitPct > 0 && wonPct >= takeProfitPct -> "TAKE_PROFIT" to
                ("Lãi ${wonPct.toInt()}% vốn (${Fmt.money(profit)}). NGỪNG — đã chốt lời.")
            balance < effectiveUnit() -> "NO_CAPITAL" to
                "Vốn còn lại thấp hơn 1 bậc cược. Không còn gì để đánh."
            else -> null
        }
    }

    /** Đơn vị nhỏ nhất đang dùng để so ngưỡng hết vốn. */
    fun effectiveUnit(): Double = maxOf(unit, 1.0)

    fun summary(): Map<String, Any?> = mapOf(
        "capital" to startCapital,
        "balance" to balance,
        "profit" to profit,
        "hands" to handsPlayed,
        "wins" to wins,
        "losses" to losses,
        "pushes" to pushes,
        "staked" to totalStaked,
        "drawdown" to maxDrawdown,
        "blocked" to blocked?.first,
        "blocked_note" to blocked?.second
    )
}
