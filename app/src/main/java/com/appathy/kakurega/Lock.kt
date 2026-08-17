package com.appathy.kakurega

import org.json.JSONArray
import org.json.JSONObject

// ---------- 解錠要素 ----------
// type: 何で守るか / param: 種別ごとの設定 / outside: 秘密を端末の外に置いたと本人が申告したか

object Elem {
    const val HIDDEN = "hidden"   // 隠し場所そのもの
    const val KEY = "key"         // 鍵アイテム（param = itemId）
    const val PIN = "pin"         // 暗証番号（param = salt:hash）
    const val BNSN = "bnsn"       // 分散片（param = setId:k）
    const val LAN = "lanpair"     // 同一Wi-Fiの第二端末（Phase 4）
}

class LockElem(val type: String, var param: String, var outside: Boolean) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("type", type)
        o.put("param", param)
        o.put("outside", outside)
        return o
    }

    fun label(): String = when (type) {
        Elem.HIDDEN -> "隠し場所"
        Elem.KEY -> "鍵アイテム"
        Elem.PIN -> "暗証番号"
        Elem.BNSN -> "分散片"
        Elem.LAN -> "二台目の端末"
        else -> type
    }

    companion object {
        fun fromJson(o: JSONObject): LockElem =
            LockElem(o.optString("type", Elem.HIDDEN), o.optString("param", ""), o.optBoolean("outside", false))
    }
}

// 錠は OR の枝の集まり、各枝は AND の要素列（条件式のDNF表現）
class Lock(val branches: MutableList<MutableList<LockElem>>) {

    fun isOpen(): Boolean = branches.isEmpty()

    fun toJson(): JSONObject {
        val o = JSONObject()
        val ba = JSONArray()
        for (b in branches) {
            val ea = JSONArray()
            for (e in b) ea.put(e.toJson())
            ba.put(ea)
        }
        o.put("branches", ba)
        return o
    }

    fun describe(): String {
        if (branches.isEmpty()) return "錠なし"
        val parts = mutableListOf<String>()
        for (b in branches) parts.add(b.joinToString("＋") { it.label() })
        return parts.joinToString("　または　")
    }

    companion object {
        fun none(): Lock = Lock(mutableListOf())

        fun single(vararg e: LockElem): Lock =
            Lock(mutableListOf(e.toMutableList()))

        fun fromJson(o: JSONObject?): Lock {
            if (o == null) return none()
            val out = mutableListOf<MutableList<LockElem>>()
            val ba = o.optJSONArray("branches")
            if (ba != null) {
                for (i in 0 until ba.length()) {
                    val ea = ba.optJSONArray(i) ?: continue
                    val b = mutableListOf<LockElem>()
                    for (j in 0 until ea.length()) b.add(LockElem.fromJson(ea.getJSONObject(j)))
                    if (b.size > 0) out.add(b)
                }
            }
            return Lock(out)
        }
    }
}

// ---------- 診断 ----------
// 攻撃者クラス: A1 覗き見 / A2 端末占有 / A3 端末解析 / A4 家庭内共犯
// 秘密の所在: 1 同一端末DB / 2 利用者の記憶 / 3 別ファイル / 4 別端末
// 規則: クラスAに安全 ⇔ すべてのOR枝に、Aが破れない要素が最低1つある

class LockDiag(
    val defended: BooleanArray,   // [A1,A2,A3,A4]
    val weakest: String,          // 実効強度を決めている枝の説明
    val lockoutRisk: List<String>,// 単独で失うと全滅する要素
    val undecidable: Boolean,
    val remedies: List<String>
)

object LockRules {

    // 所在レベル。申告がない外部要素は安全側（1）に倒す
    fun locus(e: LockElem): Int = when (e.type) {
        Elem.HIDDEN -> 1
        Elem.KEY -> 1
        Elem.PIN -> 2
        Elem.BNSN -> if (e.outside) 3 else 1
        Elem.LAN -> if (e.outside) 4 else 1
        else -> 1
    }

    // 攻撃者クラス（1..4）が破れる所在レベルの上限
    private fun breaks(cls: Int): Int = when (cls) {
        1 -> 0
        2 -> 1
        3 -> 2
        else -> 4
    }

    fun diagnose(lock: Lock): LockDiag {
        if (lock.isOpen()) {
            return LockDiag(
                booleanArrayOf(false, false, false, false),
                "錠なし（タップすれば誰でも開く）",
                listOf(),
                false,
                listOf("暗証番号を付けるだけでも、端末を触れる人への防御になります")
            )
        }

        val defended = BooleanArray(4)
        for (c in 1..4) {
            var all = true
            for (b in lock.branches) {
                var survives = false
                for (e in b) if (locus(e) > breaks(c)) survives = true
                if (!survives) {
                    all = false
                    break
                }
            }
            defended[c - 1] = all
        }

        // 最弱枝＝最も高い所在レベルが最も低い枝
        var weakIdx = 0
        var weakTop = 99
        for (i in lock.branches.indices) {
            var top = 0
            for (e in lock.branches[i]) if (locus(e) > top) top = locus(e)
            if (top < weakTop) {
                weakTop = top
                weakIdx = i
            }
        }
        val weakest = lock.branches[weakIdx].joinToString("＋") { it.label() }

        // 締め出し: すべての枝に現れる要素は、失うと開かなくなる
        val lockout = mutableListOf<String>()
        if (lock.branches.size > 0) {
            for (e in lock.branches[0]) {
                var inAll = true
                for (b in lock.branches) {
                    var found = false
                    for (x in b) if (x.type == e.type && x.param == e.param) found = true
                    if (!found) inAll = false
                }
                if (inAll) lockout.add(e.label())
            }
        }

        var undecidable = false
        for (b in lock.branches) for (e in b) {
            if ((e.type == Elem.BNSN || e.type == Elem.LAN) && !e.outside) undecidable = true
        }

        val remedies = mutableListOf<String>()
        if (!defended[2]) {
            remedies.add("端末を解析できる相手に備えるなら、秘密が端末の外にある要素（分散片・二台目の端末）をすべての枝に入れてください")
        }
        if (lock.branches.size > 1 && weakTop <= 2) {
            remedies.add("復旧経路（" + weakest + "）が実効強度を決めています。本経路と同じ強さにするか、意図して弱くしていることを承知してください")
        }
        if (undecidable) {
            remedies.add("分散片や二台目の端末を「家の外に置いた」と申告していないため、端末内にあるものとして判定しています")
        }
        if (lockout.size > 0 && lock.branches.size == 1) {
            remedies.add("失うと開かなくなる要素があります。復旧経路をもう1本足すと締め出しを防げます（ただし強度は弱いほうに揃います）")
        }

        return LockDiag(defended, weakest, lockout, undecidable, remedies)
    }

    // ---------- プリセット ----------

    val PRESET_NAMES = arrayOf(
        "かんたん（隠し場所だけ）",
        "金庫（暗証番号）",
        "厳重（鍵アイテム＋暗証番号）",
        "二台金庫（二台目の端末）",
        "最深部（分散片＋暗証番号）",
        "厳重な二台金庫（二台目＋暗証番号）",
        "家族金庫（分散片＋暗証番号 または 暗証番号）",
        "錠をはずす"
    )

    const val NEED_KEY = 0
    const val NEED_PIN = 1
    const val NEED_BNSN = 2
    const val NEED_LAN = 3

    fun presetNeeds(idx: Int): IntArray = when (idx) {
        1 -> intArrayOf(NEED_PIN)
        2 -> intArrayOf(NEED_KEY, NEED_PIN)
        3 -> intArrayOf(NEED_LAN)
        4 -> intArrayOf(NEED_BNSN, NEED_PIN)
        5 -> intArrayOf(NEED_LAN, NEED_PIN)
        6 -> intArrayOf(NEED_BNSN, NEED_PIN)
        else -> intArrayOf()
    }

    fun isRemove(idx: Int): Boolean = idx == 7

    fun buildPreset(
        idx: Int, itemId: String, pinHash: String, bnsnParam: String,
        lanParam: String, outside: Boolean, lanOutside: Boolean
    ): Lock {
        return when (idx) {
            0 -> Lock.single(LockElem(Elem.HIDDEN, "", false))
            1 -> Lock.single(LockElem(Elem.PIN, pinHash, false))
            2 -> Lock.single(LockElem(Elem.KEY, itemId, false), LockElem(Elem.PIN, pinHash, false))
            3 -> Lock.single(LockElem(Elem.LAN, lanParam, lanOutside))
            4 -> Lock.single(LockElem(Elem.BNSN, bnsnParam, outside), LockElem(Elem.PIN, pinHash, false))
            5 -> Lock.single(LockElem(Elem.LAN, lanParam, lanOutside), LockElem(Elem.PIN, pinHash, false))
            6 -> Lock(
                mutableListOf(
                    mutableListOf(LockElem(Elem.BNSN, bnsnParam, outside), LockElem(Elem.PIN, pinHash, false)),
                    mutableListOf(LockElem(Elem.PIN, pinHash, false))
                )
            )
            else -> Lock.none()
        }
    }

    // ---------- selfTest（ONTOLOGY 9節の8ケース） ----------

    fun selfTest(): String {
        val fails = mutableListOf<String>()

        fun chk(name: String, cond: Boolean) {
            if (!cond) fails.add(name)
        }

        val hidden = Lock.single(LockElem(Elem.HIDDEN, "", false))
        var d = diagnose(hidden)
        chk("1 隠し場所のみ→A2以上で防御不可", !d.defended[1] && !d.defended[2] && !d.defended[3])

        val pin = Lock.single(LockElem(Elem.PIN, "s:h", false))
        d = diagnose(pin)
        chk("2 PINのみ→A2防御可・A3不可", d.defended[1] && !d.defended[2])

        val keyPin = Lock.single(LockElem(Elem.KEY, "k1", false), LockElem(Elem.PIN, "s:h", false))
        d = diagnose(keyPin)
        chk("3 鍵+PIN→A3で防御不可", !d.defended[2])

        val lan = Lock.single(LockElem(Elem.LAN, "", true))
        d = diagnose(lan)
        chk("4 LANペア→A3防御可・A4不可", d.defended[2] && !d.defended[3])

        val bnsnIn = Lock.single(LockElem(Elem.BNSN, "id:2", false))
        d = diagnose(bnsnIn)
        chk("5 分散片が端末内→所在1に堕ちA2で不可", !d.defended[1] && d.undecidable)

        val bnsnOut = Lock.single(LockElem(Elem.BNSN, "id:2", true))
        d = diagnose(bnsnOut)
        chk("6 分散片が外→A3防御可", d.defended[2] && !d.undecidable)

        val strongOrPin = Lock(
            mutableListOf(
                mutableListOf(LockElem(Elem.BNSN, "id:2", true), LockElem(Elem.LAN, "", true)),
                mutableListOf(LockElem(Elem.PIN, "s:h", false))
            )
        )
        d = diagnose(strongOrPin)
        chk("7 強経路 OR PIN→実効強度はPINに落ちる", !d.defended[2] && d.weakest.contains("暗証番号"))

        d = diagnose(Lock.single(LockElem(Elem.LAN, "", false)))
        chk("8 所在未申告→UNDECIDABLEで安全側", d.undecidable && !d.defended[1])

        // 締め出し判定: すべての枝に共通する要素だけが挙がる
        d = diagnose(strongOrPin)
        chk("9 締め出し要素は全枝共通のみ", d.lockoutRisk.isEmpty())
        d = diagnose(keyPin)
        chk("10 単一枝は全要素が締め出し要素", d.lockoutRisk.size == 2)

        return if (fails.isEmpty()) "" else "錠の自己テスト失敗: " + fails.joinToString(" / ")
    }
}
