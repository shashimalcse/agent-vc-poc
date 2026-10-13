package dev.a2wp.wallet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
class StoredCredential(
    val handle: String,
    @SerialName("agent_id") val agentId: String,
    @SerialName("principal_id") val principalId: String,
    val format: String,
    val vct: String,
    val issuer: String,
    /** The issued SD-JWT VC (secret). */
    val credential: String,
    /** The holder private key as a JWK JSON string (secret). */
    @SerialName("holder_key") val holderKey: String,
    @SerialName("stored_at") val storedAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
) {
    override fun toString(): String = "StoredCredential(handle=$handle, vct=$vct, issuer=$issuer)"
}

@Serializable
data class StoreData(
    val credentials: List<StoredCredential> = emptyList(),
    /** "agentId\u0000verifierClientId" -> claims the user approved disclosing. */
    val approvals: Map<String, List<String>> = emptyMap(),
)

/** A small AES-256-GCM encrypted file store. The whole file is one ciphertext; writes are atomic. */
class EncryptedStore(private val path: Path, private val key: SecretKey) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var data: StoreData = load()

    private fun load(): StoreData {
        if (!Files.exists(path)) return StoreData()
        val bytes = Files.readAllBytes(path)
        require(bytes.size > MAGIC.size + IV_LEN && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "store file is not an a2wp-kt store"
        }
        val iv = bytes.copyOfRange(MAGIC.size, MAGIC.size + IV_LEN)
        val ct = bytes.copyOfRange(MAGIC.size + IV_LEN, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        val plain = try {
            cipher.doFinal(ct)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw IllegalStateException("store decryption failed (wrong key?)")
        }
        return json.decodeFromString(StoreData.serializer(), plain.toString(Charsets.UTF_8))
    }

    private fun save(d: StoreData) {
        val plain = json.encodeToString(StoreData.serializer(), d).toByteArray(Charsets.UTF_8)
        val iv = ByteArray(IV_LEN).also { rng.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        val ct = cipher.doFinal(plain)
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(tmp, MAGIC + iv + ct)
        restrictPermissions(tmp)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun addCredentials(creds: List<StoredCredential>) = synchronized(lock) {
        val next = data.copy(credentials = data.credentials + creds)
        save(next)
        data = next
    }

    /** Credentials belong to the agent whose API key acquired them; no other agent (even of the same principal) sees them. */
    fun credentialsFor(agentId: String): List<StoredCredential> = synchronized(lock) {
        data.credentials.filter { it.agentId == agentId }
    }

    /** Everything, for the admin tool only. */
    internal fun snapshot(): StoreData = synchronized(lock) { data }

    fun approvedClaims(agentId: String, verifierClientId: String): Set<String>? = synchronized(lock) {
        data.approvals[approvalKey(agentId, verifierClientId)]?.toSet()
    }

    fun recordApproval(agentId: String, verifierClientId: String, claims: Collection<String>) = synchronized(lock) {
        val k = approvalKey(agentId, verifierClientId)
        val merged = (data.approvals[k].orEmpty() + claims).distinct().sorted()
        val next = data.copy(approvals = data.approvals + (k to merged))
        save(next)
        data = next
    }

    private fun approvalKey(agentId: String, clientId: String) = "$agentId\u0000$clientId"

    companion object {
        private val MAGIC = "A2WPKT1".toByteArray(Charsets.US_ASCII)
        private const val IV_LEN = 12
        private val rng = SecureRandom()

        fun open(cfg: LoadedConfig): EncryptedStore {
            val s = cfg.config.store
            return EncryptedStore(cfg.resolve(s.path), loadKey(cfg))
        }

        fun loadKey(cfg: LoadedConfig): SecretKey {
            val s = cfg.config.store
            val fromEnv = s.keyEnv?.let { System.getenv(it) }?.takeIf { it.isNotBlank() }
            if (fromEnv != null) return decodeKey(fromEnv)
            val file = s.keyFile?.let { cfg.resolve(it) }
                ?: error("No store key: set ${s.keyEnv ?: "store.key_env"} or store.key_file")
            if (!Files.exists(file)) {
                file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
                val k = ByteArray(32).also { rng.nextBytes(it) }
                Files.writeString(file, Base64.getEncoder().encodeToString(k) + "\n")
                restrictPermissions(file)
            }
            return decodeKey(Files.readString(file).trim())
        }

        fun decodeKey(b64: String): SecretKey {
            val raw = Base64.getDecoder().decode(b64.trim())
            require(raw.size == 32) { "store key must be 32 bytes (base64)" }
            return SecretKeySpec(raw, "AES")
        }

        private fun restrictPermissions(p: Path) {
            runCatching { Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------")) }
        }
    }
}
