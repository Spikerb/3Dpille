package com.stockpile.volume.net

import com.stockpile.volume.data.RemoteJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : IOException(message)

/** Talks to the stockpile server (server/stockpile_api). */
class ApiClient(baseUrl: String, private val apiKey: String) {
    private val base = baseUrl.trimEnd('/') + "/api"

    private fun request(path: String) = Request.Builder().url(base + path).header("X-API-Key", apiKey)

    private fun execute(request: Request): String =
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = runCatching {
                    json.parseToJsonElement(body).jsonObject["detail"]?.jsonPrimitive?.content
                }.getOrNull()
                throw ApiException(resp.code, detail ?: "Server vrátil HTTP ${resp.code}")
            }
            body
        }

    private fun bytes(request: Request): ByteArray =
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, "Server vrátil HTTP ${resp.code}")
            resp.body?.bytes() ?: ByteArray(0)
        }

    /** Checks the server is reachable, the API key is right and the processing node is up. */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val health = json.parseToJsonElement(execute(request("/health").build())).jsonObject
        execute(request("/jobs").build()) // fails with 401 if the key is wrong
        val ok = health["ok"]?.jsonPrimitive?.content == "true"
        if (ok) "Spojeno. Zpracovací uzel běží."
        else "Spojeno, ale zpracovací uzel nereaguje: " +
            (health["nodeodm_error"]?.jsonPrimitive?.content ?: "neznámá chyba")
    }

    suspend fun createJob(name: String, densityTM3: Double?, clientId: String): RemoteJob = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("name", name)
            put("client_id", clientId)
            if (densityTM3 != null) put("density_t_m3", densityTM3)
        }.toString().toRequestBody(JSON)
        json.decodeFromString<RemoteJob>(execute(request("/jobs").post(body).build()))
    }

    suspend fun uploadImage(jobId: String, file: File) = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(JPEG))
            .build()
        execute(request("/jobs/$jobId/images").post(body).build())
        Unit
    }

    suspend fun startJob(jobId: String): RemoteJob = withContext(Dispatchers.IO) {
        json.decodeFromString<RemoteJob>(execute(request("/jobs/$jobId/start").post(ByteArray(0).toRequestBody()).build()))
    }

    suspend fun getJob(jobId: String): RemoteJob = withContext(Dispatchers.IO) {
        json.decodeFromString<RemoteJob>(execute(request("/jobs/$jobId").build()))
    }

    suspend fun deleteJob(jobId: String) = withContext(Dispatchers.IO) {
        execute(request("/jobs/$jobId").delete().build())
        Unit
    }

    suspend fun preview(jobId: String): ByteArray = withContext(Dispatchers.IO) {
        bytes(request("/jobs/$jobId/preview.png").build())
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
        private val JSON = "application/json".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()
        private val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()
    }
}
