package io.github.cryolitia.stickerbot

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.github.cryolitia.stickerbot.databinding.BottomSheetDownloadBinding
import java.io.File

class DownloadBottomSheetFragment : BottomSheetDialogFragment() {

    private lateinit var binding: BottomSheetDownloadBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View  {
        binding = BottomSheetDownloadBinding.inflate(inflater, container, false)
        return binding.root
    }

    fun updateProgress(progress: Float, latest: String?, emoji: String?) {
        binding.progressBar.progress = (progress * 100).toInt()

        if (latest != null && File(latest).isFile) {
            val bitmap = BitmapFactory.decodeFile(latest)
            binding.preview.setImageBitmap(bitmap)
        } else {
            binding.preview.setImageResource(R.drawable.mood)
        }

        binding.downloadingSmall.text = emoji ?: ""
    }

    companion object {
        const val TAG = "DownloadBottomSheetFragment"
    }
}