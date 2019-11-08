package one.mixin.android.ui.common

import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.manager.SupportRequestManagerFragment
import com.uber.autodispose.android.lifecycle.scope
import javax.inject.Inject
import one.mixin.android.R
import one.mixin.android.di.Injectable
import one.mixin.android.extension.booleanFromAttribute
import one.mixin.android.ui.url.UrlInterpreterActivity
import one.mixin.android.util.SystemUIManager
import one.mixin.android.widget.BottomSheet

abstract class MixinBottomSheetDialogFragment : DialogFragment(), Injectable {

    protected lateinit var contentView: View
    protected val stopScope = scope(Lifecycle.Event.ON_STOP)

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory
    protected val bottomViewModel: BottomSheetViewModel by viewModels { viewModelFactory }

    override fun getTheme() = R.style.AppTheme_Dialog

    override fun onCreateDialog(savedInstanceState: Bundle?): BottomSheet {
        return BottomSheet.Builder(requireActivity(), needFocus = true, softInputResize = true)
            .create()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            SystemUIManager.lightUI(
                window,
                !requireContext().booleanFromAttribute(R.attr.flag_night)
            )
        }
    }

    override fun onDetach() {
        super.onDetach()
        if (activity is UrlInterpreterActivity) {
            var realFragmentCount = 0
            parentFragmentManager.fragments.forEach { f ->
                if (f !is SupportRequestManagerFragment) {
                    realFragmentCount++
                }
            }
            if (realFragmentCount <= 0) {
                activity?.finish()
            }
        }
    }

    override fun dismiss() {
        if (isAdded) {
            dialog?.dismiss()
            // Prevent dialog slide animation end
            dialog?.setOnDismissListener {
                super.dismissAllowingStateLoss()
            }
        } else {
            super.dismissAllowingStateLoss()
        }
    }

    override fun showNow(manager: FragmentManager, tag: String?) {
        try {
            super.showNow(manager, tag)
        } catch (e: IllegalStateException) {
        }
    }
}
