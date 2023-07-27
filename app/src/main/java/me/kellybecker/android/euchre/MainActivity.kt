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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.kellybecker.android.euchre.logic.Card
import me.kellybecker.android.euchre.logic.Game
import me.kellybecker.android.euchre.ui.theme.EuchreTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gameInstance = Game()

        // Assuming local hosted game
        gameInstance.shuffle()
        gameInstance.cut(/* onMyCut = { /*  */ } */)
        gameInstance.deal()

        setContent { MainActivityContent(gameInstance) }
    }
}

@Composable
fun MainActivityContent(gameInstance: Game) {
    EuchreTheme {
        // A surface container using the 'background' color from the theme
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val scope = rememberCoroutineScope()

            // User Input Dialogs
            var showPickItUp by remember { mutableStateOf(false)   }
            val flowPickItUp =  remember { MutableSharedFlow<Boolean>() }
            var showSelectTrump by remember { mutableStateOf(false)  }
            val flowSelectTrump =  remember { MutableSharedFlow<String>() }
            var showYourTurn by remember { mutableStateOf(false) }
            val flowYourTurn =  remember { MutableSharedFlow<Card>()  }

            LaunchedEffect(Unit) {
                // Pick It Up to Select Trump
                showPickItUp = true
                val pickItUp = flowPickItUp.first()
                gameInstance.phasePickItUp(pickItUp)

                // Select Trump
                if(gameInstance.trump() == "") {
                    gameInstance.phaseSelectTrump {
                        coroutineScope {
                            showSelectTrump = true
                            flowSelectTrump.first()
                        }
                    }
                }

                gameInstance.phasePlay {
                    coroutineScope {
                        showYourTurn = true
                        flowYourTurn.first()
                    }
                }
            }

            Log.d("EUCHRE", gameInstance.hands.toString())

            CardTable(
                player = 1,
                gameInstance = gameInstance,
                showPickItUp = showPickItUp,
                onPickItUp = { pick ->
                    scope.launch { flowPickItUp.emit(pick); showPickItUp = false }
                },
                showSelectTrump = showSelectTrump,
                onSelectTrump = { selectTrump ->
                    scope.launch { flowSelectTrump.emit(selectTrump); showSelectTrump = false }
                },
                showYourTurn = showYourTurn,
                onYourTurn = { selectCard ->
                    scope.launch { flowYourTurn.emit(selectCard); showYourTurn = false }
                }
            )
        }
    }
}

@Composable
fun CardTable(
    player: Int,
    gameInstance: Game,
    showPickItUp: Boolean,
    onPickItUp: (Boolean) -> Unit,
    showSelectTrump: Boolean,
    onSelectTrump: (String) -> Unit,
    showYourTurn: Boolean,
    onYourTurn: (Card) -> Unit,
) {
    gameInstance.hands[(player + 0) % 4].isAI = false

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
                    GameCard(
                        gameInstance.kitty[0].suit,
                        gameInstance.kitty[0].card,
                    )
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

@OptIn(ExperimentalMaterial3Api::class)
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

    Card(modifier = Modifier.padding(2.5.dp).clickable(onClick = onClick)) {
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

    // Assuming local hosted game
    gameInstance.shuffle()
    gameInstance.cut(/* onMyCut = { /*  */ } */)
    gameInstance.deal()

    MainActivityContent(gameInstance)
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