import kotlinx.coroutines.*
import model.AppConfig
import network.Blockchain
import network.MessageCodec
import network.P2PNode
import utils.LogOutputMode
import utils.LoggerManager

fun main(args: Array<String>): Unit = runBlocking {
    val isServerMode = args.contains("--server")

    val requiredArgs = if (isServerMode) 3 else 4

    if (args.size != requiredArgs) {
        println("Uso normal: <host_ip> <porta> <identificador_grupo> <ip_peer_inicial>")
        println("Uso servidor: <host_ip> <porta> [--server]")
        return@runBlocking
    }

    val logMode = if (isServerMode) LogOutputMode.TERMINAL_ONLY else LogOutputMode.FILE_ONLY
    LoggerManager.init(
        logDir = "log",
        fileName = "blockchain.log",
        outputMode = logMode
    )

    val config = AppConfig(
        hostIp = args[0],
        port = args[1].toInt(),
        groupIdentifier = if (isServerMode) "SERVER_NODE" else args[2],
        initialPeerIp = if (isServerMode) args.getOrNull(2).takeIf { it != "--server" } else args.getOrNull(3)
    )

    val codec = MessageCodec()
    val blockchain = Blockchain(codec)

    val p2pNodeScope = CoroutineScope(this.coroutineContext + SupervisorJob())
    val node = P2PNode(p2pNodeScope, config, blockchain, codec)
    node.start()

    if (!isServerMode) {
        launch(Dispatchers.IO) {
            println("Digite uma mensagem para enviar ou '/h' para ver os chats atuais.")
            while (true) {
                print(">> ")
                val line = readlnOrNull()
                if (line.isNullOrBlank()) continue

                if (line.trim() == "/h") {
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
                    continue
                }

                launch {
                    node.createAndBroadcastChat(line)
                }
            }
        }
    } else {
        println("Nó iniciado em modo SERVIDOR...")
        delay(Long.MAX_VALUE)
    }
}