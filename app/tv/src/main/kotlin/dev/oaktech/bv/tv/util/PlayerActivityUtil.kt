package dev.oaktech.bv.tv.util

import android.content.Context
import dev.oaktech.bv.entity.proxy.ProxyArea
import dev.oaktech.bv.tv.activities.video.RemoteControllerPanelDemoActivity
import dev.oaktech.bv.tv.activities.video.VideoPlayerV3Activity
import dev.oaktech.bv.util.Prefs

fun launchPlayerActivity(
    context: Context,
    avid: Long,
    cid: Long,
    title: String,
    partTitle: String,
    played: Int,
    fromSeason: Boolean,
    subType: Int? = null,
    epid: Int? = null,
    seasonId: Int? = null,
    isVerticalVideo: Boolean = false,
    proxyArea: ProxyArea = ProxyArea.MainLand,
    playerIconIdle: String = "",
    playerIconMoving: String = ""
) {
    if (Prefs.showedRemoteControllerPanelDemo) {
        VideoPlayerV3Activity.actionStart(
            context, avid, cid, title, partTitle, played, fromSeason, subType, epid, seasonId,
            isVerticalVideo, proxyArea, playerIconIdle, playerIconMoving
        )
    } else {
        RemoteControllerPanelDemoActivity.actionStart(
            context, avid, cid, title, partTitle, played, fromSeason, subType, epid, seasonId,
            isVerticalVideo, proxyArea, playerIconIdle, playerIconMoving
        )
    }
}