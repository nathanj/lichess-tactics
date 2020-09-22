package com.github.nathanj

import com.github.nathanj.lichess.EvalDeserializer
import com.github.nathanj.lichess.LichessEval
import com.github.nathanj.lichess.LichessGame
import com.github.nathanj.models.*
import com.google.gson.GsonBuilder
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readText
import io.ktor.features.CallLogging
import io.ktor.gson.gson
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.resources
import io.ktor.http.content.static
import io.ktor.mustache.Mustache
import io.ktor.mustache.MustacheContent
import io.ktor.request.path
import io.ktor.response.respond
import io.ktor.response.respondRedirect
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.alcibiade.chess.model.ChessBoardModel
import org.alcibiade.chess.persistence.FenMarshallerImpl
import org.alcibiade.chess.persistence.PgnMarshaller
import org.alcibiade.chess.persistence.PgnMarshallerImpl
import org.alcibiade.chess.rules.ChessRules
import org.alcibiade.chess.rules.ChessRulesImpl
import org.flywaydb.core.Flyway
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.sqlobject.kotlin.onDemand
import org.slf4j.event.Level
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.net.URLEncoder
import java.time.Instant
import java.util.*
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.sign

private val logger = KotlinLogging.logger {}

fun Int.absMax(max: Int): Int {
    if (this.absoluteValue > max) {
        return this.sign * max
    }
    return this
}

/** Eval changes greater than this should be noted. */
const val BLUNDER_THRESHOLD = 250

/** Eval changes after this should not be shown since the position is completely winning. */
const val WINNING_THRESHOLD = 800

/**
 * Find positions where a large swing in evaluation failed to be taken advantage of.
 */
fun findMissedTactics(game: LichessGame, eval: List<LichessEval>): List<Int> {
    val blunders = ArrayList<Int>()
    repeat(eval.size - 2) { i ->
        val ev = eval[i].eval.absMax(WINNING_THRESHOLD)
        val ev1 = eval[i + 1].eval.absMax(WINNING_THRESHOLD)
        val ev2 = eval[i + 2].eval.absMax(WINNING_THRESHOLD)
        val delta = ev1 - ev
        val delta2 = ev2 - ev1
        val threshold2 = abs(delta) * 0.66
        logger.debug {
            val moveDisplay = "${i / 2 + 1}. ${if (i % 2 == 1) "..." else "   "}"
            "id=${game.id} move=$moveDisplay ev=$ev ev1=$ev1 ev2=$ev2 delta=$delta delta2=$delta2"
        }
        if (
        // Make sure the position is winning for us and not the opponent.
            ev1.sign == (if (i % 2 == 0) 1 else -1) &&
            // If the position has gone to a winning position.
            abs(eval[i + 1].eval) >= BLUNDER_THRESHOLD &&
            // If the move was what brought it to the winning position.
            abs(delta) >= BLUNDER_THRESHOLD &&
            // If the next move failed to take advantage of the position.
            abs(delta2) >= threshold2 &&
            // Remove small relative differences (i.e. do not show 7.0 -> 10.0 eval changes)
            abs(delta) * 2 > abs(ev1) &&
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

fun getLatestPuzzles(userId: String? = null, limit: Int = 6, shuffle: Boolean = false): Map<String, Any> {
    val puzzles = if (userId != null)
        jdbi.onDemand<PuzzleDao>().listByUser(userId, limit)
    else
        jdbi.onDemand<PuzzleDao>().list(limit)

    val boards = ArrayList<Map<String, Any>>()
    puzzles.toList().forEach { puzzle ->
        boards.add(puzzle.toMap())
    }
    if (shuffle)
        boards.shuffle()
    return mapOf("boards" to boards, "num_tactics" to boards.size)
}

fun generateBoards(games: List<LichessGame>) {
    games.filter { game -> game.analysis != null }
        .filter { game -> jdbi.onDemand<GameDao>().count(game.id) == 0 }
        .forEach { game ->
            val blunders = findMissedTactics(game, game.analysis!!)
            var position = rules.initialPosition

            try {
                val g = Game(game.id, Instant.ofEpochMilli(game.createdAt))
                jdbi.onDemand<GameDao>().insert(g)
            } catch (ex: Exception) {
                println("ex = $ex")
            }

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

                    if (blunders.contains(i - 1)) {
                        val fen = fenMarshaller.convertPositionToString(position)

                        val moveDisplay = "${i / 2 + 1}. ${if (i % 2 == 1) "..." else ""} $move"

                        try {
                            val puzzle = Puzzle(
                                id = "${game.id}_${i}",
                                userIdWhite = game.players["white"]?.user?.id?.toLowerCase() ?: "",
                                userIdBlack = game.players["black"]?.user?.id?.toLowerCase() ?: "",
                                gameId = game.id,
                                fen = fen,
                                orientation = position.nextPlayerTurn.fullName,
                                url = "https://lichess.org/${game.id}/${position.nextPlayerTurn.fullName}#${i + 1}",
                                moveNumber = i + 1,
                                moveDisplay = moveDisplay,
                                moveSource = path.source.pgnCoordinates,
                                moveDestination = path.destination.pgnCoordinates,
                                created = Instant.now()
                            )
                            jdbi.onDemand<PuzzleDao>().insert(puzzle)
                            logger.info { "generated puzzle ${game.id}_${i}" }
                        } catch (ex: Exception) {
                            println("ex = $ex")
                        }
                    }
                }
            } catch (ex: Exception) {
                println(ex)
            }
        }
}

lateinit var jdbi: Jdbi

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        Class.forName("org.sqlite.JDBC")

        val flyway = Flyway.configure().dataSource("jdbc:sqlite:file:./puzzles.db", null, null).load()
        flyway.migrate()

        jdbi = Jdbi.create("jdbc:sqlite:file:./puzzles.db")
        jdbi.installPlugins()

        val server = embeddedServer(
            Netty,
            port = 7000,
            host = "127.0.0.1",
            module = Application::mymodule
        )

        server.start(wait = false)
    }
}

fun Application.mymodule() {
    install(Mustache) {
        mustacheFactory = com.github.mustachejava.DefaultMustacheFactory("templates")
    }
    install(io.ktor.features.ContentNegotiation) {
        gson {
            setPrettyPrinting()
        }
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> !call.request.path().startsWith("/static") }
    }
    val builder = GsonBuilder()
    builder.registerTypeAdapter(LichessEval::class.java, EvalDeserializer())
    val gson = builder.create()

    routing {
        static("/static") {
            resources("static")
        }
        get("/") {
            val data = getLatestPuzzles()
            call.respond(MustacheContent("index.mustache", data))
        }

        get("/view") {
            val userId = call.parameters["q"]?.toLowerCase() ?: ""
            if (userId.isEmpty()) {
                call.respondRedirect("/")
                return@get
            }
            val fmt = call.parameters["fmt"] ?: ""
            val data = mutableMapOf<String, Any>("userId" to userId)
            data += getLatestPuzzles(userId, limit = 100, shuffle = true)
            data["userIdEnc"] = URLEncoder.encode(userId, "UTF-8")
            if (fmt == "txt")
                call.respond(MustacheContent("search-txt.mustache", data))
            else
                call.respond(MustacheContent("search.mustache", data))
        }

        get("/search") {
            val userId = call.parameters["q"]?.toLowerCase() ?: ""
            if (userId.isEmpty()) {
                call.respondRedirect("/")
                return@get
            }

            val userDao = jdbi.onDemand<UserDao>()
            val user = userDao.findOne(userId) ?: {
                val u = User(id = userId)
                userDao.insert(u)
                u
            }()

            val enc = URLEncoder.encode(userId, "UTF-8")
            val data = mutableMapOf<String, Any>("userId" to userId, "userIdEnc" to enc)

            if (!user.fetching && user.lastFetched.plusSeconds(10) < Instant.now()) {
                logger.info { "search $userId: user=$user start fetching" }
                userDao.update(user.copy(fetching = true))
                try {
                    val url =
                        "https://lichess.org/games/export/$enc?max=20&analysed=true&moves=true&evals=true&perfType=blitz,rapid,classical,correspondence"
                    logger.info { "search $userId: starting fetch for url=$url" }
                    // lichess can take a while to respond to this query
                    val result = withTimeout(240_000) {
                        HttpClient(CIO).use { client ->
                            client.get<HttpResponse>(url) {
                                header("accept", "application/x-ndjson")
                            }
                        }
                    }
                    logger.info { "search $userId: ${result.status}" }
                    when (result.status) {
                        HttpStatusCode.OK -> {
                            val games = result.readText().trim().split("\n")
                                .map { gson.fromJson(it, LichessGame::class.java) }.filterNotNull()
                            generateBoards(games)
                        }
                        HttpStatusCode.NotFound -> {
                            data["error"] =
                                "Failed to fetch games from Lichess. Username not found. Please check your spelling."
                        }
                        else -> {
                            data["error"] = "Failed to fetch games from Lichess. ${result.status}"
                        }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    logger.info { "search $userId: timed out" }
                    data["error"] = "Failed to fetch games from Lichess. Timed out."
                } catch (ex: java.lang.Exception) {
                    logger.info { "search $userId: exception: $ex" }
                    ex.printStackTrace()
                    data["error"] = "Failed to fetch games from Lichess. $ex"
                } finally {
                    logger.info { "search $userId: updating user=$user" }
                    userDao.update(user.copy(fetching = false, lastFetched = Instant.now()))
                }
                data += getLatestPuzzles(userId, limit = 100, shuffle = true)
                call.respond(MustacheContent("search.mustache", data))
            } else if (user.fetching) {
                logger.info { "search $userId: still fetching ???" }
                call.respondRedirect("/")
            } else {
                logger.info { "search $userId: NOT fetching" }
                data += getLatestPuzzles(userId, limit = 100, shuffle = true)
                call.respond(MustacheContent("search.mustache", data))
            }
        }
    }
}
