package com.bcr.android

import com.bcr.android.model.Method
import com.bcr.android.model.MethodState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bản sao cố định của phép chạy 30 tay bên Python (`tools/parity.py`): cùng kết quả
 * nghĩa là bản Kotlin cân tài và đi thang y hệt desktop.
 */
class ParityTest {

    private val expected = """
        bet B 20 P -20
        bet P 40 B -40
        bet B 80 P -80
        bet P 160 T 0
        bet P 160 B -160
        bet B 320 B 304
        bet P 20 T 0
        bet P 20 B -20
        bet B 40 P -40
        bet P 80 T 0
        bet P 80 B -80
        bet B 160 T 0
    """.trimIndent().lines().map { it.trim() }

    @Test
    fun `thirty hands match the python reference`() {
        val results = listOf(
            "P", "B", "P", "T", "B", "B", "T", "B", "P", "T", "B", "T"
        )
        var state = MethodState(started = true)
        val rows = mutableListOf<String>()
        for (result in results) {
            var plan = Method.plan(12, Method.DEFAULT_LADDER, state)
            if (plan.action == Method.ACTION_HALT) {
                state = MethodState(started = true, ladderStep = 0, side = plan.nextSide)
                plan = Method.plan(12, Method.DEFAULT_LADDER, state)
            }
            val (next, settled) = Method.advance(state, result, plan.side, plan.stake)
            state = next
            rows.add(
                "${plan.action} ${plan.side} ${plan.stake.toInt()} " +
                    "$result ${settled.delta.toInt()}"
            )
        }
        assertEquals(expected, rows)
    }
}
