package one.mixin.android.ui.call

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.Sensor.TYPE_PROXIMITY
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.View
import android.view.View.VISIBLE
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import com.tbruyelle.rxpermissions2.RxPermissions
import com.uber.autodispose.autoDispose
import kotlinx.android.synthetic.main.activity_call.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.mixin.android.R
import one.mixin.android.extension.alertDialogBuilder
import one.mixin.android.extension.belowOreo
import one.mixin.android.extension.checkInlinePermissions
import one.mixin.android.extension.fadeIn
import one.mixin.android.extension.fadeOut
import one.mixin.android.extension.fastBlur
import one.mixin.android.extension.formatMillis
import one.mixin.android.extension.isLandscape
import one.mixin.android.extension.isNotchScreen
import one.mixin.android.extension.realSize
import one.mixin.android.extension.statusBarHeight
import one.mixin.android.ui.common.BaseActivity
import one.mixin.android.ui.conversation.web.WebBottomSheetDialogFragment
import one.mixin.android.util.Session
import one.mixin.android.util.SystemUIManager
import one.mixin.android.vo.CallStateLiveData
import one.mixin.android.vo.toUser
import one.mixin.android.webrtc.CallService
import one.mixin.android.webrtc.GroupCallService
import one.mixin.android.webrtc.VoiceCallService
import one.mixin.android.webrtc.acceptInvite
import one.mixin.android.webrtc.answerCall
import one.mixin.android.webrtc.muteAudio
import one.mixin.android.webrtc.speakerPhone
import one.mixin.android.widget.CallButton
import one.mixin.android.widget.PipCallView
import timber.log.Timber
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject

class CallActivity : BaseActivity(), SensorEventListener {

    @Inject
    lateinit var viewModelFactory: ViewModelProvider.Factory
    private val viewModel: CallViewModel by lazy {
        ViewModelProvider(this, viewModelFactory).get(CallViewModel::class.java)
    }

    @Inject
    lateinit var callState: CallStateLiveData

    private var sensorManager: SensorManager? = null
    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var userAdapter: CallUserAdapter? = null

    private val self = Session.getAccount()!!.toUser()

    private val pipCallView by lazy {
        PipCallView.get()
    }

    private var uiState: CallService.CallState = CallService.CallState.STATE_IDLE

    override fun getDefaultThemeId(): Int {
        return R.style.AppTheme_Call
    }

    override fun getNightThemeId(): Int {
        return R.style.AppTheme_Night_Call
    }

    private var join = false

    @SuppressLint("InvalidWakeLockTag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        belowOreo {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        setContentView(R.layout.activity_call)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        sensorManager = getSystemService()
        powerManager = getSystemService()
        wakeLock = powerManager?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "mixin")
        if (window?.isNotchScreen() == true) {
            guideline_top.updateLayoutParams<ConstraintLayout.LayoutParams> {
                guidePercent = 0.15f
            }
        }
        join = intent.getBooleanExtra(EXTRA_JOIN, false)
        if (callState.isGroupCall()) {
            avatar.isVisible = false
            users_rv.isVisible = true
            add_iv.isVisible = true
            if (userAdapter == null) {
                userAdapter = CallUserAdapter(self)
            }
            users_rv.adapter = userAdapter
            callState.conversationId?.let {
                viewModel.observeConversationNameById(it).observe(
                    this,
                    Observer { name ->
                        name_tv?.text = name
                    }
                )
            }
            refreshUsers()
        } else {
            avatar.isVisible = true
            users_rv.isVisible = false
            add_iv.isVisible = false
            val callee = callState.user
            if (callee != null) {
                name_tv.text = callee.fullName
                avatar.setInfo(callee.fullName, callee.avatarUrl, callee.userId)
                avatar.setTextSize(48f)
                if (callee.avatarUrl != null) {
                    setBlurBg(callee.avatarUrl)
                }
            }
        }
        pip_iv.setOnClickListener {
            switch2Pip()
        }
        add_iv.setOnClickListener {
            if (callState.isGroupCall() && callState.conversationId != null) {
                GroupUsersBottomSheetDialogFragment.newInstance(callState.conversationId!!)
                    .showNow(supportFragmentManager, GroupUsersBottomSheetDialogFragment.TAG)
            }
        }
        hangup_cb.setOnClickListener {
            hangup()
        }
        answer_cb.setOnClickListener {
            handleAnswer()
        }
        close_iv.setOnClickListener {
            hangup()
        }
        encryption_tv.setOnClickListener {
            showE2EETip()
        }
        mute_cb.setOnCheckedChangeListener(object : CallButton.OnCheckedChangeListener {
            override fun onCheckedChanged(id: Int, checked: Boolean) {
                if (callState.isGroupCall()) {
                    muteAudio<GroupCallService>(this@CallActivity, checked)
                } else if (callState.isVoiceCall()) {
                    muteAudio<VoiceCallService>(this@CallActivity, checked)
                }
            }
        })
        voice_cb.setOnCheckedChangeListener(object : CallButton.OnCheckedChangeListener {
            override fun onCheckedChanged(id: Int, checked: Boolean) {
                if (callState.isGroupCall()) {
                    speakerPhone<GroupCallService>(this@CallActivity, checked)
                } else if (callState.isVoiceCall()) {
                    speakerPhone<VoiceCallService>(this@CallActivity, checked)
                }
            }
        })
        updateUI()

        callState.observe(
            this,
            Observer { state ->
                // if plan to join a group voice, do not show self before answering
                if (join && state >= CallService.CallState.STATE_ANSWERING) {
                    join = false
                }

                updateUI()
                if (callState.isGroupCall()) {
                    refreshUsers()
                }
                if (state == CallService.CallState.STATE_IDLE) {
                    call_cl.post { handleDisconnected() }
                    return@Observer
                }
                if (uiState >= state) return@Observer

                uiState = state

                when (state) {
                    CallService.CallState.STATE_DIALING -> {
                        volumeControlStream = AudioManager.STREAM_VOICE_CALL
                        call_cl.post { handleDialing() }
                    }
                    CallService.CallState.STATE_RINGING -> {
                        call_cl.post {
                            if (join) {
                                handleJoin()
                            } else {
                                handleRinging()
                            }
                        }
                    }
                    CallService.CallState.STATE_ANSWERING -> {
                        call_cl.post { handleAnswering() }
                    }
                    CallService.CallState.STATE_CONNECTED -> {
                        call_cl.post { handleConnected() }
                    }
                    CallService.CallState.STATE_BUSY -> {
                        call_cl.post { handleBusy() }
                    }
                }
            }
        )

        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        window.decorView.systemUiVisibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        window.statusBarColor = Color.TRANSPARENT

        if (pipCallView.shown) {
            pipCallView.close()
        }
    }

    override fun onResume() {
        sensorManager?.registerListener(
            this,
            sensorManager?.getDefaultSensor(TYPE_PROXIMITY),
            SensorManager.SENSOR_DELAY_UI
        )
        if (callState.connectedTime != null) {
            startTimer()
        }
        super.onResume()
    }

    override fun onPause() {
        sensorManager?.unregisterListener(this)
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        stopTimer()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (callState.isRinging()) {
            hangup()
        } else if (callState.isNotIdle()) {
            if (!checkInlinePermissions()) {
                return
            }
            switch2Pip()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        if (event.sensor.type == TYPE_PROXIMITY) {
            if (values[0] == 0.0f) {
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(10 * 60 * 1000L)
                }
            } else {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            }
        }
    }

    private fun hangup() {
        callState.handleHangup(this, join)
        finishAndRemoveTask()
    }

    private fun updateUI() {
        mute_cb?.isChecked = !callState.audioEnable
        voice_cb?.isChecked = callState.speakerEnable
        pip_iv?.isVisible = callState.isConnected()
        if (pip_iv?.isVisible == true) {
            close_iv?.isVisible = false
        }
        add_iv?.isVisible = callState.isConnected() && callState.isGroupCall()
    }

    private fun refreshUsers() = lifecycleScope.launch {
        val cid = callState.conversationId ?: return@launch
        val us = callState.getUsers(cid)
        val callees = mutableListOf<String>().apply { us?.let { addAll(it) } }
        var layoutManager: GridLayoutManager? = users_rv?.layoutManager as GridLayoutManager?
        if (layoutManager == null) {
            layoutManager = GridLayoutManager(this@CallActivity, 3)
            users_rv?.layoutManager = layoutManager
        }

        if (callees.isNullOrEmpty()) {
            userAdapter?.submitList(null)
        } else {
            val last = callees.lastOrNull()
            if (callees.size == 1 && last == self.userId) {
                layoutManager.spanCount = getSpanCount(1)
                userAdapter?.submitList(listOf(self))
                return@launch
            }
            if (last != self.userId) {
                val exists = callees.remove(self.userId)
                if (!join || exists) {
                    callees.add(self.userId)
                }
            }
            val users = viewModel.findMultiUsersByIds(callees.toSet())
            val orderByIds = callees.withIndex().associate { it.value to it.index }
            val sortedUsers = users.sortedBy { orderByIds[it.userId] }
            layoutManager.spanCount = getSpanCount(sortedUsers.size)
            userAdapter?.submitList(sortedUsers)
        }
        val currentGuestsNotConnected = userAdapter?.guestsNotConnected
        val newGuestsNotConnected = callState.getPendingUsers(cid)
        if (currentGuestsNotConnected != newGuestsNotConnected) {
            userAdapter?.guestsNotConnected = newGuestsNotConnected
            userAdapter?.notifyDataSetChanged()
        }
    }

    private fun setBlurBg(url: String) = lifecycleScope.launch(Dispatchers.IO) {
        if (url.isBlank()) return@launch
        try {
            val bitmap = Glide.with(applicationContext)
                .asBitmap()
                .load(url)
                .submit()
                .get(10, TimeUnit.SECONDS)
                .fastBlur(1f, 10)
            withContext(Dispatchers.Main) {
                bitmap?.let { bitmap ->
                    blur_iv.setImageBitmap(bitmap)
                }
            }
        } catch (e: TimeoutException) {
            Timber.e(e)
        } catch (e: GlideException) {
            Timber.e(e)
        }
    }

    private fun showE2EETip() {
        alertDialogBuilder()
            .setMessage(R.string.end_to_end_encryption_tip)
            .setNeutralButton(R.string.chat_learn) { dialog, _ ->
                WebBottomSheetDialogFragment.newInstance(getString(R.string.chat_waiting_url), callState.conversationId)
                    .showNow(supportFragmentManager, WebBottomSheetDialogFragment.TAG)
                dialog.dismiss()
            }
            .setPositiveButton(R.string.ok) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun handleAnswer() {
        RxPermissions(this)
            .request(Manifest.permission.RECORD_AUDIO)
            .autoDispose(stopScope)
            .subscribe { granted ->
                if (granted) {
                    handleAnswering()
                    if (callState.isGroupCall()) {
                        acceptInvite(this@CallActivity)
                    } else if (callState.isVoiceCall()) {
                        answerCall(this@CallActivity)
                    }
                } else {
                    callState.handleHangup(this@CallActivity)
                    handleDisconnected()
                }
            }
    }

    private var pipAnimationInProgress = false
    private fun switch2Pip() {
        if (!checkInlinePermissions() || pipAnimationInProgress) {
            return
        }
        pipAnimationInProgress = true
        val rect = PipCallView.getPipRect()
        val windowView = call_cl
        val isLandscape = isLandscape()
        if (isLandscape) {
            val screenHeight = realSize().y
            if (rect.width > screenHeight) {
                val ratio = rect.width / rect.height
                rect.height = screenHeight / ratio
                rect.width = screenHeight.toFloat()
            }
        }
        val width = if (isLandscape) windowView.height else windowView.width
        val scale = (if (isLandscape) rect.height else rect.width) / width
        window.decorView.systemUiVisibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        pipCallView.show(this, callState.connectedTime)
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(windowView, View.SCALE_X, scale),
                ObjectAnimator.ofFloat(windowView, View.SCALE_Y, scale),
                ObjectAnimator.ofFloat(
                    windowView, View.TRANSLATION_X,
                    rect.x - realSize().x * (1f - scale) / 2
                ),
                ObjectAnimator.ofFloat(
                    windowView, View.TRANSLATION_Y,
                    rect.y - statusBarHeight() - (windowView.height - rect.height) / 2
                )
            )
            interpolator = DecelerateInterpolator()
            duration = 250
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator?) {
                    if (!SystemUIManager.hasCutOut(window)) {
                        SystemUIManager.clearStyle(window)
                    }
                }

                override fun onAnimationEnd(animation: Animator?) {
                    pipAnimationInProgress = false

                    overridePendingTransition(0, 0)
                    finish()
                }
            })
            start()
        }
    }

    private fun handleDialing() {
        voice_cb.isVisible = true
        mute_cb.isVisible = true
        answer_cb.isVisible = false
        moveHangup(true, 0)
        action_tv.text = getString(R.string.call_notification_outgoing)
    }

    private fun handleRinging() {
        voice_cb.isVisible = false
        mute_cb.isVisible = false
        answer_cb.isVisible = true
        moveHangup(false, 0)
        action_tv.text = getString(R.string.call_notification_incoming_voice)
    }

    private fun handleJoin() {
        voice_cb.isVisible = false
        mute_cb.isVisible = false
        answer_cb.isVisible = true
        hangup_cb.isVisible = false
        answer_cb.updateLayoutParams<ConstraintLayout.LayoutParams> {
            horizontalBias = 0.5f
        }
        close_iv.isVisible = true
    }

    private fun handleAnswering() {
        voice_cb.fadeIn()
        mute_cb.fadeIn()
        answer_cb.fadeOut()
        moveHangup(true, 250)
        action_tv.text = getString(R.string.call_connecting)
    }

    private fun handleConnected() {
        if (!voice_cb.isVisible) {
            voice_cb.fadeIn()
        }
        if (!mute_cb.isVisible) {
            mute_cb.fadeIn()
        }
        if (answer_cb.isVisible) {
            answer_cb.fadeOut()
        }
        moveHangup(true, 250)
        startTimer()
    }

    private fun handleDisconnected() {
        finishAndRemoveTask()
    }

    private fun handleBusy() {
        handleDisconnected()
    }

    private fun moveHangup(center: Boolean, duration: Long) {
        hangup_cb.visibility = VISIBLE
        val constraintSet = ConstraintSet().apply {
            clone(call_cl)
            setHorizontalBias(hangup_cb.id, if (center) 0.5f else 0.1f)
        }
        val transition = AutoTransition().apply {
            this.duration = duration
        }
        TransitionManager.beginDelayedTransition(call_cl, transition)
        constraintSet.applyTo(call_cl)
    }

    private var timer: Timer? = null

    private fun startTimer() {
        timer = Timer(true)
        val timerTask = object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    if (callState.connectedTime != null) {
                        val duration = System.currentTimeMillis() - callState.connectedTime!!
                        action_tv.text = duration.formatMillis()
                    }
                }
            }
        }
        timer?.schedule(timerTask, 0, 1000)
    }

    private fun stopTimer() {
        timer?.cancel()
        timer?.purge()
        timer = null
    }

    private fun getSpanCount(size: Int) = when {
        size <= 1 -> 1
        size == 2 -> 2
        size <= 9 -> 3
        else -> 4
    }

    companion object {
        const val TAG = "CallActivity"
        const val EXTRA_JOIN = "extra_join"

        fun show(context: Context, join: Boolean = false) {
            Intent(context, CallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_JOIN, join)
            }.run {
                context.startActivity(this)
            }
        }
    }
}
