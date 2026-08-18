package com.appathy.kakurega

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

// BUNSAN_VAULT_API.md v1.0 の実装。
// 公開するのは shareVault が true で、かつ隠しスポットではない収納だけ。
// 施錠中の収納は「郵便受け」として振る舞う（入れられるが中は見えない・読めない）。

class VaultDocumentsProvider : DocumentsProvider() {

    private val ROOT_ID = "kakurega"
    private val DOC_ROOT = "root"

    private val defRoot = arrayOf(
        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE,
        Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES
    )

    private val defDoc = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS
    )

    override fun onCreate(): Boolean = true

    private fun db(): Db = Db(context!!)

    private fun house(): House = House.load(context!!)

    private fun vaultDir(): File {
        val d = File(context!!.filesDir, "vault")
        if (!d.exists()) d.mkdirs()
        return d
    }

    // 公開してよい収納か。隠しスポットと未公開は絶対に出さない
    private fun shared(h: House, slotId: String): SlotDef? {
        val def = h.slot(slotId) ?: return null
        if (!def.shareVault) return null
        if (def.lock.hasBnsn()) return null
        val spot = h.hotspotOfSlot(slotId) ?: return null
        if (spot.hidden) return null
        return def
    }

    private fun isOpen(def: SlotDef): Boolean =
        def.lock.isOpen() || Unlocked.slots.contains(def.id)

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: defRoot)
        val r = c.newRow()
        r.add(Root.COLUMN_ROOT_ID, ROOT_ID)
        r.add(Root.COLUMN_DOCUMENT_ID, DOC_ROOT)
        r.add(Root.COLUMN_TITLE, "カクレガ")
        r.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE)
        r.add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        r.add(Root.COLUMN_MIME_TYPES, "*/*")
        return c
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val c = MatrixCursor(projection ?: defDoc)
        val parent = parentDocumentId ?: DOC_ROOT
        val h = house()

        if (parent == DOC_ROOT) {
            for (def in h.slots) {
                if (shared(h, def.id) == null) continue
                val sc = h.sceneOfSlot(def.id)
                val title = (if (sc != null) sc.name + "の" else "") + def.name +
                    "（" + def.lock.kindLabel() + "）"
                val r = c.newRow()
                r.add(Document.COLUMN_DOCUMENT_ID, "slot:" + def.id)
                r.add(Document.COLUMN_DISPLAY_NAME, title)
                r.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                r.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
            }
            return c
        }

        if (parent.startsWith("slot:")) {
            val slotId = parent.substring(5)
            val def = shared(h, slotId) ?: return c
            if (!isOpen(def)) return c   // 施錠中は中身を見せない
            val d = db()
            for (f in d.filesIn(slotId)) {
                addFileRow(c, f)
            }
            return c
        }
        return c
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: defDoc)
        val id = documentId ?: DOC_ROOT
        if (id == DOC_ROOT) {
            val r = c.newRow()
            r.add(Document.COLUMN_DOCUMENT_ID, DOC_ROOT)
            r.add(Document.COLUMN_DISPLAY_NAME, "カクレガ")
            r.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            r.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
            return c
        }
        val h = house()
        if (id.startsWith("slot:")) {
            val def = shared(h, id.substring(5)) ?: throw FileNotFoundException()
            val sc = h.sceneOfSlot(def.id)
            val r = c.newRow()
            r.add(Document.COLUMN_DOCUMENT_ID, id)
            r.add(
                Document.COLUMN_DISPLAY_NAME,
                (if (sc != null) sc.name + "の" else "") + def.name + "（" + def.lock.kindLabel() + "）"
            )
            r.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            r.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
            return c
        }
        if (id.startsWith("file:")) {
            val fid = id.substring(5).toLongOrNull() ?: throw FileNotFoundException()
            val f = db().getFile(fid) ?: throw FileNotFoundException()
            if (shared(h, f.slotId) == null) throw FileNotFoundException()
            addFileRow(c, f)
            return c
        }
        throw FileNotFoundException()
    }

    private fun addFileRow(c: MatrixCursor, f: FileRow) {
        val real = File(vaultDir(), f.storedName)
        val r = c.newRow()
        r.add(Document.COLUMN_DOCUMENT_ID, "file:" + f.id)
        r.add(Document.COLUMN_DISPLAY_NAME, f.origName)
        r.add(Document.COLUMN_MIME_TYPE, "application/octet-stream")
        r.add(Document.COLUMN_SIZE, real.length())
        r.add(Document.COLUMN_LAST_MODIFIED, f.addedTs)
        r.add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE)
    }

    override fun createDocument(
        parentDocumentId: String?,
        mimeType: String?,
        displayName: String?
    ): String {
        val parent = parentDocumentId ?: throw FileNotFoundException()
        if (!parent.startsWith("slot:")) throw FileNotFoundException()
        val h = house()
        val slotId = parent.substring(5)
        val def = shared(h, slotId) ?: throw FileNotFoundException()
        // 施錠中でも入れられる（郵便受け）。一覧が空なので衝突は自分で避ける
        val name = uniqueName(slotId, displayName ?: "share")
        val stored = UUID.randomUUID().toString().replace("-", "")
        val real = File(vaultDir(), stored)
        try {
            real.createNewFile()
        } catch (e: Exception) {
            throw FileNotFoundException()
        }
        val mime = if (mimeType == null || mimeType.length == 0) "application/octet-stream" else mimeType
        val id = db().addFile(slotId, name, mime, 0L, stored)
        if (id < 0) throw FileNotFoundException()
        return "file:" + id
    }

    // 施錠中は一覧を返さないので、内部の台帳で衝突を避けて連番を付ける
    private fun uniqueName(slotId: String, want: String): String {
        val used = mutableSetOf<String>()
        for (f in db().filesIn(slotId)) used.add(f.origName)
        if (!used.contains(want)) return want
        val dot = want.lastIndexOf('.')
        val base = if (dot > 0) want.substring(0, dot) else want
        val ext = if (dot > 0) want.substring(dot) else ""
        var n = 1
        while (n < 10000) {
            val cand = base + " (" + n + ")" + ext
            if (!used.contains(cand)) return cand
            n++
        }
        return base + " (" + System.currentTimeMillis() + ")" + ext
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val id = documentId ?: throw FileNotFoundException()
        if (!id.startsWith("file:")) throw FileNotFoundException()
        val fid = id.substring(5).toLongOrNull() ?: throw FileNotFoundException()
        val f = db().getFile(fid) ?: throw FileNotFoundException()
        val h = house()
        val def = shared(h, f.slotId) ?: throw FileNotFoundException()
        val m = mode ?: "r"
        val writing = m.contains("w") || m.contains("t") || m.contains("a")
        if (!writing && !isOpen(def)) {
            // 施錠中は読み出しを断る
            throw FileNotFoundException()
        }
        val real = File(vaultDir(), f.storedName)
        val flags = if (writing)
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        else
            ParcelFileDescriptor.MODE_READ_ONLY
        return ParcelFileDescriptor.open(real, flags)
    }

    override fun deleteDocument(documentId: String?) {
        val id = documentId ?: throw FileNotFoundException()
        if (!id.startsWith("file:")) throw FileNotFoundException()
        val fid = id.substring(5).toLongOrNull() ?: throw FileNotFoundException()
        val d = db()
        val f = d.getFile(fid) ?: throw FileNotFoundException()
        val h = house()
        val def = shared(h, f.slotId) ?: throw FileNotFoundException()
        if (!isOpen(def)) throw FileNotFoundException()
        File(vaultDir(), f.storedName).delete()
        d.deleteFile(fid)
    }
}
