package com.maxlass.studio.device.driver

import com.maxlass.studio.pack.format.reader.FsStoryPackReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * FS Lunii 2.x/3.x driver: operates directly on the mounted partition (no USB access beyond
 * hotplug detection). Replaces `studio.driver.fs.FsStoryTellerAsyncDriver` with a coroutine
 * mount-point poller and pure file I/O.
 */
class FsStoryTellerDriver {

    companion object {
        private val log = LoggerFactory.getLogger(FsStoryTellerDriver::class.java)

        private const val DEVICE_METADATA_FILENAME = ".md"
        private const val PACK_INDEX_FILENAME = ".pi"
        private const val CONTENT_FOLDER = ".content"
        private const val NODE_INDEX_FILENAME = "ni"
        private const val NIGHT_MODE_FILENAME = "nm"
        private const val BOOT_FILENAME = "bt"

        private const val MOUNTPOINT_POLL_DELAY_MS = 1_000L
        private const val MOUNTPOINT_RETRY = 15
        private const val DELETE_DIRECTORY_MAX_ATTEMPTS = 5
        private const val DELETE_DIRECTORY_RETRY_DELAY_MILLIS = 300L
    }

    @Volatile
    internal var partitionMountPoint: Path? = null

    /** Polls the mount points until the device partition (`.md` file) appears. */
    suspend fun onPlugged() {
        val found = withTimeoutOrNull((MOUNTPOINT_RETRY * MOUNTPOINT_POLL_DELAY_MS).milliseconds) {
            var mountPoint: String? = null
            while (mountPoint == null) {
                mountPoint = listMountPoints().firstOrNull { mp ->
                    Files.exists(Paths.get(mp).resolve(DEVICE_METADATA_FILENAME))
                }
                if (mountPoint == null) {
                    delay(MOUNTPOINT_POLL_DELAY_MS.milliseconds)
                }
            }
            mountPoint
        }
        partitionMountPoint = found?.let { Paths.get(it) } ?: throw StoryTellerException("Could not locate device partition")
        log.info("FS device partition located: {}", partitionMountPoint)
    }

    fun onUnplugged() {
        partitionMountPoint = null
    }

    suspend fun getDeviceInfos(): FsDeviceInfos {
        val mountPoint = requireMountPoint()
        val mdFile = mountPoint.resolve(DEVICE_METADATA_FILENAME)
        val data = Files.readAllBytes(mdFile)
        val mdVersion = littleEndianShort(data, 0).toInt()
        val infos = when (mdVersion) {
            in 1..3 -> parseMeta1To3(data)
            in 6..7 -> parseMeta6To7(data, mdVersion)
            else -> throw StoryTellerException("Unsupported device metadata format version: $mdVersion")
        }
        val md = mdFile.toFile()
        return infos.copy(
            sdCardSizeInBytes = md.totalSpace,
            usedSpaceInBytes = md.totalSpace - md.freeSpace,
        )
    }

    suspend fun getPacksList(): List<FsStoryPackInfos> {
        val mountPoint = requireMountPoint()
        return readPackIndex(mountPoint).map { uuid ->
            val folderName = computePackFolderName(uuid.toString())
            val packFolder = mountPoint.resolve(CONTENT_FOLDER).resolve(folderName)
            val niData = Files.readAllBytes(packFolder.resolve(NODE_INDEX_FILENAME))
            FsStoryPackInfos(
                uuid = uuid,
                folderName = folderName,
                version = littleEndianShort(niData, 2),
                sizeInBytes = folderSize(packFolder),
                nightModeAvailable = Files.exists(packFolder.resolve(NIGHT_MODE_FILENAME)),
            )
        }
    }

    suspend fun deletePack(uuid: String): Boolean {
        val mountPoint = requireMountPoint()
        val uuids = readPackIndex(mountPoint).toMutableList()
        val matched = uuids.firstOrNull { it == UUID.fromString(uuid) }
            ?: throw StoryTellerException("Pack not found")
        uuids.remove(matched)
        writePackIndex(mountPoint, uuids)
        val folder = mountPoint.resolve(CONTENT_FOLDER).resolve(computePackFolderName(uuid))
        if (!deleteDirectoryWithRetry(folder)) {
            throw StoryTellerException("Failed to delete pack folder on device partition")
        }
        return true
    }

    suspend fun uploadPack(uuid: String, inputPath: String) {
        val mountPoint = requireMountPoint()
        val folderSize = folderSize(Path.of(inputPath))
        val mdFile = mountPoint.resolve(DEVICE_METADATA_FILENAME).toFile()
        if (mdFile.freeSpace < folderSize) {
            throw StoryTellerException("Not enough free space on the device")
        }
        val destFolder = mountPoint.resolve(CONTENT_FOLDER).resolve(computePackFolderName(uuid))
        destFolder.toFile().mkdirs()
        val deviceInfos = getDeviceInfos()
        copyPackFolder(Path.of(inputPath), destFolder, deviceInfos, isUpload = true)
        val uuids = readPackIndex(mountPoint).toMutableList()
        uuids.add(UUID.fromString(uuid))
        writePackIndex(mountPoint, uuids)
    }

    suspend fun downloadPack(uuid: String, outputPath: String) {
        val mountPoint = requireMountPoint()
        val uuids = readPackIndex(mountPoint)
        if (uuids.none { it == UUID.fromString(uuid) }) {
            throw StoryTellerException("Pack not found")
        }
        val sourceFolder = mountPoint.resolve(CONTENT_FOLDER).resolve(computePackFolderName(uuid))
        if (!Files.exists(sourceFolder)) {
            throw StoryTellerException("Pack folder not found")
        }
        val destFolder = Path.of(outputPath, uuid)
        destFolder.toFile().mkdirs()
        val deviceInfos = getDeviceInfos()
        copyPackFolder(sourceFolder, destFolder, deviceInfos, isUpload = false)
    }

    fun computePackFolderName(uuid: String): String {
        val uuidStr = uuid.replace("-", "")
        return uuidStr.substring(uuidStr.length - 8).uppercase()
    }

    // --- metadata parsing (pure) ---

    private fun parseMeta1To3(data: ByteArray): FsDeviceInfos {
        val major = littleEndianShort(data, 4)
        val minor = littleEndianShort(data, 6)
        val serialRaw = bigEndianLong(data, 8)
        val serial = if (serialRaw != 0L && serialRaw != -1L && serialRaw != -4_294_967_296L) {
            String.format("%014d", serialRaw)
        } else {
            null
        }
        val uuid = data.copyOfRange(254, 254 + 256)
        return FsDeviceInfos(uuid, major, minor, serial, 0, 0, null)
    }

    private fun parseMeta6To7(data: ByteArray, mdVersion: Int): FsDeviceInfos {
        // Bytes 0-1 are the metadata version, already consumed.
        val major = asciiToShort(data, 2)
        val minor = asciiToShort(data, 4)
        val snBytes = data.copyOfRange(26, 26 + 24)
        val serial = String(snBytes, StandardCharsets.UTF_8)

        val (aesKey, aesIv, btFile) = if (mdVersion == 6) {
            val key = snBytes.copyOfRange(0, 16)
            val iv = ByteArray(16)
            System.arraycopy(snBytes, 16, iv, 0, 8)
            System.arraycopy(snBytes, 0, iv, 8, 8)
            Triple(key, iv, data.copyOfRange(64, 64 + 32))
        } else {
            val key = data.copyOfRange(64, 64 + 16)
            val iv = data.copyOfRange(80, 80 + 16)
            val bt = ByteArray(32)
            System.arraycopy(snBytes, 0, bt, 0, 24)
            System.arraycopy(snBytes, 0, bt, 24, 8)
            Triple(key, iv, bt)
        }

        val uuid = ByteArray(64)
        System.arraycopy(aesKey, 0, uuid, 0, 16)
        System.arraycopy(aesIv, 0, uuid, 16, 16)
        System.arraycopy(btFile, 0, uuid, 32, 32)
        return FsDeviceInfos(uuid, major, minor, serial, 0, 0, FsDeviceKeyV3(aesKey, aesIv, btFile))
    }

    // --- pack index (.pi) ---

    private fun readPackIndex(mountPoint: Path): List<UUID> {
        val data = Files.readAllBytes(mountPoint.resolve(PACK_INDEX_FILENAME))
        val uuids = ArrayList<UUID>(data.size / 16)
        var offset = 0
        while (offset + 16 <= data.size) {
            val high = bigEndianLong(data, offset)
            val low = bigEndianLong(data, offset + 8)
            uuids.add(UUID(high, low))
            offset += 16
        }
        return uuids
    }

    private fun writePackIndex(mountPoint: Path, uuids: List<UUID>) {
        val tmp = mountPoint.resolve("$PACK_INDEX_FILENAME.new")
        DataOutputStream(Files.newOutputStream(tmp)).use { dos ->
            for (uuid in uuids) {
                dos.writeLong(uuid.mostSignificantBits)
                dos.writeLong(uuid.leastSignificantBits)
            }
        }
        Files.move(tmp, mountPoint.resolve(PACK_INDEX_FILENAME), StandardCopyOption.REPLACE_EXISTING)
    }

    // --- folder copy with ciphering ---

    private fun copyPackFolder(source: Path, dest: Path, deviceInfos: FsDeviceInfos, isUpload: Boolean) {
        val firmwareMajor = deviceInfos.firmwareMajor.toInt()
        if (firmwareMajor != 2 && firmwareMajor != 3) {
            throw StoryTellerException("Failed to copy pack folder: unsupported firmware version $firmwareMajor")
        }
        val isCleartext = FsStoryPackReader().isCleartext(source, isUpload)
        if (!isUpload) {
            Files.createFile(dest.resolve(".cleartext"))
        }
        Files.walk(source).use { stream ->
            stream.forEach { s ->
                val d = dest.resolve(source.relativize(s))
                if (Files.isDirectory(s)) {
                    if (!Files.exists(d)) {
                        Files.createDirectory(d)
                    }
                } else {
                    if (!FsCipher.shouldBeCopied(s)) return@forEach
                    when {
                        !FsCipher.shouldBeCiphered(s) -> Files.copy(s, d, StandardCopyOption.REPLACE_EXISTING)
                        firmwareMajor == 2 -> {
                            val data = Files.readAllBytes(s)
                            val out = if (isUpload) {
                                if (isCleartext) FsCipher.cipherFirstBlockCommonKey(data) else data
                            } else {
                                FsCipher.decipherFirstBlockCommonKey(data)
                            }
                            Files.write(d, out)
                        }
                        else -> {
                            val data = Files.readAllBytes(s)
                            val key = deviceInfos.deviceKeyV3 ?: throw StoryTellerException("Missing device key V3")
                            val out = if (isUpload) {
                                val clear = if (isCleartext) data else FsCipher.decipherFirstBlockCommonKey(data)
                                FsCipher.cipherFirstBlockSpecificKeyV3(clear, key)
                            } else {
                                FsCipher.decipherFirstBlockSpecificKeyV3(data, key)
                            }
                            Files.write(d, out)
                        }
                    }
                }
            }
        }
        if (firmwareMajor == 2) {
            val uuid = deviceInfos.uuid ?: throw StoryTellerException("Missing device UUID")
            FsCipher.addBootFileV2(dest, uuid)
        } else {
            FsCipher.addBootFileV3(dest, deviceInfos.deviceKeyV3!!)
        }
    }

    private fun deleteDirectoryWithRetry(directory: Path): Boolean {
        for (attempt in 1..DELETE_DIRECTORY_MAX_ATTEMPTS) {
            try {
                if (directory.toFile().exists() && !directory.toFile().deleteRecursively()) {
                    throw RuntimeException("deleteRecursively returned false")
                }
                return true
            } catch (e: Exception) {
                if (attempt >= DELETE_DIRECTORY_MAX_ATTEMPTS) {
                    log.warn("Failed to delete directory {} after {} attempts: {}", directory, attempt, e.message)
                    return false
                }
                log.debug("Failed to delete directory {} (attempt {}), retrying...", directory, attempt)
                Thread.sleep(DELETE_DIRECTORY_RETRY_DELAY_MILLIS)
            }
        }
        return false
    }

    // --- helpers ---

    private fun requireMountPoint(): Path = partitionMountPoint ?: throw StoryTellerException("No device plugged")

    private fun folderSize(path: Path): Long =
        Files.walk(path).use { stream -> stream.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }

    private fun littleEndianShort(data: ByteArray, offset: Int): Short =
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short

    private fun bigEndianLong(data: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(data, offset, 8).order(ByteOrder.BIG_ENDIAN).long

    private fun asciiToShort(data: ByteArray, offset: Int): Short =
        String(data, offset, 1, StandardCharsets.UTF_8).toShort()

    private fun listMountPoints(): List<String> {
        val os = System.getProperty("os.name").lowercase()
        if (os.contains("win")) {
            return File.listRoots().map { it.path }
        }
        if (os.contains("mac")) {
            // USB mass-storage volumes mount under /Volumes. Enumerate the directory
            // directly instead of parsing `df`: FSKit-backed FAT mounts (macOS 15+/Tahoe,
            // e.g. some Lunii firmware 3.x devices) do NOT appear in `df` output, only in
            // `mount`. Scanning /Volumes catches both classic msdosfs and FSKit mounts.
            return File("/Volumes")
                .takeIf { it.isDirectory }
                ?.listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.absolutePath }
                ?: emptyList()
        }
        val pattern = Regex("^([^ ]+)[^/]+(/.*)$")
        return runCatching {
            val process = Runtime.getRuntime().exec("df -l")
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            lines.mapNotNull { line ->
                val match = pattern.matchEntire(line) ?: return@mapNotNull null
                val dev = match.groupValues[1]
                val root = match.groupValues[2]
                if (!dev.startsWith("/dev/")) null else root
            }
        }.getOrDefault(emptyList())
    }
}
