package com.kvideo.tv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource

/**
 * 原生播放器 Activity —— 用 Media3 (ExoPlayer) 播 HLS。
 *
 * 为什么要有这个:
 *   WebView 播 HLS 依赖浏览器 MSE, 各家电视的 WebView 版本差异很大,
 *   老设备上会表现为 readyState=0 / 一直黑屏。Media3 走系统 MediaCodec,
 *   不依赖 WebView, 兼容性明显更好。
 *
 * 遥控支持: 方向键/确定/返回/播放暂停/快进快退/字幕/倍速。
 *
 * 启动方式:
 *   ```
 *   PlayerActivity.start(context, "https://host/path/master.m3u8", "片名")
 *   ```
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : android.app.Activity() {

    private var player: ExoPlayer? = null
    private var container: FrameLayout? = null
    private var playWhenReady = true
    private var playbackPosition = 0L

    companion object {
        private const val TAG = "KVideoPlayer"
        const val EXTRA_URL = "com.kvideo.tv.extra.URL"
        const val EXTRA_TITLE = "com.kvideo.tv.extra.TITLE"
        const val EXTRA_POSITION = "com.kvideo.tv.extra.POSITION"

        fun start(context: Context, url: String, title: String = "", positionMs: Long = 0L) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_POSITION, positionMs)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            Log.e(TAG, "no url in intent, finishing")
            finish()
            return
        }
        playbackPosition = intent.getLongExtra(EXTRA_POSITION, 0L)

        val layout = FrameLayout(this).apply {
            id = ViewGroup.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        container = layout
        setContentView(layout)

        val playerView = PlayerView(this).apply {
            useController = true
            // 电视上不需要触摸手势, 但需要遥控的方向键控制进度条
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        layout.addView(playerView, 0, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        player = buildPlayer(url).also { p ->
            playerView.player = p
            p.playWhenReady = playWhenReady
            p.seekTo(playbackPosition)
            p.prepare()
        }
    }

    private fun buildPlayer(url: String): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("KVideo-AndroidTV/4.10.3")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(30000)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)

        val p = ExoPlayer.Builder(this).build()
        p.setMediaSource(buildMediaSource(url, dataSourceFactory))
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "playback error: ${error.errorCodeName} ${error.message}", error)
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.player_error, error.errorCodeName),
                    Toast.LENGTH_LONG
                ).show()
            }

            override fun onPlaybackStateChanged(state: Int) {
                Log.i(TAG, "playback state: ${stateToName(state)}")
                if (state == Player.STATE_READY) {
                    playbackPosition = p.currentPosition
                }
            }
        })
        return p
    }

    /**
     * 按 URL 后缀选择 HLS 或渐进式源。
     * m3u8 -> HLS (支持主清单 + 子清单), 其余 -> 渐进式 mp4。
     */
    private fun buildMediaSource(
        url: String,
        dataSourceFactory: DefaultDataSource.Factory
    ): MediaSource {
        val isHls = url.substringBefore('?').lowercase().endsWith(".m3u8")
        return if (isHls) {
            HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(MediaItem.fromUri(url))
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(url))
        }
    }

    private fun stateToName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN($state)"
    }

    // ---------- 遥控按键 ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (p.isPlaying) p.pause() else p.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                p.pause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                p.seekTo((p.currentPosition + 10_000).coerceAtMost(p.duration))
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0L))
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // 上下键切换轨道 (字幕/音轨), Media3 默认绑定
                return super.onKeyDown(keyCode, event)
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        player?.let {
            playbackPosition = it.currentPosition
            it.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        player?.playWhenReady = playWhenReady
    }

    override fun onStop() {
        super.onStop()
        player?.let { playbackPosition = it.currentPosition }
    }

    override fun onDestroy() {
        player?.let {
            playbackPosition = it.currentPosition
            it.release()
        }
        player = null
        super.onDestroy()
    }
}
