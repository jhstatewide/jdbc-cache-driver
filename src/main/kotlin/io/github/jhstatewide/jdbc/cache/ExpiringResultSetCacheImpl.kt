package io.github.jhstatewide.jdbc.cache

import io.github.jhstatewide.jdbc.cache.locks.LockCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger
import kotlin.concurrent.withLock


internal class ExpiringResultSetCacheImpl(cacheDirectory: Path) : ExpiringResultSetCache, ResultSetOnDiskCacheImpl(
    cacheDirectory
) {

    var _maxSize: Int? = null
    var _maxAge: Duration? = null

    private val garbageCollectionLock = ReentrantLock()
    private val keyLockCoordinator = LockCoordinator()

    constructor(cacheDirectory: Path, maxSize: Int?, maxAge: Duration?) : this(cacheDirectory) {
        this._maxSize = maxSize
        this._maxAge = maxAge
    }

    private val defaultGarbageCollectionInterval: Duration = Duration.ofHours(1)

    private val logger = Logger.getLogger(ExpiringResultSetCacheImpl::class.java.name)

    private var garbageCollectionInterval: Duration = defaultGarbageCollectionInterval

    private val garbageCollectionClosure = {
        while (true) {
            Thread.sleep(defaultGarbageCollectionInterval.toMillis())
            garbageCollection()
        }
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    init {
        // start a thread with the garbage collection closure
        Thread(garbageCollectionClosure).start()
    }

    override fun setMaxSize(maxSize: Int?) {
        this._maxSize = maxSize
    }

    override fun getMaxSize(): Int? {
        return this._maxSize
    }

    override fun setGcInterval(gcInterval: Long) {
        this.garbageCollectionInterval = Duration.ofMillis(gcInterval)
    }

    override fun setMaxAge(maxAge: Duration?) {
        this._maxAge = maxAge
    }

    private fun garbageCollection() {
        // use the lock to prevent multiple threads from running garbage collection at the same time
        garbageCollectionLock.withLock {
            // look at each file in the cache directory
            jdbcCacheFiles()
                .forEach { file ->
                    keyLockCoordinator.withLock(file.name) {
                        // if the file is older than the max age, delete it
                        if (Duration.ofMillis(file.lastModified()).plus(_maxAge).isNegative) {
                            file.delete()
                            ExpirationEventBus.keyExpired(file.name)
                        }
                    }

                }
            // if the cache is larger than the max size, delete the oldest file
            if (isAboveMaxSize()) {
                // get the oldest file
                val oldestFile = jdbcCacheFiles()
                    .minByOrNull { it.lastModified() }

                if (oldestFile == null) {
                    logger.warning("Cache is above max size, but no files were found to delete.")
                    return
                }

                // delete the oldest file
                keyLockCoordinator.withLock(oldestFile.name) {
                    oldestFile.apply { this.delete() }.also {
                        // notify listeners that the key has expired
                        it?.name?.let { fileName -> ExpirationEventBus.keyExpired(fileName) }
                    }
                }
            }
        }
    }

    private fun isAboveMaxSize(): Boolean {
        getMaxSize()?.let { currentMaxSize ->
            return size() > currentMaxSize
        }
        return false
    }

    private fun jdbcCacheFiles() = this.cacheDirectory.toFile()
        .listFiles()
        ?.filterNotNull()
        // filter by files that end in the JDBC cache file extension
        ?.filter { it.name.endsWith(JDBC_CACHE_EXTENSION) } ?: emptyList()

    // overwrite set because if we are above the max size, we need to garbage collect
    override fun <T> get(
        statement: CachedStatement<*>?,
        key: String?,
        resultSetProvider: ResultSetCache.Provider?
    ): CachedOnDiskResultSet? {
        try {
            possiblyPurge(key)
        } catch (e: Exception) {
            logger.warning("Error purging cache file: ${e.localizedMessage}")
        }

        if (key == null) {
            return null
        }

        val resultSet =
            keyLockCoordinator.withLock<CachedOnDiskResultSet?>(key) {
                super.get<T>(statement, key, resultSetProvider)
            }

        // if GC is not running, start it
        if (!this.garbageCollectionLock.isLocked) {
            coroutineScope.launch {
                if (isAboveMaxSize()) {
                    garbageCollection()
                }
            }
        }

        return resultSet
    }

    private fun possiblyPurge(key: String?) {
        if (key == null) {
            return
        }

        // ensure the file exists
        if (!this.cacheDirectory.resolve(key).toFile().exists()) {
            return
        }

        // get the creation time of the file
        val lastModifiedTime = key.let { this.cacheDirectory.resolve(it).toFile().lastModified() }
        // determine if the file is older than the max age
        val fileAge = Duration.ofMillis(System.currentTimeMillis() - lastModifiedTime)

        // log the age of the oldest file and the max age
        logger.finer("Age of the file: ${fileAge.toMillis()}")
        logger.finer("Max age: ${_maxAge?.toMillis()}")

        val didExpire = if (fileAge != null && _maxAge != null) {
            fileAge > _maxAge
        } else {
            false
        }

        if (didExpire) {
            logger.finer("Purging file: $key")
            // delete the file
            keyLockCoordinator.withLock(key) {
                this.cacheDirectory.resolve(key).toFile().delete()
                // notify listeners that the key has expired
                ExpirationEventBus.keyExpired(key)
            }
        }
    }

}