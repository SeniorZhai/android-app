package one.mixin.android.ui.web

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.activity_web.*
import one.mixin.android.R
import one.mixin.android.extension.alertDialogBuilder
import one.mixin.android.extension.notNullWithElse
import one.mixin.android.ui.common.BaseActivity
import one.mixin.android.vo.App
import one.mixin.android.vo.AppCardData
import one.mixin.android.widget.SixLayout

@AndroidEntryPoint
class WebActivity : BaseActivity() {

    companion object {
        fun show(context: Context) {
            context.startActivity(
                Intent(context, WebActivity::class.java).apply {
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }

        fun show(
            context: Context,
            url: String,
            conversationId: String?,
            app: App? = null,
            appCard: AppCardData? = null
        ) {
            context.startActivity(
                Intent(context, WebActivity::class.java).apply {
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtras(
                        Bundle().apply {
                            putString(WebFragment.URL, url)
                            putString(WebFragment.CONVERSATION_ID, conversationId)
                            putParcelable(WebFragment.ARGS_APP, app)
                            putParcelable(WebFragment.ARGS_APP_CARD, appCard)
                        }
                    )
                }
            )
        }
    }

    override fun getNightThemeId(): Int = R.style.AppTheme_Night_Web

    override fun getDefaultThemeId(): Int = R.style.AppTheme_Web

    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent.extras != null) {
            overridePendingTransition(R.anim.slide_in_bottom, 0)
        } else {
            overridePendingTransition(R.anim.fade_in, 0)
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)
        container.setOnClickListener {
            finish()
        }

        six.setOnCloseListener(object : SixLayout.OnCloseListener {
            override fun onClose(index: Int) {
                releaseClip(index)
                six.loadData(clips, loadViewAction)
            }
        })

        clear.setOnClickListener {
            alertDialogBuilder()
                .setMessage(getString(R.string.web_delete_tip))
                .setNegativeButton(R.string.cancel) { dialog, _ ->
                    dialog.dismiss()
                }
                .setPositiveButton(R.string.confirm) { _, _ ->
                    releaseAll()
                    finish()
                }
                .show()
        }

        close.setOnClickListener {
            finish()
        }

        handleExtras(intent)
    }

    private var loadViewAction = fun(index: Int) {
        val extras = Bundle()
        val clip = clips[index]
        extras.putString(WebFragment.URL, clip.url)
        extras.putParcelable(WebFragment.ARGS_APP, clip.app)
        extras.putInt(WebFragment.ARGS_INDEX, index)
        isExpand = true
        supportFragmentManager.beginTransaction().add(
            R.id.container,
            WebFragment.newInstance(extras),
            WebFragment.TAG
        ).commit()
    }

    private fun handleExtras(intent: Intent) {
        six.loadData(clips, loadViewAction)
        intent.extras.notNullWithElse(
            { extras ->
                isExpand = true
                supportFragmentManager.beginTransaction().add(
                    R.id.container,
                    WebFragment.newInstance(extras),
                    WebFragment.TAG
                ).commit()
            },
            {
                FloatingWebClip.getInstance().hide()
                supportFragmentManager.findFragmentByTag(WebFragment.TAG)?.let {
                    supportFragmentManager.beginTransaction().remove(it).commit()
                }
            }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExtras(intent)
    }

    private var isExpand = false

    override fun finish() {
        collapse()
        super.finish()
        if (isExpand) {
            overridePendingTransition(0, R.anim.slide_out_bottom)
        } else {
            overridePendingTransition(0, R.anim.fade_out)
        }
    }
}
