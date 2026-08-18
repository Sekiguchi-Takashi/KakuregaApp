package com.appathy.kakurega

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class Hotspot(
    val id: String,
    var label: String,
    var rx: Float,
    var ry: Float,
    var rw: Float,
    var rh: Float,
    var hidden: Boolean,
    var kind: String,
    var target: String,
    var art: String
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("label", label)
        o.put("rx", rx.toDouble())
        o.put("ry", ry.toDouble())
        o.put("rw", rw.toDouble())
        o.put("rh", rh.toDouble())
        o.put("hidden", hidden)
        o.put("kind", kind)
        o.put("target", target)
        o.put("art", art)
        return o
    }

    companion object {
        const val KIND_GOTO = "goto"
        const val KIND_SLOT = "slot"

        fun fromJson(o: JSONObject): Hotspot = Hotspot(
            o.optString("id", newId("h")),
            o.optString("label", ""),
            o.optDouble("rx", 0.1).toFloat(),
            o.optDouble("ry", 0.1).toFloat(),
            o.optDouble("rw", 0.2).toFloat(),
            o.optDouble("rh", 0.2).toFloat(),
            o.optBoolean("hidden", false),
            o.optString("kind", KIND_SLOT),
            o.optString("target", ""),
            o.optString("art", "panel")
        )
    }
}

class Scene(
    val id: String,
    var name: String,
    var image: String?,
    val hotspots: MutableList<Hotspot>
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        if (image != null) o.put("image", image)
        val a = JSONArray()
        for (h in hotspots) a.put(h.toJson())
        o.put("hotspots", a)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Scene {
            val list = mutableListOf<Hotspot>()
            val a = o.optJSONArray("hotspots")
            if (a != null) {
                for (i in 0 until a.length()) list.add(Hotspot.fromJson(a.getJSONObject(i)))
            }
            val img = if (o.isNull("image")) null else o.optString("image", "")
            return Scene(
                o.optString("id", newId("s")),
                o.optString("name", "部屋"),
                if (img.isNullOrBlank()) null else img,
                list
            )
        }
    }
}

class SlotDef(
    val id: String,
    var name: String,
    var hint: String,
    var lock: Lock,
    var shareVault: Boolean
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("hint", hint)
        o.put("lock", lock.toJson())
        o.put("shareVault", shareVault)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SlotDef = SlotDef(
            o.optString("id", newId("k")),
            o.optString("name", "収納"),
            o.optString("hint", ""),
            Lock.fromJson(o.optJSONObject("lock")),
            o.optBoolean("shareVault", false)
        )
    }
}

// 鍵アイテム。at = 置いてあるホットスポットのid / "inventory" = 入手済み
class Item(val id: String, var name: String, var at: String) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("at", at)
        return o
    }

    companion object {
        const val INVENTORY = "inventory"
        fun fromJson(o: JSONObject): Item =
            Item(o.optString("id", newId("i")), o.optString("name", "鍵"), o.optString("at", INVENTORY))
    }
}

const val ASSET_PREFIX = "asset:"

fun newId(prefix: String): String =
    prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10)

class House(
    var version: Int,
    var startScene: String,
    val scenes: MutableList<Scene>,
    val slots: MutableList<SlotDef>,
    val items: MutableList<Item>
) {
    fun scene(id: String): Scene? = scenes.firstOrNull { it.id == id }
    fun slot(id: String): SlotDef? = slots.firstOrNull { it.id == id }

    fun item(id: String): Item? = items.firstOrNull { it.id == id }
    fun itemsAt(hotspotId: String): List<Item> = items.filter { it.at == hotspotId }
    fun hasItem(id: String): Boolean = items.any { it.id == id && it.at == Item.INVENTORY }

    fun hotspotOfSlot(slotId: String): Hotspot? {
        for (s in scenes) for (h in s.hotspots) {
            if (h.kind == Hotspot.KIND_SLOT && h.target == slotId) return h
        }
        return null
    }

    fun sceneOfSlot(slotId: String): Scene? =
        scenes.firstOrNull { s -> s.hotspots.any { it.kind == Hotspot.KIND_SLOT && it.target == slotId } }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("version", version)
        o.put("startScene", startScene)
        val a = JSONArray()
        for (s in scenes) a.put(s.toJson())
        o.put("scenes", a)
        val b = JSONArray()
        for (s in slots) b.put(s.toJson())
        o.put("slots", b)
        val c = JSONArray()
        for (s in items) c.put(s.toJson())
        o.put("items", c)
        return o
    }

    companion object {
        private const val FILE = "house.json"

        fun load(ctx: Context): House {
            val f = File(ctx.filesDir, FILE)
            if (!f.exists()) {
                val h = seed()
                save(ctx, h)
                return h
            }
            return try {
                val o = JSONObject(f.readText())
                val scenes = mutableListOf<Scene>()
                val sa = o.optJSONArray("scenes")
                if (sa != null) for (i in 0 until sa.length()) scenes.add(Scene.fromJson(sa.getJSONObject(i)))
                val slots = mutableListOf<SlotDef>()
                val ka = o.optJSONArray("slots")
                if (ka != null) for (i in 0 until ka.length()) slots.add(SlotDef.fromJson(ka.getJSONObject(i)))
                val items = mutableListOf<Item>()
                val ia = o.optJSONArray("items")
                if (ia != null) for (i in 0 until ia.length()) items.add(Item.fromJson(ia.getJSONObject(i)))
                if (scenes.isEmpty()) {
                    seed()
                } else {
                    val h = House(
                        o.optInt("version", 1),
                        o.optString("startScene", scenes[0].id),
                        scenes,
                        slots,
                        items
                    )
                    if (migrate(h)) save(ctx, h)
                    h
                }
            } catch (e: Exception) {
                seed()
            }
        }

        fun save(ctx: Context, h: House) {
            try {
                File(ctx.filesDir, FILE).writeText(h.toJson().toString(2))
            } catch (e: Exception) {
            }
        }

        // 版が上がったときだけ家を書き足す。既存の部屋・収納・配置には触れない
        private fun migrate(h: House): Boolean {
            var changed = false
            if (h.version < 2) {
                if (h.scene("atelier") == null) {
                    val hs = mutableListOf<Hotspot>()
                    for (r in ATELIER) {
                        val slotId = r[0] as String
                        val name = r[1] as String
                        val kind = r[6] as String
                        if (kind == Hotspot.KIND_SLOT && h.slot(slotId) == null) {
                            h.slots.add(SlotDef(slotId, name, r[7] as String, Lock.none(), false))
                        }
                        hs.add(
                            Hotspot(
                                "h_" + slotId, name,
                                (r[2] as Double).toFloat(), (r[3] as Double).toFloat(),
                                (r[4] as Double).toFloat(), (r[5] as Double).toFloat(),
                                r[8] as Boolean, kind, slotId, "panel"
                            )
                        )
                    }
                    val at = Scene("atelier", "資料室", ASSET_PREFIX + "room_atelier.jpg", hs)
                    h.scenes.add(at)
                    val living = h.scene("living")
                    if (living != null && living.hotspots.none { it.kind == Hotspot.KIND_GOTO && it.target == "atelier" }) {
                        living.hotspots.add(
                            Hotspot(newId("h"), "資料室へ", 0.02f, 0.10f, 0.13f, 0.20f,
                                false, Hotspot.KIND_GOTO, "atelier", "door")
                        )
                    }
                    h.startScene = "atelier"
                }
                h.version = 2
                changed = true
            }
            return changed
        }

        // slotId, 表示名, rx, ry, rw, rh, kind, ヒント, 隠しか
        private val ATELIER: List<List<Any>> = listOf(
            listOf("at_bookshelf", "本棚", 0.005, 0.03, 0.14, 0.34, Hotspot.KIND_SLOT, "背表紙の奥", false),
            listOf("at_drawer", "引き出し", 0.005, 0.51, 0.155, 0.16, Hotspot.KIND_SLOT, "写真向け", false),
            listOf("at_files", "書類棚", 0.26, 0.41, 0.27, 0.10, Hotspot.KIND_SLOT, "窓下の棚", false),
            listOf("at_table", "作業台", 0.33, 0.48, 0.32, 0.06, Hotspot.KIND_SLOT, "広げっぱなしの資料", false),
            listOf("at_boxes", "台の下の箱", 0.34, 0.555, 0.30, 0.11, Hotspot.KIND_SLOT, "まとめて放り込む場所", false),
            listOf("at_shelf_r", "資料棚", 0.80, 0.30, 0.195, 0.38, Hotspot.KIND_SLOT, "右壁いっぱいの棚", false),
            listOf("at_loft", "ロフト棚", 0.71, 0.03, 0.28, 0.16, Hotspot.KIND_SLOT, "はしごを登った先", false),
            listOf("at_coffee", "コーヒーテーブル", 0.60, 0.83, 0.32, 0.16, Hotspot.KIND_SLOT, "手前の低い机", false),
            listOf("at_sofa", "ソファ", 0.855, 0.68, 0.14, 0.13, Hotspot.KIND_SLOT, "クッションの間", false),
            listOf("living", "リビングへ", 0.72, 0.32, 0.06, 0.19, Hotspot.KIND_GOTO, "", false),
            listOf("at_clock", "時計の裏", 0.428, 0.235, 0.035, 0.055, Hotspot.KIND_SLOT, "文字盤の裏側", true),
            listOf("at_pic_c", "絵の裏", 0.43, 0.295, 0.03, 0.05, Hotspot.KIND_SLOT, "額と壁のすきま", true),
            listOf("at_pic_r", "額の裏", 0.673, 0.33, 0.028, 0.045, Hotspot.KIND_SLOT, "扉の横の額", true),
            listOf("at_pot", "鉢植えの土", 0.165, 0.52, 0.05, 0.08, Hotspot.KIND_SLOT, "根の下", true),
            listOf("at_ladder_l", "はしごの裏", 0.10, 0.38, 0.10, 0.12, Hotspot.KIND_SLOT, "立てかけた影", true),
            listOf("at_ladder_r", "はしごの段", 0.77, 0.25, 0.07, 0.12, Hotspot.KIND_SLOT, "踏板の裏", true),
            listOf("at_rug", "ラグの下", 0.27, 0.67, 0.42, 0.07, Hotspot.KIND_SLOT, "めくった下", true),
            listOf("at_floor", "床板の下", 0.38, 0.76, 0.16, 0.06, Hotspot.KIND_SLOT, "浮いている一枚", true),
            listOf("at_book", "本のページ", 0.01, 0.85, 0.19, 0.14, Hotspot.KIND_SLOT, "開いたまま挟む", true),
            listOf("at_pen", "ペン立て", 0.235, 0.83, 0.065, 0.11, Hotspot.KIND_SLOT, "ペンの底", true),
            listOf("at_greenbook", "緑の本の下", 0.30, 0.91, 0.13, 0.085, Hotspot.KIND_SLOT, "積んだ本の間", true),
            listOf("at_mug", "マグカップ", 0.785, 0.90, 0.045, 0.07, Hotspot.KIND_SLOT, "飲みかけの底", true),
            listOf("at_light", "天井の照明", 0.325, 0.085, 0.035, 0.028, Hotspot.KIND_SLOT, "埋め込みの奥", true),
            listOf("at_windowpot", "窓辺の鉢", 0.385, 0.345, 0.04, 0.055, Hotspot.KIND_SLOT, "受け皿の下", true),
            listOf("at_chair", "椅子の下", 0.60, 0.42, 0.055, 0.06, Hotspot.KIND_SLOT, "座面の裏", true)
        )

        private fun seed(): House {
            val slots = mutableListOf(
                SlotDef("bookshelf", "本棚", "PDF・テキスト向け（どのファイルでも置けます）", Lock.none(), false),
                SlotDef("stereo", "ステレオ", "音楽向け（どのファイルでも置けます）", Lock.none(), false),
                SlotDef("tvstand", "テレビ台", "動画向け（どのファイルでも置けます）", Lock.none(), false),
                SlotDef("drawer", "引き出し", "写真向け（どのファイルでも置けます）", Lock.none(), false),
                SlotDef("floor", "床下", "隠し場所。なんでも", Lock.none(), false)
            )
            val hs = mutableListOf(
                Hotspot("h_bookshelf", "本棚", 0.05f, 0.30f, 0.20f, 0.45f, false, Hotspot.KIND_SLOT, "bookshelf", "bookshelf"),
                Hotspot("h_stereo", "ステレオ", 0.30f, 0.54f, 0.16f, 0.13f, false, Hotspot.KIND_SLOT, "stereo", "stereo"),
                Hotspot("h_tvstand", "テレビ台", 0.50f, 0.63f, 0.26f, 0.12f, false, Hotspot.KIND_SLOT, "tvstand", "tvstand"),
                Hotspot("h_drawer", "引き出し", 0.79f, 0.47f, 0.17f, 0.28f, false, Hotspot.KIND_SLOT, "drawer", "drawer"),
                Hotspot("h_floor", "床下", 0.36f, 0.86f, 0.18f, 0.09f, true, Hotspot.KIND_SLOT, "floor", "floor")
            )
            val living = Scene("living", "リビング", null, hs)
            val h = House(1, "living", mutableListOf(living), slots, mutableListOf())
            migrate(h)
            return h
        }
    }
}
