package me.kellybecker.android.euchure.server.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Duration
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.containsKey
import kotlin.collections.filter
import kotlin.collections.forEach
import kotlin.collections.get
import kotlin.collections.mutableListOf
import kotlin.collections.mutableMapOf
import kotlin.collections.set

val connections: SocketPool = SocketPool()

class SocketPool: MutableMap<String, MutableList<suspend (Frame) -> Unit>> by mutableMapOf() {
    fun removeFromRoom(
        room: String?,
        lmbda: suspend (Frame) -> Unit,
        msg: String = "Unhooking lambda function"
    ) {
        if(room == null) return

        if(this.containsKey(room)) {
            if(this[room]!!.contains(lmbda)) {
                println(msg)
                this[room]!!.remove(lmbda)

                if (this[room]!!.size < 1) {
                    println("Removing unused room: \"$room\"")
                    this.remove(room)
                }
            }
        }
    }

    fun addToRoom(room: String?, lmbda: suspend (Frame) -> Unit) {
        if(room == null) return

        if(!this.containsKey(room)) {
            println("Created a new room")
            this[room] = mutableListOf()
        }
        println("Joining room: \"$room\"")
        this[room]!!.add(lmbda)
    }

    fun numRooms(): Int = this.size
    fun numConnections(): Int = this.map{ it.value.size }.sum()
    fun numConnectionsInRoom(room: String?): Int {
        if(room == null || !this.containsKey(room)) return 0
        return this[room]!!.size
    }
}

fun Application.configureSockets() {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }
    routing {
        webSocket("/ws") { // websocketSession
            val lmbda: suspend (Frame) -> Unit = { outgoing.send(it) }
            var roomID: String? = null

            println("\n\n[SERVER:$roomID] Client Connected\n")
            println("\tRooms: ${connections.numRooms()}")
            println("\tConnections: ${connections.numConnections()}")
            println("\nConnection Object Dump\n${connections.toString()}\n")

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val txt = frame.readText()
                        println("[Server:$roomID] Incoming (Raw):\n\t$txt")
                        val jsn = Json.parseToJsonElement(txt)
                        val _roomID = jsn.jsonObject["roomID"].toString().substring(
                            1, jsn.jsonObject["roomID"].toString().length - 1
                        )

                        // Assign a room and ensure enrollment
                        if(roomID != _roomID) {
                            connections.removeFromRoom(roomID, lmbda, "Leaving old room...")
                            println("Changing room...")
                            roomID = "${_roomID}"

                            connections.addToRoom(roomID, lmbda)
                        }

                        println("\n\n[Server:$roomID] Incoming: ${jsn.jsonObject}\n")
                        println("\tRooms: ${connections.numRooms()}")
                        println("\tConnections: ${connections.numConnections()}")
                        println("\nConnection Object Dump\n${connections.toString()}\n")

                        // Relay the message to the connections
                        if(connections.containsKey(roomID)) {
                            println("\tPlayers in room: \"$roomID\" (${connections.numConnectionsInRoom(roomID)})")
                            connections[roomID]!!.filter{
                                if(jsn.jsonObject["loopback"].toString() == "true") { true }
                                else { it != lmbda }
                            }.forEach({ it(Frame.Text(txt)) })
                        }

                        // Disconnection
                        if (txt.equals("bye", ignoreCase = true)) {
                            connections.removeFromRoom(roomID, lmbda)
                            close(CloseReason(CloseReason.Codes.NORMAL, "Client said BYE"))
                        }
                    }
                }
            } catch(e: ClosedSendChannelException) {
                println("[SERVER:$roomID] Send Channel Closed\n\t\"${e.toString()}\"")
                if(connections.containsKey(roomID)) {
                    connections[roomID]!!.remove(lmbda)
                }
                closeExceptionally(e)
            } catch(e: ClosedReceiveChannelException) {
                println("[SERVER:$roomID] Receive Channel Closed\n\t\"${e.toString()}\"")
                if(connections.containsKey(roomID)) {
                    connections[roomID]!!.remove(lmbda)
                }
                closeExceptionally(e)
            } catch(e: Throwable) {
                println("[SERVER:$roomID] Error\n\t\"${e.toString()}\"")
                if(connections.containsKey(roomID)) {
                    connections[roomID]!!.remove(lmbda)
                }
                closeExceptionally(e)
            }
        }
    }
}
