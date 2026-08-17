package com.appathy.kakurega

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

// 二台の端末に秘密を割って持たせる錠。
//
//   ペア設定時に a / b / c の3つを作る
//     a … 錠を持つ側（この端末）だけが持つ半分。錠の param に入る
//     b … 鍵になる側の端末だけが持つ半分
//     c … 両方が持つ通信鍵。LANに b の平文を流さないためだけに使う
//   錠には SHA-256(a||b) だけを保存する。
//
//   解錠は、乱数 N を送り、相手が E = b XOR HMAC(c, N) を返す。
//   こちらは c から b を復元し SHA-256(a||b) が一致すれば開ける。
//   盗聴者は c を持たないので b を取り出せず、
//   仮に b を得ても a はこの端末の中なので、b 単体では開かない。

object Lan {

    const val SERVICE_TYPE = "_kakurega._tcp."
    const val SERVICE_NAME = "Kakurega"
    private const val KEYRING = "lan_keyring"

    private val main = Handler(Looper.getMainLooper())

    class Peer(val name: String, val host: InetAddress, val port: Int)

    // ---------- 小道具 ----------

    fun rand(n: Int): ByteArray {
        val b = ByteArray(n)
        SecureRandom().nextBytes(b)
        return b
    }

    fun hex(b: ByteArray): String {
        val sb = StringBuilder()
        for (x in b) sb.append(String.format("%02x", x))
        return sb.toString()
    }

    fun unhex(s: String): ByteArray {
        val n = s.length / 2
        val out = ByteArray(n)
        for (i in 0 until n) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hmac(key: ByteArray, msg: ByteArray): ByteArray {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(msg)
    }

    fun xor(a: ByteArray, b: ByteArray): ByteArray {
        val n = a.size
        val out = ByteArray(n)
        for (i in 0 until n) out[i] = (a[i].toInt() xor b[i % b.size].toInt()).toByte()
        return out
    }

    // 目視照合用の6桁。a は含めない（鍵になる側は a を知らないため）
    fun confirmCode(pairId: String, b: ByteArray, c: ByteArray): String {
        val d = sha256(pairId.toByteArray(Charsets.UTF_8), b, c)
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (d[i].toLong() and 0xFF)
        return String.format("%06d", v % 1000000L)
    }

    // ---------- 鍵になる側（サーバ） ----------

    private var server: ServerSocket? = null
    private var nsd: NsdManager? = null
    private var reg: NsdManager.RegistrationListener? = null
    private var serving = false

    fun isServing(): Boolean = serving

    fun startKeyDevice(ctx: Context, onPaired: (String) -> Unit, onError: (String) -> Unit) {
        if (serving) return
        try {
            val ss = ServerSocket(0)
            server = ss
            serving = true
            Thread {
                while (serving) {
                    try {
                        val s = ss.accept()
                        handle(ctx, s, onPaired)
                    } catch (e: Exception) {
                        if (serving) main.post { onError("接続に失敗しました") }
                        break
                    }
                }
            }.start()

            val info = NsdServiceInfo()
            info.serviceName = SERVICE_NAME
            info.serviceType = SERVICE_TYPE
            info.port = ss.localPort
            val m = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsd = m
            val r = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) {
                    main.post { onError("同じWi-Fiに出られませんでした") }
                }

                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
            }
            reg = r
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, r)
        } catch (e: Exception) {
            serving = false
            onError("待ち受けを始められませんでした")
        }
    }

    fun stopKeyDevice() {
        serving = false
        try {
            val m = nsd
            val r = reg
            if (m != null && r != null) m.unregisterService(r)
        } catch (e: Exception) {
        }
        try {
            server?.close()
        } catch (e: Exception) {
        }
        server = null
        reg = null
        nsd = null
    }

    private fun handle(ctx: Context, s: Socket, onPaired: (String) -> Unit) {
        try {
            s.soTimeout = 8000
            val br = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val ow = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            val line = br.readLine() ?: return
            val t = line.trim().split(" ")
            val p = ctx.getSharedPreferences(KEYRING, Context.MODE_PRIVATE)
            if (t.size >= 4 && t[0] == "PAIR") {
                val pairId = t[1]
                p.edit().putString(pairId, t[2] + ":" + t[3]).apply()
                val code = confirmCode(pairId, unhex(t[2]), unhex(t[3]))
                ow.write("OK " + code + "\n")
                ow.flush()
                main.post { onPaired(code) }
            } else if (t.size >= 3 && t[0] == "AUTH") {
                val pairId = t[1]
                val stored = p.getString(pairId, null)
                if (stored == null) {
                    ow.write("NO\n")
                } else {
                    val parts = stored.split(":")
                    val b = unhex(parts[0])
                    val c = unhex(parts[1])
                    val n = unhex(t[2])
                    ow.write("RESP " + hex(xor(b, hmac(c, n))) + "\n")
                }
                ow.flush()
            } else {
                ow.write("NO\n")
                ow.flush()
            }
        } catch (e: Exception) {
        } finally {
            try {
                s.close()
            } catch (e: Exception) {
            }
        }
    }

    fun forgetPair(ctx: Context, pairId: String) {
        ctx.getSharedPreferences(KEYRING, Context.MODE_PRIVATE).edit().remove(pairId).apply()
    }

    fun pairCount(ctx: Context): Int =
        ctx.getSharedPreferences(KEYRING, Context.MODE_PRIVATE).all.size

    // ---------- 錠を持つ側（クライアント） ----------

    fun discover(ctx: Context, timeoutMs: Long, done: (List<Peer>) -> Unit) {
        val found = mutableListOf<Peer>()
        val m = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        var listener: NsdManager.DiscoveryListener? = null
        val stop = Runnable {
            try {
                val l = listener
                if (l != null) m.stopServiceDiscovery(l)
            } catch (e: Exception) {
            }
            main.post { done(found.toList()) }
        }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) {
                main.post { done(listOf()) }
            }

            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onServiceLost(i: NsdServiceInfo) {}
            override fun onServiceFound(i: NsdServiceInfo) {
                if (!i.serviceType.contains("kakurega")) return
                m.resolveService(i, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(x: NsdServiceInfo, code: Int) {}
                    override fun onServiceResolved(x: NsdServiceInfo) {
                        synchronized(found) {
                            val h = x.host
                            if (h != null && found.none { it.host == h && it.port == x.port }) {
                                found.add(Peer(x.serviceName ?: SERVICE_NAME, h, x.port))
                            }
                        }
                    }
                })
            }
        }
        listener = l
        try {
            m.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            main.post { done(listOf()) }
            return
        }
        main.postDelayed(stop, timeoutMs)
    }

    private fun ask(peer: Peer, line: String): String? {
        return try {
            val s = Socket()
            s.connect(java.net.InetSocketAddress(peer.host, peer.port), 5000)
            s.soTimeout = 8000
            val ow = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            ow.write(line + "\n")
            ow.flush()
            val br = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val r = br.readLine()
            s.close()
            r
        } catch (e: Exception) {
            null
        }
    }

    // ペア設定。成功すると錠に入れる param と、目視照合用の6桁を返す
    fun pair(peer: Peer, done: (String?, String?) -> Unit) {
        Thread {
            val pairId = newId("p")
            val a = rand(16)
            val b = rand(16)
            val c = rand(16)
            val r = ask(peer, "PAIR " + pairId + " " + hex(b) + " " + hex(c))
            if (r == null || !r.startsWith("OK")) {
                main.post { done(null, null) }
            } else {
                val theirCode = r.trim().split(" ").getOrNull(1) ?: ""
                val mine = confirmCode(pairId, b, c)
                if (theirCode != mine) {
                    main.post { done(null, null) }
                } else {
                    val expect = hex(sha256(a, b))
                    val param = pairId + "|" + hex(a) + "|" + hex(c) + "|" + expect
                    main.post { done(param, mine) }
                }
            }
        }.start()
    }

    // 解錠。param から pairId/a/c/期待値 を取り出して相手に問い合わせる
    fun unlock(ctx: Context, param: String, done: (Boolean, String) -> Unit) {
        val f = param.split("|")
        if (f.size < 4) {
            done(false, "錠の設定が壊れています")
            return
        }
        discover(ctx, 4000L) { peers ->
            if (peers.isEmpty()) {
                done(false, "同じWi-Fiに二台目が見つかりません")
            } else {
                Thread {
                    val a = unhex(f[1])
                    val c = unhex(f[2])
                    var ok = false
                    for (p in peers) {
                        val n = rand(16)
                        val r = ask(p, "AUTH " + f[0] + " " + hex(n))
                        if (r != null && r.startsWith("RESP")) {
                            val e = unhex(r.trim().split(" ")[1])
                            val b = xor(e, hmac(c, n))
                            if (hex(sha256(a, b)) == f[3]) {
                                ok = true
                                break
                            }
                        }
                    }
                    val res = ok
                    main.post {
                        if (res) done(true, "二台目と繋がりました")
                        else done(false, "相手の端末がこの錠の相方ではありません")
                    }
                }.start()
            }
        }
    }
}
