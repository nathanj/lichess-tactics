@file:Suppress("MemberVisibilityCanPrivate")

package com.github.nathanj

import com.github.kittinunf.fuel.httpGet
import com.github.nathanj.lichess.*
import com.github.nathanj.models.Game
import com.github.nathanj.models.Puzzle
import com.github.nathanj.models.User
import com.github.nathanj.models.Vote
import com.github.scribejava.core.builder.ServiceBuilder
import com.github.scribejava.core.model.OAuth2AccessToken
import com.github.scribejava.core.model.OAuthRequest
import com.github.scribejava.core.model.Verb
import com.google.gson.GsonBuilder
import io.javalin.Context
import io.javalin.HaltException
import io.javalin.Javalin
import io.javalin.embeddedserver.Location
import mu.KotlinLogging
import org.alcibiade.chess.model.ChessBoardModel
import org.alcibiade.chess.persistence.FenMarshallerImpl
import org.alcibiade.chess.persistence.PgnMarshaller
import org.alcibiade.chess.persistence.PgnMarshallerImpl
import org.alcibiade.chess.rules.ChessRules
import org.alcibiade.chess.rules.ChessRulesImpl
import org.litote.kmongo.*
import org.litote.kmongo.MongoOperator.*
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalTime
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.absoluteValue
import kotlin.math.sign

private val logger = KotlinLogging.logger {}

val client = KMongo.createClient()
val database = client.getDatabase("test")!!
val dbGames = database.getCollection<Game>()
val dbPuzzles = database.getCollection<Puzzle>()
val dbUsers = database.getCollection<User>()
val dbVotes = database.getCollection<Vote>()

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

fun getLatestPuzzles(userId: String? = null, limit: Int = 6, shuffle: Boolean = false): Map<String, Any> {
    val puzzles = if (userId != null) {
        dbPuzzles.find("{$or: [{userIdBlack: '$userId', orientation: 'black'}, {userIdWhite: '$userId', orientation: 'white'}]}")
    } else {
        dbPuzzles.find()
    }.sort("{ added: -1 }").limit(limit)
    val boards = ArrayList<Map<String, Any>>()
    puzzles.toList().forEach { puzzle ->
        boards.add(puzzle.toMap())
    }
    if (shuffle)
        boards.shuffle()
    return mapOf("boards" to boards, "num_tactics" to boards.size)
}

fun generateBoards(games: List<LichessGame>, user: String) {
    games.filter { game -> game.analysis != null }
            .filter { game -> dbGames.count("{_id: '${game.id}'}") == 0L }
            .forEach { game ->
                val blunders = findMissedTactics(game, game.analysis!!)
                var position = rules.initialPosition
                val isWhite = game.players["white"]!!.user.id == user
                val color = if (isWhite) "white" else "black"

                try {
                    dbGames.save(Game(
                            _id = game.id,
                            createdAt = Instant.ofEpochMilli(game.createdAt)
                    ))
                } catch (ex: Exception) {
                    println("ex = ${ex}")
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

                        //val isMyBlunder = ((isWhite && i % 2 == 1) ||
                        //        (!isWhite && i % 2 == 0))

                        //if (blunders.contains(i - 1) && isMyBlunder) {
                        if (blunders.contains(i - 1)) {
                            val fen = fenMarshaller.convertPositionToString(position)

                            val moveDisplay = "${i / 2 + 1}. ${if (i % 2 == 1) "..." else ""} $move"

                            try {
                                dbPuzzles.save(Puzzle(
                                        _id = "${game.id}_${i}",
                                        userIdWhite = game.players["white"]!!.user.id,
                                        userIdBlack = game.players["black"]!!.user.id,
                                        gameId = game.id,
                                        fen = fen,
                                        orientation = position.nextPlayerTurn.fullName,
                                        url = "https://lichess.org/${game.id}/${position.nextPlayerTurn.fullName}#${i + 1}",
                                        move_number = i + 1,
                                        move_display = moveDisplay,
                                        move_source = path.source.pgnCoordinates,
                                        move_destination = path.destination.pgnCoordinates,
                                        votes = 0,
                                        added = Instant.now()
                                ))
                            } catch (ex: Exception) {
                                println("ex = ${ex}")
                            }
                        }
                    }
                } catch (ex: Exception) {
                    println(ex)
                }
            }
}

fun getStandardData(ctx: Context): MutableMap<String, Any> {
    val data = mutableMapOf<String, Any>()
    val session = ctx.request().getSession(false)
    session?.getAttribute("userId")?.let { userId ->
        data["userId"] = userId
        data["loggedIn"] = true
    }
    return data
}

object Main {

    @JvmStatic
    fun main(args: Array<String>) {
        //val lichessId = System.getenv("LICHESS_ID") ?: error("Environment variable LICHESS_ID not set!")
        //val lichessKey = System.getenv("LICHESS_KEY") ?: error("Environment variable LICHESS_KEY not set!")
        val lichessId = "asdf"
        val lichessKey = "asdf"

        val builder = GsonBuilder()
        builder.registerTypeAdapter(LichessEval::class.java, EvalDeserializer())
        val gson = builder.create()

        val app = Javalin.create()
        app.port(7000)
        app.enableStaticFiles("static", Location.EXTERNAL)
        app.enableStandardRequestLogging()
        //app.requestLogLevel(LogLevel.EXTENSIVE)
        app.get("/") { ctx ->
            val data = getStandardData(ctx)
            data += getLatestPuzzles()
            ctx.renderMustache("templates/index.mustache", data)
        }

        //app.get("/search") { ctx ->
        //    val q = ctx.queryParam("q") ?: ""
        //    val nb = ctx.queryParam("nb") ?: "25"
        //    val type = ctx.queryParam("type") ?: ""
        //    val time = ctx.queryParams("time") ?: arrayOf("blitz", "rapid", "classical", "correspondence")
        //    if (q.isEmpty()) {
        //        ctx.redirect("/")
        //    } else {
        //        try {
        //            val n = Math.min(nb.toIntOrNull() ?: 25, 100)

        //            //val request = OAuthRequest(Verb.GET, "https://lichess.org/games/export/${URLEncoder.encode(userId, "UTF-8")}?max=20&analysed=true&moves=true&evals=true&perfType=blitz,rapid,classical,correspondence")
        //            val url = "https://lichess.org/games/export/${URLEncoder.encode(q.trim(), "UTF-8")}?max=$n&analysed=true&moves=true&evals=true&perfType=${time.joinToString(",")}"

        //            //request.headers["accept"] = "application/x-ndjson"
        //            val get = url.httpGet()
        //            get.headers["accept"] = "application/x-ndjson"
        //            val (_, _, result) = get.responseString()
        //            //val games = gson.fromJson(result.get(), LichessGames::class.java)
        //            val games = result.get().trim().split("\n").map { gson.fromJson(it, LichessGame::class.java) }
        //            generateBoards(games, q)
        //            val data = getLatestPuzzles(q, limit = 100, shuffle = true)
        //            if (type == "txt") {
        //                ctx.renderMustache("templates/search-txt.mustache", data)
        //            } else {
        //                ctx.renderMustache("templates/search.mustache", data)
        //            }
        //        } catch (ex: Exception) {
        //            ctx.status(500)
        //            ctx.result("There was an error. Make sure you typed your username correctly!\nError: " + ex.toString())
        //        }
        //    }
        //}

        val state = "secret" + Random().nextInt(999_999)
        val service = ServiceBuilder(lichessId)
                .apiSecret(lichessKey)
                .state(state)
                .scope("game:read")
                .callback("http://localhost:7000/callback")
                .build(LichessApi)

        app.get("/logout") { ctx ->
            ctx.request().session.invalidate()
            ctx.redirect("/")
        }

        app.get("/login") { ctx ->
            val requestToken = service.authorizationUrl
            println("requestToken = ${requestToken}")
            ctx.redirect(requestToken)
        }

        app.get("/callback") { ctx ->
            val code = ctx.queryParam("code")!!
            val accessToken = service.getAccessToken(code)
            println("accessToken = ${accessToken}")
            println("accessToken.rawResponse = ${accessToken.rawResponse}")
            println("accessToken.json = ${accessToken.json}")

            val session = ctx.request().session
            session.setAttribute("accessToken", accessToken.accessToken)
            session.setAttribute("refreshToken", accessToken.refreshToken)
            session.setAttribute("expiration", LocalTime.now().plusSeconds(accessToken.expiresIn.toLong()))

            //val expiration = session.getAttribute("expiration") as LocalTime
            //if (LocalTime.now().minusMinutes(5) > expiration) {
            //    val newToken = service.refreshAccessToken(accessToken.refreshToken)
            //    println("newToken = ${newToken}")
            //}

            val request = OAuthRequest(Verb.GET, "https://lichess.org/api/account")
            service.signRequest(accessToken, request)
            val response = service.execute(request)
            val user = gson.fromJson(response.body, LichessUser::class.java)
            println("response.body = ${response.body}")
            println("user = ${user}")
            session.setAttribute("userId", user.id)

//            val stmt = db.prepareStatement("insert into sessions (access_token, refresh_token, expiration) values (?, ?, ?)")
//            stmt.setString(1, accessToken.accessToken)
//            stmt.setString(2, accessToken.refreshToken)
//            stmt.setInt(3, accessToken.expiresIn)
//            stmt.executeUpdate()

            ctx.redirect("/")
        }

        app.get("/search") { ctx ->
            val userId = ctx.queryParam("q")?.toLowerCase() ?: ""
            if (userId.isEmpty()) {
                ctx.redirect("/")
                throw HaltException("redirect")
            }

            //val session = ctx.request().getSession(false)

            //if (session == null) {
            //    ctx.redirect("/")
            //}

            val data = getStandardData(ctx)

            //val userId = ctx.sessionAttribute("userId") as String
            //val userId = q

            val user = dbUsers.findOneById(userId) ?: User(_id = userId)

            if (!user.fetching && user.lastFetched.plusSeconds(300) < Instant.now()) {
                logger.info { "search: user=$user start fetching" }
                dbUsers.updateOne(user.copy(fetching = true), upsert())
                thread(start = true, name = "fetch ${user._id}") {
                    try {
                        //val accessToken = OAuth2AccessToken(session.getAttribute("accessToken") as String)
                        //val request = OAuthRequest(Verb.GET, "https://lichess.org/games/export/${URLEncoder.encode(userId, "UTF-8")}?max=10&analysed=true&moves=true&evals=true&perfType=blitz,rapid,classical,correspondence")
                        //request.headers["accept"] = "application/x-ndjson"
                        //service.signRequest(accessToken, request)
                        //logger.info { "fetchnew: starting fetch for user=$user" }
                        //val response = service.execute(request)
                        //logger.info { "fetchnew: finished fetch for user=$user" }
                        //val games = response.body.trim().split("\n").map { gson.fromJson(it, LichessGame::class.java) }
                        //generateBoards(games, user._id)

                        val url = "https://lichess.org/games/export/${URLEncoder.encode(userId, "UTF-8")}?max=20&analysed=true&moves=true&evals=true&perfType=blitz,rapid,classical,correspondence"
                        val get = url.httpGet()
                                .header("accept" to "application/x-ndjson")
                                .timeout(60_000)
                                .timeoutRead(60_000)
                        logger.info { "fetchnew: starting fetch for user=$user url=$url" }
                        val (_, _, result) = get.responseString()
                        logger.info { "fetchnew: finished fetch for user=$user" }
                        logger.debug { "result=$result" }
                        val games = result.get().trim().split("\n").map { gson.fromJson(it, LichessGame::class.java) }
                        generateBoards(games, user._id)
                    } finally {
                        dbUsers.updateOne(user.copy(fetching = false, lastFetched = Instant.now()))
                    }
                }
                ctx.renderMustache("templates/fetching.mustache", data)
            } else if (user.fetching) {
                logger.info { "search: user=$user still fetching" }
                ctx.renderMustache("templates/fetching.mustache", data)
            } else {
                logger.info { "search: user=$user NOT fetching" }
                data += getLatestPuzzles(userId, limit = 100, shuffle = true)
//                ctx.redirect("/fetchnew?q=${URLEncoder.encode(userId, "UTF-8")}")
                ctx.renderMustache("templates/search.mustache", data)
            }
        }

        app.get("/fetchnew") { ctx ->
            val session = ctx.request().getSession(false)
            if (session == null) {
                ctx.redirect("/")
                throw HaltException("redirect")
            }

            val data = getStandardData(ctx)
            val userId = session.getAttribute("userId") as String
//            val userId = ctx.queryParam("q") ?: ""
//            if (userId.isEmpty()) {
//                ctx.redirect("/")
//                throw HaltException("redirect")
//            }
            data += getLatestPuzzles(userId, limit = 100, shuffle = true)

            ctx.renderMustache("templates/search.mustache", data)
        }

        app.get("/shuffle") { ctx ->
            val session = ctx.request().getSession(false)
            if (session == null) {
                ctx.redirect("/")
                throw HaltException("redirect")
            }

            val data = getStandardData(ctx)
            val userId = session.getAttribute("userId") as String
            data += getLatestPuzzles(userId, limit = 100, shuffle = true)

            ctx.renderMustache("templates/search.mustache", data)
        }

        app.get("/vote") { ctx ->
            //val session = ctx.request().getSession(false) ?: throw HaltException(401, "Must be logged in!")
            //println("session = ${session}")
            //val userId = session.getAttribute("userId") as String
            val userId = "nathanj439"
            val id = ctx.queryParam("id") ?: throw HaltException("bad request: no id")
            val dir = ctx.queryParam("dir") ?: throw HaltException("bad request: no dir")
            val up = dir == "up"

//            val v = Vote(
//                    _id = id,
//                    userId = userId,
//                    up = dir == "up"
//            )
            logger.info { "VOTE: userId=$userId votes for id=$id dir=$dir" }
            //voteCol.updateOne(v, UpdateOptions().upsert(true))

            val incBy = if (up) 1 else -1

            val v = dbVotes.findOne(Vote::userId eq userId, Vote::boardId eq id)
            val multiple = if (v != null && v.up != up) {
                dbVotes.updateOne(v.copy(up = up))
                2
            } else {
                dbVotes.insertOne(Vote(
                        boardId = id,
                        userId = userId,
                        up = up
                ))
                1
            }

            // Adjust puzzle.votes
            dbPuzzles.updateOneById(id, inc(Puzzle::votes, incBy * multiple))
        }

        app.start()
    }
}
