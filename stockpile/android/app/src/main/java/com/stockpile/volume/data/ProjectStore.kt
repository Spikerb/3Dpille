package com.stockpile.volume.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Projects live in app storage: projects/<id>/project.json plus JPEG files in projects/<id>/photos.
 * One instance per process, shared by the UI and the upload worker.
 */
class ProjectStore private constructor(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _projects = MutableStateFlow(loadAll())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    fun dir(id: String) = File(root, id)
    fun photosDir(id: String) = File(dir(id), "photos").apply { mkdirs() }

    private fun loadAll(): List<Project> =
        (root.listFiles() ?: emptyArray())
            .mapNotNull { d ->
                runCatching { json.decodeFromString<Project>(File(d, "project.json").readText()) }.getOrNull()
            }
            .sortedByDescending { it.createdAt }

    private fun write(p: Project) {
        val d = dir(p.id).apply { mkdirs() }
        val tmp = File(d, "project.json.tmp")
        tmp.writeText(json.encodeToString(p))
        tmp.renameTo(File(d, "project.json"))
    }

    private fun publish(p: Project) {
        _projects.value = (_projects.value.filterNot { it.id == p.id } + p).sortedByDescending { it.createdAt }
    }

    fun get(id: String): Project? = _projects.value.firstOrNull { it.id == id }

    @Synchronized
    fun create(name: String, densityTM3: Double?): Project {
        val p = Project(
            id = UUID.randomUUID().toString().replace("-", ""),
            name = name.trim(),
            densityTM3 = densityTM3,
            createdAt = System.currentTimeMillis(),
        )
        write(p); publish(p)
        return p
    }

    @Synchronized
    fun update(id: String, change: (Project) -> Project): Project? {
        val current = get(id) ?: return null
        val p = change(current)
        write(p); publish(p)
        return p
    }

    @Synchronized
    fun delete(id: String) {
        dir(id).deleteRecursively()
        _projects.value = _projects.value.filterNot { it.id == id }
    }

    /** Reserves the next photo file name (IMG_0001.jpg, IMG_0002.jpg, ...). */
    @Synchronized
    fun newPhotoFile(id: String): File {
        val p = get(id) ?: error("Unknown project")
        update(id) { it.copy(nextPhotoIndex = it.nextPhotoIndex + 1) }
        return File(photosDir(id), "IMG_%04d.jpg".format(p.nextPhotoIndex))
    }

    fun addPhoto(id: String, fileName: String, heading: Float) {
        update(id) { it.copy(photoHeadings = it.photoHeadings + (fileName to heading)) }
    }

    fun removePhoto(id: String, fileName: String) {
        File(photosDir(id), fileName).delete()
        update(id) { it.copy(photoHeadings = it.photoHeadings - fileName, uploaded = it.uploaded - fileName) }
    }

    /** Photos that belong to the project (ignores half-written files). */
    fun photos(id: String): List<File> {
        val names = get(id)?.photoHeadings?.keys ?: return emptyList()
        return names.sorted().map { File(photosDir(id), it) }.filter { it.exists() && it.length() > 0 }
    }

    companion object {
        @Volatile private var instance: ProjectStore? = null
        fun get(context: Context): ProjectStore =
            instance ?: synchronized(this) {
                instance ?: ProjectStore(context.applicationContext).also { instance = it }
            }
    }
}
