package com.ikoude.cnb2api.core

import com.ikoude.cnb2api.data.Bucket
import com.ikoude.cnb2api.data.Ledger
import java.util.Locale

/** 额度文案与判定。数字全部由这里算，不让用户或界面自己拼。 */
object QuotaMath {

    fun remainPercent(c: Bucket?): Double? = c?.remainPercent

    fun isExhausted(ledger: Ledger?): Boolean = ledger?.credit?.exhausted == true

    fun noticeVars(account: String, ledger: Ledger?, threshold: Int): Map<String, String>? {
        val credit = ledger?.credit ?: return null
        if (credit.total <= 0) return null
        val consumed = credit.consumed
        // 先定 total/consumed 的显示值，remain 由两者相减得到，保证三数自洽。
        val totalText = num1(credit.total).toDoubleOrNull() ?: return null
        val consumedText = num1(consumed).toDoubleOrNull() ?: return null
        val remainText = num1((totalText - consumedText).coerceAtLeast(0.0))
        val percent = (consumed / credit.total * 100)
        return mapOf(
            "account" to account,
            "unit" to "cr",
            "used" to num1(credit.used),
            "frozen" to num1(credit.frozen),
            "consumed" to num1(consumedText),
            "remain" to remainText,
            "total" to num1(totalText),
            "percent" to String.format(Locale.US, "%.2f", percent),
            "threshold" to num1(threshold.toDouble()),
        )
    }

    fun renderNotice(template: String, vars: Map<String, String>?): String {
        if (template.isBlank()) return ""
        return Regex("""\{(\w+)\}""").replace(template) { m ->
            val key = m.groupValues[1]
            if (vars != null && vars.containsKey(key)) vars[key]!! else m.value
        }
    }

    private fun num1(v: Double?): String {
        if (v == null) return "?"
        val r = kotlin.math.round(v * 10) / 10
        return if (r == r.toLong().toDouble()) r.toLong().toString() else String.format(Locale.US, "%.1f", r)
    }
}

/** 账号排序：可用 > 余额多；低于保底线的降到最后；额度未知不拦。 */
object AccountPicker {
    data class CreditState(val usable: Boolean, val known: Boolean, val ratio: Double?, val belowFloor: Boolean)

    fun creditState(m: Monitor, floorPercent: Int): CreditState {
        val usable = m.url.isNotBlank() && m.reachable && !m.broken
        val credit = m.ledger?.credit
        val known = credit != null
        val ratio = credit?.remainPercent
        return CreditState(usable, known, ratio, ratio != null && floorPercent > 0 && ratio < floorPercent)
    }

    fun canServe(m: Monitor, floorPercent: Int): Boolean {
        val s = creditState(m, floorPercent)
        return s.usable && !s.belowFloor
    }

    fun best(list: List<Monitor>, floorPercent: Int): Monitor? = list
        .map { m ->
            val s = creditState(m, floorPercent)
            val tier = when {
                !s.usable -> 2
                s.belowFloor -> 1
                !s.known -> 0
                (m.ledger?.credit?.remain ?: 0.0) <= 0 -> 1
                else -> 0
            }
            val left = if (tier == 0 && s.known) (m.ledger?.credit?.remain ?: 0.0).coerceAtLeast(0.0) else -1.0
            Triple(m, tier, left)
        }
        .sortedWith(compareBy<Triple<Monitor, Int, Double>> { it.second }.thenByDescending { it.third })
        .firstOrNull()?.first
}
