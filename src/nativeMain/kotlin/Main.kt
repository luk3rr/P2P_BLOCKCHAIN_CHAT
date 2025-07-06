import kotlinx.coroutines.*
import model.AppConfig
import network.Blockchain
import network.MessageCodec
import network.P2PNode
import utils.LogOutputMode
import utils.LoggerManager

fun main(args: Array<String>): Unit = runBlocking {
    if (args.size < 3) {
        println("Uso: <host_ip> <porta> <identificador_grupo> [ip_peer_inicial]")
        return@runBlocking
    }

    LoggerManager.init(
        "log",
        "blockchain.log",
        LogOutputMode.FILE_ONLY,
    )

    val config = AppConfig(
        hostIp = args[0],
        port = args[1].toInt(),
        groupIdentifier = args[2],
        initialPeerIp = args.getOrNull(3)
    )

    val codec = MessageCodec()
    val blockchain = Blockchain(codec)

    val p2pNodeScope = CoroutineScope(this.coroutineContext + SupervisorJob())

    val node = P2PNode(p2pNodeScope, config, blockchain, codec)
    node.start()

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
}