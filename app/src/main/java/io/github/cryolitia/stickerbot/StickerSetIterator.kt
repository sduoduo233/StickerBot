package io.github.cryolitia.stickerbot

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * List all downloaded sticker sets
 * @return list of ((directory, metadata file), StickerSet?, thumb image)
 */
fun iterateStickerSet(context: Context): ArrayList<Triple<Pair<File, File>, StickerSet?, File?>> {
    val stickersDirectory = File(context.getExternalFilesDir(null), "Stickers")
    if (!stickersDirectory.exists()) {
        stickersDirectory.mkdirs()
    }

    val arrayList = ArrayList<Triple<Pair<File, File>, StickerSet?, File?>>(stickersDirectory.safetyListFiles().size)

    for (directory in stickersDirectory.safetyListFiles()) {
        if (directory.isDirectory) {
            val metadataFile = File(
                context.getExternalFilesDir(null),
                "Metadata/${directory.name}.json"
            )
            var metadata: StickerSet? = null

            if (metadataFile.exists()) {
                try {
                    metadata = Json.decodeFromString<StickerSet>(metadataFile.readText())
                } catch (e: Exception) {
                    Log.w("", e)
                }
            }
            var image: File? = null
            try {
                image = directory.listFiles { file ->
                    file.name.contains("thumb", true)
                }?.get(0)
            } catch (_: Exception) {
                //ignore
            }
            if (image == null) {
                try {
                    image = directory.listFiles()?.get(0)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            arrayList.add(Triple(Pair(directory, metadataFile), metadata, image))
        }
    }
    return arrayList
}