package atm.bloodworkxgaming.serverstarter.packtype.modrinth

import atm.bloodworkxgaming.serverstarter.InternetManager
import atm.bloodworkxgaming.serverstarter.ServerStarter.Companion.LOGGER
import atm.bloodworkxgaming.serverstarter.config.ConfigFile
import atm.bloodworkxgaming.serverstarter.packtype.AbstractZipbasedPackType
import atm.bloodworkxgaming.serverstarter.packtype.writeToFile
import com.google.gson.JsonParser
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.URISyntaxException
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

private val MODRINTH_URL_REGEX = Pattern.compile("https://cdn\\.modrinth\\.com/data/([a-zA-Z0-9]+)/versions/([a-zA-Z0-9]+)/(.+)")
private val MODRINTH_MODLOADERS = listOf("fabric-loader", "forge", "neoforge", "quilt-loader")

class ModrinthPackType(private val configFile: ConfigFile, internetManager: InternetManager) : AbstractZipbasedPackType(configFile, internetManager) {

    private var loaderVersion: String = configFile.install.loaderVersion
    private var mcVersion: String = configFile.install.mcVersion
    private val oldFiles = File(basePath + "OLD_TO_DELETE/")

    override fun cleanUrl(url: String) = url
    override fun getForgeVersion() = loaderVersion
    override fun getMCVersion() = mcVersion

    @Throws(IOException::class)
    override fun handleZip(file: File, pathMatchers: List<PathMatcher>) {
        // delete old installer folder
        FileUtils.deleteDirectory(oldFiles)

        // start with deleting the mods folder as it is not guaranteed to have override mods
        val modsFolder = File(basePath + "mods/")

        if (modsFolder.exists())
            FileUtils.moveDirectory(modsFolder, File(oldFiles, "mods"))
        LOGGER.info("Moved the mods folder")

        LOGGER.info("Starting to unzip files.")
        // unzip start
        try {
            ZipInputStream(FileInputStream(file)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry

                loop@ while (entry != null) {
                    LOGGER.info("Entry in zip: $entry", true)
                    val name = entry.name

                    // special manifest treatment
                    if (name == "modrinth.index.json")
                        zis.writeToFile(File(basePath + "modrinth.index.json"))


                    // overrides
                    if (name.startsWith("overrides/")) {
                        val path = entry.name.substring(10)

                        when {
                            pathMatchers.any { it.matches(Paths.get(path)) } ->
                                LOGGER.info("Skipping $path as it is on the ignore List.", true)


                            !name.endsWith("/") -> {
                                val outfile = File(basePath + path)
                                LOGGER.info("Copying zip entry to = $outfile", true)


                                outfile.parentFile?.mkdirs()

                                zis.writeToFile(outfile)
                            }

                            name != "overrides/" -> {
                                val newFolder = File(basePath + path)
                                if (newFolder.exists())
                                    FileUtils.moveDirectory(newFolder, File(oldFiles, path))

                                LOGGER.info("Folder moved: " + newFolder.absolutePath, true)
                            }
                        }
                    }

                    entry = zis.nextEntry
                }


                zis.closeEntry()
            }
        } catch (e: IOException) {
            LOGGER.error("Could not unzip files", e)
            throw e
        }

        LOGGER.info("Done unzipping the files.")
    }

    @Throws(IOException::class)
    override fun postProcessing() {
        val mods = ArrayList<ModEntryRaw>()
        val file = File(basePath + "modrinth.index.json")

        if(!file.exists()){
            LOGGER.error("No Modrinth Index json found. Skipping mod downloads")
            return
        }

        InputStreamReader(FileInputStream(file), "utf-8").use { reader ->
            val json = JsonParser.parseReader(reader).asJsonObject
            LOGGER.info("manifest JSON Object: $json", true)

            val dependencies = json.getAsJsonObject("dependencies")

            if (mcVersion.isEmpty()) {
                mcVersion = dependencies.getAsJsonPrimitive("minecraft").asString
            }


            if (loaderVersion.isEmpty()) {
                for (validLoader in MODRINTH_MODLOADERS) {
                    if (dependencies.has(validLoader)) {
                        loaderVersion = dependencies.getAsJsonPrimitive(validLoader).asString
                        break
                    }
                }
                if (loaderVersion.isEmpty()) {
                    LOGGER.error("No valid loader found in dependencies. Skipping mod downloads")
                    return
                }
            }

            // gets all the mods
            for (jsonFile in json.getAsJsonArray("files")) {
                val fileObj = jsonFile.asJsonObject
                val filePath = fileObj.getAsJsonPrimitive("path").asString
                if (!filePath.startsWith("mods/")) continue
                val env = fileObj.getAsJsonObject("env")
                if (env != null && env.has("server") && env.getAsJsonPrimitive("server").asString == "unsupported") {
                    continue
                }
                val download = fileObj.getAsJsonArray("downloads").get(0).asString
                val matcher = MODRINTH_URL_REGEX.matcher(download)
                val isModrinthUrl = matcher.matches()
                val projectID = if (isModrinthUrl) matcher.group(1) else ""
                val fileID = if (isModrinthUrl) matcher.group(2) else ""

                mods.add(ModEntryRaw(projectID, fileID, download))
            }

        }

        downloadMods(mods)
    }

    private fun downloadMods(mods: List<ModEntryRaw>) {
        val ignoreSet = HashSet<String>()
        val ignoreListTemp = configFile.install.getFormatSpecificSettingOrDefault<List<Any>>("ignoreProject", null)

        ignoreListTemp?.filterIsInstance<String>()?.forEach {
            ignoreSet.add(it)
        }


        val urls = ConcurrentLinkedQueue<String>()

        mods.parallelStream().forEach { mod ->
            if (ignoreSet.isNotEmpty() && ignoreSet.contains(mod.projectID)) {
                LOGGER.info("Skipping mod with projectID: " + mod.projectID)
                return@forEach
            }

            if (mod.downloadUrl.isNotEmpty()) {
                urls.add(mod.downloadUrl)
                return@forEach
            }

            LOGGER.error("No download url found for mod with projectID: " + mod.projectID)
        }

        LOGGER.info("Mods to download: $urls", true)

        processMods(urls)

    }

    /**
     * Downloads all mods, with a second fallback if failed
     * This is done in parallel for better performance
     *
     * @param mods List of urls
     */
    private fun processMods(mods: Collection<String>) {
        // constructs the ignore list
        val ignorePatterns = configFile.install.ignoreFiles
            .filter { it.startsWith("mods/") }
            .map { Pattern.compile(it.substring(it.lastIndexOf('/'))) }
            .toMutableList()

        // downloads the mods
        val count = AtomicInteger(0)
        val totalCount = mods.size
        val fallbackList = ArrayList<String>()

        mods.stream().parallel().forEach { s -> processSingleMod(s, count, totalCount, fallbackList, ignorePatterns) }

        val secondFail = ArrayList<String>()
        fallbackList.forEach { s -> processSingleMod(s, count, totalCount, secondFail, ignorePatterns) }

        if (secondFail.isNotEmpty()) {
            LOGGER.warn("Failed to download (a) mod(s):")
            for (s in secondFail) {
                LOGGER.warn("\t" + s)
            }
        }
    }

    /**
     * Downloads a single mod and saves to the /mods directory
     *
     * @param mod            URL of the mod
     * @param counter        current counter of how many mods have already been downloaded
     * @param totalCount     total count of mods that have to be downloaded
     * @param fallbackList   List to write to when it failed
     * @param ignorePatterns Patterns of mods which should be ignored
     */
    private fun processSingleMod(mod: String, counter: AtomicInteger, totalCount: Int, fallbackList: MutableList<String>, ignorePatterns: List<Pattern>) {
        try {
            val modName = FilenameUtils.getName(mod)
            for (ignorePattern in ignorePatterns) {
                if (ignorePattern.matcher(modName).matches()) {
                    LOGGER.info("[${counter.incrementAndGet()}/${totalCount}] Skipped ignored mod: $modName")
                }
            }

            internetManager.downloadToFile(mod, File(basePath + "mods/" + modName))
            LOGGER.info( "[${String.format("% 3d", counter.incrementAndGet())}/${totalCount}] Downloaded mod: $modName")
        } catch (e: IOException) {
            LOGGER.error("Failed to download mod", e)
            fallbackList.add(mod)

        } catch (e: URISyntaxException) {
            LOGGER.error("Invalid url for $mod", e)
        }

    }
}

/**
 * Data class to keep projectID and fileID together
 */
data class ModEntryRaw(val projectID: String, val fileID: String, val downloadUrl: String)
