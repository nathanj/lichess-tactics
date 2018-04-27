@file:Suppress("MemberVisibilityCanPrivate")

package com.github.nathanj

import com.github.kittinunf.fuel.httpGet
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import io.javalin.Javalin
import io.javalin.embeddedserver.Location
import mu.KotlinLogging
import org.alcibiade.chess.model.ChessBoardModel
import org.alcibiade.chess.persistence.FenMarshallerImpl
import org.alcibiade.chess.persistence.PgnMarshaller
import org.alcibiade.chess.persistence.PgnMarshallerImpl
import org.alcibiade.chess.rules.ChessRules
import org.alcibiade.chess.rules.ChessRulesImpl
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.lang.reflect.Type
import java.net.URLEncoder
import kotlin.math.absoluteValue
import kotlin.math.sign

private val logger = KotlinLogging.logger {}

data class Eval(
        val eval: Int
)

class EvalDeserializer : JsonDeserializer<Eval> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): Eval {
        json.asJsonObject.get("mate")?.let {
            return Eval(it.asInt.sign * 99999)
        }
        return Eval(json.asJsonObject.get("eval")!!.asInt)
    }
}

data class Player(
        val userId: String
)

data class Game(
        val id: String,
        val speed: String,
        val moves: String,
        val players: Map<String, Player>,
        val analysis: List<Eval>?
)

data class LichessGames(
        val currentPageResults: List<Game>
)

fun Int.absMax(max: Int): Int {
    if (this.absoluteValue > max) {
        return this.sign * max
    }
    return this
}

fun findBlunders(eval: List<Eval>): List<Int> {
    val blunders = ArrayList<Int>()
    eval.zipWithNext().forEachIndexed { index, (e1, e2) ->
        if (Math.abs(e1.eval - e2.eval) >= 300 && Math.abs(e2.eval) > Math.abs(e1.eval)) {
            blunders.add(index)
        }
    }
    return blunders
}

/** Eval changes greater than this should be noted. */
const val BLUNDER_THRESHOLD = 250

/** Eval changes after this should not be shown since the position is completely winning. */
const val WINNING_THRESHOLD = 2000

/**
 * Find positions where a large swing in evaluation failed to be taken advantage of.
 */
fun findMissedTactics(game: Game, eval: List<Eval>): List<Int> {
    val blunders = ArrayList<Int>()
    repeat(eval.size - 2) { i ->
        val ev = eval[i].eval.absMax(WINNING_THRESHOLD)
        val ev1 = eval[i + 1].eval.absMax(WINNING_THRESHOLD)
        val ev2 = eval[i + 2].eval.absMax(WINNING_THRESHOLD)
        val delta = ev1 - ev
        val delta2 = ev2 - ev1
        val threshold2 = Math.abs(delta) * 0.66
        logger.debug {
            val moveDisplay = "${i / 2 + 1}. ${if (i % 2 == 1) "..." else "   "}"
            "id=${game.id} move=$moveDisplay ev=$ev ev1=$ev1 ev2=$ev2 delta=$delta delta2=$delta2"
        }
        if (
        // Make sure the position is winning for us and not the opponent.
        ev1.sign == (if (i % 2 == 0) 1 else -1) &&
                // If the position has gone to a winning position.
                Math.abs(eval[i + 1].eval) >= BLUNDER_THRESHOLD &&
                // If the move was what brought it to the winning position.
                Math.abs(delta) >= BLUNDER_THRESHOLD &&
                // If the next move failed to take advantage of the position.
                Math.abs(delta2) >= threshold2 &&
                // Remove small relative differences (i.e. do not show 7.0 -> 10.0 eval changes)
                Math.abs(delta) * 2 > Math.abs(ev1) &&
                delta.sign != delta2.sign
                ) {
            logger.debug { "Next move is missed tactic ->" }
            blunders.add(i)
        }
    }
    return blunders
}

private val context = AnnotationConfigApplicationContext(ChessRulesImpl::class.java, PgnMarshallerImpl::class.java)
private val rules = context.getBean(ChessRules::class.java)
private val marshaller = context.getBean(PgnMarshaller::class.java)
private val fenMarshaller = FenMarshallerImpl()

fun generateBoards(games: LichessGames, user: String, time: Array<String>): Map<String, Any> {
    val boards = ArrayList<Map<String, String>>()
    val map = HashMap<String, Any>()

    map.put("num_games", games.currentPageResults.size)
    val filteredGames = games.currentPageResults
            .filter { it.analysis != null }
            .filter { time.contains(it.speed) }
    map.put("num_analyzed_games", filteredGames.size)

    filteredGames.forEach { game ->
        val blunders = findMissedTactics(game, game.analysis!!)
        var position = rules.initialPosition
        val isWhite = game.players["white"]!!.userId == user
        val color = if (isWhite) "white" else "black"

        try {
            game.moves.split(" ").forEachIndexed { i, move ->
                val path = marshaller.convertPgnToMove(position, move)
                val updates = rules.getUpdatesForMove(position, path)
                val afterMove = ChessBoardModel()
                afterMove.setPosition(position)
                for (update in updates) {
                    update.apply(afterMove)
                }
                afterMove.nextPlayerTurn()
                position = afterMove

                val isMyBlunder = ((isWhite && i % 2 == 1) ||
                        (!isWhite && i % 2 == 0))

                if (blunders.contains(i - 1) && isMyBlunder) {
                    val fen = fenMarshaller.convertPositionToString(position)

                    val moveDisplay = "${i / 2 + 1}. ${if (i % 2 == 1) "..." else ""} $move"

                    boards.add(
                            mapOf("game_id" to game.id,
                                    "fen" to fen,
                                    "fen_link" to fen.replace(' ', '_'),
                                    "orientation" to position.nextPlayerTurn.fullName,
                                    "url" to "https://lichess.org/${game.id}/$color#${i + 1}",
                                    "move_number" to "${i + 1}",
                                    "move_display" to moveDisplay,
                                    "move_source" to path.source.pgnCoordinates,
                                    "move_destination" to path.destination.pgnCoordinates,
                                    "id" to "${game.id}_${i}"))
                }
            }
        } catch (ex: Exception) {
            println(ex)
        }

    }

    boards.shuffle()
    map.put("boards", boards)
    map.put("num_tactics", boards.size)

    return map
}

object Main {

    @JvmStatic
    fun main(args: Array<String>) {

        val builder = GsonBuilder()
        builder.registerTypeAdapter(Eval::class.java, EvalDeserializer())
        val gson = builder.create()

        val app = Javalin.create()
        app.port(7000)
        app.enableStaticFiles("static", Location.EXTERNAL)
        app.enableStandardRequestLogging()
        app.get("/") { ctx ->
            ctx.renderMustache("templates/index.mustache")
        }

        app.get("/search") { ctx ->
            val q = ctx.queryParam("q") ?: ""
            val nb = ctx.queryParam("nb") ?: "25"
            val type = ctx.queryParam("type") ?: ""
            val time = ctx.queryParams("time") ?: arrayOf("blitz", "rapid", "classical", "correspondence")
            if (q.isEmpty()) {
                ctx.redirect("/")
            } else {
                try {
                    val n = Math.min(nb.toIntOrNull() ?: 25, 100)
                    val url = "https://lichess.org/api/user/${URLEncoder.encode(q.trim(), "UTF-8")}/games?nb=$n&page=1&with_analysis=1&with_moves=1"
                    val (_, _, result) = url.httpGet().responseString()
                    val games = gson.fromJson(result.get(), LichessGames::class.java)
                    val data = generateBoards(games, q, time)
                    if (type == "txt") {
                        ctx.renderMustache("templates/search-txt.mustache", data)
                    } else {
                        ctx.renderMustache("templates/search.mustache", data)
                    }
                } catch (ex: Exception) {
                    ctx.status(500)
                    ctx.result("There was an error. Make sure you typed your username correctly!\nError: " + ex.toString())
                }
            }
        }

        app.start()
    }
}
