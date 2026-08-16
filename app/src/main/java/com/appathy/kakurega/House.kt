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

class SlotDef(val id: String, var name: String, var hint: String, var lockPreset: String) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("hint", hint)
        o.put("lock", lockPreset)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): SlotDef = SlotDef(
            o.optString("id", newId("k")),
            o.optString("name", "収納"),
            o.optString("hint", ""),
            o.optString("lock", "none")
        )
    }
}

fun newId(prefix: String): String =
    prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10)

class House(
    var version: Int,
    var startScene: String,
    val scenes: MutableList<Scene>,
    val slots: MutableList<SlotDef>
) {
    fun scene(id: String): Scene? = scenes.firstOrNull { it.id == id }
    fun slot(id: String): SlotDef? = slots.firstOrNull { it.id == id }

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
                if (scenes.isEmpty()) seed()
                else House(
                    o.optInt("version", 1),
                    o.optString("startScene", scenes[0].id),
                    scenes,
                    slots
                )
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

        private fun seed(): House {
            val slots = mutableListOf(
                SlotDef("bookshelf", "本棚", "PDF・テキスト向け（どのファイルでも置けます）", "none"),
                SlotDef("stereo", "ステレオ", "音楽向け（どのファイルでも置けます）", "none"),
                SlotDef("tvstand", "テレビ台", "動画向け（どのファイルでも置けます）", "none"),
                SlotDef("drawer", "引き出し", "写真向け（どのファイルでも置けます）", "none"),
                SlotDef("floor", "床下", "隠し場所。なんでも", "none")
            )
            val hs = mutableListOf(
                Hotspot("h_bookshelf", "本棚", 0.05f, 0.30f, 0.20f, 0.45f, false, Hotspot.KIND_SLOT, "bookshelf", "bookshelf"),
                Hotspot("h_stereo", "ステレオ", 0.30f, 0.54f, 0.16f, 0.13f, false, Hotspot.KIND_SLOT, "stereo", "stereo"),
                Hotspot("h_tvstand", "テレビ台", 0.50f, 0.63f, 0.26f, 0.12f, false, Hotspot.KIND_SLOT, "tvstand", "tvstand"),
                Hotspot("h_drawer", "引き出し", 0.79f, 0.47f, 0.17f, 0.28f, false, Hotspot.KIND_SLOT, "drawer", "drawer"),
                Hotspot("h_floor", "床下", 0.36f, 0.86f, 0.18f, 0.09f, true, Hotspot.KIND_SLOT, "floor", "floor")
            )
            val living = Scene("living", "リビング", null, hs)
            return House(1, "living", mutableListOf(living), slots)
        }
    }
}
