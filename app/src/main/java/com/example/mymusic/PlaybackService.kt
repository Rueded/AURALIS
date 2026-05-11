package com.example.mymusic

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    // 👇 新增：伴生对象，用于全局保存 audioSessionId，方便其他地方（如EQ）调用
    companion object {
        var audioSessionId: Int = 0
    }

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .build()

        // 👇 新增：在 player 创建完成后，立刻获取并保存它的 audioSessionId
        audioSessionId = player.audioSessionId

        // ── 点击通知栏跳转全屏播放器 ──
        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            action = "OPEN_PLAYER_FULLSCREEN"
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent) // ← 绑定进去
            .build()

        val notificationProvider = object : DefaultMediaNotificationProvider(this) {
            override fun getMediaButtons(
                session: MediaSession,
                playerCommands: Player.Commands,
                customLayout: ImmutableList<CommandButton>,
                showPauseButton: Boolean
            ): ImmutableList<CommandButton> {
                val defaultButtons = super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
                val filteredList = defaultButtons.filter {
                    it.playerCommand != Player.COMMAND_SEEK_FORWARD &&
                            it.playerCommand != Player.COMMAND_SEEK_BACK
                }
                return ImmutableList.copyOf(filteredList)
            }
        }

        notificationProvider.setSmallIcon(R.drawable.ic_notification_logo)
        setMediaNotificationProvider(notificationProvider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}