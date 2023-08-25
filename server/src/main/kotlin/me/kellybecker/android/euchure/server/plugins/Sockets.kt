package me.kellybecker.android.euchure.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.closeExceptionally
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import java.time.Duration

val connections: SocketPool = SocketPool()

class SocketConnection(
    var roomID: String?,
    val session: DefaultWebSocketServerSession
) {
    suspend fun forIncoming(func: suspend (txt: String) -> Unit) {
        session.incoming.consumeEach {
            connections.clean()

            when(it) {
                is Frame.Close -> {
                    println("[Session:${session.hashCode()}] Closing the Connection...")
                }
                is Frame.Ping -> {
                    println("[Session:${session.hashCode()}] PING")
                }
                is Frame.Pong -> {
                    println("[Session:${session.hashCode()}] PING")
                }
                is Frame.Text -> {
                    val txt = it.readText()
                    println("[Session:${session.hashCode()};ROOM:$roomID] Incoming (Raw):\n\t$txt")
                    func(txt)
                }
                is Frame.Binary -> { /*do nothing*/ }
            }
        }
    }

    suspend fun forIncoming(func: suspend (txt: String, jsn: JsonElement) -> Unit) {
        forIncoming { txt: String ->
            val jsn = Json.parseToJsonElement(txt)

            // Update Room ID
            roomID = jsn.jsonObject["roomID"].toString().substring(
                1, jsn.jsonObject["roomID"].toString().length - 1
            )

            println("[Session:${session.hashCode()};ROOM:$roomID] Incoming:\n\t${jsn.jsonObject}")
            func(txt, jsn)
        }
    }

    suspend fun close() = connections.close(this)
}

class SocketPool: MutableList<SocketConnection> by mutableListOf() {
    fun addToRoom(roomID: String?, session: DefaultWebSocketServerSession) {
        val socket = selectSession(session)

        if(socket != null) {
            socket.roomID = roomID
        } else {
            add(SocketConnection(roomID, session))
        }
    }

    fun removeFromRoom(session: DefaultWebSocketServerSession) {
        val socket = selectSession(session)

        if(socket != null) {
            socket.roomID = null
        }
    }

    suspend fun clean() = this.filter{ !it.session.isActive }.forEach { it.close() }

    suspend fun selectRoom(roomID: String?, func: suspend (SocketConnection) -> Unit) {
        val sockets = this.filter{ it.session.isActive }
            .filter{ it.roomID == roomID }

        println("${sockets}")
        sockets.forEach { func(it) }
    }

    fun selectSession(session: DefaultWebSocketServerSession): SocketConnection? {
        return this.find { it.session == session }
    }

    suspend fun close(socket: SocketConnection?) {
        if(socket != null) {
            remove(socket)

            socket.session.close(CloseReason(
                CloseReason.Codes.NORMAL,
                "Closed by SocketPool"
            ))
        }
    }

    suspend fun close(session: DefaultWebSocketServerSession) {
        close(selectSession(session))
    }

    suspend fun close(socket: SocketConnection?, e: Throwable) {
        if(socket != null) {
            remove(socket)
            socket.session.closeExceptionally(e)
        }
    }

    suspend fun close(session: DefaultWebSocketServerSession, e: Throwable) {
        close(selectSession(session), e)
    }

    fun numConnections(): Int = this.size
    fun numRooms(): Int = this.map{ it.roomID }.distinct().size
    fun numConnectionsInRoom(roomID: String?): Int = this.filter{ it.roomID == roomID }.size
}

fun Application.configureSockets() {
    install(WebSockets)
    routing {
        webSocket("/ws") { // websocketSession
            connections.addToRoom(null, this)

            println("\n\n[SERVER] Client Connected\n")
            println("\tRooms: ${connections.numRooms()}")
            println("\tConnections: ${connections.numConnections()}")

            try {
                val session = connections.selectSession(this)

                session!!.forIncoming { txt: String, jsn: JsonElement ->
                    println("\tRooms: ${connections.numRooms()}")
                    println("\tConnections: ${connections.numConnections()}")

                    connections.selectRoom(session.roomID) {
                        println("[Connection:${it.roomID}]")
                        if(session != it || jsn.jsonObject["loopback"].toString() == "true") {
                             it.session.outgoing.send(Frame.Text(txt))
                        }
                    }

                    if (txt.equals("bye", ignoreCase = true)) {
                        connections.close(this)
                    }
                }
            } catch(e: ClosedSendChannelException) {
                connections.close(this, e)
                println("[SERVER] Send Channel Closed\n\t\"${e}\"")
            } catch(e: ClosedReceiveChannelException) {
                connections.close(this, e)
                println("[SERVER] Receive Channel Closed\n\t\"${e}\"")
            } catch(e: Throwable) {
                connections.close(this, e)
                println("[SERVER] Error\n\t\"${e}\"\t${e.stackTraceToString()}")
            }
        }
    }
}
