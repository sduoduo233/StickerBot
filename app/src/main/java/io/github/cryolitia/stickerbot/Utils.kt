package io.github.cryolitia.stickerbot

import android.content.Context
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.datastore.core.IOException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.append
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class Utils {
}


suspend fun HttpClient.getTelegramFile(token: String, fileId: String): TelegramFileDownloadResult {
    val response2 = get("https://api.telegram.org/bot$token/getFile") {
        url { parameters.append("file_id", fileId) }
        method = HttpMethod.Get
        headers { append(HttpHeaders.Accept, ContentType.Application.Json) }
    }
    if (!response2.status.isSuccess()) {
        throw IOException("Failed to get file: ${response2.status}")
    }

    val file: TelegramResult<TelegramFile> = response2.body()
    if (!file.ok || file.result == null) {
        throw IOException("Failed to get file: ${file.description}")
    }

    val response3 = get("https://api.telegram.org/file/bot$token/${file.result.file_path}")
    if (!response3.status.isSuccess()) {
        throw IOException("Failed to get file: ${response3.status}")
    }

    val bytes = response3.bodyAsBytes()

    return TelegramFileDownloadResult(
        bytes,
        file.result.file_unique_id,
        file.result.file_path ?: ""
    )
}

context(context: Context)
suspend fun String.toast() {
    withContext(Dispatchers.Main) {
        Toast.makeText(context, this@toast, Toast.LENGTH_LONG).show()
    }
}

context(context: Context)
suspend fun String.alert() {
    withContext(Dispatchers.Main) {
        MaterialAlertDialogBuilder(context)
            .setMessage(this@alert)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }
}

context(context: Context)
suspend fun Throwable.alert() {
    withContext(Dispatchers.Main) {
        val textView = TextView(context)
        textView.text = this@alert.stackTraceToString()
        textView.movementMethod = ScrollingMovementMethod()
        textView.setHorizontallyScrolling(true)
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            32F,
            context.resources.displayMetrics
        ).toInt()
        textView.setPadding(px, px, px, 0)
        MaterialAlertDialogBuilder(context)
            .setTitle(this@alert.toString())
            .setView(textView)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }
}

context(context: Context)
suspend fun Pair<String, String>.alert() {
    withContext(Dispatchers.Main) {
        MaterialAlertDialogBuilder(context)
            .setTitle(this@alert.first)
            .setMessage(this@alert.second)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }
}


context(context: Context)
suspend fun loadingDialog(): AlertDialog {
    return withContext(Dispatchers.Main) {
        val linearProgressIndicator = LinearProgressIndicator(context).apply {
            isIndeterminate = true
        }
        val prepareDialog = MaterialAlertDialogBuilder(context)
            .setTitle("Loading……")
            .setView(linearProgressIndicator)
            .setCancelable(false)
            .create()
        prepareDialog.show()
        val params = linearProgressIndicator.layoutParams
        if (params is ViewGroup.MarginLayoutParams) {
            val dp = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                16F,
                context.resources.displayMetrics
            ).toInt()
            params.setMargins(dp, dp, dp, 0)
            linearProgressIndicator.layoutParams = params
        }
        return@withContext prepareDialog
    }
}

fun File.safetyListFiles(): Array<File> = listFiles() ?: arrayOf()
