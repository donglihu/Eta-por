package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.agent.model.AgentHttpClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 技能商店数据源：腾讯 SkillHub（https://api.skillhub.cn）。
 * 对齐 miniAI 的 `SkillHubApi`：分类 / 列表（搜索/分类/排序）/ 文件列表 / 下载。
 *
 * 商店请求走独立的短超时客户端：这里的都是只读小请求，用模型链路那套
 * 15 秒连接 + 60 秒读取 + 失败重试的配置，弱网时用户会盯着转圈三十秒。
 */
internal object SkillHubApi {
    private const val BASE_URL = "https://api.skillhub.cn"

    /** 列表/分类这类小请求：连不上就该立刻报错，而不是让用户等。 */
    private const val METADATA_CONNECT_TIMEOUT_MS = 5_000L
    private const val METADATA_READ_TIMEOUT_MS = 10_000L

    /** 下载技能包允许慢一些，但也要有上限，避免一直转圈。 */
    private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 10_000L
    private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000L

    private val metadataClient: OkHttpClient by lazy {
        AgentHttpClient.client.newBuilder()
            .connectTimeout(METADATA_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(METADATA_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(METADATA_READ_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val downloadClient: OkHttpClient by lazy {
        AgentHttpClient.client.newBuilder()
            .connectTimeout(DOWNLOAD_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(DOWNLOAD_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** 分类。GET /api/v1/categories → { count, items:[{key,name,nameEn,sortOrder,active}] } */
    suspend fun listCategories(): List<SkillHubCategory> = withContext(Dispatchers.IO) {
        val text = get("$BASE_URL/api/v1/categories")
        val items = JSONObject(text).optJSONArray("items") ?: return@withContext emptyList()
        (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            if (!item.optBoolean("active", true)) return@mapNotNull null
            SkillHubCategory(
                key = item.optString("key"),
                name = item.optString("name").ifBlank { item.optString("nameEn") },
                nameEn = item.optString("nameEn").takeIf { it.isNotBlank() },
                sortOrder = item.optInt("sortOrder"),
            )
        }.sortedBy { it.sortOrder }
    }

    /**
     * 技能列表。GET /api/skills?page=&pageSize=&keyword=&category=&sortBy=&order=
     * 响应 → { code, data:{ skills:[...] } }
     */
    suspend fun listSkills(
        page: Int = 1,
        pageSize: Int = 30,
        keyword: String? = null,
        category: String? = null,
        sortBy: String? = null,
        order: String? = null,
    ): List<SkillHubSummary> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/api/skills".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("pageSize", pageSize.toString())
            if (!keyword.isNullOrBlank()) addQueryParameter("keyword", keyword)
            if (!category.isNullOrBlank()) addQueryParameter("category", category)
            if (!sortBy.isNullOrBlank()) addQueryParameter("sortBy", sortBy)
            if (!order.isNullOrBlank()) addQueryParameter("order", order)
        }.build()
        val text = get(url.toString())
        val data = JSONObject(text).optJSONObject("data") ?: return@withContext emptyList()
        val skills = data.optJSONArray("skills") ?: return@withContext emptyList()
        (0 until skills.length()).mapNotNull { index ->
            val item = skills.optJSONObject(index) ?: return@mapNotNull null
            SkillHubSummary.fromJson(item)
        }
    }

    /** 文件列表。GET /api/v1/skills/{slug}/files → { count, files:[{path,sha256,size}], version } */
    suspend fun listFiles(slug: String): List<SkillHubFile> = withContext(Dispatchers.IO) {
        val text = get("$BASE_URL/api/v1/skills/$slug/files")
        val files = JSONObject(text).optJSONArray("files") ?: return@withContext emptyList()
        (0 until files.length()).mapNotNull { index ->
            val item = files.optJSONObject(index) ?: return@mapNotNull null
            SkillHubFile(
                path = item.optString("path"),
                sha256 = item.optString("sha256"),
                size = item.optLong("size"),
            )
        }
    }

    /** 下载技能 ZIP。GET /api/v1/download?slug={slug} → 302 到 COS，最终返回 zip 字节。 */
    suspend fun download(slug: String): ByteArray = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/api/v1/download".toHttpUrl().newBuilder()
            .addQueryParameter("slug", slug)
            .build()
        val request = Request.Builder().url(url).get().build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("下载失败：HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("下载失败：空响应")
            val output = ByteArrayOutputStream()
            body.byteStream().use { input -> input.copyTo(output) }
            output.toByteArray()
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        metadataClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }
}

data class SkillHubCategory(
    val key: String,
    val name: String,
    val nameEn: String? = null,
    val sortOrder: Int = 0,
)

data class SkillHubSummary(
    val slug: String,
    val name: String,
    val description: String = "",
    val descriptionZh: String = "",
    val category: String = "",
    val downloads: Int = 0,
    val iconUrl: String? = null,
    val version: String = "",
) {
    val displayDescription: String get() = descriptionZh.ifBlank { description }

    companion object {
        fun fromJson(item: JSONObject): SkillHubSummary = SkillHubSummary(
            slug = item.optString("slug"),
            name = item.optString("name"),
            description = item.optString("description"),
            descriptionZh = item.optString("description_zh"),
            category = item.optString("category"),
            downloads = item.optInt("downloads"),
            iconUrl = item.optString("iconUrl").takeIf { it.isNotBlank() && it != "null" },
            version = item.optString("version"),
        )
    }
}

data class SkillHubFile(
    val path: String,
    val sha256: String = "",
    val size: Long = 0L,
)
