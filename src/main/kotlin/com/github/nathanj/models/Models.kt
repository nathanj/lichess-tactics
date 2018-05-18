package com.github.nathanj.models

import java.time.Instant

data class User(
        val _id: String,
        val fetching: Boolean = false,
        val lastFetched: Instant = Instant.EPOCH
)

data class Vote(
        val boardId: String,
        val userId: String,
        val up: Boolean
)

data class Puzzle(
        val _id: String,
        val userIdWhite: String,
        val userIdBlack: String,
        val gameId: String,
        val fen: String,
        val orientation: String,
        val url: String,
        val move_number: Int,
        val move_display: String,
        val move_source: String,
        val move_destination: String,
        val votes: Int,
        val added: Instant
) {
    fun toMap(): Map<String, String> {
        return mapOf("id" to _id,
                "game_id" to gameId,
                "votes" to votes.toString(),
                "fen" to fen,
                "fen_link" to fen.replace(' ', '_'),
                "orientation" to orientation,
                "url" to url,
                "move_number" to move_number.toString(),
                "move_display" to move_display,
                "move_source" to move_source,
                "move_destination" to move_destination)
    }
}

data class Game(
        val _id: String,
        val createdAt: Instant
)

