package com.bcr.android

import com.bcr.android.model.Fmt
import com.bcr.android.model.Method
import com.bcr.android.model.MethodState
import com.bcr.android.model.MoneyBook
import com.bcr.android.model.Placement
import com.bcr.android.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logic 1-1 phải khớp bản desktop Python `bcr/models/method.py`. */
class MethodTest {

    private val road = "BBPPBTB".repeat(3)

    @Test
    fun `default ladder is the eight rungs the user gave`() {
        assertEquals(
            listOf(20.0, 40.0, 80.0, 160.0, 320.0, 640.0, 1280.0, 2560.0),
            Method.DEFAULT_LADDER
        )
        assertEquals(5100.0, Method.DEFAULT_LADDER.sum(), 0.001)
    }

    @Test
    fun `multiply ladder doubles the base eight times`() {
        assertEquals(
            listOf(50.0, 100.0, 200.0, 400.0, 800.0, 1600.0, 3200.0, 6400.0),
            Method.multiplyLadder(50.0)
        )
    }

    @Test
    fun `first hand is banker at the first rung`() {
        val plan = Method.plan(road.length, Method.DEFAULT_LADDER, MethodState())
        assertEquals(Method.ACTION_START, plan.action)
        assertEquals(Method.BANKER, plan.side)
        assertEquals(20.0, plan.stake, 0.001)
    }

    @Test
    fun `waits while the road is shorter than the configured minimum`() {
        val plan = Method.plan(9, Method.DEFAULT_LADDER, MethodState(), minHands = 10)
        assertEquals(Method.ACTION_WAIT, plan.action)
        assertEquals(0.0, plan.stake, 0.001)
    }

    @Test
    fun `a fresh table can start immediately because the minimum is zero`() {
        val plan = Method.plan(0, Method.DEFAULT_LADDER, MethodState())
        assertEquals(Method.ACTION_START, plan.action)
        assertEquals(Method.BANKER, plan.side)
    }

    @Test
    fun `halts after the last rung`() {
        val plan = Method.plan(road.length, Method.DEFAULT_LADDER, MethodState(started = true, ladderStep = 8))
        assertEquals(Method.ACTION_HALT, plan.action)
        assertTrue(plan.reason.contains("8 bậc"))
    }

    @Test
    fun `win resets the ladder and flips the side`() {
        val (next, settled) = Method.advance(
            MethodState(started = true, ladderStep = 3, side = Method.BANKER),
            Method.BANKER, Method.BANKER, 160.0
        )
        assertEquals("win", settled.outcome)
        assertEquals(0, next.ladderStep)
        assertEquals(Method.PLAYER, next.side)
        assertEquals(152.0, settled.delta, 0.001)
    }

    @Test
    fun `loss goes up one rung and flips the side`() {
        val (next, settled) = Method.advance(
            MethodState(started = true, ladderStep = 2, side = Method.PLAYER),
            Method.BANKER, Method.PLAYER, 80.0
        )
        assertEquals("loss", settled.outcome)
        assertEquals(3, next.ladderStep)
        assertEquals(Method.BANKER, next.side)
        assertEquals(-80.0, settled.delta, 0.001)
    }

    @Test
    fun `tie keeps the rung and the side`() {
        val (next, settled) = Method.advance(
            MethodState(started = true, ladderStep = 4, side = Method.PLAYER),
            Method.TIE, Method.PLAYER, 320.0
        )
        assertEquals("push", settled.outcome)
        assertEquals(0.0, settled.delta, 0.001)
        assertEquals(4, next.ladderStep)
        assertEquals(Method.PLAYER, next.side)
    }

    @Test
    fun `player win pays one for one`() {
        val settled = Method.settle(Method.PLAYER, Method.PLAYER, 100.0)
        assertEquals(100.0, settled.delta, 0.001)
        assertEquals("win", settled.outcome)
    }

    @Test
    fun `banker win pays commission`() {
        val settled = Method.settle(Method.BANKER, Method.BANKER, 100.0)
        assertEquals(95.0, settled.delta, 0.001)
    }

    @Test
    fun `sides alternate strictly ignoring the road`() {
        var state = MethodState(started = true)
        val results = listOf(
            Method.BANKER, Method.BANKER, Method.PLAYER, Method.PLAYER,
            Method.BANKER, Method.BANKER, Method.BANKER, Method.PLAYER
        )
        val bets = mutableListOf<String>()
        for (result in results) {
            val plan = Method.plan(road.length, Method.DEFAULT_LADDER, state)
            bets.add(plan.side)
            state = Method.advance(state, result, plan.side, plan.stake).first
        }
        assertEquals(listOf("B", "P", "B", "P", "B", "P", "B", "P"), bets)
    }

    @Test
    fun `check table needs the minimum hand count but no hard maximum`() {
        assertTrue(Method.checkTable(10, 10, 50).eligible)
        assertTrue(Method.checkTable(50, 10, 50).eligible)
        // Cầu đã dài hơn mốc tham khảo vẫn đánh được (người dùng tự mở bàn).
        assertTrue(Method.checkTable(51, 10, 50).eligible)
        assertFalse(Method.checkTable(9, 10, 50).eligible)
        assertFalse(Method.checkTable(0, 10, 50).eligible)
    }

    @Test
    fun `hand range is clamped and swapped`() {
        assertEquals(0 to 50, Method.normalizeHands(-5, 999))
        assertEquals(20 to 30, Method.normalizeHands(30, 20))
    }

    @Test
    fun `a manual ladder of three rungs is valid`() {
        assertEquals(listOf(10.0, 20.0, 40.0), Method.ladderStakes(listOf(10.0, 20.0, 40.0)))
    }

    @Test
    fun `zero stake is rejected`() {
        var threw = false
        try {
            Method.ladderStakes(listOf(0.0, 5.0))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `money formats vietnamese style`() {
        assertEquals("5.100", Fmt.money(5100.0))
        assertEquals("1.280", Fmt.money(1280.0))
        assertEquals("+512", Fmt.signed(512.0))
        assertEquals("-5.100", Fmt.money(-5100.0))
    }

    @Test
    fun `money parsing accepts grouping and decimals`() {
        assertEquals(1500000.0, Fmt.parseMoney("1.500.000"), 0.001)
        assertEquals(5100.0, Fmt.parseMoney("5.100"), 0.001)
        assertEquals(1500.5, Fmt.parseMoney("1500,50"), 0.001)
        assertEquals(6000.0, Fmt.parseMoney("6000 VN2"), 0.001)
    }
}

/** Vòng đời phiên: chỉ tính tay khi đã xác nhận đặt chip thật. */
class SessionTest {

    private fun session(letters: String = "B".repeat(12)): Session {
        val s = Session(money = MoneyBook(capital = 6000.0, unit = 20.0))
        s.bind("id:t1", "Bàn 1", letters)
        return s
    }

    @Test
    fun `bind loads the road and resets the method`() {
        val s = session("BBPP")
        assertEquals("BBPP", s.letters)
        assertEquals(4, s.results.size)
        assertEquals(0, s.method.ladderStep)
        assertEquals(Method.BANKER, s.method.side)
    }

    @Test
    fun `start waits for a new hand before arming`() {
        val s = session()
        assertTrue(s.start())
        assertTrue(s.running)
        assertTrue(s.entryWaiting)
        assertEquals(null, s.pendingPlan())
    }

    @Test
    fun `first new hand is consumed without settling`() {
        val s = session()
        s.start()
        val step = s.process(s.letters + "P")
        assertEquals(com.bcr.android.model.Step.ARMED, step)
        assertFalse(s.entryWaiting)
        assertNotNull(s.pendingPlan())
        assertEquals(0, s.money.handsPlayed)
    }

    @Test
    fun `a hand is only counted when the chip was confirmed`() {
        val s = session()
        s.start()
        s.process(s.letters + "P")          // ăn tay mở màn, chốt kế hoạch
        val plan = s.pendingPlan()!!
        s.markPlacing()
        assertEquals(com.bcr.android.model.Step.IDLE, s.process(s.letters + "B"))  // chờ xác nhận
        s.markPlaced(true, "ok", plan)
        s.process(s.letters + "B")
        assertEquals(1, s.money.handsPlayed)
        assertEquals(1, s.log.size)
        assertTrue(s.log.first().placed)
    }

    @Test
    fun `a hand without a placed chip is skipped and never touches money`() {
        val s = session()
        val balance = s.money.balance
        s.start()
        s.process(s.letters + "P")
        val plan = s.pendingPlan()!!
        s.markPlaced(false, "khay chip trống", plan)
        s.process(s.letters + "B")
        assertEquals(balance, s.money.balance, 0.001)
        assertEquals(0, s.money.handsPlayed)
        assertEquals(1, s.placeSkips)
        assertEquals("not_placed", s.log.last().outcome)
    }

    @Test
    fun `stops after three skipped hands in a row`() {
        val s = session("B".repeat(12))
        s.start()
        var letters = s.letters
        // tay mở màn
        letters += "P"
        s.process(letters)
        for (i in 0 until 3) {
            val plan = s.pendingPlan()!!
            s.markPlaced(false, "trượt", plan)
            letters += if (letters.last() == 'P') "B" else "P"
            s.process(letters)
        }
        assertFalse(s.running)
        assertTrue(s.note.contains("bỏ"))
    }

    @Test
    fun `road jump after entry pauses instead of settling back-dated hands`() {
        val s = session("B".repeat(12))
        s.start()
        s.process(s.letters + "P")            // tay mở màn: chỉ đồng bộ cầu
        val step = s.process(s.letters + "PB") // nhảy 2 tay khi đã có kế hoạch
        assertEquals(com.bcr.android.model.Step.PAUSED, step)
        assertFalse(s.running)
        assertEquals(0, s.money.handsPlayed)
    }

    @Test
    fun `entry syncs even when several hands landed during the scan`() {
        val s = session("B".repeat(12))
        s.start()
        val step = s.process(s.letters + "PBP")
        assertEquals(com.bcr.android.model.Step.ARMED, step)
        assertNotNull(s.pendingPlan())
        assertEquals(0, s.money.handsPlayed)
    }

    @Test
    fun `later hands never settle two results at once`() {
        val s = session("B".repeat(12))
        s.start()
        s.process(s.letters + "P")
        val plan = s.pendingPlan()!!
        s.markPlaced(true, "ok", plan)
        val step = s.process(s.letters + "PBP")   // nhảy 3 tay sau khi đã đặt
        assertEquals(com.bcr.android.model.Step.PAUSED, step)
        assertEquals(0, s.money.handsPlayed)
    }

    @Test
    fun `entry hand that already shows a long streak exits the table`() {
        // 12 tay, vệt Banker đang chạy 8 tay; ngưỡng thoát đặt 3 nên phải rời bàn.
        val s = Session(exitStreak = 3)
        s.bind("id:t1", "Bàn 1", "BPBPBBBBBBBB")
        assertEquals(8, Method.currentStreak(s.letters).second)
        s.start()
        val step = s.process(s.letters + "B")
        assertEquals(com.bcr.android.model.Step.PAUSED, step)
        assertFalse(s.running)
        assertTrue(s.exitDone)
        assertEquals(0, s.money.handsPlayed)
    }

    @Test
    fun `current streak ignores ties`() {
        // Hoà không ngắt vệt: "BPPTPP" → B1, P2 rồi T bị bỏ qua, P nối tiếp = P4.
        assertEquals("P" to 4, Method.currentStreak("BPPTPP"))
        // Kết thúc bằng P sau một B thì vệt hiện tại chỉ là 1.
        assertEquals("P" to 1, Method.currentStreak("BPPTBTP"))
        assertEquals("B" to 2, Method.currentStreak("PBTTB"))
        assertEquals("" to 0, Method.currentStreak("TTT"))
    }

    @Test
    fun `fixed stake keeps the ladder at rung one`() {
        val s = Session(fixedStake = 35.0)
        s.bind("id:t1", "Bàn 1", "B".repeat(12))
        s.start()
        s.process(s.letters + "B")
        val plan = s.pendingPlan()!!
        assertEquals(35.0, plan.stake, 0.001)
        assertEquals(0, plan.ladderStep)
    }

    @Test
    fun `money blocks at take profit`() {
        val book = MoneyBook(capital = 1000.0, unit = 20.0, takeProfitPct = 10.0)
        book.settle(Method.PLAYER, Method.PLAYER, 200.0)   // +200 = 20% vốn
        assertEquals("TAKE_PROFIT", book.blocked?.first)
    }

    @Test
    fun `banker win pays 95 percent`() {
        val book = MoneyBook(capital = 1000.0, unit = 20.0)
        val settled = book.settle(Method.BANKER, Method.BANKER, 100.0)
        assertEquals(95.0, settled.delta, 0.001)
        assertEquals(1095.0, book.balance, 0.001)
    }

    @Test
    fun `tie is a push in the money book`() {
        val book = MoneyBook(capital = 1000.0, unit = 20.0)
        val settled = book.settle(Method.BANKER, Method.TIE, 100.0)
        assertEquals(0.0, settled.delta, 0.001)
        assertEquals("push", settled.outcome)
        assertEquals(1000.0, book.balance, 0.001)
    }
}
