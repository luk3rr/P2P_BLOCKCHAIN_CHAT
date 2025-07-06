package model

import kotlinx.serialization.Serializable

/**
 * Representa um único chat no histórico (blockchain).
 *
 * @param text O conteúdo da mensagem em ASCII.
 * @param verificationCode O código verificador de 16 bytes minerado.
 * @param md5Hash O hash MD5 de 16 bytes do chat.
 */
@Serializable
data class Chat(
    val text: String,
    val verificationCode: ByteArray,
    val md5Hash: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (this::class != other?.let { it::class }) return false
        other as Chat
        if (text != other.text) return false
        if (!verificationCode.contentEquals(other.verificationCode)) return false
        if (!md5Hash.contentEquals(other.md5Hash)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = text.hashCode()
        result = 31 * result + verificationCode.contentHashCode()
        result = 31 * result + md5Hash.contentHashCode()
        return result
    }
}

