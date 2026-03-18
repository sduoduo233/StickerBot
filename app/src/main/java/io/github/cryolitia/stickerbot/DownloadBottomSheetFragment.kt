package io.github.cryolitia.stickerbot

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.github.cryolitia.stickerbot.databinding.BottomSheetDownloadBinding
import java.io.File

class DownloadBottomSheetFragment() : BottomSheetDialogFragment() {


    private lateinit var onViewCreated: () -> Unit
    private lateinit var onDismiss: () -> Unit

    private lateinit var binding: BottomSheetDownloadBinding

    fun setListeners(onViewCreated: () -> Unit, onDismiss: () -> Unit) {
        this.onViewCreated = onViewCreated
        this.onDismiss = onDismiss
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View  {
        binding = BottomSheetDownloadBinding.inflate(inflater, container, false)

        dialog!!.setOnDismissListener {
            onDismiss()
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        onViewCreated()
        super.onViewCreated(view, savedInstanceState)
    }

    fun updateProgress(progress: Float, latest: String?, emoji: String?) {
        binding.progressBar.progress = (progress * 100).toInt()

        binding.progressBar.isIndeterminate = progress == 0f

        if (latest != null && File(latest).isFile) {
            val bitmap = BitmapFactory.decodeFile(latest)
            binding.preview.setImageBitmap(bitmap)
        } else {
            binding.preview.setImageBitmap(null)
        }

        binding.downloadingSmall.text = emoji ?: ""
    }

    companion object {
        const val TAG = "DownloadBottomSheetFragment"
    }
}