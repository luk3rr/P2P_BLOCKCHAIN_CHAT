import kotlinx.coroutines.*
import model.AppConfig
import network.Blockchain
import network.MessageCodec
import network.P2PNode
import utils.LogOutputMode
import utils.LoggerManager

fun main(args: Array<String>): Unit = runBlocking {
    val config = parseConfig(args) ?: return@runBlocking

    val logMode = if (config.isServerMode) LogOutputMode.TERMINAL_ONLY else LogOutputMode.FILE_ONLY
    LoggerManager.init(logDir = "log", fileName = "blockchain.log", outputMode = logMode)

    val codec = MessageCodec()
    val blockchain = Blockchain(codec)
    val p2pNodeScope = CoroutineScope(this.coroutineContext + SupervisorJob())
    val node = P2PNode(p2pNodeScope, config, blockchain, codec)

    node.start()

    if (config.isServerMode) {
        delay(Long.MAX_VALUE)
    } else {
        startUserInputLoop(node, blockchain)
    }
}

/**
 * Inicia o loop infinito para ler a entrada do usuário e processar comandos.
 */
private fun CoroutineScope.startUserInputLoop(node: P2PNode, blockchain: Blockchain) {
    launch(Dispatchers.IO) {
        println("Digite uma mensagem para enviar ou '/h' para ver os chats atuais.")
        while (true) {
            print(">> ")
            val line = readlnOrNull()
            if (line.isNullOrBlank()) continue

            when (line.trim()) {
                "/h" -> {
                    val currentChain = blockchain.getChain()
                    println("\n======================= HISTÓRICO DE CHAT ==========================")
                    if (currentChain.isEmpty()) {
                        println("(O histórico está vazio)")
                    } else {
                        currentChain.forEachIndexed { index, chat ->
                            println("[${index + 1}] -> ${chat.text}")
                        }
                    }
                    println("====================================================================\n")
                }
                else -> {
                    launch {
                        node.createAndBroadcastChat(line)
                    }
                }
            }
        }
    }
}

/**
 * Analisa os argumentos da linha de comando e cria um objeto AppConfig
 * Retorna null se os argumentos forem inválidos
 */
private fun parseConfig(args: Array<String>): AppConfig? {
    val isServerMode = args.contains("--server")

    val hostIp = args.find { it.startsWith("--host-ip=") }?.substringAfter("=") ?: "0.0.0.0"
    val port = args.find { it.startsWith("--port=") }?.substringAfter("=")?.toIntOrNull() ?: 51511
    val groupIdentifier = args.find { it.startsWith("--id=") }?.substringAfter("=")
    val initialPeerIp = args.find { it.startsWith("--peer=") }?.substringAfter("=")
    val advertisedIp = args.find { it.startsWith("--advertised-ip=") }?.substringAfter("=")

    if (!isServerMode && groupIdentifier.isNullOrBlank()) {
        println("Erro: O modo normal requer um identificador de grupo. Use --id=<seu_id>")
        println("Uso: --host-ip=<ip> --port=<porta> --id=<id_grupo> [--peer=<ip_peer>] [--advertised-ip=<ip_publico>]")
        return null
    }

    return AppConfig(
        hostIp = hostIp,
        port = port,
        groupIdentifier = groupIdentifier ?: "SERVER_NODE",
        initialPeerIp = initialPeerIp,
        advertisedIp = advertisedIp,
        isServerMode = isServerMode
    )
}
