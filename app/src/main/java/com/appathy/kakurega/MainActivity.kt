package com.appathy.kakurega

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : Activity() {

    private lateinit var db: Db
    private lateinit var house: House
    private lateinit var root: FrameLayout

    private var screen = "scene"
    private var sceneId = ""
    private var currentSlot: String? = null
    private var editing = false
    private var pendingExportId: Long = -1
    private var player: MediaPlayer? = null
    private var imgTargetScene: String? = null
    private var pendingLockSlot: String? = null
    private var pendingLockElem: LockElem? = null

    private val vaultDir: File by lazy {
        val d = File(filesDir, "vault")
        if (!d.exists()) d.mkdirs()
        val nm = File(d, ".nomedia")
        if (!nm.exists()) nm.createNewFile()
        d
    }

    private val sceneDir: File by lazy {
        val d = File(filesDir, "scenes")
        if (!d.exists()) d.mkdirs()
        val nm = File(d, ".nomedia")
        if (!nm.exists()) nm.createNewFile()
        d
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        house = House.load(this)
        sceneId = house.startScene
        if (house.scene(sceneId) == null && house.scenes.size > 0) sceneId = house.scenes[0].id
        root = FrameLayout(this)
        setContentView(root)
        showScene()
        val bad = LockRules.selfTest()
        if (bad.length > 0) {
            AlertDialog.Builder(this)
                .setTitle("錠の自己テストに失敗")
                .setMessage(bad + "\n\n診断の表示が信用できない状態です。")
                .setPositiveButton("わかった", null)
                .show()
        }
    }

    private fun save() {
        House.save(this, house)
    }

    private val opened = mutableSetOf<String>()   // このセッションで開けた収納

    private fun prefs() = getSharedPreferences("kakurega", MODE_PRIVATE)

    // ---------- 部屋 ----------

    private fun showScene() {
        stopPlayer()
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        screen = "scene"
        currentSlot = null
        root.removeAllViews()
        val sc = house.scene(sceneId)
        if (sc == null) {
            toast("部屋がありません")
            return
        }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.parseColor("#1B1B22"))

        val bar = LinearLayout(this)
        bar.setPadding(20, 16, 20, 8)
        val title = TextView(this)
        title.text = sc.name + (if (editing) "　［増築モード］" else "")
        title.setTextColor(if (editing) Color.parseColor("#FFD54F") else Color.parseColor("#DDDDEE"))
        title.textSize = 16f
        title.gravity = Gravity.CENTER_VERTICAL
        val menu = Button(this)
        menu.text = "メニュー"
        menu.setOnClickListener { mainMenu() }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(menu)
        col.addView(bar)

        val note = TextView(this)
        note.text = if (editing)
            "空いている所を指でなぞって新しい場所を作る／既存の枠をタップで編集"
        else
            "家具をタップ　長押しで調べられる場所を表示"
        note.setTextColor(Color.parseColor("#8888AA"))
        note.textSize = 11f
        note.setPadding(20, 0, 20, 10)
        col.addView(note)

        val bmp = loadSceneBitmap(sc)
        val view = SceneView(this, sc, bmp, { db.counts() }, { h -> onSpot(h) })
        view.editMode = editing
        view.onDrawn = { l, t, w, hh -> createSpot(sc, l, t, w, hh) }
        col.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(col)
    }

    private fun onSpot(h: Hotspot) {
        if (editing) {
            editSpot(h)
            return
        }
        val found = house.itemsAt(h.id)
        if (found.isNotEmpty()) {
            for (it2 in found) it2.at = Item.INVENTORY
            save()
            AlertDialog.Builder(this)
                .setTitle("見つけた")
                .setMessage(found.joinToString("、") { it.name } + " を手に入れた")
                .setPositiveButton("持つ", null)
                .show()
        }
        if (h.kind == Hotspot.KIND_GOTO) {
            if (house.scene(h.target) == null) {
                toast("行き先の部屋がありません")
                return
            }
            sceneId = h.target
            showScene()
        } else {
            tryOpen(h.target)
        }
    }

    // ---------- 錠 ----------

    private fun tryOpen(slotId: String) {
        val def = house.slot(slotId)
        if (def == null || def.lock.isOpen() || opened.contains(slotId)) {
            showSlot(slotId)
            return
        }
        val branches = def.lock.branches
        // 満たせる枝を探す。足りない要素があれば、その要素の解除手順へ進む
        for (b in branches) {
            if (b.all { satisfied(it) }) {
                opened.add(slotId)
                showSlot(slotId)
                return
            }
        }
        // 一番あと少しの枝を選び、最初の未達要素を解除させる
        var best: MutableList<LockElem>? = null
        var bestMissing = 99
        for (b in branches) {
            val miss = b.count { !satisfied(it) }
            if (miss < bestMissing) {
                bestMissing = miss
                best = b
            }
        }
        val branch = best ?: return
        val need = branch.firstOrNull { !satisfied(it) } ?: return
        when (need.type) {
            Elem.PIN -> askPin(slotId, need)
            Elem.KEY -> {
                val nm = house.item(need.param)?.name ?: "鍵"
                AlertDialog.Builder(this)
                    .setTitle(def.name)
                    .setMessage(nm + " が要る。家のどこかにあるはずだ。")
                    .setPositiveButton("わかった", null)
                    .show()
            }
            Elem.BNSN -> askShares(slotId, need)
            Elem.LAN -> askLan(slotId, need)
            else -> showSlot(slotId)
        }
    }

    private fun satisfied(e: LockElem): Boolean = when (e.type) {
        Elem.HIDDEN -> true                       // たどり着いた時点で満たされている
        Elem.KEY -> house.hasItem(e.param)
        Elem.PIN -> pinOk.contains(e.param)
        Elem.BNSN -> bnsnOk.contains(e.param)
        Elem.LAN -> lanOk.contains(e.param)
        else -> false
    }

    private val pinOk = mutableSetOf<String>()
    private val bnsnOk = mutableSetOf<String>()
    private val lanOk = mutableSetOf<String>()

    private fun askLan(slotId: String, e: LockElem) {
        AlertDialog.Builder(this)
            .setTitle("二台目の端末が要る")
            .setMessage("相手の端末でカクレガを開き、メニューの「二台目の端末」から「この端末を鍵にする」を選んでください。同じWi-Fiに繋いだうえで探します。")
            .setPositiveButton("探す") { _, _ ->
                toast("探しています…")
                Lan.unlock(this, e.param) { ok, msg ->
                    toast(msg)
                    if (ok) {
                        lanOk.add(e.param)
                        tryOpen(slotId)
                    }
                }
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun askPin(slotId: String, e: LockElem) {
        val until = prefs().getLong("pinlock_" + slotId, 0L)
        val now = System.currentTimeMillis()
        if (now < until) {
            toast("しばらく待ってください（あと " + ((until - now) / 1000 + 1) + " 秒）")
            return
        }
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        et.hint = "暗証番号"
        AlertDialog.Builder(this)
            .setTitle("暗証番号")
            .setView(et)
            .setPositiveButton("開ける") { _, _ ->
                if (checkPin(et.text.toString(), e.param)) {
                    pinOk.add(e.param)
                    prefs().edit().putInt("pinfail_" + slotId, 0).apply()
                    tryOpen(slotId)
                } else {
                    val n = prefs().getInt("pinfail_" + slotId, 0) + 1
                    val ed = prefs().edit().putInt("pinfail_" + slotId, n)
                    if (n >= 5) {
                        val wait = 30000L * (1L shl Math.min(n - 5, 6))
                        ed.putLong("pinlock_" + slotId, System.currentTimeMillis() + wait)
                        toast("違います。" + (wait / 1000) + " 秒待ってください")
                    } else {
                        toast("違います")
                    }
                    ed.apply()
                }
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun askShares(slotId: String, e: LockElem) {
        val parts = e.param.split(":")
        val k = if (parts.size > 1) (parts[1].toIntOrNull() ?: 2) else 2
        AlertDialog.Builder(this)
            .setTitle("分散片が要る")
            .setMessage("この錠は分散片を " + k + " 個そろえると開きます。端末の中には取り込みません。開けるときだけ選んでください。")
            .setPositiveButton("片を選ぶ") { _, _ ->
                pendingLockSlot = slotId
                pendingLockElem = e
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "*/*"
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                startActivityForResult(i, REQ_SHARES)
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun checkPin(input: String, stored: String): Boolean {
        val ix = stored.indexOf(':')
        if (ix <= 0) return false
        val salt = stored.substring(0, ix)
        return hashPin(salt, input) == stored
    }

    private fun hashPin(salt: String, pin: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val d = md.digest((salt + "|" + pin).toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(salt)
        sb.append(':')
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun newPinHash(pin: String): String {
        val r = java.security.SecureRandom()
        val s = ByteArray(8)
        r.nextBytes(s)
        val sb = StringBuilder()
        for (b in s) sb.append(String.format("%02x", b))
        return hashPin(sb.toString(), pin)
    }

    // .bnsn のヘッダだけを読む（BunsanApp の FORMAT_SPEC.md 準拠、復元はしない）
    private fun readBnsn(u: Uri): Pair<String, Int>? {
        return try {
            contentResolver.openInputStream(u)?.use { ins ->
                val magic = ByteArray(4)
                if (ins.read(magic) != 4) return null
                if (String(magic, Charsets.US_ASCII) != "BNSN") return null
                val lenB = ByteArray(4)
                if (ins.read(lenB) != 4) return null
                var len = 0
                for (b in lenB) len = (len shl 8) or (b.toInt() and 0xFF)
                if (len <= 0 || len > 65536) return null
                val hb = ByteArray(len)
                var off = 0
                while (off < len) {
                    val r = ins.read(hb, off, len - off)
                    if (r <= 0) break
                    off += r
                }
                val o = org.json.JSONObject(String(hb, 0, off, Charsets.UTF_8))
                val id = o.optString("id", "")
                val x = o.optInt("x", -1)
                if (id.length == 0 || x < 0) null else Pair(id, x)
            }
        } catch (ex: Exception) {
            null
        }
    }

    private fun loadSceneBitmap(sc: Scene): Bitmap? {
        val nm = sc.image
        if (nm == null) return null
        if (nm.startsWith(ASSET_PREFIX)) {
            return try {
                assets.open(nm.substring(ASSET_PREFIX.length)).use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
        }
        val f = File(sceneDir, nm)
        if (!f.exists()) return null
        return decodeScaled(f)
    }

    // ---------- メニュー ----------

    private fun mainMenu() {
        val items = if (editing)
            arrayOf("部屋の一覧・追加", "この部屋の画像を選ぶ", "この部屋の名前", "増築モードを終わる")
        else
            arrayOf("調べられる場所を光らせる", "持ちもの", "二台目の端末", "部屋の一覧・追加", "増築モードに入る")
        AlertDialog.Builder(this)
            .setTitle(if (editing) "増築メニュー" else "メニュー")
            .setItems(items) { _, w ->
                if (editing) {
                    when (w) {
                        0 -> showScenes()
                        1 -> pickSceneImage(sceneId)
                        2 -> renameScene()
                        3 -> {
                            editing = false
                            showScene()
                        }
                    }
                } else {
                    when (w) {
                        0 -> showScene().also { root.postDelayed({ hintNow() }, 100) }
                        1 -> showInventory()
                        2 -> lanMenu()
                        3 -> showScenes()
                        4 -> {
                            editing = true
                            showScene()
                        }
                    }
                }
            }
            .show()
    }

    private fun hintNow() {
        val col = root.getChildAt(0)
        if (col is LinearLayout) {
            for (i in 0 until col.childCount) {
                val v = col.getChildAt(i)
                if (v is SceneView) v.showHints()
            }
        }
    }

    private fun renameScene() {
        val sc = house.scene(sceneId) ?: return
        val et = EditText(this)
        et.setText(sc.name)
        AlertDialog.Builder(this)
            .setTitle("部屋の名前")
            .setView(et)
            .setPositiveButton("決定") { _, _ ->
                val t = et.text.toString().trim()
                if (t.length > 0) {
                    sc.name = t
                    save()
                    showScene()
                }
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    // ---------- 部屋の一覧 ----------

    private fun showScenes() {
        screen = "scenes"
        root.removeAllViews()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.parseColor("#22222A"))
        col.setPadding(24, 24, 24, 24)
        val t = TextView(this)
        t.text = "部屋の一覧"
        t.textSize = 20f
        t.setTextColor(Color.WHITE)
        col.addView(t)
        val note = TextView(this)
        note.text = "★は最初に入る部屋。タップでその部屋へ移動、長押しで操作。"
        note.textSize = 11f
        note.setTextColor(Color.parseColor("#8888AA"))
        col.addView(note)

        val sv = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        for (s in house.scenes) {
            val row = TextView(this)
            val star = if (s.id == house.startScene) "★ " else "　 "
            val img = if (s.image == null) "（描画）" else "（画像）"
            row.text = star + s.name + "　" + img + "　場所 " + s.hotspots.size
            row.setTextColor(Color.parseColor("#DDDDEE"))
            row.setPadding(8, 26, 8, 26)
            row.setOnClickListener {
                sceneId = s.id
                showScene()
            }
            row.setOnLongClickListener {
                sceneMenu(s)
                true
            }
            list.addView(row)
        }
        sv.addView(list)
        col.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val btns = LinearLayout(this)
        val add = Button(this)
        add.text = "部屋を追加"
        add.setOnClickListener { addScene() }
        val back = Button(this)
        back.text = "もどる"
        back.setOnClickListener { showScene() }
        btns.addView(add, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(back, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(btns)
        root.addView(col)
    }

    private fun sceneMenu(s: Scene) {
        val items = arrayOf("画像を選ぶ", "画像を外す", "名前を変える", "最初に入る部屋にする", "この部屋へ行く扉を今の部屋に作る", "部屋を削除")
        AlertDialog.Builder(this)
            .setTitle(s.name)
            .setItems(items) { _, w ->
                when (w) {
                    0 -> pickSceneImage(s.id)
                    1 -> {
                        s.image = null
                        save()
                        showScenes()
                    }
                    2 -> {
                        val et = EditText(this)
                        et.setText(s.name)
                        AlertDialog.Builder(this)
                            .setTitle("部屋の名前")
                            .setView(et)
                            .setPositiveButton("決定") { _, _ ->
                                val t = et.text.toString().trim()
                                if (t.length > 0) {
                                    s.name = t
                                    save()
                                    showScenes()
                                }
                            }
                            .setNegativeButton("やめる", null)
                            .show()
                    }
                    3 -> {
                        house.startScene = s.id
                        save()
                        showScenes()
                    }
                    4 -> makeDoorTo(s)
                    5 -> deleteScene(s)
                }
            }
            .show()
    }

    private fun makeDoorTo(s: Scene) {
        val cur = house.scene(sceneId)
        if (cur == null) return
        if (cur.id == s.id) {
            toast("同じ部屋です")
            return
        }
        cur.hotspots.add(
            Hotspot(
                newId("h"), s.name + "へ", 0.02f, 0.10f, 0.14f, 0.22f,
                false, Hotspot.KIND_GOTO, s.id, "door"
            )
        )
        save()
        toast(cur.name + " に扉を作りました（増築モードで位置を直せます）")
        showScenes()
    }

    private fun deleteScene(s: Scene) {
        if (house.scenes.size <= 1) {
            toast("最後の部屋は消せません")
            return
        }
        val slotIds = mutableListOf<String>()
        for (h in s.hotspots) if (h.kind == Hotspot.KIND_SLOT) slotIds.add(h.target)
        var files = 0
        for (id in slotIds) files += db.filesIn(id).size
        val msg = if (files > 0)
            "この部屋には " + files + " 個のファイルがあります。部屋を消してもファイルは残りますが、たどり着けなくなります。"
        else
            "この部屋を削除します。"
        AlertDialog.Builder(this)
            .setTitle(s.name + " を削除")
            .setMessage(msg)
            .setPositiveButton("削除") { _, _ ->
                house.scenes.remove(s)
                for (o in house.scenes) {
                    val it = o.hotspots.iterator()
                    while (it.hasNext()) {
                        val h = it.next()
                        if (h.kind == Hotspot.KIND_GOTO && h.target == s.id) it.remove()
                    }
                }
                if (house.startScene == s.id) house.startScene = house.scenes[0].id
                if (sceneId == s.id) sceneId = house.scenes[0].id
                save()
                showScenes()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun addScene() {
        val et = EditText(this)
        et.hint = "部屋の名前"
        AlertDialog.Builder(this)
            .setTitle("部屋を追加")
            .setView(et)
            .setPositiveButton("追加") { _, _ ->
                val t = et.text.toString().trim()
                val name = if (t.length > 0) t else "新しい部屋"
                val s = Scene(newId("s"), name, null, mutableListOf())
                house.scenes.add(s)
                save()
                showScenes()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun pickSceneImage(target: String) {
        imgTargetScene = target
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        startActivityForResult(i, REQ_SCENE_IMG)
    }

    // ---------- ホットスポット編集 ----------

    private fun createSpot(sc: Scene, l: Float, t: Float, w: Float, h: Float) {
        val items = arrayOf("収納（ファイルを置く）", "移動口（別の部屋へ）")
        AlertDialog.Builder(this)
            .setTitle("ここを何にしますか")
            .setItems(items) { _, which ->
                if (which == 0) {
                    val et = EditText(this)
                    et.hint = "名前（例: たんす）"
                    val cb = CheckBox(this)
                    cb.text = "隠し場所にする（名前も枠も出さない）"
                    val box = LinearLayout(this)
                    box.orientation = LinearLayout.VERTICAL
                    box.setPadding(40, 20, 40, 0)
                    box.addView(et)
                    box.addView(cb)
                    AlertDialog.Builder(this)
                        .setTitle("収納を作る")
                        .setView(box)
                        .setPositiveButton("作る") { _, _ ->
                            val nm = et.text.toString().trim()
                            val name = if (nm.length > 0) nm else "収納"
                            val slotId = newId("k")
                            house.slots.add(SlotDef(slotId, name, "どのファイルでも置けます", Lock.none()))
                            sc.hotspots.add(
                                Hotspot(newId("h"), name, l, t, w, h, cb.isChecked, Hotspot.KIND_SLOT, slotId, "panel")
                            )
                            save()
                            showScene()
                        }
                        .setNegativeButton("やめる", null)
                        .show()
                } else {
                    val others = house.scenes.filter { it.id != sc.id }
                    if (others.isEmpty()) {
                        toast("先に別の部屋を追加してください")
                        return@setItems
                    }
                    val names = others.map { it.name }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("どの部屋へ")
                        .setItems(names) { _, idx ->
                            val dst = others[idx]
                            sc.hotspots.add(
                                Hotspot(newId("h"), dst.name + "へ", l, t, w, h, false, Hotspot.KIND_GOTO, dst.id, "door")
                            )
                            save()
                            showScene()
                        }
                        .show()
                }
            }
            .show()
    }

    private fun editSpot(h: Hotspot) {
        val sc = house.scene(sceneId) ?: return
        val items = arrayOf("名前を変える", if (h.hidden) "隠しを解除" else "隠し場所にする", "少し大きく", "少し小さく", "削除")
        AlertDialog.Builder(this)
            .setTitle(h.label)
            .setItems(items) { _, w ->
                when (w) {
                    0 -> {
                        val et = EditText(this)
                        et.setText(h.label)
                        AlertDialog.Builder(this)
                            .setTitle("名前")
                            .setView(et)
                            .setPositiveButton("決定") { _, _ ->
                                val t = et.text.toString().trim()
                                if (t.length > 0) {
                                    h.label = t
                                    if (h.kind == Hotspot.KIND_SLOT) house.slot(h.target)?.name = t
                                    save()
                                    showScene()
                                }
                            }
                            .setNegativeButton("やめる", null)
                            .show()
                    }
                    1 -> {
                        h.hidden = !h.hidden
                        save()
                        showScene()
                    }
                    2 -> {
                        scaleSpot(h, 1.15f)
                        save()
                        showScene()
                    }
                    3 -> {
                        scaleSpot(h, 0.87f)
                        save()
                        showScene()
                    }
                    4 -> deleteSpot(sc, h)
                }
            }
            .show()
    }

    private fun scaleSpot(h: Hotspot, f: Float) {
        val cx = h.rx + h.rw / 2f
        val cy = h.ry + h.rh / 2f
        var nw = h.rw * f
        var nh = h.rh * f
        if (nw > 0.9f) nw = 0.9f
        if (nh > 0.9f) nh = 0.9f
        if (nw < 0.04f) nw = 0.04f
        if (nh < 0.03f) nh = 0.03f
        h.rw = nw
        h.rh = nh
        h.rx = Math.max(0f, Math.min(1f - nw, cx - nw / 2f))
        h.ry = Math.max(0f, Math.min(1f - nh, cy - nh / 2f))
    }

    private fun deleteSpot(sc: Scene, h: Hotspot) {
        var msg = "この場所を消します。"
        if (h.kind == Hotspot.KIND_SLOT) {
            val n = db.filesIn(h.target).size
            if (n > 0) msg = "中に " + n + " 個のファイルがあります。場所を消してもファイルは残りますが、たどり着けなくなります。"
        }
        AlertDialog.Builder(this)
            .setTitle(h.label + " を消す")
            .setMessage(msg)
            .setPositiveButton("消す") { _, _ ->
                sc.hotspots.remove(h)
                save()
                showScene()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    // ---------- 収納 ----------

    private fun showSlot(slotId: String) {
        stopPlayer()
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        screen = "slot"
        currentSlot = slotId
        root.removeAllViews()
        val def = house.slot(slotId)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.parseColor("#22222A"))
        col.setPadding(24, 24, 24, 24)
        val title = TextView(this)
        title.text = if (def != null) def.name else "収納"
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        val hint = TextView(this)
        hint.text = if (def != null) def.hint else ""
        hint.textSize = 12f
        hint.setTextColor(Color.parseColor("#8888AA"))
        col.addView(title)
        col.addView(hint)

        val listWrap = ScrollView(this)
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val rows = db.filesIn(slotId)
        if (rows.isEmpty()) {
            val empty = TextView(this)
            empty.text = "（空っぽ）"
            empty.setTextColor(Color.parseColor("#666677"))
            empty.setPadding(0, 32, 0, 32)
            list.addView(empty)
        }
        for (f in rows) {
            val tv = TextView(this)
            tv.text = f.origName + "\n" + human(f.size) + "　" + f.mime
            tv.setTextColor(Color.parseColor("#DDDDEE"))
            tv.setPadding(8, 24, 8, 24)
            tv.setOnClickListener { fileMenu(f) }
            list.addView(tv)
        }
        listWrap.addView(list)
        col.addView(listWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (def != null) {
            val lk = TextView(this)
            lk.text = "錠: " + def.lock.describe()
            lk.textSize = 12f
            lk.setTextColor(Color.parseColor("#7BD88F"))
            lk.setPadding(0, 12, 0, 0)
            lk.setOnClickListener { lockMenu(def) }
            col.addView(lk)
        }

        val btns = LinearLayout(this)
        val put = Button(this)
        put.text = "しまう"
        put.setOnClickListener { pickImport() }
        val back = Button(this)
        back.text = "部屋にもどる"
        back.setOnClickListener { showScene() }
        val lockBtn = Button(this)
        lockBtn.text = "錠"
        lockBtn.setOnClickListener { if (def != null) lockMenu(def) }
        btns.addView(put, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(lockBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(back, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(btns)
        root.addView(col)
    }

    // ---------- 錠の設定 ----------

    private fun lockMenu(def: SlotDef) {
        val d = LockRules.diagnose(def.lock)
        val sb = StringBuilder()
        sb.append("いまの錠: ").append(def.lock.describe()).append("\n\n")
        sb.append(badge(d)).append("\n")
        if (d.lockoutRisk.isNotEmpty()) {
            sb.append("失うと開かなくなる: ").append(d.lockoutRisk.joinToString("、")).append("\n")
        }
        for (r in d.remedies) sb.append("・").append(r).append("\n")
        AlertDialog.Builder(this)
            .setTitle(def.name + " の錠")
            .setMessage(sb.toString())
            .setPositiveButton("錠を変える") { _, _ -> choosePreset(def) }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun badge(d: LockDiag): String {
        val names = arrayOf("A1 のぞき見", "A2 端末を触られる", "A3 端末を解析される", "A4 家の中の共犯")
        val sb = StringBuilder()
        for (i in 0 until 4) {
            sb.append(if (d.defended[i]) "○ " else "× ").append(names[i]).append("\n")
        }
        sb.append("実効強度を決めているのは: ").append(d.weakest)
        return sb.toString()
    }

    private fun choosePreset(def: SlotDef) {
        AlertDialog.Builder(this)
            .setTitle("錠を選ぶ")
            .setItems(LockRules.PRESET_NAMES) { _, idx ->
                if (LockRules.isRemove(idx)) {
                    def.lock = Lock.none()
                    save()
                    toast("錠をはずしました")
                    showSlot(def.id)
                    return@setItems
                }
                buildLock(def, idx)
            }
            .show()
    }

    private fun buildLock(def: SlotDef, idx: Int) {
        val needs = LockRules.presetNeeds(idx)
        var itemId = ""
        var pinHash = ""
        var bnsnParam = ""
        var lanParam = ""
        var outside = false
        var lanOutside = false

        fun finish() {
            def.lock = LockRules.buildPreset(idx, itemId, pinHash, bnsnParam, lanParam, outside, lanOutside)
            save()
            opened.add(def.id)
            lockMenu(def)
        }

        fun askBnsn(after: () -> Unit) {
            val idEt = EditText(this)
            idEt.hint = "分散セットのid（片のヘッダに入っている値）"
            val kEt = EditText(this)
            kEt.inputType = InputType.TYPE_CLASS_NUMBER
            kEt.hint = "必要な片の数 k"
            val cb = CheckBox(this)
            cb.text = "片は端末の外（別のメディアや人）に置いた"
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(40, 20, 40, 0)
            box.addView(idEt)
            box.addView(kEt)
            box.addView(cb)
            AlertDialog.Builder(this)
                .setTitle("分散片で守る")
                .setView(box)
                .setPositiveButton("次へ") { _, _ ->
                    val id = idEt.text.toString().trim()
                    val k = kEt.text.toString().trim().toIntOrNull() ?: 2
                    if (id.length == 0) {
                        toast("idが要ります")
                    } else {
                        bnsnParam = id + ":" + k
                        outside = cb.isChecked
                        after()
                    }
                }
                .setNegativeButton("やめる", null)
                .show()
        }

        fun askNewPin(after: () -> Unit) {
            val et = EditText(this)
            et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            et.hint = "4〜8桁"
            AlertDialog.Builder(this)
                .setTitle("暗証番号を決める")
                .setView(et)
                .setPositiveButton("決定") { _, _ ->
                    val v = et.text.toString().trim()
                    if (v.length < 4) {
                        toast("4桁以上にしてください")
                    } else {
                        pinHash = newPinHash(v)
                        after()
                    }
                }
                .setNegativeButton("やめる", null)
                .show()
        }

        fun askKey(after: () -> Unit) {
            val et = EditText(this)
            et.setText("鍵")
            AlertDialog.Builder(this)
                .setTitle("鍵アイテムを作る")
                .setMessage("この鍵を家のどこかに置きます。置き場所はこのあと選びます。")
                .setView(et)
                .setPositiveButton("作る") { _, _ ->
                    val nm = et.text.toString().trim()
                    val it2 = Item(newId("i"), if (nm.length > 0) nm else "鍵", Item.INVENTORY)
                    house.items.add(it2)
                    itemId = it2.id
                    save()
                    placeItem(it2) { after() }
                }
                .setNegativeButton("やめる", null)
                .show()
        }

        fun askLanPair(after: () -> Unit) {
            AlertDialog.Builder(this)
                .setTitle("二台目の端末で守る")
                .setMessage("相手の端末でカクレガを開き、メニューの「二台目の端末」から「この端末を鍵にする」を選んでください。同じWi-Fiに繋いでから探します。")
                .setPositiveButton("探す") { _, _ ->
                    toast("探しています…")
                    Lan.discover(this, 4000L) { peers ->
                        if (peers.isEmpty()) {
                            toast("見つかりませんでした")
                        } else {
                            Lan.pair(peers[0]) { param, code ->
                                if (param == null) {
                                    toast("ペア設定に失敗しました")
                                } else {
                                    lanParam = param
                                    val cb = CheckBox(this)
                                    cb.text = "相手の端末はこの家とは別の場所にある"
                                    val box = LinearLayout(this)
                                    box.orientation = LinearLayout.VERTICAL
                                    box.setPadding(40, 20, 40, 0)
                                    box.addView(cb)
                                    AlertDialog.Builder(this)
                                        .setTitle("確認番号 " + code)
                                        .setMessage("相手の端末にも同じ番号が出ていますか。違う番号なら、途中に別の端末が入っています。")
                                        .setView(box)
                                        .setPositiveButton("同じだった") { _, _ ->
                                            lanOutside = cb.isChecked
                                            after()
                                        }
                                        .setNegativeButton("違った") { _, _ -> toast("設定をやめました") }
                                        .show()
                                }
                            }
                        }
                    }
                }
                .setNegativeButton("やめる", null)
                .show()
        }

        var chain: () -> Unit = { finish() }
        for (i in needs.indices.reversed()) {
            val next = chain
            val what = needs[i]
            chain = when (what) {
                LockRules.NEED_KEY -> ({ askKey(next) })
                LockRules.NEED_PIN -> ({ askNewPin(next) })
                LockRules.NEED_LAN -> ({ askLanPair(next) })
                else -> ({ askBnsn(next) })
            }
        }
        chain()
    }

    private fun placeItem(item: Item, after: () -> Unit) {
        val spots = mutableListOf<Pair<Scene, Hotspot>>()
        for (sc in house.scenes) for (h in sc.hotspots) spots.add(Pair(sc, h))
        if (spots.isEmpty()) {
            after()
            return
        }
        val names = spots.map { it.first.name + "　" + (if (it.second.hidden) "（隠し）" else "") + it.second.label }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(item.name + " をどこに置く")
            .setItems(names) { _, i ->
                item.at = spots[i].second.id
                save()
                toast(spots[i].first.name + " に置きました")
                after()
            }
            .setOnCancelListener { after() }
            .show()
    }

    private fun lanMenu() {
        val on = Lan.isServing()
        val n = Lan.pairCount(this)
        val items = arrayOf(
            if (on) "鍵になるのをやめる" else "この端末を鍵にする",
            "預かっている錠を忘れる（" + n + "件）"
        )
        AlertDialog.Builder(this)
            .setTitle("二台目の端末")
            .setMessage(
                if (on) "いま鍵として待ち受けています。同じWi-Fiの相手から開けられます。"
                else "この端末を、別の端末の錠を開けるための鍵にできます。"
            )
            .setItems(items) { _, w ->
                if (w == 0) {
                    if (on) {
                        Lan.stopKeyDevice()
                        toast("待ち受けをやめました")
                    } else {
                        Lan.startKeyDevice(this, { code ->
                            AlertDialog.Builder(this)
                                .setTitle("確認番号 " + code)
                                .setMessage("相手の端末にも同じ番号が出ていれば、その相手と繋がっています。")
                                .setPositiveButton("わかった", null)
                                .show()
                        }, { msg -> toast(msg) })
                        toast("鍵として待ち受けます")
                    }
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("忘れる")
                        .setMessage("この端末が預かっている相方の秘密を全部消します。相手の錠は二度と開けられなくなります。")
                        .setPositiveButton("消す") { _, _ ->
                            getSharedPreferences("lan_keyring", MODE_PRIVATE).edit().clear().apply()
                            toast("消しました")
                        }
                        .setNegativeButton("やめる", null)
                        .show()
                }
            }
            .show()
    }

    private fun showInventory() {
        val have = house.items.filter { it.at == Item.INVENTORY }
        val msg = if (have.isEmpty()) "何も持っていない。" else have.joinToString("\n") { "・" + it.name }
        AlertDialog.Builder(this)
            .setTitle("持ちもの")
            .setMessage(msg)
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun fileMenu(f: FileRow) {
        val items = arrayOf("見る", "取り出す", "別の場所へ移す", "燃やす")
        AlertDialog.Builder(this)
            .setTitle(f.origName)
            .setItems(items) { _, w ->
                when (w) {
                    0 -> showViewer(f)
                    1 -> pickExport(f)
                    2 -> moveFile(f)
                    3 -> confirmBurn(f)
                }
            }
            .show()
    }

    private fun moveFile(f: FileRow) {
        val targets = house.slots.filter { it.id != f.slotId }
        if (targets.isEmpty()) {
            toast("ほかに収納がありません")
            return
        }
        val names = targets.map { d ->
            val sc = house.sceneOfSlot(d.id)
            d.name + (if (sc != null) "（" + sc.name + "）" else "")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("どこへ移す")
            .setItems(names) { _, idx ->
                db.moveFile(f.id, targets[idx].id)
                toast("移しました")
                currentSlot?.let { showSlot(it) }
            }
            .show()
    }

    private fun showViewer(f: FileRow) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        screen = "viewer"
        currentSlot = f.slotId
        root.removeAllViews()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.BLACK)
        val bar = LinearLayout(this)
        val back = Button(this)
        back.text = "もどる"
        back.setOnClickListener { showSlot(f.slotId) }
        val name = TextView(this)
        name.text = f.origName
        name.setTextColor(Color.WHITE)
        name.gravity = Gravity.CENTER_VERTICAL
        name.setPadding(16, 0, 0, 0)
        bar.addView(back)
        bar.addView(name)
        col.addView(bar)
        val body = FrameLayout(this)
        col.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(col)

        val file = File(vaultDir, f.storedName)
        if (f.mime.startsWith("image/")) {
            val iv = ImageView(this)
            iv.setImageBitmap(decodeScaled(file))
            body.addView(iv)
        } else if (f.mime.startsWith("text/") || f.mime == "application/json") {
            val sc = ScrollView(this)
            val tv = TextView(this)
            tv.setTextColor(Color.parseColor("#DDDDEE"))
            tv.textSize = 14f
            tv.setPadding(24, 24, 24, 24)
            tv.text = readTextHead(file)
            sc.addView(tv)
            body.addView(sc)
        } else if (f.mime.startsWith("audio/")) {
            body.addView(playerView(file, null))
        } else if (f.mime.startsWith("video/")) {
            val sv = SurfaceView(this)
            body.addView(sv)
            body.addView(
                playerView(file, sv),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            )
        } else {
            val tv = TextView(this)
            tv.text = "この形式の内蔵ビューアは未対応です。\n「取り出す」で書き出して開いてください。"
            tv.setTextColor(Color.parseColor("#AAAAAA"))
            tv.setPadding(24, 48, 24, 24)
            body.addView(tv)
        }
    }

    private fun playerView(file: File, sv: SurfaceView?): View {
        val mp = MediaPlayer()
        player = mp
        var ready = false
        try {
            val fis = FileInputStream(file)
            mp.setDataSource(fis.fd)
            fis.close()
        } catch (e: Exception) {
            toast("開けません")
        }
        val playBtn = Button(this)
        playBtn.text = "再生"
        if (sv == null) {
            try {
                mp.prepare()
                ready = true
            } catch (e: Exception) {
                toast("再生できません")
            }
        } else {
            sv.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) {
                    mp.setDisplay(h)
                    try {
                        mp.prepare()
                        ready = true
                    } catch (e: Exception) {
                        toast("再生できません")
                    }
                }

                override fun surfaceChanged(h: SurfaceHolder, fmt: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {}
            })
        }
        mp.setOnCompletionListener { playBtn.text = "再生" }
        playBtn.setOnClickListener {
            if (!ready) {
                toast("準備中です")
            } else {
                try {
                    if (mp.isPlaying) {
                        mp.pause()
                        playBtn.text = "再生"
                    } else {
                        mp.start()
                        playBtn.text = "一時停止"
                    }
                } catch (e: Exception) {
                    toast("再生できません")
                }
            }
        }
        val row = LinearLayout(this)
        row.setPadding(16, 16, 16, 16)
        row.addView(playBtn)
        return row
    }

    private fun stopPlayer() {
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
    }

    // ---------- 取込・書出 ----------

    private fun pickImport() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, REQ_IMPORT)
    }

    private fun pickExport(f: FileRow) {
        pendingExportId = f.id
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = if (f.mime.length == 0) "application/octet-stream" else f.mime
        i.putExtra(Intent.EXTRA_TITLE, f.origName)
        startActivityForResult(i, REQ_EXPORT)
    }

    private fun confirmBurn(f: FileRow) {
        AlertDialog.Builder(this)
            .setTitle("燃やす")
            .setMessage(f.origName + " をアプリ内から完全に削除します。元に戻せません。")
            .setPositiveButton("燃やす") { _, _ ->
                File(vaultDir, f.storedName).delete()
                db.deleteFile(f.id)
                toast("燃やしました")
                currentSlot?.let { showSlot(it) }
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQ_IMPORT) {
            val slot = currentSlot ?: return
            val uris = mutableListOf<Uri>()
            val cd = data.clipData
            if (cd != null) {
                for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
            } else {
                data.data?.let { uris.add(it) }
            }
            var ok = 0
            for (u in uris) if (importOne(u, slot)) ok++
            toast("しまいました: " + ok + "件")
            showSlot(slot)
        } else if (requestCode == REQ_EXPORT) {
            val u = data.data ?: return
            val f = db.getFile(pendingExportId) ?: return
            try {
                contentResolver.openOutputStream(u)?.use { out ->
                    FileInputStream(File(vaultDir, f.storedName)).use { it.copyTo(out) }
                }
                toast("取り出しました")
            } catch (e: Exception) {
                toast("失敗しました")
            }
        } else if (requestCode == REQ_SHARES) {
            val slot = pendingLockSlot ?: return
            val e = pendingLockElem ?: return
            val parts = e.param.split(":")
            val wantId = parts[0]
            val k = if (parts.size > 1) (parts[1].toIntOrNull() ?: 2) else 2
            val uris = mutableListOf<Uri>()
            val cd = data.clipData
            if (cd != null) {
                for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri)
            } else {
                data.data?.let { uris.add(it) }
            }
            val xs = mutableSetOf<Int>()
            var wrong = 0
            for (u in uris) {
                val r = readBnsn(u)
                if (r == null) wrong++
                else if (r.first != wantId) wrong++
                else xs.add(r.second)
            }
            if (xs.size >= k) {
                bnsnOk.add(e.param)
                toast("片が " + xs.size + " 個そろった")
                tryOpen(slot)
            } else {
                toast("足りません（有効な片 " + xs.size + " / 必要 " + k + "）")
            }
        } else if (requestCode == REQ_SCENE_IMG) {
            val u = data.data ?: return
            val target = imgTargetScene ?: return
            val sc = house.scene(target) ?: return
            try {
                val stored = UUID.randomUUID().toString().replace("-", "")
                contentResolver.openInputStream(u)?.use { ins ->
                    FileOutputStream(File(sceneDir, stored)).use { ins.copyTo(it) }
                }
                val old = sc.image
                sc.image = stored
                save()
                if (old != null && !old.startsWith(ASSET_PREFIX)) File(sceneDir, old).delete()
                toast("画像を置きました")
            } catch (e: Exception) {
                toast("画像を読み込めませんでした")
            }
            if (screen == "scenes") showScenes() else showScene()
        }
    }

    private fun importOne(u: Uri, slot: String): Boolean {
        return try {
            var name = "file"
            var size = 0L
            contentResolver.query(u, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: "file"
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
            val mime = contentResolver.getType(u) ?: "application/octet-stream"
            val stored = UUID.randomUUID().toString().replace("-", "")
            val ins = contentResolver.openInputStream(u) ?: return false
            ins.use { s ->
                FileOutputStream(File(vaultDir, stored)).use { s.copyTo(it) }
            }
            if (size == 0L) size = File(vaultDir, stored).length()
            db.addFile(slot, name, mime, size, stored)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- 小道具 ----------

    private fun decodeScaled(file: File): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, o)
            var s = 1
            while (o.outWidth / s > 2048 || o.outHeight / s > 2048) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            BitmapFactory.decodeFile(file.absolutePath, o2)
        } catch (e: Exception) {
            null
        }
    }

    private fun readTextHead(file: File): String {
        return try {
            val max = 200000
            val bytes = FileInputStream(file).use { ins ->
                val cap = Math.min(file.length(), max.toLong()).toInt()
                val buf = ByteArray(cap)
                var off = 0
                while (off < buf.size) {
                    val r = ins.read(buf, off, buf.size - off)
                    if (r <= 0) break
                    off += r
                }
                buf.copyOf(off)
            }
            val s = String(bytes, Charsets.UTF_8)
            if (file.length() > max) s + "\n\n…（先頭200KBのみ表示）" else s
        } catch (e: Exception) {
            "読めませんでした"
        }
    }

    private fun human(b: Long): String {
        if (b < 1024) return b.toString() + " B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    override fun onBackPressed() {
        if (screen == "viewer") {
            val slot = currentSlot
            if (slot != null) showSlot(slot) else showScene()
        } else if (screen == "slot" || screen == "scenes") {
            showScene()
        } else if (editing) {
            editing = false
            showScene()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopPlayer()
        Lan.stopKeyDevice()
        super.onDestroy()
    }

    companion object {
        private const val REQ_IMPORT = 1
        private const val REQ_EXPORT = 2
        private const val REQ_SCENE_IMG = 3
        private const val REQ_SHARES = 4
    }
}
