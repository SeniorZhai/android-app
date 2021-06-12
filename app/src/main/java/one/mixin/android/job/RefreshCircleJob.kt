package one.mixin.android.job

import com.birbit.android.jobqueue.Params
import one.mixin.android.db.insertUpdate
import one.mixin.android.extension.notNullWithElse
import one.mixin.android.extension.nowInUtc
import one.mixin.android.session.Session
import one.mixin.android.vo.Circle
import one.mixin.android.vo.CircleConversation
import one.mixin.android.vo.ConversationCategory
import one.mixin.android.vo.ConversationStatus
import one.mixin.android.vo.Participant
import one.mixin.android.vo.createConversation

class RefreshCircleJob(
    val circleId: String? = null
) : BaseJob(
    Params(PRIORITY_UI_HIGH)
        .groupBy("refresh_circles").requireNetwork().persist()
) {

    companion object {
        const val REFRESH_CIRCLE_CONVERSATION_LIMIT = 500
    }

    private val refreshUserIdSet = mutableSetOf<String>()

    override fun onRun() {
        if (circleId == null) {
            val circleResponse = circleService.getCircles().execute().body()
            if (circleResponse?.isSuccess == true) {
                circleResponse.data?.let { cList ->
                    cList.forEach { c ->
                        handleCircle(c) { circleConversation ->
                            circleConversation.userId.notNullWithElse(
                                { userId ->
                                    val createdAt = nowInUtc()
                                    val conversation = createConversation(
                                        circleConversation.conversationId,
                                        ConversationCategory.CONTACT.name,
                                        userId,
                                        ConversationStatus.START.ordinal
                                    )
                                    val participants = arrayListOf(
                                        Participant(conversation.conversationId, userId, "", createdAt),
                                        Participant(
                                            conversation.conversationId,
                                            Session.getAccountId()!!,
                                            "",
                                            createdAt
                                        )
                                    )
                                    appDatabase.runInTransaction {
                                        conversationDao.insert(conversation)
                                        participantDao.insertList(participants)
                                    }
                                },
                                {
                                    jobManager.addJobInBackground(
                                        RefreshConversationJob(
                                            circleConversation.conversationId,
                                            skipRefreshCircle = true
                                        )
                                    )
                                }
                            )
                        }
                        circleDao.insertUpdate(c)
                    }
                }
            }
        } else {
            val circleResponse = circleService.getCircle(circleId).execute().body()
            if (circleResponse?.isSuccess == true) {
                circleResponse.data?.let { c ->
                    handleCircle(c)
                    circleDao.insertUpdate(c)
                }
            }
        }

        if (refreshUserIdSet.isNotEmpty()) {
            jobManager.addJobInBackground(RefreshUserJob(refreshUserIdSet.toList()))
        }
    }

    private fun handleCircle(c: Circle, offset: String? = null, conversationHandler: ((CircleConversation) -> Unit)? = null) {
        val ccResponse = circleService.getCircleConversations(
            c.circleId,
            offset,
            REFRESH_CIRCLE_CONVERSATION_LIMIT
        ).execute().body()
        if (ccResponse?.isSuccess == true) {
            ccResponse.data?.let { ccList ->
                ccList.forEach { cc ->
                    circleConversationDao.insertUpdate(cc)
                    cc.userId?.let { uid ->
                        if (!refreshUserIdSet.contains(uid)) {
                            val user = userDao.findUser(uid)
                            if (user == null) {
                                refreshUserIdSet.add(uid)
                            }
                        }
                    }
                    conversationHandler?.invoke(cc)
                }

                if (ccList.size >= REFRESH_CIRCLE_CONVERSATION_LIMIT) {
                    handleCircle(c, ccList.last().createdAt)
                }
            }
        }
    }
}
