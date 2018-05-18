package com.github.nathanj.lichess

import com.github.scribejava.core.builder.api.DefaultApi20
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import java.lang.reflect.Type
import kotlin.math.sign

object LichessApi : DefaultApi20() {
    override fun getAuthorizationBaseUrl(): String {
        return "https://oauth.lichess.org/oauth/authorize"
    }

    override fun getAccessTokenEndpoint(): String {
        return "https://oauth.lichess.org/oauth"
    }
}

data class LichessEval(
        val eval: Int
)

class EvalDeserializer : JsonDeserializer<LichessEval> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): LichessEval {
        json.asJsonObject.get("mate")?.let {
            return LichessEval(it.asInt.sign * 99999)
        }
        return LichessEval(json.asJsonObject.get("eval")!!.asInt)
    }
}

data class LichessUser(
        val id: String
)

data class LichessPlayer(
        val user: LichessUser
)

data class LichessGame(
        val id: String,
        val speed: String,
        val moves: String,
        val players: Map<String, LichessPlayer>,
        val createdAt: Long,
        val analysis: List<LichessEval>?
)
