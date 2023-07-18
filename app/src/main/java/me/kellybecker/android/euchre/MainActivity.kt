package me.kellybecker.android.euchre

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.kellybecker.android.euchre.logic.Game
import me.kellybecker.android.euchre.ui.theme.EuchreTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MainActivityContent() }
    }
}

@Composable
fun MainActivityContent() {
    EuchreTheme {
        // A surface container using the 'background' color from the theme
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val gameInstance = Game()

            // Assuming local hosted game
            gameInstance.shuffle()
            gameInstance.cut(/* onMyCut = { /*  */ } */)
            gameInstance.deal()

            Log.d("CARDS", gameInstance.hands.toString())

            CardTable(0, gameInstance)
            gameInstance.onShouldPickUp = {
            }
        }
    }
}

@Composable
fun CardTable(player: Int, gameInstance: Game) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        // Player 2
        Row(modifier = Modifier.rotate(180.0F), horizontalArrangement = Arrangement.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Greeting("Player ${player + 2 % 4}, Cards: ${gameInstance.hands[player + 2 % 4].size}")
                Row {
                    gameInstance.hands[player + 2 % 4].forEach {
                        GameCard(it.suit, it.card)
                    }
                }
            }
        }
        Row(modifier = Modifier
            .height(235.dp)
            .wrapContentSize(unbounded = true)) {
            // Player 1
            Row(modifier = Modifier.rotate(90.0F)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Greeting("Player ${player + 1 % 4}, Cards: ${gameInstance.hands[player + 1 % 4].size}")
                    Row {
                        gameInstance.hands[player + 1 % 4].forEach {
                            GameCard(it.suit, it.card)
                        }
                    }
                }
            }
            Column(modifier = Modifier.height(45.dp).wrapContentSize(unbounded = true)) {
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
            Column(modifier = Modifier.height(45.dp).wrapContentSize(unbounded = true)) {
                Text("Trick")
                gameInstance.trickCards.forEach {
                    GameCard(it.value.suit, it.value.card)
                }
            }
            // Player 3
            Row(modifier = Modifier.rotate(-90.0F)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Greeting("Player ${player + 3 % 4}, Cards: ${gameInstance.hands[player + 3 % 4].size}")
                    Row {
                        gameInstance.hands[player + 3 % 4].forEach {
                            GameCard(it.suit, it.card)
                        }
                    }
                }
            }
        }
        // Player 0
        Row(horizontalArrangement = Arrangement.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Greeting("Player ${player + 0 % 4}, Cards: ${gameInstance.hands[player + 0 % 4].size}")
                Row {
                    gameInstance.hands[player + 0 % 4].forEach {
                        GameCard(it.suit, it.card)
                    }
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
fun GameCard(suit: String, face: String) {
    val red = listOf("♥", "♦")

    val suitColor = if(suit in red) {
        Color.Red
    } else {
        Color.Black
    }

    Card(modifier = Modifier.padding(2.5.dp)) {
        Row(modifier = Modifier.padding(1.7.dp, end = 3.2563.dp)) {
            Text(suit, color = suitColor, fontWeight = FontWeight.Black)
            Text(face)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainActivityPreview() {
    MainActivityContent()
}