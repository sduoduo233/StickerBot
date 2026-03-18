package io.github.cryolitia.stickerbot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.Data
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.cryolitia.stickerbot.databinding.DialogDownloadBinding
import io.github.cryolitia.stickerbot.databinding.FragmentMainBinding
import kotlinx.coroutines.launch
import java.io.File

private data class AdapterItem (
    val title: String,
    val name: String,
    val image: Bitmap?,
    val directory: File,
    val metadataFile: File
)

private class StickerSetAdapter(
    private val dataSet: Array<AdapterItem>,
    private val onClick: (AdapterItem) -> Unit = {},
) :
    RecyclerView.Adapter<StickerSetAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.image)
        val displayName: TextView = view.findViewById(R.id.display_name)
        val id: TextView = view.findViewById(R.id.sticker_id)
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(viewGroup.context).inflate(R.layout.item_stickerset, viewGroup, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val item = dataSet[position]
        viewHolder.imageView.setImageBitmap(item.image)
        viewHolder.displayName.text = item.title
        viewHolder.id.text = item.name
        viewHolder.itemView.isClickable = true
        viewHolder.itemView.setOnClickListener {
            onClick(item)
        }
    }

    override fun getItemCount() = dataSet.size
}

class FirstFragment : Fragment() {

    /**
     * Download / update sticker set
     */
    private fun onFabClicked() {

        val ctx = this@FirstFragment.requireContext()

        lifecycleScope.launch {
            val token = ctx.getPreference(stringPreferencesKey(TELEGRAM_BOT_TOKEN))

            if (token.isNullOrBlank()) {
                with(ctx) { "Please set telegram bot token in settings firstly.".toast() }
                return@launch
            }

            MaterialAlertDialogBuilder(ctx).run {

                var textInputEditText: TextInputEditText
                val textInputLayout: TextInputLayout = TextInputLayout(
                    ctx,
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
                            with(ctx) {
                                "Please input stickers link".toast()
                            }
                        }
                        return@setPositiveButton
                    }

                    val dialogBinding = DialogDownloadBinding.inflate(layoutInflater)
                    val progressDialog = MaterialAlertDialogBuilder(ctx)
                        .setTitle("Downloading Stickers")
                        .setView(dialogBinding.root)
                        .setCancelable(false)
                        .setNegativeButton("Cancel") { _, _ ->
                            WorkManager.getInstance(ctx)
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

                    val workManager = WorkManager.getInstance(ctx)
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
                                    with(ctx) {
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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        val binding = FragmentMainBinding.inflate(inflater)

        // recycler view

        val stickerSetArray = iterateStickerSet(requireContext())
        val adapterItems = ArrayList<AdapterItem>(stickerSetArray.size)
        for (stickerSet in stickerSetArray) {
            val metadata = stickerSet.second
            val image = stickerSet.third
            val directory = stickerSet.first.first
            val metadataFile = stickerSet.first.second

            var bitmap: Bitmap? = null
            if (image != null) {
                bitmap = BitmapFactory.decodeFile(image.absolutePath)
            }

            adapterItems.add(AdapterItem(
                metadata!!.title,
                metadata.name,
                bitmap,
                directory,
                metadataFile
            ))
        }

        binding.recyclerView.adapter = StickerSetAdapter(adapterItems.toTypedArray(), { item ->
            // on item click

            lifecycleScope.launch {

                val recyclerView = RecyclerView(requireContext())
                val flexLayoutManager = FlexboxLayoutManager(context)
                flexLayoutManager.flexDirection = FlexDirection.ROW
                flexLayoutManager.justifyContent = JustifyContent.SPACE_AROUND
                recyclerView.layoutManager = flexLayoutManager

                recyclerView.adapter = GalleryAdapter(
                    requireContext(),
                    getPreviewScale(requireContext()),
                    item.directory.safetyListFiles(),
                ) { file ->
                    lifecycleScope.launch {
                        shareSticker(file, requireContext())
                    }
                }

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(item.title)
                    .setView(recyclerView)
                    .setNegativeButton("Delete") { _, _ ->


                        MaterialAlertDialogBuilder(requireContext())
                            .setMessage("Delete?")
                            .setNegativeButton("Confirm") { _, _ ->
                                item.directory.deleteRecursively()
                                if (item.metadataFile.exists()) {
                                    item.metadataFile.delete()
                                }
                                requireActivity().recreate()
                            }
                            .setNeutralButton("Cancel") { dialog, _ ->
                                dialog.dismiss()
                            }
                            .create()
                            .show()

                    }
                    .setNeutralButton("Update") { dialog, _ ->

                        dialog.dismiss()
                        // (activity as MainActivity).downloadStickers(requireContext(), directory.name)

                    }
                    .setPositiveButton("Close") { dialog, _ ->

                        dialog.dismiss()

                    }
                    .create()
                    .show()

                val params = recyclerView.layoutParams
                if (params is ViewGroup.MarginLayoutParams) {
                    val dp = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        16F,
                        resources.displayMetrics
                    ).toInt()
                    params.setMargins(dp, dp, dp, 0)
                    recyclerView.layoutParams = params
                }

            }

        })

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // fab

        binding.fab.setOnClickListener {
            onFabClicked()
        }

        // menu

        (requireHost() as MenuHost).addMenuProvider(object : MenuProvider {

            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.menu_main, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                // Handle action bar item clicks here. The action bar will
                // automatically handle clicks on the Home/Up button, so long
                // as you specify a parent activity in AndroidManifest.xml.
                return when (menuItem.itemId) {
                    R.id.action_settings -> {
                        findNavController().navigate(R.id.action_FirstFragment_to_Setting)
                        true
                    }

                    R.id.action_search -> {
                        findNavController().navigate(R.id.action_FirstFragment_to_Search)
                        true
                    }

                    else -> false
                }
            }

        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        return binding.root
    }

}