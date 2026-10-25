package com.moblized.courseshelf.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder

class ApiException(val code: Int, message: String) : IOException(message)

/**
 * Talks to an OfflineU server. The server keeps one "active course" for everyone,
 * so course-scoped calls (lesson, progress, media status, files) need [loadCourse] first.
 */
class OfflineUApi(private val client: OkHttpClient, private val json: Json) {

    @Volatile
    var baseUrl: String = ""

    fun url(pathAndQuery: String): String {
        if (baseUrl.isBlank()) throw ApiException(0, "No server address set")
        return baseUrl.trimEnd('/') + pathAndQuery
    }

    /** Turns a server-relative src such as "/files/Intro.mp4" into a full URL. */
    fun absolute(src: String): String =
        if (src.startsWith("http://") || src.startsWith("https://")) src else url(src)

    fun previewUrl(courseRelativeFile: String): String =
        url("/api/preview?path=${enc(courseRelativeFile)}")

    suspend fun health(base: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(normalizeServerUrl(base) + "/health").build()
        client.newCall(request).execute().use { response ->
            response.isSuccessful && response.body?.string().orEmpty().contains("healthy")
        }
    }

    suspend fun state(): StateResponse = get("/api/state", StateResponse.serializer())

    suspend fun browse(path: String? = null): BrowseResponse =
        get(if (path.isNullOrBlank()) "/browse" else "/browse?path=${enc(path)}", BrowseResponse.serializer())

    suspend fun loadCourse(coursePath: String) {
        post("/load_course", buildJsonObject { put("course_path", coursePath) })
    }

    /** Clears the active course so /api/state returns the remembered course list. */
    suspend fun resetCourse() {
        post("/api/reset_course", JsonObject(emptyMap()))
    }

    suspend fun lesson(relPath: String): LessonResponse =
        get("/api/lesson?path=${enc(relPath)}", LessonResponse.serializer())

    suspend fun mediaStatus(relPath: String): MediaStatus =
        get("/api/media/status?path=${enc(relPath)}", MediaStatus.serializer())

    /** Omitted values stay untouched on the server; seconds never move backwards there. */
    suspend fun progress(relPath: String, seconds: Int? = null, completed: Boolean? = null) {
        post("/api/progress", buildJsonObject {
            put("lesson_path", relPath)
            if (seconds != null) put("progress_seconds", seconds)
            if (completed != null) put("completed", completed)
        })
    }

    suspend fun text(src: String): String = withContext(Dispatchers.IO) {
        execute(Request.Builder().url(absolute(src)).build())
    }

    suspend fun download(src: String, target: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(absolute(src)).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "HTTP ${response.code}")
            target.parentFile?.mkdirs()
            val temp = File(target.path + ".part")
            response.body?.byteStream()?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: throw ApiException(response.code, "Empty response")
            if (!temp.renameTo(target)) throw IOException("Could not save ${target.name}")
        }
    }

    private suspend fun <T> get(path: String, deserializer: DeserializationStrategy<T>): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url(path)).header("Accept-Language", "en").build()
            json.decodeFromString(deserializer, execute(request))
        }

    private suspend fun post(path: String, body: JsonObject): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url(path))
            .header("Accept-Language", "en")
            .post(body.toString().toRequestBody(JSON))
            .build()
        execute(request)
    }

    private fun execute(request: Request): String =
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
                }.getOrNull()
                throw ApiException(response.code, message ?: "Server answered HTTP ${response.code}")
            }
            text
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

        /** "192.168.1.5:30500" -> "http://192.168.1.5:30500" */
        fun normalizeServerUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return trimmed
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
        }
    }
}
