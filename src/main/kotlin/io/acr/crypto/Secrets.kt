package io.acr.crypto

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM at-rest encryption for forge tokens, same scheme as mongo-explorer v3.
 *
 * The master key lives outside the SQLite file so that copying the database alone does not
 * hand over the tokens.
 */
class Secrets(private val keyPath: Path) {

    class CorruptKeyException(path: Path, size: Int) : IllegalStateException(
        "La clave maestra en $path mide $size bytes y no es una clave AES válida. " +
            "Borrala para regenerarla; vas a tener que volver a cargar los tokens.",
    )

    private val key: SecretKeySpec by lazy { SecretKeySpec(loadOrCreateKey(), "AES") }
    private val rng = SecureRandom()

    fun encrypt(plain: String): ByteArray {
        val iv = ByteArray(IV_BYTES).also(rng::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(blob: ByteArray): String {
        require(blob.size > IV_BYTES) { "cifrado demasiado corto" }
        val iv = blob.copyOfRange(0, IV_BYTES)
        val body = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    /**
     * Escribe la clave a un temporal y la mueve de forma atómica. La versión anterior hacía
     * `createFile()` y recién después `write()`, dejando una ventana en la que otro proceso leía
     * un archivo de 0 bytes como si fuera la clave; y si dos procesos arrancaban a la vez, el
     * segundo moría con FileAlreadyExistsException.
     */
    private fun loadOrCreateKey(): ByteArray {
        readExisting()?.let { return it }

        Files.createDirectories(keyPath.parent)
        val generated = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().encoded
        val tmp = Files.createTempFile(keyPath.parent, ".master", ".tmp")
        restrict(tmp)
        Files.write(tmp, generated)
        try {
            Files.move(tmp, keyPath, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            // Otro proceso ganó la carrera: su clave es la buena, la nuestra se descarta.
            Files.deleteIfExists(tmp)
            return readExisting() ?: throw e
        }
        restrict(keyPath)
        return generated
    }

    private fun readExisting(): ByteArray? {
        if (!Files.exists(keyPath)) return null
        val bytes = Files.readAllBytes(keyPath)
        // Una clave truncada por un crash previo fallaría después, en Cipher.init, con un mensaje
        // que no dice nada. Mejor romper acá diciendo exactamente qué pasó y cómo salir.
        if (bytes.size !in VALID_KEY_SIZES) throw CorruptKeyException(keyPath, bytes.size)
        return bytes
    }

    /** Best-effort: en sistemas sin POSIX (Windows) no hay vista de permisos que aplicar. */
    private fun restrict(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val VALID_KEY_SIZES = setOf(16, 24, 32)
    }
}
