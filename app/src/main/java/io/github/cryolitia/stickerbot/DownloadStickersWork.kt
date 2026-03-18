package io.github.cryolitia.stickerbot

import android.content.Context
import android.content.Intent
import android.text.style.TtsSpan
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.google.firebase.appindexing.FirebaseAppIndex
import com.google.firebase.appindexing.builders.Indexables
import com.google.firebase.appindexing.builders.StickerBuilder
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.append
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.apache.commons.io.IOUtils
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.min

class DownloadStickersWork(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {

        try {
            val context = applicationContext
            var stickersUrl = inputData.getString("STICKERS_URL") ?: ""

            // input validation

            val token = context.getPreference(stringPreferencesKey(TELEGRAM_BOT_TOKEN)) ?: ""
            if (token.isBlank()) {
                return Result.failure(
                    Data.Builder().putString("error", "Telegram token is not set.").build()
                )
            }
            if (stickersUrl.isBlank()) {
                return Result.failure(
                    Data.Builder().putString("error", "Stickers URL is blank.").build()
                )
            }

            val match = Regex("^https://t\\.me/(addemoji|addstickers)/(.+)$").find(stickersUrl)
            if (match != null) {
                stickersUrl = match.groupValues[2]
            } else {
                return Result.failure(
                    Data.Builder().putString("error", "Stickers URL is invalid.").build()
                )
            }

            // get app settings

            val useProxy = context.getPreference(booleanPreferencesKey(USE_HTTP_PROXY), false)
            var httpProxy = ""
            if (useProxy) {
                httpProxy = context.getPreference(stringPreferencesKey(HTTP_PROXY), "")
                if (httpProxy.isBlank()) {
                    return Result.failure(
                        Data.Builder().putString(
                            "error",
                            "Proxy is enabled but not set, please check in settings!"
                        ).build()
                    )
                }
            }

            val maxSize = context.getPreference(
                stringPreferencesKey(LIMIT_SIZE),
                "512"
            ).toFloatOrNull() ?: 512.0F

            // initialize http client

            val client = HttpClient(OkHttp) {
                install(ContentNegotiation) {
                    json(Json {
                        ignoreUnknownKeys = true
                    })
                }
                if (useProxy) {
                    engine {
                        proxy = ProxyBuilder.http(httpProxy)
                    }
                }
            }

            // get stickers set

            val response = client.get("https://api.telegram.org/bot$token/getStickerSet") {
                url {
                    parameters.append("name", stickersUrl)
                }
                method = HttpMethod.Get
                headers {
                    append(HttpHeaders.Accept, ContentType.Application.Json)
                }
            }

            if (!response.status.isSuccess()) {
                return Result.failure(
                    Data.Builder().putString(
                        "error",
                        "get sticker set \n" + response.status.toString() + " " + response.body<String>()
                    ).build()
                )
            }

            val stickerSetResultBody: TelegramResult<StickerSet> = response.body()
            val stickerSetResult = stickerSetResultBody.result
            if (!stickerSetResultBody.ok || stickerSetResult == null) {
                return Result.failure(
                    Data.Builder().putString("error", stickerSetResultBody.toString()).build()
                )
            }

            val stickersDirectory = File(context.getExternalFilesDir(null), "Stickers")
            val stickerSetDirectory = File(stickersDirectory, stickerSetResult.name)
            if (!stickerSetDirectory.exists()) stickerSetDirectory.mkdirs()

            if (stickerSetResult.thumb != null) {
                val (data, _, filePath) = client.getTelegramFile(token, stickerSetResult.thumb.file_id)

                val extension = filePath.substring(filePath.lastIndexOf('.'))
                val stickerFile = File(stickerSetDirectory, "thumb$extension")
                if (!stickerFile.exists()) withContext(Dispatchers.IO) {
                    stickerFile.createNewFile()
                }
                stickerFile.writeBytes(data)
            }

            // download stickers

            val stickerPackBuilder = Indexables.stickerPackBuilder()
                .setName(stickerSetResult.title)
                .setUrl("cryolitia://stickerset/${stickerSetResult.name}")
            val stickerList = mutableListOf<StickerBuilder>()

            setProgress(Data.Builder().putFloat("progress", 0f).build())

            for ((i, sticker) in stickerSetResult.stickers.withIndex()) {
                var (stickerBytes, fileUniqueId, filePath) = client.getTelegramFile(token, sticker.file_id)

                lateinit var stickerFile: File

                Log.d("DownloadStickersWork", "download sticker ${sticker.file_unique_id}, animated = ${sticker.is_animated}, video = ${sticker.is_video}")

                if (sticker.is_animated || sticker.is_video) {

                    withContext(Dispatchers.IO) {
                        var encoder: FFmpegEncoder? = null

                        try {
                            // .tgs file
                            if (sticker.is_animated) {
                                val input = ByteArrayInputStream(stickerBytes)
                                val gzip = GZIPInputStream(input)
                                stickerBytes = IOUtils.toByteArray(gzip)
                                input.close()
                                gzip.close()
                            }

                            stickerFile = File(stickerSetDirectory, "$fileUniqueId.gif")
                            encoder = FFmpegEncoder(
                                stickerFile,
                                this,
                                context,
                                { log -> log.print() },
                                { statistics ->
                                    Log.d("DownloadStickersWork", statistics.toFormatedString())
                                })
                            encoder.start()

                            if (sticker.is_animated) {
                                // .tgs file decode

                                val decoder = LottieStickerDecoder(
                                    stickerBytes,
                                    context,
                                    sticker.file_unique_id
                                )
                                decoder.start()

                                val width = decoder.getWidth()
                                val height = decoder.getHeight()
                                val frames = decoder.getDuration()
                                val scaleFactor = minOf(
                                    min(maxSize, sticker.width.toFloat()) / width,
                                    min(maxSize, sticker.height.toFloat()) / height,
                                    1.0F
                                )

                                try {

                                    decoder.getFrames { bitmap, idx, rate ->

                                        encoder.rate = rate
                                        encoder.addFrame(
                                            bitmap.scale(
                                                (width * scaleFactor).toInt(),
                                                (height * scaleFactor).toInt()
                                            )
                                        )

                                        setProgressAsync(
                                            Data.Builder()
                                                .putFloat("progress", idx.toFloat() / frames.toFloat())
                                                .putString("sub_progress", stickerFile.absolutePath)
                                                .build()
                                        )

                                    }

                                    encoder.process()
                                } finally {
                                    decoder.end()
                                    encoder.end()
                                }
                            } else {

                                // video sticker

                                encoder.process(stickerBytes, maxSize.toInt()) { previewFile ->
                                    Log.d("DownloadStickersWork", "video sticker preview ${previewFile.absolutePath}")
                                }
                            }
                        } finally {
                            encoder?.end()
                        }
                    }
                } else {

                    // not animated or video

                    val extension = filePath.substring(filePath.lastIndexOf('.'))
                    stickerFile = File(stickerSetDirectory, fileUniqueId + extension)
                    withContext(Dispatchers.IO) {
                        if (stickerFile.exists()) stickerFile.delete()
                        stickerFile.createNewFile()
                        stickerFile.writeBytes(stickerBytes)
                    }
                }

                if (!sticker.is_video) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "io.github.cryolitia.stickerbot.stickerprovider",
                        stickerFile
                    )
                    context.grantUriPermission(
                        "com.google.android.inputmethod.latin",
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    )

                    val stickerBuilder = Indexables.stickerBuilder().apply {
                        setName(sticker.file_unique_id)
                        setUrl("cryolitia://stickerset/${stickerSetResult.name}/${sticker.file_unique_id}")
                        setImage(uri.toString())
                        if (!sticker.emoji.isNullOrBlank()) {
                            setDescription(sticker.emoji)
                            setKeywords(sticker.emoji)
                            setIsPartOf(
                                Indexables.stickerPackBuilder()
                                    .setName(stickerSetResult.title)
                            )
                        }
                    }

                    stickerList.add(stickerBuilder)
                }

                Log.d("DownloadStickersWork", "sticker ${sticker.file_unique_id} downloaded to ${stickerFile.absolutePath}")

                setProgressAsync(
                    Data.Builder()
                        .putFloat("progress", i.toFloat() / stickerSetResult.stickers.size)
                        .putString("latest", stickerFile.absolutePath)
                        .putString("emoji", sticker.emoji)
                        .build()
                )
            }

            if (stickerList.isNotEmpty()) {
                stickerPackBuilder.setHasSticker(*stickerList.toTypedArray())
                FirebaseAppIndex.getInstance(context).update(
                    stickerPackBuilder.build(),
                    *stickerList.map {
                        it.build()
                    }.toTypedArray()
                )
            }

            val stickerSetMetadata = Json.encodeToString(stickerSetResult)
            val metadataDirectory = File(context.getExternalFilesDir(null), "Metadata")
            if (!metadataDirectory.exists()) {
                metadataDirectory.mkdirs()
            }
            val metadataFile = File(metadataDirectory, "${stickerSetResult.name}.json")
            if (!metadataFile.exists()) {
                withContext(Dispatchers.IO) {
                    metadataFile.createNewFile()
                    metadataFile.writeText(stickerSetMetadata)
                }
            }


            setProgressAsync(
                Data.Builder()
                    .putFloat("progress", 1f)
                    .build()
            )

            return Result.success();
        } catch (e: Exception) {
            Log.e("DownloadStickersWork", "error", e);
            val errorString = e.javaClass.simpleName + ": " + e.message + "\n\n" + Log.getStackTraceString(e);
            return Result.failure(Data.Builder().putString("error", errorString).build())
        }

    }
}