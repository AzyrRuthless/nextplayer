package dev.anilbeesetti.nextplayer.core.media.sync

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import coil3.ImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.common.Dispatcher
import dev.anilbeesetti.nextplayer.core.common.NextDispatchers
import dev.anilbeesetti.nextplayer.core.common.di.ApplicationScope
import dev.anilbeesetti.nextplayer.core.common.extensions.VIDEO_COLLECTION_URI
import dev.anilbeesetti.nextplayer.core.common.extensions.getStorageVolumes
import dev.anilbeesetti.nextplayer.core.common.extensions.prettyName
import dev.anilbeesetti.nextplayer.core.common.extensions.scanPaths
import dev.anilbeesetti.nextplayer.core.common.extensions.scanStorage
import dev.anilbeesetti.nextplayer.core.database.converter.UriListConverter
import dev.anilbeesetti.nextplayer.core.database.dao.DirectoryDao
import dev.anilbeesetti.nextplayer.core.database.dao.MediumDao
import dev.anilbeesetti.nextplayer.core.database.dao.MediumStateDao
import dev.anilbeesetti.nextplayer.core.database.entities.DirectoryEntity
import dev.anilbeesetti.nextplayer.core.database.entities.MediumEntity
import dev.anilbeesetti.nextplayer.core.datastore.datasource.AppPreferencesDataSource
import dev.anilbeesetti.nextplayer.core.media.model.MediaVideo
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
class LocalMediaSynchronizer @Inject constructor(
    private val mediumDao: MediumDao,
    private val mediumStateDao: MediumStateDao,
    private val directoryDao: DirectoryDao,
    private val imageLoader: ImageLoader,
    private val appPreferencesDataSource: AppPreferencesDataSource,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @ApplicationContext private val context: Context,
    @Dispatcher(NextDispatchers.IO) private val dispatcher: CoroutineDispatcher,
) : MediaSynchronizer {

    private var mediaSyncingJob: Job? = null

    private val _syncProgress = MutableStateFlow(-1)
    override val syncProgress: StateFlow<Int> = _syncProgress.asStateFlow()

    override suspend fun refresh(path: String?): Boolean {
        return path?.let { context.scanPaths(listOf(path)) }
            ?: context.getStorageVolumes().all { context.scanStorage(it.path) }
    }

    override fun startSync() {
        if (mediaSyncingJob != null) return
        mediaSyncingJob = combine(
            getMediaVideosFlow(),
            appPreferencesDataSource.preferences,
            ::Pair
        ).mapLatest { (media, preferences) ->
            withContext(dispatcher) {
                getFinalMediaList(media, preferences)
            }
        }.onEach { finalMediaList ->
            applicationScope.launch { updateDirectories(finalMediaList) }
            applicationScope.launch { updateMedia(finalMediaList) }
        }.launchIn(applicationScope)
    }

    override fun stopSync() {
        mediaSyncingJob?.cancel()
    }

    private suspend fun updateDirectories(media: List<MediaVideo>) =
        withContext(Dispatchers.Default) {
            val directories = context.getStorageVolumes().flatMap {
                getDirectoryEntities(currentFolder = it, media = media)
            }
            directoryDao.upsertAll(directories)

            val currentDirectoryPaths = directories.map { it.path }

            val unwantedDirectories = directoryDao.getAll().first()
                .filterNot { it.path in currentDirectoryPaths }

            val unwantedDirectoriesPaths = unwantedDirectories.map { it.path }

            directoryDao.delete(unwantedDirectoriesPaths)
        }

    private fun getDirectoryEntities(
        parentFolder: File? = null,
        currentFolder: File,
        media: List<MediaVideo>,
    ): List<DirectoryEntity> {
        val hasMediaInCurrentFolder = media.any { it.data.startsWith("${currentFolder.path}/") }
        if (!hasMediaInCurrentFolder) return emptyList()

        val currentDirectoryEntity = DirectoryEntity(
            path = currentFolder.path,
            name = currentFolder.prettyName,
            modified = currentFolder.lastModified(),
            parentPath = parentFolder?.path ?: "/",
        )

        // Use media file paths instead of File.listFiles() to derive subdirectories.
        // This avoids Android 11+ Scoped Storage restrictions where listFiles() returns null
        // for directories we cannot enumerate directly without MANAGE_EXTERNAL_STORAGE.
        val subDirectories = media
            .filter { it.data.startsWith("${currentFolder.path}/") }
            .mapNotNull { video ->
                val remaining = video.data.removePrefix("${currentFolder.path}/")
                val segment = remaining.substringBefore("/")
                if ("/" in remaining) File(currentFolder, segment) else null
            }
            .distinctBy { it.path }
            .flatMap { file ->
                getDirectoryEntities(
                    parentFolder = currentFolder,
                    currentFolder = file,
                    media = media,
                )
            }

        return listOf(currentDirectoryEntity) + subDirectories
    }

    private suspend fun updateMedia(media: List<MediaVideo>) = withContext(Dispatchers.Default) {
        val mediumEntities = media.map {
            val file = File(it.data)
            val mediumEntity = mediumDao.get(it.uri.toString())
            mediumEntity?.copy(
                path = file.path,
                name = file.name,
                size = it.size,
                width = it.width,
                height = it.height,
                duration = it.duration,
                mediaStoreId = it.id,
                modified = it.dateModified,
                parentPath = file.parent!!,
            ) ?: MediumEntity(
                uriString = it.uri.toString(),
                path = it.data,
                name = file.name,
                parentPath = file.parent!!,
                modified = it.dateModified,
                size = it.size,
                width = it.width,
                height = it.height,
                duration = it.duration,
                mediaStoreId = it.id,
            )
        }

        mediumDao.upsertAll(mediumEntities)

        val currentMediaUris = mediumEntities.map { it.uriString }

        val unwantedMedia = mediumDao.getAllWithInfo().first()
            .filterNot { it.mediumEntity.uriString in currentMediaUris }

        val unwantedMediaUris = unwantedMedia.map { it.mediumEntity.uriString }

        mediumDao.delete(unwantedMediaUris)
        mediumStateDao.delete(unwantedMediaUris)

        // Delete unwanted thumbnails
        unwantedMedia.forEach { media ->
            try {
                imageLoader.diskCache?.remove(media.mediumEntity.uriString)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Release external subtitle uri permission if not used by any other media
        launch {
            val currentMediaExternalSubs = mediumEntities.flatMap {
                val mediaState = mediumStateDao.get(it.uriString) ?: return@flatMap emptyList<String>()
                UriListConverter.fromStringToList(mediaState.externalSubs)
            }.toSet()

            unwantedMedia.onEach { mediumWithInfo ->
                val mediumState = mediumWithInfo.mediumStateEntity ?: return@onEach
                for (sub in UriListConverter.fromStringToList(mediumState.externalSubs)) {
                    if (sub !in currentMediaExternalSubs) {
                        try {
                            context.contentResolver.releasePersistableUriPermission(sub, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        }
    }

    private fun getMediaVideosFlow(
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortOrder: String? = "${MediaStore.Video.Media.DISPLAY_NAME} ASC",
    ): Flow<List<MediaVideo>> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(getMediaVideo(selection, selectionArgs, sortOrder))
            }
        }
        context.contentResolver.registerContentObserver(VIDEO_COLLECTION_URI, true, observer)
        // initial value
        trySend(getMediaVideo(selection, selectionArgs, sortOrder))
        // close
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.flowOn(dispatcher).distinctUntilChanged()

    private fun getMediaVideo(
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): List<MediaVideo> {
        val mediaVideos = mutableListOf<MediaVideo>()
        context.contentResolver.query(
            VIDEO_COLLECTION_URI,
            VIDEO_PROJECTION,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->

            val idColumn = cursor.getColumnIndex(MediaStore.Video.Media._ID)
            val dataColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
            val durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            val widthColumn = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val heightColumn = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            val sizeColumn = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
            val dateModifiedColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                mediaVideos.add(
                    MediaVideo(
                        id = id,
                        data = cursor.getString(dataColumn),
                        duration = cursor.getLong(durationColumn),
                        uri = ContentUris.withAppendedId(VIDEO_COLLECTION_URI, id),
                        width = cursor.getInt(widthColumn),
                        height = cursor.getInt(heightColumn),
                        size = cursor.getLong(sizeColumn),
                        dateModified = cursor.getLong(dateModifiedColumn),
                    ),
                )
            }
        }
        return mediaVideos.filter { File(it.data).exists() }
    }

    private suspend fun getFinalMediaList(
        mediaStoreList: List<MediaVideo>,
        preferences: ApplicationPreferences
    ): List<MediaVideo> {
        // No supplementary scan needed: MediaStore behavior already matches defaults.
        if (!preferences.showHidden && preferences.recognizeNomedia) {
            return mediaStoreList
        }

        val manualList = scanStorageForVideos(preferences)

        val mediaByPath = mediaStoreList.associateBy { it.data }.toMutableMap()

        val missingVideos = manualList.filter { !mediaByPath.containsKey(it.data) }
        val missingCount = missingVideos.size
        var processedCount = 0

        if (missingCount > 0) {
            _syncProgress.value = 0
        }

        try {
            for (manualVideo in missingVideos) {
                // Cooperative cancellation point. Note: if retriever.setDataSource blocks or hangs
                // (e.g., due to corrupt files or slow IO), cancellation will be delayed until this yield().
                kotlinx.coroutines.yield()
                val updatedVideo = try {
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(manualVideo.data)
                        val duration = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                        val width = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                            ?.toIntOrNull() ?: 0
                        val height = retriever
                            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                            ?.toIntOrNull() ?: 0
                        manualVideo.copy(duration = duration, width = width, height = height)
                    }
                } catch (e: Throwable) {
                    manualVideo
                }
                mediaByPath[updatedVideo.data] = updatedVideo
                processedCount++
                _syncProgress.value = (processedCount * 100) / missingCount
            }
        } finally {
            if (missingCount > 0) {
                _syncProgress.value = -1
            }
        }

        return mediaByPath.values.toList()
    }

    private fun scanStorageForVideos(preferences: ApplicationPreferences): List<MediaVideo> {
        val result = mutableListOf<MediaVideo>()
        val volumes = context.getStorageVolumes()
        val volumeRoots = volumes.map { it.path }.toSet()
        for (volume in volumes) {
            scanDirectory(volume, VIDEO_EXTENSIONS, preferences, result, volumeRoots)
        }
        return result
    }

    private fun File.isHiddenPath(volumeRoots: Set<String>): Boolean {
        var current: File? = this
        while (current != null && current.path !in volumeRoots) {
            if (current.name.startsWith(".")) {
                return true
            }
            current = current.parentFile
        }
        return false
    }

    private fun scanDirectory(
        dir: File,
        extensions: Set<String>,
        preferences: ApplicationPreferences,
        result: MutableList<MediaVideo>,
        volumeRoots: Set<String>
    ) {
        if (!dir.exists() || !dir.isDirectory) return

        val isVolumeRoot = dir.path in volumeRoots
        val isRootAndroid = dir.name.equals("Android", ignoreCase = true) &&
            dir.parent in volumeRoots
        if (isRootAndroid) return

        val isHidden = dir.isHiddenPath(volumeRoots)
        if (!preferences.showHidden && isHidden) return

        val files = dir.listFiles() ?: return

        // Skip dirs that contain .nomedia, UNLESS:
        // - This is a volume root (root-level .nomedia should not block the entire storage), OR
        // - showHidden is true AND this is a hidden path (user explicitly wants to see it).
        if (preferences.recognizeNomedia && !isVolumeRoot) {
            val shouldRespectNomedia = !(preferences.showHidden && isHidden)
            if (shouldRespectNomedia) {
                val hasNomedia = files.any { it.name.equals(".nomedia", ignoreCase = true) }
                if (hasNomedia) return
            }
        }

        for (file in files) {
            if (file.isDirectory) {
                scanDirectory(file, extensions, preferences, result, volumeRoots)
            } else {
                if (!preferences.showHidden && file.name.startsWith(".")) continue
                val ext = file.extension.lowercase()
                if (ext in extensions) {
                    val size = file.length()
                    if (size > 0) {
                        val uri = android.net.Uri.fromFile(file)
                        // Use a synthetic 64-bit ID derived from the file path, forced negative to avoid MediaStore ID collisions.
                        val id = java.util.UUID.nameUUIDFromBytes(file.path.toByteArray()).mostSignificantBits or Long.MIN_VALUE
                        result.add(
                            MediaVideo(
                                id = id,
                                data = file.path,
                                duration = 0L,
                                uri = uri,
                                width = 0,
                                height = 0,
                                size = size,
                                dateModified = file.lastModified() / 1000L,
                            )
                        )
                    }
                }
            }
        }
    }

    companion object {
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "webm", "avi", "flv", "3gp", "3g2", "ts", "mts", "m2ts", "vob", "ogv", "mov", "qt", "wmv", "asf"
        )

        val VIDEO_PROJECTION = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
        )
    }
}
