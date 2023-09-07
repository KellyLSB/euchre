package me.kellybecker.android.euchre

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.kellybecker.android.euchre.logic.Card
import me.kellybecker.android.euchre.logic.Game
import me.kellybecker.android.euchre.logic.WSData
import me.kellybecker.android.euchre.ui.theme.EuchreTheme
import java.net.URI

class MainActivity : ComponentActivity() {
    val gameInstance: Game = Game()
    lateinit var scope: CoroutineScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            scope = rememberCoroutineScope()
            MainActivityContent(scope, gameInstance)
        }
    }

    override fun onPause() {
        super.onPause()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainActivityContent(scope: CoroutineScope, gameInstance: Game) {
    // Recomposer
    var _recompose by remember { mutableStateOf(false) }
    val recompose: () -> Unit = { _recompose = !_recompose }

    // WebSockets / Select my Hand
    var wsURI by remember { mutableStateOf("ws://10.0.2.2:8080/ws") }
    var idRoom by remember { mutableStateOf("Room") }
    var idPlayer by remember { mutableStateOf(1) }
    var connected by remember { mutableStateOf(false) }
    var showLobby by remember { mutableStateOf(true) }
    var showReady  by remember { mutableStateOf(false) }
    val flowReady  = remember { MutableSharedFlow<Boolean>() }

    // User Input Dialogs
    var showCutDeck by remember { mutableStateOf(false)   }
    val flowCutDeck  = remember { MutableSharedFlow<Boolean>() }
    var showPickItUp by remember { mutableStateOf(false)   }
    val flowPickItUp =  remember { MutableSharedFlow<Boolean>() }
    var showSelectTrump by remember { mutableStateOf(false)  }
    val flowSelectTrump =  remember { MutableSharedFlow<String>() }
    var showGoAlone by remember { mutableStateOf(false) }
    val flowGoAlone =  remember { MutableSharedFlow<Boolean>() }
    var showYourTurn by remember { mutableStateOf(false) }
    val flowYourTurn =  remember { MutableSharedFlow<Card>()  }

    LaunchedEffect(Unit) {
        gameInstance.webSocket.roomID = idRoom
        gameInstance.webSocket.playerID = idPlayer

        gameInstance.webSocket.onClose { connected = it }

        // Game Events
        gameInstance.webSocket.onMessage { it, relayed ->
            gameInstance.wsMessage(it, relayed = relayed)
        }

        // UI Events unlrelated to player
        gameInstance.webSocket.onMessage { it, relayed ->
            Log.d("OnMsg", "Relayed: $relayed, $it")

            when(it.methodID) {
                "claimHost" -> if(relayed) showReady = true
                "phaseDeal" -> if(it.boolean) recompose()
                "phasePickItUp" -> if(it.boolean) recompose()
                "phaseSelectTrump" -> if(it.string != "") recompose()
                "phasePlay" -> if(it.card != Card("", "")) recompose()
                else -> {}
            }
        }

        // Player Events
        // I'm the foreigner; receive my play
        scope.launch {
            gameInstance.webSocket.receiverFlow.collect {
                if (it.playerID == idPlayer) {
                    when (it.methodID) {
                        "@phaseReady" -> showReady = gameInstance.readyCheck(
                            playerID = it.playerAlt,
                            boolean = it.boolean,
                        )
                    }
                }
            }
        }

        println("Ready?")

        flowReady.first()

        gameInstance.phaseReady()
        println("READIED")

        while(true) {
            gameInstance.phaseShuffle()
            println("SHUFFLED")
            gameInstance.phaseCut {
                showCutDeck = true
                flowCutDeck.first()
            }
            println("CUT")
            gameInstance.phaseDeal()
            println("DEALT")
            gameInstance.phasePickItUp {
                showPickItUp = true
                flowPickItUp.first()
            }
            gameInstance.phaseSelectTrump {
                showSelectTrump = true
                flowSelectTrump.first()
            }
            gameInstance.phaseGoAlone {
                showGoAlone = true
                flowGoAlone.first()
            }
            gameInstance.phasePlay {
                showYourTurn = true
                flowYourTurn.first()
            }

            showCutDeck = false
            showPickItUp = false
            showSelectTrump = false
            gameInstance.phaseRecollect()
        }
    }

    Log.d("EUCHRE", gameInstance.hands.toString())

    EuchreTheme {
        // A surface container using the 'background' color from the theme
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if(showLobby) {
                    TopAppBar(
                        title = {
                            Column {
                                BasicTextField(
                                    value = wsURI,
                                    textStyle = LocalTextStyle.current.copy(
                                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                        color = if(connected) {
                                            Color.Green
                                        } else {
                                            Color.Red
                                        }
                                    ),
                                    onValueChange = { u: String -> wsURI = u }
                                )
                                BasicTextField(
                                    value = idRoom,
                                    onValueChange = { r: String ->
                                        gameInstance.webSocket.roomID = r
                                        idRoom = r
                                    }
                                )
                            }
                        },
                        actions = {
                            (0..3).forEach {
                                ReadyPlayerSelect(
                                    player = it,
                                    gameInstance = gameInstance,
                                    idPlayer = idPlayer,
                                    onChangePlayer = {
                                        idPlayer = it
                                        gameInstance.webSocket.playerID = idPlayer
                                    }
                                )
                            }
                        }
                    )
                    Row {
                        if(!connected || connected && showReady) {
                            Button(onClick = {
                                scope.launch {
                                    gameInstance.wsSend(WSData(
                                        methodID = "aiOverride",
                                        boolean = true,
                                    ))

                                    showLobby = false
                                    flowReady.emit(true)
                                }
                            }) {
                                Text("Ready")
                            }
                        }
                        Button(onClick = {
                            scope.launch {
                                gameInstance.webSocket.connect(URI(wsURI))
                                connected = gameInstance.webSocket.isConnected()
                            }
                        }) {
                            if(connected) {
                                Text("Reconnect")
                            } else {
                                Text("Connect")
                            }
                        }
                    }
                    Row {
                        Button(onClick = { wsURI = "ws://192.168.1.181:8080/ws" }) {
                            Text("192.168.1.181")
                        }
                        Button(onClick = { wsURI = "ws://10.0.2.2:8080/ws" }) {
                            Text("10.0.2.2")
                        }
                    }
                } else {
                    CardTable(
                        _recompose = _recompose,
                        recompose = recompose,
                        player = idPlayer,
                        gameInstance = gameInstance,
                        showCutDeck = showCutDeck,
                        onCutDeck = { cut ->
                            scope.launch { flowCutDeck.emit(cut); showCutDeck = false }
                        },
                        showPickItUp = showPickItUp,
                        onPickItUp = { pick ->
                            scope.launch { flowPickItUp.emit(pick); showPickItUp = false }
                        },
                        showSelectTrump = showSelectTrump,
                        onSelectTrump = { selectTrump ->
                            scope.launch {
                                flowSelectTrump.emit(selectTrump); showSelectTrump = false
                            }
                        },
                        showGoAlone = showGoAlone,
                        onGoAlone = { goAlone ->
                            scope.launch { flowGoAlone.emit(goAlone); showGoAlone = false }
                        },
                        showYourTurn = showYourTurn,
                        onYourTurn = { selectCard ->
                            scope.launch { flowYourTurn.emit(selectCard); showYourTurn = false }
                        }
                    )
                }
            }
        }
    }
}

/**
 * ReadyPlayerSelect templates the player selection prompt
 */
@Composable
fun ReadyPlayerSelect(
    player: Int,
    gameInstance: Game,
    idPlayer: Int,
    onChangePlayer: (Int) -> Unit,
) {
    Button(
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (idPlayer == player) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                if(gameInstance.readyChecks[player]) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                }
            }
        ), onClick = {
            if(gameInstance.isHost.first != idPlayer)
            if(!gameInstance.readyChecks[player]) onChangePlayer(player)
        }
    ) {
        Text("${player}")
        if(gameInstance.readyChecks[player]) Text("✓")
    }
}

@Composable
fun CardTable(
    _recompose: Boolean,
    recompose: () -> Unit,
    player: Int,
    gameInstance: Game,
    showCutDeck: Boolean,
    onCutDeck: (Boolean) -> Unit,
    showPickItUp: Boolean,
    onPickItUp: (Boolean) -> Unit,
    showSelectTrump: Boolean,
    onSelectTrump: (String) -> Unit,
    showGoAlone: Boolean,
    onGoAlone: (Boolean) -> Unit,
    showYourTurn: Boolean,
    onYourTurn: (Card) -> Unit,
) {
    if(_recompose) {}

    if(showCutDeck) {
        AlertDialog(
            onDismissRequest = {},
            dismissButton = {
                Button(onClick = { onCutDeck(false) }) {
                    Text("Pass")
                }
            },
            confirmButton = {
                Button(onClick = { onCutDeck(true) }) {
                    Text("Cut")
                }
            },
            title = {
                Text("Cut the Deck?")
            }
        )
    }

    if(showPickItUp) {
        AlertDialog(
            onDismissRequest = {},
            dismissButton = {
                Button(onClick = { onPickItUp(false) }) {
                    Text("Pass")
                }
            },
            confirmButton = {
                Button(onClick = { onPickItUp(true) }) {
                    Text("Pick It Up")
                }
            },
            title = {
                Column {
                    Text("Should Pick it Up?")
                    GameCard(
                        gameInstance.kitty[0].suit,
                        gameInstance.kitty[0].card,
                    )
                }
            }
        )
    }

    if(showSelectTrump) {
        AlertDialog(
            onDismissRequest = {},
            dismissButton = {
                Button(onClick = { onSelectTrump("") }) {
                    Text("Pass")
                }
            },
            confirmButton = {},
            title = {
                Column {
                    Text("Select Trump")
                    Row {
                        GameCard("♠", onClick = { onSelectTrump("♠") })
                        GameCard("♥", onClick = { onSelectTrump("♥") })
                    }
                    Row {
                        GameCard("♦", onClick = { onSelectTrump("♦") })
                        GameCard("♣", onClick = { onSelectTrump("♣") })
                    }
                }
            }
        )
    }

    if(showGoAlone) {
        AlertDialog(
            onDismissRequest = {},
            dismissButton = {
                Button(onClick = { onGoAlone(false) }) {
                    Text("No")
                }
            },
            confirmButton = {
                Button(onClick = { onGoAlone(true) }) {
                    Text("Yes")
                }
            },
            title = {
                Column {
                    Text("Should I Go Alone?")
                }
            }
        )
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        // Player 2
        CardHand(
            playerHand = (player + 2) % 4,
            gameInstance = gameInstance,
            modifier = Modifier.rotate(180.0F),
            openHand = if(gameInstance.openHand) {
                true
            } else {
                player == (player + 2) % 4
            }
        )
        Row(modifier = Modifier
            .height(235.dp)
            .wrapContentSize(unbounded = true)) {
            // Player 1
            CardHand(
                playerHand = (player + 1) % 4,
                gameInstance = gameInstance,
                modifier = Modifier.rotate(90.0F),
                openHand = if(gameInstance.openHand) {
                    true
                } else {
                    player == (player + 1) % 4
                }
            )
            Column(modifier = Modifier
                .height(45.dp)
                .wrapContentSize(unbounded = true),
                horizontalAlignment = Alignment.End
            ) {
                if(gameInstance.trump() == "") {
                    Text("Kitty")
                    if(gameInstance.kitty.isNotEmpty()) {
                        GameCard(
                            gameInstance.kitty[0].suit,
                            gameInstance.kitty[0].card,
                        )
                    }
                } else {
                    Text("Trump")
                    GameCard(gameInstance.trump(), "")
                }
            }
            Column(modifier = Modifier
                .height(45.dp)
                .wrapContentSize(unbounded = true)) {
                Text("Trick")
                gameInstance.trickCards.forEach {
                    GameCard(it.value.suit, it.value.card)
                }
            }
            // Player 3
            CardHand(
                playerHand = (player + 3) % 4,
                gameInstance = gameInstance,
                modifier = Modifier.rotate(-90.0F),
                openHand = if(gameInstance.openHand) {
                    true
                } else {
                    player == (player + 3) % 4
                }
            )
        }
        // Player 0
        CardHand(
            playerHand = (player + 0) % 4,
            gameInstance = gameInstance,
            openHand = if(gameInstance.openHand) {
                true
            } else {
                player == (player + 0) % 4
            },
            showYourTurn = showYourTurn,
            onYourTurn = onYourTurn,
        )

        if(showYourTurn) {
            Text("Its your turn.")
        }

        Row {
            gameInstance.hands.forEach { hand ->
                Column {
                    Text("Player ${hand.hand}:")
                    hand.tricks.forEach { trick ->
                        Column(modifier = Modifier.border(BorderStroke(1.dp, Color.Black))) {
                            trick.forEach { (_, card) ->
                                GameCard(card.suit, card.card)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CardHand(
    playerHand: Int,
    gameInstance: Game,
    modifier: Modifier = Modifier,
    openHand: Boolean = true,
    showYourTurn: Boolean = false,
    onYourTurn: (Card) -> Unit = {},
) {
    Log.d("EUCHRE", "Player: $playerHand")
    Row(modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Greeting("Player ${playerHand}, Cards: ${gameInstance.hands[playerHand].size}")
            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                gameInstance.hands[playerHand].forEach {
                    GameCard(
                        it.suit, it.card, openHand,
                        onClick = if (showYourTurn) {
                            { onYourTurn(it) }
                        } else { {} }
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Composable
fun GameCard(
    suit: String,
    face: String = "",
    openHand: Boolean = true,
    onClick: () -> Unit = {}
) {
    val red = listOf("♥", "♦")

    val suitColor = if(suit in red) {
        Color.Red
    } else {
        Color.Black
    }

    Card(modifier = Modifier
        .padding(2.5.dp)
        .clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(1.7.dp, end = 3.2563.dp)) {
            if(openHand) {
                Text(suit, color = suitColor, fontWeight = FontWeight.Black)
                Text(face)
            } else {
                Text("EU", fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Preview
@Composable
fun GameCardPreview() {
    GameCard("♥", "A")
}

@Preview(showBackground = true)
@Composable
fun MainActivityPreview() {
    val gameInstance = Game()
    val scope = rememberCoroutineScope()
    MainActivityContent(scope, gameInstance)
}

// Sample coroutine code from Jacob
//@Composable
//fun GameScreen() {
//    val gameState = GameState()
//    val scope = rememberCoroutineScope()
//    var showUserInputDialog by remember { mutableStateOf(false) }
//    val userChoice = remember { MutableSharedFlow<GameChoice>() }
//
//    LaunchedEffect(Unit) {
//        while (true) {
//            gameState.advance()
//            if (gameState.isUserTurn()) {
//                showUserInputDialog = true
//                gameState.setUserChoice(userChoice.first())
//            } else {
//                // AI chooses
//                delay(500)
//            }
//        }
//
//        if (showUserInputDialog) {
//            Button(onClick = { scope.launch { userChoice.emit(/*what they chose*/) } })
//        }
//    }
//}
