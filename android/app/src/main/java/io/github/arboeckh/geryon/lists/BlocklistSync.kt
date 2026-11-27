package io.github.arboeckh.geryon.lists

import android.content.Context
import android.util.Log
import androidx.core.util.AtomicFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.arboeckh.geryon.geryon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** An external list that fills a catalogue category. */
data class BlocklistSource(
    val categoryId: String,
    /** Tried in order until one works. */
    val urls: List<String>,
    /** Fewer entries than this means a broken download (captive portal, error page). */
    val minEntries: Int,
)

/**
 * Downloads maintained domain lists for the hard categories, keeps them in `filesDir`
 * in packed form, and hands them to [io.github.arboeckh.geryon.block.BlockRepository]
 * as [DomainSet]s. Refreshed about daily by [BlocklistWorker].
 */
class BlocklistSync private constructor(private val context: Context) {

    private val dir = File(context.filesDir, "blocklists").apply { mkdirs() }

    /** Lists currently loaded, by category. */
    private val loaded = HashMap<String, DomainSet>()

    /** Packed files on disk, loaded into the repository. Missing or corrupt files are skipped. */
    suspend fun loadCached() = lock.withLock {
        withContext(Dispatchers.IO) {
            for (source in SOURCES) {
                if (source.categoryId in loaded) continue
                val file = AtomicFile(packedFile(source))
                if (!file.baseFile.exists()) continue
                runCatching { file.openRead().buffered().use { DomainSet.read(it) } }
                    .onSuccess { loaded[source.categoryId] = it }
                    .onFailure { Log.w(TAG, "Dropping corrupt list ${source.categoryId}", it) }
            }
            publish()
        }
    }

    /** True once every source has a file on disk. */
    fun hasAllLists(): Boolean = SOURCES.all { packedFile(it).exists() }

    /**
     * Downloads every source that changed. A failed source keeps its previous list.
     * Returns true if all sources are now up to date.
     */
    suspend fun refresh(): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            var ok = true
            for (source in SOURCES) {
                val result = runCatching { download(source) }
                    .onFailure { Log.w(TAG, "Couldn't refresh ${source.categoryId}", it) }
                result.getOrNull()?.let {
                    loaded[source.categoryId] = it
                    publish() // free the old set before building the next one
                }
                if (result.isFailure) ok = false
            }
            ok
        }
    }

    private fun publish() {
        context.geryon.blocks.setExtraCategoryDomains(HashMap<String, Set<String>>(loaded))
    }

    /** Returns the new list, or null if the server says it hasn't changed. */
    private fun download(source: BlocklistSource): DomainSet? {
        val etagFile = File(dir, "${source.categoryId}.etag")
        val haveList = packedFile(source).exists()
        var lastError: Exception? = null
        for (url in source.urls) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "geryon")
                if (haveList && etagFile.exists()) conn.setRequestProperty("If-None-Match", etagFile.readText())
                when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_NOT_MODIFIED -> return null
                    HttpURLConnection.HTTP_OK -> Unit
                    else -> throw IOException("HTTP $code from $url")
                }
                val set = conn.inputStream.bufferedReader(Charsets.UTF_8).use { DomainSet.parse(it) }
                if (set.size < source.minEntries) throw IOException("Only ${set.size} entries from $url")

                val file = AtomicFile(packedFile(source))
                val out = file.startWrite()
                try {
                    val buffered = out.buffered()
                    set.write(buffered)
                    buffered.flush()
                    file.finishWrite(out)
                } catch (e: Exception) {
                    file.failWrite(out)
                    throw e
                }
                val etag = conn.getHeaderField("ETag")
                if (etag != null) etagFile.writeText(etag) else etagFile.delete()
                Log.i(TAG, "${source.categoryId}: ${set.size} domains")
                return set
            } catch (e: Exception) {
                lastError = e
            } finally {
                conn.disconnect()
            }
        }
        throw lastError ?: IOException("No URL for ${source.categoryId}")
    }

    private fun packedFile(source: BlocklistSource) = File(dir, "${source.categoryId}.gds")

    companion object {
        private const val TAG = "BlocklistSync"
        private const val PERIODIC_WORK = "blocklists-refresh"
        private const val INITIAL_WORK = "blocklists-initial"

        private val lock = Mutex()

        private fun hagezi(name: String) = listOf(
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/$name-onlydomains.txt",
            "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/$name-onlydomains.txt",
            "https://codeberg.org/hagezi/mirror2/raw/branch/main/dns-blocklists/wildcard/$name-onlydomains.txt",
        )

        /**
         * HaGeZi's maintained lists (GPL-3.0), "onlydomains" variant: one domain per line,
         * meant to match subdomains too, which is how the catalogue matches.
         * Category ids must exist in assets/catalog.json, or the lists are ignored.
         */
        val SOURCES = listOf(
            BlocklistSource("adult", hagezi("nsfw"), minEntries = 1_000),
            BlocklistSource("gambling", hagezi("gambling"), minEntries = 1_000),
            BlocklistSource("doh", hagezi("doh"), minEntries = 100),
        )

        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Call once at app start: loads cached lists, downloads them now if any is missing,
         * and schedules the daily refresh.
         */
        fun start(context: Context) {
            val app = context.applicationContext
            appScope.launch {
                val sync = get(app)
                sync.loadCached()
                val wm = WorkManager.getInstance(app)
                val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                if (!sync.hasAllLists()) {
                    wm.enqueueUniqueWork(
                        INITIAL_WORK,
                        ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<BlocklistWorker>().setConstraints(online).build(),
                    )
                }
                wm.enqueueUniquePeriodicWork(
                    PERIODIC_WORK,
                    ExistingPeriodicWorkPolicy.KEEP,
                    PeriodicWorkRequestBuilder<BlocklistWorker>(1, TimeUnit.DAYS)
                        .setConstraints(online)
                        .setInitialDelay(1, TimeUnit.DAYS)
                        .build(),
                )
            }
        }

        @Volatile private var instance: BlocklistSync? = null

        /** One instance per process, so loaded sets are never held twice. */
        fun get(context: Context): BlocklistSync = instance ?: synchronized(this) {
            instance ?: BlocklistSync(context.applicationContext).also { instance = it }
        }
    }
}

/** Refreshes the lists; retried with backoff if any source failed. */
class BlocklistWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = BlocklistSync.get(applicationContext)
        sync.loadCached()
        return if (sync.refresh() || runAttemptCount >= 3) Result.success() else Result.retry()
    }
}
