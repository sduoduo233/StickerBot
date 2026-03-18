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
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceClickListener
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class FirstFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())

        val stickerSetArray = iterateStickerSet(requireContext())

        for (stickerSet in stickerSetArray) {
            val directory = stickerSet.first.first
            val metadataFile = stickerSet.first.second
            val metadata = stickerSet.second
            val image = stickerSet.third

            var bitmap: Bitmap? = null
            if (image != null) {
                val stream = image.inputStream()
                try {
                    bitmap = BitmapFactory.decodeStream(stream)
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    stream.close()
                }
            }

            val preference = Preference(requireContext())
            if (metadata != null) {
                preference.title = metadata.title
                preference.summary = metadata.name
            } else {
                preference.title = directory.name
            }
            preference.icon = bitmap?.toDrawable(resources)
            preference.onPreferenceClickListener = OnPreferenceClickListener {
                lifecycleScope.launch {
                    val recyclerView = RecyclerView(requireContext())
                    val flexLayoutManager = FlexboxLayoutManager(context)
                    flexLayoutManager.flexDirection = FlexDirection.ROW
                    flexLayoutManager.justifyContent = JustifyContent.SPACE_AROUND
                    recyclerView.layoutManager = flexLayoutManager

                    recyclerView.adapter = GalleryAdapter(
                        requireContext(),
                        getPreviewScale(requireContext()),
                        directory.safetyListFiles(),
                    ) { file ->
                        lifecycleScope.launch {
                            shareSticker(file, requireContext())
                        }
                    }

                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(preference.title)
                        .setView(recyclerView)
                        .setNegativeButton("Delete") { _, _ ->
                            MaterialAlertDialogBuilder(requireContext())
                                .setMessage("Delete?")
                                .setNegativeButton("Confirm") { _, _ ->
                                    directory.deleteRecursively()
                                    if (metadataFile.exists()) {
                                        metadataFile.delete()
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
                true
            }
            preferenceScreen.addPreference(preference)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

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

        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as MainActivity).fab.visibility = View.VISIBLE
    }
}