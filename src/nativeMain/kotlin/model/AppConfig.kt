package model

/**
 * Armazena as configurações de inicialização da aplicação.
 *
 * @param hostIp O endereço IP no qual este nó deve ouvir por conexões.
 * @param port A porta de rede para toda a comunicação (padrão 51511).
 * @param initialPeerIp O endereço IP opcional de um par já na rede para o bootstrap.
 * @param groupIdentifier O nome ou identificador do seu grupo, para ser usado nos chats.
 */
data class AppConfig(
    val hostIp: String,
    val port: Int = 51511,
    val groupIdentifier: String,
    val initialPeerIp: String? = null,
    val advertisedIp: String? = null,
    val isServerMode: Boolean = false
)