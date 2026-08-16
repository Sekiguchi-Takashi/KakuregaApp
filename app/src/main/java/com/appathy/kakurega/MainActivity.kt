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
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
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
    private lateinit var root: FrameLayout
    private var screen = "scene"
    private var currentSlot: String? = null
    private var pendingExportId: Long = -1
    private var player: MediaPlayer? = null

    private val slotNames = mapOf(
        "bookshelf" to "本棚",
        "stereo" to "ステレオ",
        "tvstand" to "テレビ台",
        "drawer" to "引き出し",
        "floor" to "床下"
    )
    private val slotHints = mapOf(
        "bookshelf" to "PDF・テキスト向け（どのファイルでも置けます）",
        "stereo" to "音楽向け（どのファイルでも置けます）",
        "tvstand" to "動画向け（どのファイルでも置けます）",
        "drawer" to "写真向け（どのファイルでも置けます）",
        "floor" to "隠し場所。なんでも"
    )

    private val vaultDir: File by lazy {
        val d = File(filesDir, "vault")
        if (!d.exists()) d.mkdirs()
        val nm = File(d, ".nomedia")
        if (!nm.exists()) nm.createNewFile()
        d
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db(this)
        root = FrameLayout(this)
        setContentView(root)
        showScene()
    }

    private fun showScene() {
        stopPlayer()
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        screen = "scene"
        currentSlot = null
        root.removeAllViews()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.parseColor("#1B1B22"))
        val bar = TextView(this)
        bar.text = "カクレガ ― 家具をタップ / 長押しで調べられる場所を表示"
        bar.setTextColor(Color.parseColor("#8888AA"))
        bar.textSize = 12f
        bar.setPadding(24, 24, 24, 12)
        col.addView(bar)
        val scene = SceneView(this, { id -> showSlot(id) }, { db.counts() })
        col.addView(
            scene,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(col)
    }

    private fun showSlot(slotId: String) {
        stopPlayer()
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        screen = "slot"
        currentSlot = slotId
        root.removeAllViews()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.parseColor("#22222A"))
        col.setPadding(24, 24, 24, 24)
        val title = TextView(this)
        title.text = slotNames[slotId] ?: slotId
        title.textSize = 22f
        title.setTextColor(Color.WHITE)
        val hint = TextView(this)
        hint.text = slotHints[slotId] ?: ""
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
        col.addView(
            listWrap,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        val btns = LinearLayout(this)
        val put = Button(this)
        put.text = "しまう"
        put.setOnClickListener { pickImport() }
        val back = Button(this)
        back.text = "部屋にもどる"
        back.setOnClickListener { showScene() }
        btns.addView(put, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(back, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(btns)
        root.addView(col)
    }

    private fun fileMenu(f: FileRow) {
        val items = arrayOf("見る", "取り出す", "燃やす")
        AlertDialog.Builder(this)
            .setTitle(f.origName)
            .setItems(items) { _, w ->
                when (w) {
                    0 -> showViewer(f)
                    1 -> pickExport(f)
                    2 -> confirmBurn(f)
                }
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
        col.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        root.addView(col)

        val file = File(vaultDir, f.storedName)
        when {
            f.mime.startsWith("image/") -> {
                val iv = ImageView(this)
                iv.setImageBitmap(decodeScaled(file))
                body.addView(iv)
            }
            f.mime.startsWith("text/") || f.mime == "application/json" -> {
                val sc = ScrollView(this)
                val tv = TextView(this)
                tv.setTextColor(Color.parseColor("#DDDDEE"))
                tv.textSize = 14f
                tv.setPadding(24, 24, 24, 24)
                tv.text = readTextHead(file)
                sc.addView(tv)
                body.addView(sc)
            }
            f.mime.startsWith("audio/") -> {
                body.addView(playerView(file, null))
            }
            f.mime.startsWith("video/") -> {
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
            }
            else -> {
                val tv = TextView(this)
                tv.text = "この形式の内蔵ビューアは未対応です。\n「取り出す」で書き出して開いてください。"
                tv.setTextColor(Color.parseColor("#AAAAAA"))
                tv.setPadding(24, 48, 24, 24)
                body.addView(tv)
            }
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
            toast("開けません: " + e.message)
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
                return@setOnClickListener
            }
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
        val row = LinearLayout(this)
        row.setPadding(16, 16, 16, 16)
        row.addView(playBtn)
        return row
    }

    private fun stopPlayer() {
        try { player?.stop() } catch (e: Exception) {}
        try { player?.release() } catch (e: Exception) {}
        player = null
    }

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
        i.type = if (f.mime.isBlank()) "application/octet-stream" else f.mime
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
                toast("失敗: " + e.message)
            }
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
                val buf = ByteArray(minOf(file.length(), max.toLong()).toInt())
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
        when (screen) {
            "viewer" -> {
                val slot = currentSlot
                if (slot != null) showSlot(slot) else showScene()
            }
            "slot" -> showScene()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopPlayer()
        super.onDestroy()
    }

    companion object {
        private const val REQ_IMPORT = 1
        private const val REQ_EXPORT = 2
    }
}
