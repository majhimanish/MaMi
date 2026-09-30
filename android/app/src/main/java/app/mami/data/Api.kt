package app.mami.data

import app.mami.core.IdentityBundle
import app.mami.core.SignedOneTimeKey
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** An error answer from the server. [code] is the machine-readable reason, like "wrong_code". */
class ApiException(val status: Int, val code: String) : IOException("HTTP $status: $code")

val MamiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    classDiscriminator = "type"
}

@Serializable
data class IdentityDto(
    val curve25519: String,
    val ed25519: String,
    @SerialName("curve25519_signature") val curve25519Signature: String,
) {
    fun toCore() = IdentityBundle(curve25519, ed25519, curve25519Signature)
}

fun IdentityBundle.toDto() = IdentityDto(curve25519, ed25519, curve25519Signature)

@Serializable
data class OneTimeKeyDto(@SerialName("key_id") val keyId: String, val key: String, val signature: String) {
    fun toCore() = SignedOneTimeKey(keyId, key, signature)
}

@Serializable
data class AuthVerifiedDto(
    val token: String,
    @SerialName("user_id") val userId: String,
    @SerialName("new_user") val newUser: Boolean,
)

@Serializable
data class PresenceDto(val online: Boolean, @SerialName("last_seen_ms") val lastSeenMs: Long? = null)

@Serializable
data class PartnerDto(
    @SerialName("user_id") val userId: String,
    val email: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("paired_at_ms") val pairedAtMs: Long,
    val identity: IdentityDto? = null,
    val presence: PresenceDto = PresenceDto(false),
)

@Serializable
data class InviteDto(
    val code: String,
    @SerialName("partner_email") val partnerEmail: String? = null,
    @SerialName("expires_at_ms") val expiresAtMs: Long,
)

@Serializable
data class MeDto(
    @SerialName("user_id") val userId: String,
    val email: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("has_identity") val hasIdentity: Boolean,
    @SerialName("one_time_key_count") val oneTimeKeyCount: Long,
    val partner: PartnerDto? = null,
    val invite: InviteDto? = null,
)

@Serializable
data class ClaimedKeyDto(val identity: IdentityDto, @SerialName("one_time_key") val oneTimeKey: OneTimeKeyDto)

@Serializable
data class EnvelopeDto(
    val seq: Long? = null,
    val id: String,
    /** "message", "status", "ephemeral" or "delivered". */
    val kind: String,
    @SerialName("message_type") val messageType: Int? = null,
    val body: String? = null,
    @SerialName("at_ms") val atMs: Long,
)

@Serializable
data class SendRequestDto(
    val id: String,
    val kind: String,
    @SerialName("message_type") val messageType: Int,
    val body: String,
    val push: Boolean,
)

@Serializable
data class AcceptedDto(val id: String, @SerialName("at_ms") val atMs: Long)

@Serializable
private data class KeyCountDto(@SerialName("one_time_key_count") val oneTimeKeyCount: Long)

/** The MaMi REST API. Every call throws [IOException] (including [ApiException]) on failure. */
class Api(private val settings: Settings, private val http: OkHttpClient) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun authStart(email: String) {
        call("POST", "/v1/auth/start", buildJsonObject { put("email", email) }, auth = false)
    }

    suspend fun authVerify(email: String, code: String): AuthVerifiedDto =
        call(
            "POST",
            "/v1/auth/verify",
            buildJsonObject {
                put("email", email)
                put("code", code)
            },
            AuthVerifiedDto.serializer(),
            auth = false,
        )

    suspend fun signOut() {
        call("POST", "/v1/auth/sign-out")
    }

    suspend fun me(): MeDto = call("GET", "/v1/me", null, MeDto.serializer())

    suspend fun setDisplayName(name: String) {
        call("PATCH", "/v1/me", buildJsonObject { put("display_name", name) })
    }

    suspend fun deleteAccount() {
        call("DELETE", "/v1/me")
    }

    suspend fun uploadKeys(identity: IdentityBundle?, oneTimeKeys: List<SignedOneTimeKey>): Long {
        val body = buildJsonObject {
            if (identity != null) put("identity", MamiJson.encodeToJsonElement(IdentityDto.serializer(), identity.toDto()))
            putJsonArray("one_time_keys") {
                oneTimeKeys.forEach {
                    add(MamiJson.encodeToJsonElement(OneTimeKeyDto.serializer(), OneTimeKeyDto(it.keyId, it.key, it.signature)))
                }
            }
        }
        return call("PUT", "/v1/keys", body, KeyCountDto.serializer()).oneTimeKeyCount
    }

    suspend fun setPushToken(token: String?) {
        call("PUT", "/v1/push-token", buildJsonObject { put("token", token) })
    }

    suspend fun createInvite(partnerEmail: String?): InviteDto =
        call("POST", "/v1/invites", buildJsonObject { put("partner_email", partnerEmail) }, InviteDto.serializer())

    suspend fun cancelInvite() {
        call("DELETE", "/v1/invites")
    }

    suspend fun acceptInvite(code: String): PartnerDto =
        call("POST", "/v1/invites/accept", buildJsonObject { put("code", code) }, PartnerDto.serializer())

    suspend fun unpair() {
        call("DELETE", "/v1/partner")
    }

    suspend fun claimPartnerKey(): ClaimedKeyDto = call("POST", "/v1/partner/claim-key", JsonObject(emptyMap()), ClaimedKeyDto.serializer())

    suspend fun envelopes(afterSeq: Long): List<EnvelopeDto> =
        call("GET", "/v1/envelopes?after_seq=$afterSeq", null, ListSerializer(EnvelopeDto.serializer()))

    suspend fun send(request: SendRequestDto): AcceptedDto =
        call("POST", "/v1/envelopes", MamiJson.encodeToJsonElement(SendRequestDto.serializer(), request).jsonObject, AcceptedDto.serializer())

    suspend fun ack(seqs: List<Long>) {
        if (seqs.isEmpty()) return
        call("POST", "/v1/envelopes/ack", buildJsonObject { putJsonArray("seqs") { seqs.forEach { add(it) } } })
    }

    private suspend fun call(method: String, path: String, body: JsonObject? = null, auth: Boolean = true) {
        execute(method, path, body, auth)
    }

    private suspend fun <T> call(
        method: String,
        path: String,
        body: JsonObject?,
        serializer: KSerializer<T>,
        auth: Boolean = true,
    ): T = MamiJson.decodeFromString(serializer, execute(method, path, body, auth))

    private suspend fun execute(method: String, path: String, body: JsonObject?, auth: Boolean): String =
        withContext(Dispatchers.IO) {
            val builder = try {
                Request.Builder().url(settings.serverUrl + path)
            } catch (e: IllegalArgumentException) {
                throw ApiException(0, "bad_server_address")
            }
            if (auth) {
                val token = settings.token ?: throw ApiException(401, "unauthorized")
                builder.header("Authorization", "Bearer $token")
            }
            val requestBody = when {
                body != null -> body.toString().toRequestBody(jsonType)
                method == "GET" || method == "DELETE" -> null
                else -> "{}".toRequestBody(jsonType)
            }
            builder.method(method, requestBody)
            http.newCall(builder.build()).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    val code = runCatching {
                        MamiJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
                    }.getOrNull()
                    throw ApiException(response.code, code ?: "http_${response.code}")
                }
                text
            }
        }
}
