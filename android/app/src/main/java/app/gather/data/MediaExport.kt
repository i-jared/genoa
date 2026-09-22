package app.gather.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.InputStream

object MediaExport {
    fun save(context: Context, input: InputStream, name: String, mime: String, kind: String) {
        val collection =
            when (kind) {
                "photo" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        val folder =
            when (kind) {
                "photo" -> Environment.DIRECTORY_PICTURES
                "audio" -> Environment.DIRECTORY_MUSIC
                else -> Environment.DIRECTORY_MOVIES
            }
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Genoa")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri =
            context.contentResolver.insert(collection, values)
                ?: error("Unable to create the saved file")
        try {
            context.contentResolver.openOutputStream(uri)!!.use { input.copyTo(it) }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
    }
}
