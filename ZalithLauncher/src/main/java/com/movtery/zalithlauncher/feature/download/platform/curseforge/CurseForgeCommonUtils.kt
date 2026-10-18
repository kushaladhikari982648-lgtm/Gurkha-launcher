package com.movtery.zalithlauncher.feature.download.platform.curseforge

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.feature.download.Filters
import com.movtery.zalithlauncher.feature.download.InfoCache
import com.movtery.zalithlauncher.feature.download.enums.Category
import com.movtery.zalithlauncher.feature.download.enums.Classify
import com.movtery.zalithlauncher.feature.download.enums.Platform
import com.movtery.zalithlauncher.feature.download.item.InfoItem
import com.movtery.zalithlauncher.feature.download.item.ScreenshotItem
import com.movtery.zalithlauncher.feature.download.item.SearchResult
import com.movtery.zalithlauncher.feature.download.item.VersionItem
import com.movtery.zalithlauncher.feature.download.platform.PlatformNotSupportedException
import com.movtery.zalithlauncher.feature.download.utils.CategoryUtils
import com.movtery.zalithlauncher.feature.download.utils.PlatformUtils.Companion.safeRun
import com.movtery.zalithlauncher.feature.download.utils.VersionTypeUtils
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.utils.MCVersionRegex.Companion.RELEASE_REGEX
import com.movtery.zalithlauncher.utils.ZHTools
import com.movtery.zalithlauncher.utils.stringutils.StringUtilsKt
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler
import net.kdt.pojavlaunch.utils.GsonJsonUtils
import java.io.IOException
import java.net.URLEncoder
import java.util.TreeSet
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.function.Consumer
import kotlin.math.min

class CurseForgeCommonUtils {
    companion object {
        private const val ALGO_SHA_1 = 1
        private const val CURSEFORGE_MINECRAFT_GAME_ID = 432
        private const val CURSEFORGE_SEARCH_COUNT = 20
        /** The API returns at most 50 results per request (https://docs.curseforge.com/rest-api/#pagination-limits). */
        private const val CURSEFORGE_PAGINATION_SIZE = 50
        /** ...and only the first 10000 results of a list: index + pageSize must stay <= 10000. */
        private const val CURSEFORGE_MAX_RESULTS = 10000
        /** Parallel requests when loading the remaining pages of a long file list. */
        private const val CURSEFORGE_PAGE_THREADS = 4
        /** IDs per request of the batch endpoints (POST /v1/mods, POST /v1/mods/files). */
        private const val CURSEFORGE_BATCH_SIZE = 100
        internal const val CURSEFORGE_MODPACK_CLASS_ID = 4471
        internal const val CURSEFORGE_MOD_CLASS_ID = 6

        internal fun putDefaultParams(params: HashMap<String, Any>, filters: Filters, index: Int) {
            params["gameId"] = CURSEFORGE_MINECRAFT_GAME_ID
            params["searchFilter"] = filters.name
            params["sortField"] = filters.sort.curseforge
            params["sortOrder"] = "desc"
            // The API rejects requests beyond its result window, so ask for fewer results near its end
            params["pageSize"] = min(CURSEFORGE_SEARCH_COUNT, CURSEFORGE_MAX_RESULTS - index).coerceAtLeast(1)
            filters.category.curseforgeID?.let { if (filters.category != Category.ALL) params["categoryId"] = it }
            filters.mcVersion?.let { if (it.isNotEmpty()) params["gameVersion"] = it }
            params["index"] = index
        }

        /** The string value of a member, or null if it is missing or JSON null (Gson's asString throws then). */
        internal fun JsonObject.stringOrNull(name: String): String? =
            get(name)?.takeIf { it.isJsonPrimitive }?.asString

        private fun JsonObject.longOrNull(name: String): Long? =
            get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

        private fun JsonObject.booleanOrFalse(name: String): Boolean =
            get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false

        internal fun getAllCategories(hit: JsonObject): Set<Category> {
            val list: MutableSet<Category> = TreeSet()
            val categories = hit.get("categories")?.takeIf { it.isJsonArray }?.asJsonArray ?: return list
            for (category in categories) {
                val id = (category as? JsonObject)?.stringOrNull("id") ?: continue
                CategoryUtils.getCategoryByCurseForge(id)?.let { list.add(it) }
            }
            return list
        }

        internal fun getIconUrl(hit: JsonObject): String? {
            return runCatching {
                hit.getAsJsonObject("logo").get("thumbnailUrl").asString
            }.getOrNull()
        }

        internal fun getScreenshots(api: ApiHandler, projectId: String): List<ScreenshotItem> {
            searchModFromID(api, projectId)?.let { jsonObject ->
                val hit = GsonJsonUtils.getJsonObjectSafe(jsonObject, "data") ?: return emptyList()
                val screenshots = hit.get("screenshots")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
                val screenshotItems: MutableList<ScreenshotItem> = ArrayList()
                screenshots.forEach { element ->
                    val screenshotObject = element as? JsonObject ?: return@forEach
                    // Screenshots without a title or a description have JSON null there
                    val url = screenshotObject.stringOrNull("url") ?: return@forEach
                    screenshotItems.add(
                        ScreenshotItem(
                            url,
                            StringUtilsKt.getNonEmptyOrBlank(screenshotObject.stringOrNull("title") ?: ""),
                            StringUtilsKt.getNonEmptyOrBlank(screenshotObject.stringOrNull("description") ?: ""),
                        )
                    )
                }
                return screenshotItems
            }
            return emptyList()
        }

        internal fun getResults(api: ApiHandler, lastResult: SearchResult, filters: Filters, classId: Int, classify: Classify): SearchResult? {
            if (filters.category != Category.ALL && filters.category.curseforgeID == null) {
                throw PlatformNotSupportedException("The platform does not support the ${filters.category} category!")
            }

            val params = HashMap<String, Any>()
            putDefaultParams(params, filters, lastResult.previousCount)
            params["classId"] = classId

            val response = api.get("mods/search", params, JsonObject::class.java) ?: return null
            val dataArray = response.getAsJsonArray("data") ?: return null

            val infoItems: MutableList<InfoItem> = ArrayList()
            for (data in dataArray) {
                val dataElement = data as? JsonObject ?: continue
                getInfoItem(dataElement, classify)?.let { item ->
                    infoItems.add(item)
                }
            }

            return returnResults(lastResult, infoItems, dataArray, response)
        }

        internal fun getInfoItem(dataObject: JsonObject, classify: Classify): InfoItem? {
            // Gson turns JSON null into false, so only a real false counts. The member can also be missing
            // altogether, which used to throw a NullPointerException and broke the whole search.
            if (dataObject.get("allowModDistribution")?.takeIf { it.isJsonPrimitive }?.asBoolean == false) {
                Logging.i("CurseForgeCommonUtils", "Skipping project ${dataObject.stringOrNull("name")}: its author does not allow downloads from other apps")
                return null
            }

            return runCatching {
                InfoItem(
                    classify,
                    Platform.CURSEFORGE,
                    dataObject.get("id").asString,
                    dataObject.stringOrNull("slug") ?: "",
                    getAuthors(dataObject.get("authors")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()).toTypedArray(),
                    dataObject.stringOrNull("name") ?: "",
                    dataObject.stringOrNull("summary") ?: "",
                    dataObject.longOrNull("downloadCount") ?: 0L,
                    ZHTools.getDate(dataObject.stringOrNull("dateCreated") ?: dataObject.stringOrNull("dateModified")),
                    getIconUrl(dataObject),
                    getAllCategories(dataObject).toList(),
                )
            }.getOrElse { e ->
                // One malformed project must not break the whole result page
                Logging.e("CurseForgeCommonUtils", "Skipping a project that could not be read: ${Tools.printToString(e)}")
                null
            }
        }

        /**
         * The game versions a file is for, without the other tags CurseForge puts in the same list
         * (mod loaders, "Client", "Java 17", ...).
         */
        internal fun getMinecraftVersions(fileObject: JsonObject): MutableSet<String> {
            val mcVersions: MutableSet<String> = TreeSet()
            fileObject.get("gameVersions")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
                if (element.isJsonPrimitive) mcVersions.add(element.asString)
            }
            val releaseRegex = RELEASE_REGEX
            val nonMCVersion: MutableSet<String> = TreeSet()
            mcVersions.forEach(Consumer { string: String ->
                if (!releaseRegex.matcher(string).find()) nonMCVersion.add(string)
            })
            if (nonMCVersion.isNotEmpty()) mcVersions.removeAll(nonMCVersion)
            return mcVersions
        }

        @Throws(Throwable::class)
        internal fun getVersions(api: ApiHandler, infoItem: InfoItem, force: Boolean): List<VersionItem>? {
            if (!force && InfoCache.VersionCache.containsKey(infoItem.projectId)) return InfoCache.VersionCache.get(infoItem.projectId)

            val allData = getPaginatedData(api, infoItem.projectId)

            val versionsItem: MutableList<VersionItem> = ArrayList()
            for (data in allData) {
                try {
                    val downloadUrl = getDownloadUrl(data) ?: continue
                    versionsItem.add(
                        VersionItem(
                            infoItem.projectId,
                            data.get("displayName").asString,
                            data.longOrNull("downloadCount") ?: 0L,
                            ZHTools.getDate(data.get("fileDate").asString),
                            getMinecraftVersions(data).toList(),
                            VersionTypeUtils.getVersionType(data.get("releaseType").asString),
                            data.get("fileName").asString,
                            getSha1FromData(data),
                            downloadUrl
                        )
                    )
                } catch (e: Exception) {
                    Logging.e("CurseForgeHelper", Tools.printToString(e))
                    continue
                }
            }

            InfoCache.VersionCache.put(infoItem.projectId, versionsItem)
            return versionsItem
        }

        internal fun getAuthors(array: JsonArray): List<String> {
            val authors: MutableList<String> = ArrayList()
            for (authorElement in array) {
                (authorElement as? JsonObject)?.stringOrNull("name")?.let { authors.add(it) }
            }
            return authors
        }

        internal fun getSha1FromData(jsonObject: JsonObject): String? {
            val hashes = GsonJsonUtils.getJsonArraySafe(jsonObject, "hashes") ?: return null
            for (jsonElement in hashes) {
                // The sha1 = 1; md5 = 2;
                val jsonObject1 = GsonJsonUtils.getJsonObjectSafe(jsonElement)
                if (GsonJsonUtils.getIntSafe(jsonObject1, "algo", -1) == ALGO_SHA_1) {
                    return GsonJsonUtils.getStringSafe(jsonObject1, "value")
                }
            }
            return null
        }

        /**
         * The CDN link of a file. CurseForge leaves "downloadUrl" empty for some files; their CDN path is
         * made of the file id and the file name, which must be URL-encoded (spaces, "+", "[", ...).
         */
        internal fun buildEdgeUrl(fileId: Long, fileName: String): String {
            val encodedName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
            return "https://edge.forgecdn.net/files/${fileId / 1000}/${fileId % 1000}/$encodedName"
        }

        /** The download link of a file object of the API, built from its id and name when "downloadUrl" is null. */
        internal fun getDownloadUrl(fileObject: JsonObject): String? {
            fileObject.stringOrNull("downloadUrl")?.takeIf { it.isNotBlank() }?.let { return it }
            val id = fileObject.longOrNull("id") ?: return null
            val fileName = fileObject.stringOrNull("fileName") ?: return null
            return buildEdgeUrl(id, fileName)
        }

        internal fun getDownloadUrl(api: ApiHandler, projectID: Long, fileID: Long): String? {
            // First try the official api endpoint
            val response = api.safeRun { get("mods/$projectID/files/$fileID/download-url", JsonObject::class.java) }
            response?.stringOrNull("data")?.takeIf { it.isNotBlank() }?.let { return it }

            // Otherwise, fallback to building an edge link
            val fallbackResponse = api.safeRun { get("mods/$projectID/files/$fileID", JsonObject::class.java) }
            val modData = GsonJsonUtils.getJsonObjectSafe(fallbackResponse, "data") ?: return null
            return getDownloadUrl(modData)
        }

        internal fun getDownloadSha1(api: ApiHandler, projectID: Long, fileID: Long): String? {
            // Try the api endpoint, die in the other case
            val response = api.safeRun { get("mods/$projectID/files/$fileID", JsonObject::class.java) }
            val data = GsonJsonUtils.getJsonObjectSafe(response, "data") ?: return null
            return getSha1FromData(data)
        }

        internal fun searchModFromID(api: ApiHandler, id: String): JsonObject? {
            return api.safeRun { get("mods/$id", JsonObject::class.java) }?.also {
                Logging.i("CurseForge_searchModFromID", it.toString())
            }
        }

        /**
         * Loads many objects with the batch endpoints (POST /v1/mods with "modIds", POST /v1/mods/files with
         * "fileIds"): one request per [CURSEFORGE_BATCH_SIZE] ids instead of one request per id. Returns the
         * objects by id; ids that could not be loaded are missing from the result (callers fall back to the
         * single requests for them).
         */
        private fun getByIds(api: ApiHandler, endpoint: String, idsMember: String, ids: Collection<Long>): Map<Long, JsonObject> {
            val result = HashMap<Long, JsonObject>()
            for (chunk in ids.distinct().chunked(CURSEFORGE_BATCH_SIZE)) {
                val body = JsonObject().apply {
                    add(idsMember, JsonArray().apply { chunk.forEach { add(it) } })
                }
                val response = api.safeRun { post(endpoint, body, JsonObject::class.java) }
                val data = GsonJsonUtils.getJsonArraySafe(response, "data")
                if (data == null) {
                    Logging.w("CurseForgeCommonUtils", "$endpoint: no data for ${chunk.size} ids, falling back to single requests")
                    continue
                }
                for (element in data) {
                    val obj = element as? JsonObject ?: continue
                    obj.longOrNull("id")?.let { result[it] = obj }
                }
            }
            return result
        }

        /** The projects with these ids (POST /v1/mods), by id. */
        internal fun getModsByIds(api: ApiHandler, modIds: Collection<Long>): Map<Long, JsonObject> =
            getByIds(api, "mods", "modIds", modIds)

        /** The files with these ids (POST /v1/mods/files), by id: download link, name and hashes in one request. */
        internal fun getFilesByIds(api: ApiHandler, fileIds: Collection<Long>): Map<Long, JsonObject> =
            getByIds(api, "mods/files", "fileIds", fileIds)

        /**
         * All files of a project. The API returns 50 files per request (this used to ask for 500 and stop after
         * the first answer because it held fewer than 500 files, so only the newest 50 files of a project were
         * ever listed). The first page tells the total count; the other pages are then loaded in parallel.
         */
        @Throws(IOException::class)
        internal fun getPaginatedData(api: ApiHandler, projectId: String): List<JsonObject> {
            fun loadPage(index: Int): JsonObject {
                val params = HashMap<String, Any>()
                params["index"] = index
                params["pageSize"] = CURSEFORGE_PAGINATION_SIZE
                return api.get("mods/$projectId/files", params, JsonObject::class.java)
                    ?: throw IOException("No answer for the files of project $projectId (index $index)")
            }

            val pages = ArrayList<JsonObject>()
            val firstPage = loadPage(0)
            pages.add(firstPage)

            val totalCount = GsonJsonUtils.getJsonObjectSafe(firstPage, "pagination")?.longOrNull("totalCount")?.toInt()
            if (totalCount != null) {
                val remaining = (CURSEFORGE_PAGINATION_SIZE until min(totalCount, CURSEFORGE_MAX_RESULTS) step CURSEFORGE_PAGINATION_SIZE)
                    .filter { it + CURSEFORGE_PAGINATION_SIZE <= CURSEFORGE_MAX_RESULTS }
                if (remaining.isNotEmpty()) {
                    val executor = Executors.newFixedThreadPool(min(CURSEFORGE_PAGE_THREADS, remaining.size))
                    try {
                        val futures = remaining.map { index -> executor.submit(Callable { loadPage(index) }) }
                        futures.forEach { pages.add(it.get()) } // in order: newest files first, like the API
                    } catch (e: ExecutionException) {
                        throw (e.cause as? IOException) ?: IOException("Failed to load the files of project $projectId", e.cause)
                    } finally {
                        executor.shutdownNow()
                    }
                }
            } else {
                // No pagination info: keep asking until a page is not full
                var index = CURSEFORGE_PAGINATION_SIZE
                var last = firstPage
                while (index + CURSEFORGE_PAGINATION_SIZE <= CURSEFORGE_MAX_RESULTS &&
                    (GsonJsonUtils.getJsonArraySafe(last, "data")?.size() ?: 0) >= CURSEFORGE_PAGINATION_SIZE) {
                    last = loadPage(index)
                    pages.add(last)
                    index += CURSEFORGE_PAGINATION_SIZE
                }
            }

            val dataList: MutableList<JsonObject> = ArrayList()
            for (page in pages) {
                val data = GsonJsonUtils.getJsonArraySafe(page, "data") ?: throw IOException("Invalid data!")
                for (element: JsonElement in data) {
                    val fileInfo = element as? JsonObject ?: continue
                    if (fileInfo.booleanOrFalse("isServerPack")) continue
                    dataList.add(fileInfo)
                }
            }
            return dataList
        }

        internal fun returnResults(
            lastResult: SearchResult,
            infoItems: List<InfoItem>,
            dataArray: JsonArray,
            response: JsonObject
        ): SearchResult = lastResult.apply {
            this.infoItems.addAll(infoItems)
            this.previousCount += dataArray.size()
            this.totalResultCount = GsonJsonUtils.getJsonObjectSafe(response, "pagination")?.longOrNull("totalCount")?.toInt()
                ?: this.previousCount
            // A full page can be the last one too: the API serves no results beyond its 10000 result window
            this.isLastPage = dataArray.size() < CURSEFORGE_SEARCH_COUNT ||
                    this.previousCount >= min(this.totalResultCount, CURSEFORGE_MAX_RESULTS)
        }
    }
}
