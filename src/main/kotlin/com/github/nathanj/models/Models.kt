package com.github.nathanj.models

import org.jdbi.v3.sqlobject.statement.SqlQuery
import org.jdbi.v3.sqlobject.statement.SqlUpdate
import java.time.Instant

data class User(
        val id: String,
        val fetching: Boolean = false,
        val lastFetched: Instant = Instant.EPOCH
)

interface UserDao {
    @SqlUpdate("""
        update users
        set fetching = :user.fetching, last_fetched = :user.lastFetched
        where id = :user.id
    """)
    fun update(user: User)

    @SqlUpdate("""
        insert into users (id, fetching, last_fetched)
        values (:user.id, :user.fetching, :user.lastFetched)
    """)
    fun insert(user: User)

    @SqlQuery("""
        select * from users where id = :id
    """)
    fun findOne(id: String): User?
}

data class Puzzle(
        val id: String,
        val userIdWhite: String,
        val userIdBlack: String,
        val gameId: String,
        val fen: String,
        val orientation: String,
        val url: String,
        val moveNumber: Int,
        val moveDisplay: String,
        val moveSource: String,
        val moveDestination: String,
        val created: Instant
) {
    fun toMap(): Map<String, String> {
        return mapOf("id" to id,
                "gameId" to gameId,
                "fen" to fen,
                "fenLink" to fen.replace(' ', '_'),
                "orientation" to orientation,
                "url" to url,
                "moveNumber" to moveNumber.toString(),
                "moveDisplay" to moveDisplay,
                "moveSource" to moveSource,
                "moveDestination" to moveDestination)
    }
}

interface PuzzleDao {
    @SqlQuery("""
        select * from puzzles
        where (user_id_black = :user_id and orientation = 'black')
           or (user_id_white = :user_id and orientation = 'white')
        order by created
        limit :limit
    """)
    fun listByUser(user_id: String, limit: Int = 10): List<Puzzle>

    @SqlQuery("""
        select * from puzzles
        order by created
        limit :limit
    """)
    fun list(limit: Int = 10): List<Puzzle>

    @SqlUpdate("""
        insert into puzzles (id,
                             user_id_white,
                             user_id_black,
                             game_id,
                             fen,
                             orientation,
                             url,
                             move_number,
                             move_display,
                             move_source,
                             move_destination,
                             created)
        values (:puzzle.id,
                :puzzle.userIdWhite,
                :puzzle.userIdBlack,
                :puzzle.gameId,
                :puzzle.fen,
                :puzzle.orientation,
                :puzzle.url,
                :puzzle.moveNumber,
                :puzzle.moveDisplay,
                :puzzle.moveSource,
                :puzzle.moveDestination,
                :puzzle.created)
    """)
    fun insert(puzzle: Puzzle)
}

data class Game(
        val id: String,
        val created: Instant
)

interface GameDao {
    @SqlUpdate("""
        insert into games (id, created)
        values (:game.id, :game.created)
    """)
    fun insert(game: Game)

    @SqlQuery("""
        select count(*)
        from games
        where id = :id
    """)
    fun count(id: String): Int
}
