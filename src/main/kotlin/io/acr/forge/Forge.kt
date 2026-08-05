package io.acr.forge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Read PRs from a hosting provider and publish a review back to one. */
interface Forge {
    suspend fun listPullRequests(repo: RepoRecord): List<PullRequest>
    suspend fun postComment(repo: RepoRecord, prId: Long, body: String): PostedComment

    /**
     * Un PR puntual. Existe para no pedir la lista entera cuando sólo hace falta uno: además de
     * ser una llamada en vez de N páginas, bajo rate limiting la lista falla y dejaba la pantalla
     * del PR sin datos (y con ella el visor de código y el de commits).
     */
    suspend fun getPullRequest(repo: RepoRecord, prId: Long): PullRequest?

    /** The PR's whole comment thread, so a review can see what was already said. */
    suspend fun listComments(repo: RepoRecord, prId: Long): List<PrComment>

    /** Responde a un comentario existente, colgando la respuesta de su hilo. */
    suspend fun postReply(repo: RepoRecord, prId: Long, parentCommentId: String, body: String): PostedComment

    /** Publishes a comment anchored to a file and line, the way a reviewer marks code. */
    suspend fun postInlineComment(
        repo: RepoRecord,
        prId: Long,
        body: String,
        path: String,
        line: Int?,
        headSha: String,
    ): PostedComment
}

class ForgeException(message: String) : Exception(message)

object Forges {
    fun of(provider: Provider): Forge = when (provider) {
        Provider.BITBUCKET -> BitbucketForge()
        Provider.GITHUB -> GitHubForge()
    }
}

internal val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(15))
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build()

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("io.acr.forge")

/**
 * @param idempotent si la petición se puede repetir sin efectos. Un GET sí; un POST que crea un
 *   comentario NO: si el servidor lo procesó y se perdió la respuesta, repetirlo publica el
 *   comentario dos veces. Para POST sólo se reintenta el 401, que es previo a la autorización y
 *   por lo tanto garantiza que nada se creó — es justamente el caso del token de Atlassian recién
 *   emitido, que responde 401 de forma intermitente mientras propaga.
 */
internal suspend fun send(
    request: HttpRequest,
    attempts: Int = 7,
    idempotent: Boolean = true,
    context: String = "",
): String = withContext(Dispatchers.IO) {
    var lastBody = ""
    var lastStatus = 0
    repeat(attempts) { attempt ->
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) return@withContext response.body()
        lastStatus = response.statusCode()
        lastBody = response.body()
        val retryable = when {
            // Bitbucket limita con 401 y GitHub con 403 (+ mensaje de rate limit); ninguno usa
            // 429 de forma consistente. Ambos son previos a cualquier efecto, así que reintentar
            // es seguro incluso para un POST.
            lastStatus == 401 -> true
            lastStatus == 403 && lastBody.contains("rate limit", ignoreCase = true) -> true
            !idempotent -> false
            lastStatus == 429 || lastStatus >= 500 -> true
            else -> false
        }
        if (!retryable) throw ForgeException(describe(context, lastStatus, lastBody))
        // No dormir después del último intento: eran ~20s de espera muerta antes de fallar.
        if (attempt == attempts - 1) return@repeat
        // Backoff exponencial con jitter. Bitbucket devuelve 401 —no 429— cuando limita por
        // frecuencia: deja pasar unas pocas seguidas y después corta un rato. Con esperas de
        // cientos de milisegundos los 6 reintentos caían todos dentro de la misma ventana de
        // bloqueo; hay que esperar en escala de segundos para salir de ella.
        val backoff = (1_000L shl attempt).coerceAtMost(20_000L)
        delay(backoff + (0..400).random())
    }
    throw ForgeException(describe(context, lastStatus, lastBody, attempts))
}

private fun describe(context: String, status: Int, body: String, attempts: Int? = null): String {
    val where = if (context.isBlank()) "" else "[$context] "
    val tries = attempts?.let { " tras $it intentos" } ?: ""
    val hint = if (status == 401) {
        "\n\nBitbucket responde 401 también cuando limita por frecuencia, no sólo cuando el " +
            "token es inválido. Si otras llamadas al mismo repo funcionan, es límite de tasa: " +
            "esperá unos segundos y reintentá."
    } else ""
    return "${where}HTTP $status$tries — ${body.take(300)}$hint"
}

/** Sigue la paginación hasta agotarla y devuelve todos los elementos. */
internal suspend fun pagedBitbucket(
    firstUrl: String,
    build: (String) -> HttpRequest,
    maxPages: Int = 20,
    context: String = "",
): List<JsonObject> {
    val out = mutableListOf<JsonObject>()
    var url: String? = firstUrl
    var page = 0
    while (url != null && page < maxPages) {
        val obj = lenientJson.parseToJsonElement(send(build(url), context = context)).jsonObject
        obj["values"]?.jsonArray?.forEach { out += it.jsonObject }
        url = obj["next"]?.jsonPrimitive?.contentOrNull
        page++
    }
    if (page >= maxPages && url != null) {
        log.warn("[{}] paginación cortada en {} páginas; hay más datos sin traer", context, maxPages)
    }
    return out
}

/** GitHub pagina con el header Link; se recorre hasta que no haya rel="next". */
internal suspend fun pagedGitHub(
    firstUrl: String,
    build: (String) -> HttpRequest,
    maxPages: Int = 20,
    context: String = "",
): List<JsonObject> = withContext(Dispatchers.IO) {
    val out = mutableListOf<JsonObject>()
    var url: String? = firstUrl
    var page = 0
    while (url != null && page < maxPages) {
        // sendWithHeaders y no httpClient.send directo: así hereda los reintentos y el backoff,
        // que es justo lo que hacía falta cuando GitHub limita por frecuencia.
        val (body, headers) = sendWithHeaders(build(url), context = context)
        lenientJson.parseToJsonElement(body).jsonArray.forEach { out += it.jsonObject }
        url = nextLink(headers)
        page++
    }
    if (page >= maxPages && url != null) {
        log.warn("[{}] paginación cortada en {} páginas; hay más datos sin traer", context, maxPages)
    }
    out
}

/** Igual que [send] pero devuelve también los headers, que GitHub necesita para paginar. */
internal suspend fun sendWithHeaders(
    request: HttpRequest,
    attempts: Int = 7,
    context: String = "",
): Pair<String, java.net.http.HttpHeaders> = withContext(Dispatchers.IO) {
    var lastBody = ""
    var lastStatus = 0
    repeat(attempts) { attempt ->
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) return@withContext response.body() to response.headers()
        lastStatus = response.statusCode()
        lastBody = response.body()
        val retryable = lastStatus == 401 || lastStatus == 429 || lastStatus >= 500 ||
            (lastStatus == 403 && lastBody.contains("rate limit", ignoreCase = true))
        if (!retryable) throw ForgeException(describe(context, lastStatus, lastBody))
        if (attempt == attempts - 1) return@repeat
        delay((1_000L shl attempt).coerceAtMost(20_000L) + (0..400).random())
    }
    throw ForgeException(describe(context, lastStatus, lastBody, attempts))
}

/**
 * Extrae el rel="next" del header Link. Se parte por ">," y no por "," a secas porque una URL
 * puede contener comas en sus parámetros y cortarla a la mitad daría una URL rota.
 */
internal fun nextLink(headers: java.net.http.HttpHeaders): String? =
    headers.firstValue("link").orElse(null)
        ?.split(">,")
        ?.firstOrNull { it.contains("rel=\"next\"") }
        ?.substringAfter('<')?.substringBefore('>')

internal fun JsonObject.str(vararg path: String): String? {
    var node: kotlinx.serialization.json.JsonElement = this
    for (key in path) {
        node = (node as? JsonObject)?.get(key) ?: return null
    }
    // La hoja puede no ser un escalar si el proveedor cambia el esquema; `jsonPrimitive` tiraría.
    return (node as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}

/** Entero tolerante: null, ausente o de otro tipo devuelven null en vez de romper el listado. */
internal fun JsonObject.int(key: String): Int? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toIntOrNull()

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull()

/** Bitbucket Cloud. Authenticates with an Atlassian API token sent as a Bearer credential. */
class BitbucketForge : Forge {

    private fun request(url: String, token: String?) = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("Accept", "application/json")
        .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }

    /** Identidad del repo, para que un error diga siempre a cuál pertenece. */
    private fun ctx(repo: RepoRecord) = "${repo.name} · ${repo.owner}/${repo.slug}"

    override suspend fun listPullRequests(repo: RepoRecord): List<PullRequest> {
        val url = "$API/repositories/${repo.owner}/${repo.slug}/pullrequests?state=OPEN&pagelen=50"
        // mapNotNull y no map: un PR con forma inesperada se salta, no rompe todo el listado.
        return pagedBitbucket(url, { request(it, repo.token).GET().build() }, context = ctx(repo))
            .mapNotNull { pr ->
            val id = pr.long("id") ?: return@mapNotNull null
            PullRequest(
                id = id,
                title = pr.str("title") ?: "(sin título)",
                author = pr.str("author", "display_name") ?: "?",
                sourceBranch = pr.str("source", "branch", "name") ?: "?",
                targetBranch = pr.str("destination", "branch", "name") ?: "?",
                headSha = pr.str("source", "commit", "hash") ?: "",
                commentCount = pr.int("comment_count") ?: 0,
                updatedOn = pr.str("updated_on")?.take(16)?.replace('T', ' ') ?: "",
                url = pr.str("links", "html", "href") ?: "",
            )
        }
    }

    override suspend fun getPullRequest(repo: RepoRecord, prId: Long): PullRequest? {
        val url = "$API/repositories/${repo.owner}/${repo.slug}/pullrequests/$prId"
        val pr = lenientJson.parseToJsonElement(
            send(request(url, repo.token).GET().build(), context = ctx(repo)),
        ).jsonObject
        val id = pr.long("id") ?: return null
        return PullRequest(
            id = id,
            title = pr.str("title") ?: "(sin título)",
            author = pr.str("author", "display_name") ?: "?",
            sourceBranch = pr.str("source", "branch", "name") ?: "?",
            targetBranch = pr.str("destination", "branch", "name") ?: "?",
            headSha = pr.str("source", "commit", "hash") ?: "",
            commentCount = pr.int("comment_count") ?: 0,
            updatedOn = pr.str("updated_on")?.take(16)?.replace('T', ' ') ?: "",
            url = pr.str("links", "html", "href") ?: "",
        )
    }

    override suspend fun postComment(repo: RepoRecord, prId: Long, body: String): PostedComment {
        val url = "$API/repositories/${repo.owner}/${repo.slug}/pullrequests/$prId/comments"
        val payload = JsonObject(
            mapOf("content" to JsonObject(mapOf("raw" to kotlinx.serialization.json.JsonPrimitive(body)))),
        ).toString()
        val response = send(
            request(url, repo.token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            idempotent = false,
            context = ctx(repo),
        )
        val json = lenientJson.parseToJsonElement(response).jsonObject
        val id = json["id"]?.jsonPrimitive?.contentOrNull ?: ""
        // Bitbucket returns a diff-tab anchor even for general comments, which is where they are
        // *not* rendered. Point at the Overview tab so the link actually lands on the comment.
        val prUrl = "https://bitbucket.org/${repo.owner}/${repo.slug}/pull-requests/$prId"
        return PostedComment(id, "$prUrl#comment-$id")
    }

    override suspend fun listComments(repo: RepoRecord, prId: Long): List<PrComment> {
        val url = "$API/repositories/${repo.owner}/${repo.slug}/pullrequests/$prId/comments?pagelen=100"
        return pagedBitbucket(url, { request(it, repo.token).GET().build() }, context = ctx(repo)).map { c ->
            val inline = c["inline"] as? JsonObject
            PrComment(
                commentId = c["id"]?.jsonPrimitive?.contentOrNull ?: "",
                author = c.str("user", "display_name") ?: "?",
                body = c.str("content", "raw").orEmpty(),
                inlinePath = inline?.get("path")?.jsonPrimitive?.contentOrNull,
                // `to` is the line on the new side; `from` on the old one. Either identifies the anchor.
                inlineLine = inline?.int("to") ?: inline?.int("from"),
                deleted = c["deleted"]?.jsonPrimitive?.contentOrNull == "true",
                createdOn = c.str("created_on").orEmpty(),
                parentId = c.str("parent", "id"),
            )
        }
    }

    override suspend fun postReply(
        repo: RepoRecord,
        prId: Long,
        parentCommentId: String,
        body: String,
    ): PostedComment {
        val url = "$API/repositories/${repo.owner}/${repo.slug}/pullrequests/$prId/comments"
        val payload = JsonObject(
            mapOf(
                "content" to JsonObject(mapOf("raw" to kotlinx.serialization.json.JsonPrimitive(body))),
                "parent" to JsonObject(
                    mapOf("id" to kotlinx.serialization.json.JsonPrimitive(parentCommentId.toLongOrNull() ?: 0L)),
                ),
            ),
        ).toString()
        val response = send(
            request(url, repo.token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            idempotent = false,
            context = ctx(repo),
        )
        val json = lenientJson.parseToJsonElement(response).jsonObject
        val id = json["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val prUrl = "https://bitbucket.org/${repo.owner}/${repo.slug}/pull-requests/$prId"
        return PostedComment(id, "$prUrl#comment-$id")
    }

    override suspend fun postInlineComment(
        repo: RepoRecord,
        prId: Long,
        body: String,
        path: String,
        line: Int?,
        headSha: String,
    ): PostedComment {
        // Bitbucket requiere `to` (o `from`) para anclar; un inline sin línea es rechazado o
        // pierde el ancla. Si no hay línea, se publica como comentario general citando el archivo.
        if (line == null) return postComment(repo, prId, "`$path`\n\n$body")

        val url = "$API/repositories/${repo.owner}/${repo.slug}/pullrequests/$prId/comments"
        val inline = buildMap {
            put("path", kotlinx.serialization.json.JsonPrimitive(path))
            // `to` es la línea del lado nuevo, que es donde se revisa.
            put("to", kotlinx.serialization.json.JsonPrimitive(line))
        }
        val payload = JsonObject(
            mapOf(
                "content" to JsonObject(mapOf("raw" to kotlinx.serialization.json.JsonPrimitive(body))),
                "inline" to JsonObject(inline),
            ),
        ).toString()
        val response = send(
            request(url, repo.token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            idempotent = false,
            context = ctx(repo),
        )
        val json = lenientJson.parseToJsonElement(response).jsonObject
        val id = json["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val prUrl = "https://bitbucket.org/${repo.owner}/${repo.slug}/pull-requests/$prId"
        return PostedComment(id, "$prUrl#comment-$id")
    }

    private companion object {
        const val API = "https://api.bitbucket.org/2.0"
    }
}

/** GitHub. Uses a fine-grained or classic PAT as a Bearer credential. */
class GitHubForge : Forge {

    private fun request(url: String, token: String?) = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }

    private fun ctx(repo: RepoRecord) = "${repo.name} · ${repo.owner}/${repo.slug}"

    override suspend fun listPullRequests(repo: RepoRecord): List<PullRequest> {
        val url = "$API/repos/${repo.owner}/${repo.slug}/pulls?state=open&per_page=50"
        return pagedGitHub(url, { request(it, repo.token).GET().build() }, context = ctx(repo))
            .mapNotNull { pr ->
            val number = pr.long("number") ?: return@mapNotNull null
            PullRequest(
                id = number,
                title = pr.str("title") ?: "(untitled)",
                author = pr.str("user", "login") ?: "?",
                sourceBranch = pr.str("head", "ref") ?: "?",
                targetBranch = pr.str("base", "ref") ?: "?",
                headSha = pr.str("head", "sha") ?: "",
                commentCount = pr.int("comments") ?: 0,
                updatedOn = pr.str("updated_at")?.take(16)?.replace('T', ' ') ?: "",
                url = pr.str("html_url") ?: "",
                isDraft = pr.str("draft") == "true",
            )
        }
    }

    override suspend fun getPullRequest(repo: RepoRecord, prId: Long): PullRequest? {
        val url = "$API/repos/${repo.owner}/${repo.slug}/pulls/$prId"
        val pr = lenientJson.parseToJsonElement(
            send(request(url, repo.token).GET().build(), context = ctx(repo)),
        ).jsonObject
        val number = pr.long("number") ?: return null
        return PullRequest(
            id = number,
            title = pr.str("title") ?: "(untitled)",
            author = pr.str("user", "login") ?: "?",
            sourceBranch = pr.str("head", "ref") ?: "?",
            targetBranch = pr.str("base", "ref") ?: "?",
            headSha = pr.str("head", "sha") ?: "",
            commentCount = pr.int("comments") ?: 0,
            updatedOn = pr.str("updated_at")?.take(16)?.replace('T', ' ') ?: "",
            url = pr.str("html_url") ?: "",
        )
    }

    override suspend fun postComment(repo: RepoRecord, prId: Long, body: String): PostedComment {
        // PR-level comments go through the issues API on GitHub.
        val url = "$API/repos/${repo.owner}/${repo.slug}/issues/$prId/comments"
        val payload = JsonObject(mapOf("body" to kotlinx.serialization.json.JsonPrimitive(body))).toString()
        val response = send(
            request(url, repo.token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            idempotent = false,
            context = ctx(repo),
        )
        val json = lenientJson.parseToJsonElement(response).jsonObject
        return PostedComment(
            id = json["id"]?.jsonPrimitive?.contentOrNull ?: "",
            url = json.str("html_url") ?: "",
        )
    }

    override suspend fun listComments(repo: RepoRecord, prId: Long): List<PrComment> {
        // GitHub splits a PR thread in two: general comments live on the issues API, inline ones
        // on the pulls API. Fetching only the first would silently drop every code comment.
        val general = pagedGitHub(
            "$API/repos/${repo.owner}/${repo.slug}/issues/$prId/comments?per_page=100",
            { request(it, repo.token).GET().build() },
            context = ctx(repo),
        )
        val inline = pagedGitHub(
            "$API/repos/${repo.owner}/${repo.slug}/pulls/$prId/comments?per_page=100",
            { request(it, repo.token).GET().build() },
            context = ctx(repo),
        )
        fun parse(list: List<JsonObject>, isInline: Boolean) =
            list.map { c ->
                PrComment(
                    // Los dos endpoints tienen secuencias de id independientes, así que un id
                    // pelado podría colisionar entre ambos y pisarse en la clave única local.
                    commentId = (if (isInline) "rc-" else "ic-") + (c.str("id") ?: ""),
                    author = c.str("user", "login") ?: "?",
                    body = c.str("body").orEmpty(),
                    inlinePath = if (isInline) c.str("path") else null,
                    inlineLine = if (isInline) c.int("line") else null,
                    deleted = false,
                    createdOn = c.str("created_at").orEmpty(),
                    // GitHub sólo encadena respuestas en los comentarios de código.
                    parentId = if (isInline) c.str("in_reply_to_id")?.let { "rc-$it" } else null,
                )
            }
        return (parse(general, false) + parse(inline, true)).sortedBy { it.createdOn }
    }

    override suspend fun postReply(
        repo: RepoRecord,
        prId: Long,
        parentCommentId: String,
        body: String,
    ): PostedComment {
        // Los ids de comentarios de código llevan el prefijo "rc-" para no chocar con los de
        // issue; acá hay que devolverlo al id crudo que espera la API.
        val raw = parentCommentId.removePrefix("rc-").removePrefix("ic-")
        val url = "$API/repos/${repo.owner}/${repo.slug}/pulls/$prId/comments/$raw/replies"
        val payload = JsonObject(
            mapOf("body" to kotlinx.serialization.json.JsonPrimitive(body)),
        ).toString()
        val response = send(
            request(url, repo.token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            idempotent = false,
            context = ctx(repo),
        )
        val json = lenientJson.parseToJsonElement(response).jsonObject
        return PostedComment(
            id = json["id"]?.jsonPrimitive?.contentOrNull ?: "",
            url = json.str("html_url") ?: "",
        )
    }

    override suspend fun postInlineComment(
        repo: RepoRecord,
        prId: Long,
        body: String,
        path: String,
        line: Int?,
        headSha: String,
    ): PostedComment {
        // Igual que en Bitbucket: sin línea no hay ancla posible, va como comentario general.
        if (line == null) return postComment(repo, prId, "`$path`\n\n$body")

        // GitHub exige el commit al que se ancla; sin él rechaza el comentario inline.
        val payload = JsonObject(
            buildMap {
                put("body", kotlinx.serialization.json.JsonPrimitive(body))
                put("commit_id", kotlinx.serialization.json.JsonPrimitive(headSha))
                put("path", kotlinx.serialization.json.JsonPrimitive(path))
                put("side", kotlinx.serialization.json.JsonPrimitive("RIGHT"))
                line?.let { put("line", kotlinx.serialization.json.JsonPrimitive(it)) }
            },
        ).toString()
        val response = send(
            request("$API/repos/${repo.owner}/${repo.slug}/pulls/$prId/comments", repo.token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
            idempotent = false,
            context = ctx(repo),
        )
        val json = lenientJson.parseToJsonElement(response).jsonObject
        return PostedComment(
            id = json["id"]?.jsonPrimitive?.contentOrNull ?: "",
            url = json.str("html_url") ?: "",
        )
    }

    private companion object {
        const val API = "https://api.github.com"
    }
}
