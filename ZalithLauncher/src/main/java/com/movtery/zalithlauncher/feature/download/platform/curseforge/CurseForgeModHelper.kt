package com.movtery.zalithlauncher.feature.download.platform.curseforge

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.movtery.zalithlauncher.feature.download.Filters
import com.movtery.zalithlauncher.feature.download.InfoCache
import com.movtery.zalithlauncher.feature.download.enums.Category
import com.movtery.zalithlauncher.feature.download.enums.Classify
import com.movtery.zalithlauncher.feature.download.enums.ModLoader
import com.movtery.zalithlauncher.feature.download.enums.Platform
import com.movtery.zalithlauncher.feature.download.item.DependenciesInfoItem
import com.movtery.zalithlauncher.feature.download.item.InfoItem
import com.movtery.zalithlauncher.feature.download.item.ModInfoItem
import com.movtery.zalithlauncher.feature.download.item.ModLikeVersionItem
import com.movtery.zalithlauncher.feature.download.item.ModVersionItem
import com.movtery.zalithlauncher.feature.download.item.SearchResult
import com.movtery.zalithlauncher.feature.download.item.VersionItem
import com.movtery.zalithlauncher.feature.download.platform.PlatformNotSupportedException
import com.movtery.zalithlauncher.feature.download.platform.curseforge.CurseForgeCommonUtils.Companion.stringOrNull
import com.movtery.zalithlauncher.feature.download.utils.DependencyUtils
import com.movtery.zalithlauncher.feature.download.utils.ModLoaderUtils
import com.movtery.zalithlauncher.feature.download.utils.PlatformUtils
import com.movtery.zalithlauncher.feature.download.utils.VersionTypeUtils
import com.movtery.zalithlauncher.feature.log.Logging
import com.movtery.zalithlauncher.utils.MCVersionRegex.Companion.RELEASE_REGEX
import com.movtery.zalithlauncher.utils.ZHTools
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler
import net.kdt.pojavlaunch.utils.GsonJsonUtils
import java.util.TreeSet

class CurseForgeModHelper {
    companion object {
        @Throws(Throwable::class)
        internal fun modLikeSearch(api: ApiHandler, lastResult: SearchResult, filters: Filters, type: Int, classify: Classify): SearchResult? {
            if (filters.category != Category.ALL && filters.category.curseforgeID == null) {
                throw PlatformNotSupportedException("The platform does not support the ${filters.category} category!")
            }

            PlatformUtils.searchModLikeWithChinese(filters, type == CurseForgeCommonUtils.CURSEFORGE_MOD_CLASS_ID)?.let {
                filters.name = it
            }

            val params = HashMap<String, Any>()
            CurseForgeCommonUtils.putDefaultParams(params, filters, lastResult.previousCount)
            params["classId"] = type
            filters.modloader?.let { params["modLoaderTypes"] = "[${it.curseforgeId}]" }

            val response = api.get("mods/search", params, JsonObject::class.java) ?: return null
            val dataArray = response.getAsJsonArray("data") ?: return null

            val infoItems: MutableList<InfoItem> = ArrayList()
            for (data in dataArray) {
                val dataElement = data as? JsonObject ?: continue
                CurseForgeCommonUtils.getInfoItem(dataElement, classify)?.let { item ->
                    infoItems.add(
                        ModInfoItem(
                            item.classify,
                            Platform.CURSEFORGE,
                            item.projectId,
                            item.slug,
                            item.author,
                            item.title,
                            item.description,
                            item.downloadCount,
                            item.uploadDate,
                            item.iconUrl,
                            item.category,
                            getModLoaders(dataElement.get("latestFilesIndexes")?.takeIf { it.isJsonArray }?.asJsonArray)
                        )
                    )
                }
            }

            return CurseForgeCommonUtils.returnResults(lastResult, infoItems, dataArray, response)
        }

        @Throws(Throwable::class)
        internal fun <T : VersionItem> getModOrModPackVersions(
            api: ApiHandler,
            infoItem: InfoItem,
            force: Boolean,
            cache: InfoCache.CacheBase<MutableList<T>>,
            createVersionItem: (
                projectId: String,
                title: String,
                downloadCount: Long,
                fileDate: String,
                mcVersions: List<String>,
                releaseType: String,
                fileHash: String?,
                downloadUrl: String,
                modloaders: List<ModLoader>,
                fileName: String,
                dependencies: List<DependenciesInfoItem>?
            ) -> T
        ): List<T>? {
            if (!force && cache.containsKey(infoItem.projectId)) return cache.get(infoItem.projectId)

            val allModData = CurseForgeCommonUtils.getPaginatedData(api, infoItem.projectId)
            // The projects the files depend on, loaded with one request instead of one request per project
            val dependencyProjects = prefetchDependencies(api, allModData)

            val versionItems: MutableList<T> = ArrayList()
            val invalidDependencies: MutableList<String> = ArrayList()
            for (modData in allModData) {
                try {
                    // 获取版本信息
                    val mcVersions: MutableSet<String> = TreeSet()
                    for (gameVersionElement in modData.getAsJsonArray("gameVersions")) {
                        val gameVersion = gameVersionElement.asString
                        mcVersions.add(gameVersion)
                    }

                    val modloaders: MutableList<ModLoader> = ArrayList()
                    mcVersions.forEach { ModLoaderUtils.addModLoaderToList(modloaders, it) }

                    // 过滤非MC版本的元素
                    val releaseRegex = RELEASE_REGEX
                    val nonMCVersion: MutableSet<String> = TreeSet()
                    mcVersions.forEach { string: String ->
                        if (!releaseRegex.matcher(string).find()) nonMCVersion.add(string)
                    }
                    if (nonMCVersion.isNotEmpty()) mcVersions.removeAll(nonMCVersion)

                    val dependencies = modData.get("dependencies")?.takeIf { it.isJsonArray }?.asJsonArray
                    val dependencyInfoList: MutableList<DependenciesInfoItem> = ArrayList()
                    if (dependencies != null && dependencies.size() != 0) {
                        for (dependency in dependencies) {
                            val dObject = dependency as? JsonObject ?: continue
                            val modId = dObject.get("modId")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                            if (invalidDependencies.contains(modId)) continue

                            if (!InfoCache.DependencyInfoCache.containsKey(modId)) {
                                val hit = dependencyProjects[modId] ?: GsonJsonUtils.getJsonObjectSafe(
                                    CurseForgeCommonUtils.searchModFromID(api, modId), "data"
                                )

                                // A dependency that can't be read is skipped (before, the exception dropped
                                // every version of the mod that depends on it)
                                val dependencyItem = hit?.let { project ->
                                    runCatching {
                                        DependenciesInfoItem(
                                            infoItem.classify,
                                            Platform.CURSEFORGE,
                                            modId,
                                            project.stringOrNull("slug") ?: "",
                                            CurseForgeCommonUtils.getAuthors(project.get("authors")?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()).toTypedArray(),
                                            project.stringOrNull("name") ?: modId,
                                            project.stringOrNull("summary") ?: "",
                                            project.get("downloadCount")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                                            ZHTools.getDate(project.stringOrNull("dateCreated") ?: project.stringOrNull("dateModified")),
                                            CurseForgeCommonUtils.getIconUrl(project),
                                            CurseForgeCommonUtils.getAllCategories(project).toList(),
                                            getModLoaders(project.get("latestFilesIndexes")?.takeIf { it.isJsonArray }?.asJsonArray),
                                            DependencyUtils.getDependencyTypeFromCurseForge(dObject.stringOrNull("relationType") ?: "")
                                        )
                                    }.onFailure { e ->
                                        Logging.e("CurseForgeHelper", "Skipping dependency $modId: ${Tools.printToString(e)}")
                                    }.getOrNull()
                                }
                                if (dependencyItem != null) InfoCache.DependencyInfoCache.put(modId, dependencyItem)
                                else invalidDependencies.add(modId)
                            }

                            val cacheItem = InfoCache.DependencyInfoCache.get(modId)
                            cacheItem?.let { dependencyInfoList.add(it) }
                        }
                    }

                    // CurseForge leaves downloadUrl empty for some files: use their CDN link then
                    val downloadUrl = CurseForgeCommonUtils.getDownloadUrl(modData) ?: continue
                    versionItems.add(
                        createVersionItem(
                            infoItem.projectId,
                            modData.get("displayName").asString,
                            modData.get("downloadCount").asLong,
                            modData.get("fileDate").asString,
                            mcVersions.toList(),
                            modData.get("releaseType").asString,
                            CurseForgeCommonUtils.getSha1FromData(modData),
                            downloadUrl,
                            modloaders,
                            modData.get("fileName").asString,
                            dependencyInfoList.ifEmpty { null }
                        )
                    )
                } catch (e: Exception) {
                    Logging.e("CurseForgeHelper", Tools.printToString(e))
                    continue
                }
            }

            cache.put(infoItem.projectId, versionItems)
            return versionItems
        }

        @Throws(Throwable::class)
        internal fun getModVersions(api: ApiHandler, infoItem: InfoItem, force: Boolean): List<ModVersionItem>? {
            return getModOrModPackVersions(
                api,
                infoItem,
                force,
                InfoCache.ModVersionCache
            ) { projectId, title, downloadCount, fileDate, mcVersions, releaseType, fileHash, downloadUrl, modloaders, fileName, dependencies ->
                ModVersionItem(
                    projectId,
                    title,
                    downloadCount,
                    ZHTools.getDate(fileDate),
                    mcVersions,
                    VersionTypeUtils.getVersionType(releaseType),
                    fileName,
                    fileHash,
                    downloadUrl,
                    modloaders,
                    dependencies ?: emptyList()
                )
            }
        }

        @Throws(Throwable::class)
        internal fun getModPackVersions(api: ApiHandler, infoItem: InfoItem, force: Boolean): List<ModLikeVersionItem>? {
            return getModOrModPackVersions(
                api,
                infoItem,
                force,
                InfoCache.ModPackVersionCache
            ) { projectId, title, downloadCount, fileDate, mcVersions, releaseType, fileHash, downloadUrl, modloaders, fileName, _ ->
                ModLikeVersionItem(
                    projectId,
                    title,
                    downloadCount,
                    ZHTools.getDate(fileDate),
                    mcVersions,
                    VersionTypeUtils.getVersionType(releaseType),
                    fileName,
                    fileHash,
                    downloadUrl,
                    modloaders
                )
            }
        }

        /**
         * Loads the projects that the files depend on with the batch endpoint (POST /v1/mods) instead of one
         * request per project. Returns them by project id (projects already cached or not loaded are missing).
         */
        private fun prefetchDependencies(api: ApiHandler, files: List<JsonObject>): Map<String, JsonObject> {
            val ids = LinkedHashSet<Long>()
            for (file in files) {
                val dependencies = file.get("dependencies")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                for (dependency in dependencies) {
                    val modId = (dependency as? JsonObject)?.get("modId")?.takeIf { it.isJsonPrimitive } ?: continue
                    if (InfoCache.DependencyInfoCache.containsKey(modId.asString)) continue
                    runCatching { modId.asLong }.getOrNull()?.let { ids.add(it) }
                }
            }
            if (ids.isEmpty()) return emptyMap()
            return CurseForgeCommonUtils.getModsByIds(api, ids).mapKeys { it.key.toString() }
        }

        private fun getModLoaders(data: JsonArray?): List<ModLoader> {
            val modLoaders: MutableSet<ModLoader> = HashSet()
            if (data == null) return emptyList()
            for (element in data) {
                val jsonObject = element as? JsonObject ?: continue
                // "modLoader" is missing or JSON null for files without a loader (Gson's asString throws on null)
                val modloader = jsonObject.get("modLoader")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                ModLoaderUtils.getModLoaderByCurseForge(modloader)?.let {
                    modLoaders.add(it)
                }
            }
            return modLoaders.toList()
        }
    }
}