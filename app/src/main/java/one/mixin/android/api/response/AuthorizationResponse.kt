package one.mixin.android.api.response

import android.annotation.SuppressLint
import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.android.parcel.Parcelize
import one.mixin.android.vo.App

@SuppressLint("ParcelCreator")
@Parcelize
class AuthorizationResponse(
    @SerializedName("authorization_id")
    val authorizationId: String,
    val authorization_code: String,
    val scopes: List<String>,
    @SerializedName("code_id")
    val codeId: String,
    val app: App,
    @SerializedName("created_at")
    val createAt: String,
    @SerializedName("accessed_at")
    val accessedAt: String
) : Parcelable
