package com.shell.sss.hell.adb

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** Cliente ADB minimalista: conexión (con STLS), autenticación y shell remoto. */
class AdbConnection {

    companion object {
        const val A_CNXN = 0x4e584e43
        const val A_AUTH = 0x48545541
        const val A_OPEN = 0x4e45504f
        const val A_OKAY = 0x59414b4f
        const val A_CLSE = 0x45534c43
        const val A_WRTE = 0x45545257
        const val A_STLS = 0x53544c53
        const val AUTH_TOKEN = 1
        const val AUTH_SIGN = 2
        const val AUTH_RSAPUBLICKEY = 3
    }

    data class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var nextLocalId = 1

    @Volatile
    var connected = false
        private set

    var onOutput: ((String) -> Unit)? = null

    fun connect(host: String, port: Int, useTls: Boolean = true): Result<String> = runCatching {
        disconnect()
        val raw = Socket(host, port)
        raw.tcpNoDelay = true
        var inp = DataInputStream(raw.getInputStream())
        var out = DataOutputStream(raw.getOutputStream())

        if (useTls) {
            writeMessage(out, A_STLS, 1, 0, ByteArray(0))
            val stls = readMessage(inp)
            if (stls.cmd != A_STLS) throw Exception("El dispositivo no aceptó TLS")
            val ssl = upgradeTls(raw, host, port)
            inp = DataInputStream(ssl.getInputStream())
            out = DataOutputStream(ssl.getOutputStream())
        }

        writeMessage(out, A_CNXN, 0x01000000, 256 * 1024,
            "host::features=shell_v2,cmd".toByteArray())
        var msg = readMessage(inp)

        if (msg.cmd == A_AUTH) {
            writeMessage(out, A_AUTH, AUTH_SIGN, 0, AdbKey.sign(msg.data))
            msg = readMessage(inp)
            if (msg.cmd == A_AUTH) {
                val pk = (AdbKey.adbPublicKey() + "\u0000").toByteArray()
                writeMessage(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, pk)
                msg = readMessage(inp)
            }
        }
        if (msg.cmd != A_CNXN) throw Exception("Autenticación ADB fallida")

        input = inp
        output = out
        connected = true
        "Conectado a $host:$port"
    }

    private fun upgradeTls(raw: Socket, host: String, port: Int): SSLSocket {
        val keyManager = object : X509ExtendedKeyManager() {
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?) = "adb"
            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?) = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(AdbKey.certificate())
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf("adb")
            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = null
            override fun getPrivateKey(alias: String?): PrivateKey = AdbKey.keyPair.private
        }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(keyManager), arrayOf(trustAll), SecureRandom())
        val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        ssl.useClientMode = true
        ssl.startHandshake()
        return ssl
    }

    /** Ejecuta un comando shell y vuelca la salida por [onOutput]. */
    fun shell(command: String) {
        val out = output
        if (!connected || out == null) {
            onOutput?.invoke("[No hay conexión. Conéctate primero.]\n")
            return
        }
        val local = nextLocalId++
        writeMessage(out, A_OPEN, local, 0, "shell:$command".toByteArray())
        Thread {
            try {
                var closed = false
                while (!closed && connected) {
                    val m = readMessage(input!!)
                    when (m.cmd) {
                        A_OKAY -> Unit // stream abierto
                        A_WRTE -> {
                            onOutput?.invoke(String(m.data))
                            writeMessage(output!!, A_OKAY, local, m.arg0, ByteArray(0))
                        }
                        A_CLSE -> {
                            writeMessage(output!!, A_CLSE, local, m.arg0, ByteArray(0))
                            closed = true
                        }
                    }
                }
            } catch (e: Exception) {
                connected = false
                onOutput?.invoke("\n[Conexión cerrada: ${e.message}]\n")
            }
        }.start()
    }

    fun disconnect() {
        connected = false
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        input = null
        output = null
    }

    private fun writeMessage(out: DataOutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val buf = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        var crc = 0
        for (b in data) crc += b.toInt() and 0xFF
        buf.putInt(cmd)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(data.size)
        buf.putInt(crc)
        buf.putInt(cmd xor -1)
        buf.put(data)
        synchronized(out) {
            out.write(buf.array())
            out.flush()
        }
    }

    private fun readMessage(inp: DataInputStream): Msg {
        val header = ByteArray(24)
        inp.readFully(header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val length = buf.int
        buf.int // crc
        val magic = buf.int
        if (magic != (cmd xor -1)) throw Exception("Mensaje ADB corrupto (magic inválido)")
        val data = ByteArray(length)
        if (length > 0) inp.readFully(data)
        return Msg(cmd, arg0, arg1, data)
    }
}
