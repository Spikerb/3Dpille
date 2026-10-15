package com.stockpile.volume.chain

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.stockpile.volume.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class XamanException(message: String, val authExpired: Boolean = false) : IOException(message)

/**
 * Xaman (formerly Xumm) integration without a backend:
 *  - sign-in with OAuth2 Authorization Code + PKCE, which needs only the public API key
 *    (the API secret must never be shipped inside an app);
 *  - the resulting 24 h JWT creates sign requests ("payloads") via the /api/v1/jwt endpoints.
 *
 * In the Xaman Developer Console (https://apps.xumm.dev) the app's redirect URIs / return URLs must
 * include [REDIRECT_URI] and the signed page under [PAGES_BASE].
 */
object Xaman {
    // Xaman's developer console only accepts https:// return addresses, so Xaman returns to two small
    // pages on GitHub Pages (repo folder /xaman) which immediately hand the result to the app via
    // stockpilevolume://xaman/auth and stockpilevolume://xaman/signed (see AndroidManifest).
    const val PAGES_BASE = "https://spikerb.github.io/3Dpille/xaman"
    const val REDIRECT_URI = "$PAGES_BASE/auth.html"
    const val RETURN_URL = "$PAGES_BASE/signed.html?payload={id}"
    private const val AUTH_URL = "https://oauth2.xumm.app/auth"
    private const val TOKEN_URL = "https://oauth2.xumm.app/token"
    private const val USERINFO_URL = "https://oauth2.xumm.app/userinfo"
    private const val API = "https://xumm.app/api/v1/jwt"

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val rnd = SecureRandom()

    private fun b64url(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun randomToken(n: Int) = b64url(ByteArray(n).also { rnd.nextBytes(it) })

    // ------------------------------------------------------------------ sign-in (PKCE)

    /** Opens the Xaman sign-in page; it comes back to [REDIRECT_URI] (see MainActivity). */
    fun startSignIn(context: Context, settings: Settings) {
        val apiKey = settings.xamanApiKey
        require(apiKey.isNotBlank()) { "Chybí Xaman API klíč (Nastavení)." }
        val verifier = randomToken(48)
        val state = randomToken(16)
        settings.pkceVerifier = verifier
        settings.pkceState = state
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val uri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", apiKey)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "XummPkce")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("force_network", settings.network)
            .build()
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    }

    /** Handles stockpilevolume://xaman/auth?code=…&state=…; stores the JWT and the signed-in account. */
    suspend fun completeSignIn(uri: Uri, settings: Settings): String = withContext(Dispatchers.IO) {
        uri.getQueryParameter("error")?.let { throw XamanException("Přihlášení zrušeno: $it") }
        val code = uri.getQueryParameter("code") ?: throw XamanException("Xaman nevrátil autorizační kód.")
        if (uri.getQueryParameter("state") != settings.pkceState) throw XamanException("Neplatná odpověď přihlášení (state).")
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", settings.xamanApiKey)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", settings.pkceVerifier)
            .build()
        val tokenResp = call(Request.Builder().url(TOKEN_URL).post(form).build())
        val jwt = tokenResp["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw XamanException("Xaman nevrátil přístupový token.")
        settings.pkceVerifier = ""; settings.pkceState = ""

        val claims = decodeJwt(jwt)
        val exp = claims["exp"]?.jsonPrimitive?.longOrNull ?: (System.currentTimeMillis() / 1000 + 23 * 3600)
        val info = runCatching { call(Request.Builder().url(USERINFO_URL).header("Authorization", "Bearer $jwt").build()) }.getOrNull()
        val account = info?.get("account")?.jsonPrimitive?.contentOrNull
            ?: info?.get("sub")?.jsonPrimitive?.contentOrNull
            ?: claims["sub"]?.jsonPrimitive?.contentOrNull
            ?: ""
        settings.xamanJwt = jwt
        settings.xamanJwtExp = exp
        settings.xamanAccount = account
        account
    }

    private fun decodeJwt(jwt: String): JsonObject = runCatching {
        val part = jwt.split(".")[1]
        json.parseToJsonElement(String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))).jsonObject
    }.getOrDefault(JsonObject(emptyMap()))

    // ------------------------------------------------------------------ payloads

    data class Created(val uuid: String, val signUrl: String)

    data class Status(
        val resolved: Boolean, val signed: Boolean, val cancelled: Boolean, val expired: Boolean,
        val txid: String?, val account: String?, val dispatchedResult: String?,
    )

    suspend fun createPayload(settings: Settings, body: JsonObject): Created = withContext(Dispatchers.IO) {
        val r = call(
            Request.Builder().url("$API/payload").bearer(settings)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        )
        val uuid = r["uuid"]?.jsonPrimitive?.contentOrNull ?: throw XamanException("Xaman nevytvořil žádost o podpis.")
        val next = r["next"]?.jsonObject?.get("always")?.jsonPrimitive?.contentOrNull ?: "https://xumm.app/sign/$uuid"
        Created(uuid, next)
    }

    suspend fun payloadStatus(settings: Settings, uuid: String): Status = withContext(Dispatchers.IO) {
        val r = call(Request.Builder().url("$API/payload/$uuid").bearer(settings).build())
        val meta = r["meta"]?.jsonObject ?: throw XamanException("Neplatná odpověď Xamanu.")
        val resp = r["response"]?.jsonObject
        fun b(k: String) = meta[k]?.jsonPrimitive?.booleanOrNull == true
        fun s(k: String) = resp?.get(k)?.jsonPrimitive?.contentOrNull
        Status(b("resolved"), b("signed"), b("cancelled"), b("expired"), s("txid"), s("account"), s("dispatched_result"))
    }

    private fun Request.Builder.bearer(settings: Settings): Request.Builder {
        if (!settings.xamanSignedIn) throw XamanException("Přihlášení do Xamanu vypršelo.", authExpired = true)
        return header("Authorization", "Bearer ${settings.xamanJwt}")
    }

    private fun call(req: Request): JsonObject = http.newCall(req).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (resp.code == 401 || resp.code == 403) throw XamanException("Přihlášení do Xamanu vypršelo.", authExpired = true)
        if (!resp.isSuccessful || obj == null) {
            val err = obj?.get("error")
            val detail = runCatching { err?.jsonObject?.get("code")?.jsonPrimitive?.content }.getOrNull()
                ?: runCatching { err?.jsonPrimitive?.content }.getOrNull()
                ?: obj?.get("error_description")?.jsonPrimitive?.contentOrNull
            throw XamanException("Xaman: HTTP ${resp.code}${detail?.let { " ($it)" } ?: ""}")
        }
        obj
    }
}
