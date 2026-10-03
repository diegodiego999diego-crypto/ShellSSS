package com.shell.sss.hell.adb

import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.net.Socket
import java.security.SecureRandom

/**
 * Emparejamiento por depuración inalámbrica (Android 11+).
 * Habla TLS-PSK con adbd usando el código de 6 dígitos como clave
 * y le envía nuestra public key ADB.
 */
object AdbPairing {

    fun pair(host: String, port: Int, code: String): Result<String> = runCatching {
        Socket(host, port).use { socket ->
            socket.soTimeout = 20_000
            val protocol = TlsClientProtocol(socket.inputStream, socket.outputStream)
            val identity = BasicTlsPSKIdentity("pairing", code.toByteArray(Charsets.UTF_8))
            val client = object : PSKTlsClient(BcTlsCrypto(SecureRandom()), identity) {
                override fun getSupportedVersions(): Array<ProtocolVersion> =
                    arrayOf(ProtocolVersion.TLSv13, ProtocolVersion.TLSv12)
            }
            protocol.connect(client)

            // Paquete de emparejamiento: [version=1][tipo=0][payload = adb public key]
            val payload = AdbKey.adbPublicKey().toByteArray(Charsets.UTF_8)
            protocol.outputStream.apply {
                write(1)
                write(0)
                write(payload)
                flush()
            }

            val input = protocol.inputStream
            val version = input.read()
            val type = input.read()
            if (version < 0) throw Exception("Sin respuesta del dispositivo")
            if (type != 1) throw Exception("Emparejamiento rechazado (código o puerto incorrectos)")
            protocol.close()
            "Dispositivo emparejado correctamente"
        }
    }
}
