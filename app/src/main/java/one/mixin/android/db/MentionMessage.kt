package one.mixin.android.db

import androidx.room.Dao
import one.mixin.android.vo.MentionMessage

@Dao
interface MentionMessageDao : BaseDao<MentionMessage>