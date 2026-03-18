package io.github.cryolitia.stickerbot

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.PowerManager
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.core.view.WindowCompat
import androidx.core.widget.doAfterTextChanged
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.appindexing.FirebaseAppIndex
import com.google.firebase.appindexing.builders.Indexables
import com.google.firebase.appindexing.builders.StickerBuilder
import io.github.cryolitia.stickerbot.databinding.ActivityMainBinding
import io.github.cryolitia.stickerbot.databinding.DialogDownloadBinding
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.http
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.append
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.apache.commons.io.IOUtils
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.system.exitProcess


val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    lateinit var fab: FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {

        DynamicColors.applyToActivityIfAvailable(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        fab = binding.fab
        binding.fab.setOnClickListener {
            lifecycleScope.launch {
                val token = getPreference(stringPreferencesKey(TELEGRAM_BOT_TOKEN))
                if (token.isNullOrBlank()) {
                    with(this@MainActivity) {
                        "Please set telegram bot token in settings firstly.".toast()
                    }
                    return@launch
                }
                MaterialAlertDialogBuilder(this@MainActivity).run {
                    var textInputEditText: TextInputEditText
                    val textInputLayout: TextInputLayout = TextInputLayout(
                        this@MainActivity,
                        null,
                        com.google.android.material.R.attr.textInputOutlinedStyle
                    ).apply {
                        prefixText = "https://t.me/addstickers/"
                        textInputEditText = TextInputEditText(this.context)
                        addView(textInputEditText)
                    }
                    setTitle("Input Stickers link")
                    setView(textInputLayout)
                    setPositiveButton(
                        "OK"
                    ) { _, _ ->
                        val stickersUrl = textInputEditText.text.toString()
                        if (stickersUrl.isBlank()) {
                            lifecycleScope.launch {
                                with(this@MainActivity) {
                                    "Please input stickers link".toast()
                                }
                            }
                            return@setPositiveButton
                        }

                        val dialogBinding = DialogDownloadBinding.inflate(layoutInflater)
                        val progressDialog = MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("Downloading Stickers")
                            .setView(dialogBinding.root)
                            .setCancelable(false)
                            .setNegativeButton("Cancel") { _, _ ->
                                WorkManager.getInstance(this@MainActivity)
                                    .cancelAllWorkByTag(stickersUrl)
                            }
                            .create()
                        progressDialog.show()

                        val request = OneTimeWorkRequest.Builder(DownloadStickersWork::class.java)
                            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                            .addTag(stickersUrl)
                            .setInputData(
                                Data.Builder().putString("STICKERS_URL", stickersUrl).build()
                            )
                            .build()

                        val workManager = WorkManager.getInstance(this@MainActivity)
                        workManager.enqueue(request)

                        lifecycleScope.launch {
                            workManager.getWorkInfoByIdFlow(request.id).collect { workInfo ->
                                if (workInfo != null) {
                                    val progress = workInfo.progress.getFloat("progress", 0f)
                                    val subProgress = workInfo.progress.getString("sub_progress")
                                    val latest = workInfo.progress.getString("latest")

                                    if (subProgress != null) {
                                        dialogBinding.FrameLinearProgressIndicator.progress =
                                            (progress * 100).toInt()
                                        dialogBinding.FrameTextView.text =
                                            "${(progress * 100).toInt()}%"
                                        dialogBinding.FFmpegDetail.text =
                                            "Processing: ${File(subProgress).name}"
                                    } else {
                                        dialogBinding.linearProgressIndicator.progress =
                                            (progress * 100).toInt()
                                        dialogBinding.progressText.text =
                                            "${(progress * 100).toInt()}%"
                                    }

                                    if (latest != null) {
                                        dialogBinding.StickerDetail.text =
                                            "Latest: ${File(latest).name}"
                                        val bitmap = BitmapFactory.decodeFile(latest)
                                        if (bitmap != null) {
                                            dialogBinding.StickerImage.setImageBitmap(bitmap)
                                        }
                                    }

                                    if (workInfo.state.isFinished) {
                                        progressDialog.dismiss()
                                        with(this@MainActivity) {
                                            if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                                                "Download finished".toast()
                                            } else if (workInfo.state == WorkInfo.State.FAILED) {
                                                val error =
                                                    workInfo.outputData.getString("error")
                                                        ?: "Unknown error"
                                                error.alert()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
                    create()
                    show()
                }

            }
        }
    }

    override fun onResume() {
        super.onResume()
    }


    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}

data class TelegramFileDownloadResult(
    val data: ByteArray,
    val fileUniqueId: String,
    val filePath: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TelegramFileDownloadResult

        if (!data.contentEquals(other.data)) return false
        if (fileUniqueId != other.fileUniqueId) return false
        if (filePath != other.filePath) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + fileUniqueId.hashCode()
        result = 31 * result + filePath.hashCode()
        return result
    }
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
