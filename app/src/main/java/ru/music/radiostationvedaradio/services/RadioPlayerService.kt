package ru.music.radiostationvedaradio.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.*
import android.media.session.MediaSessionManager
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import ru.music.radiostationvedaradio.R
import ru.music.radiostationvedaradio.view.MainActivity
import ru.music.radiostationvedaradio.viewmodel.ViewModelMainActivity

const val ACTION_PLAY = "ru.music.vedaradio.ACTION_PLAY"
const val ACTION_PAUSE = "ru.music.vedaradio.ACTION_PAUSE"
const val ACTION_CANCEL = "ru.music.vedaradio.ACTION_CANCEL"
const val CHANNEL_ID = "ru.music.vedaradio.ID"
const val NOTIFICATION_ID = 101



class RadioPlayerService : Service(), MediaPlayer.OnCompletionListener,
    MediaPlayer.OnPreparedListener,
    MediaPlayer.OnErrorListener, MediaPlayer.OnSeekCompleteListener, MediaPlayer.OnInfoListener,
    MediaPlayer.OnBufferingUpdateListener, AudioManager.OnAudioFocusChangeListener {

    inner class LocalBinder : Binder() {
        fun getService(dataModel: ViewModelMainActivity): RadioPlayerService {
            //dataModelInner = dataModel
            return this@RadioPlayerService
        }
    }

    override fun onBind(intent: Intent): IBinder {
        return iBinder
    }

    private val iBinder: IBinder = LocalBinder()
    var urlString: String? = null

    private var resumePosition: Int = 0
    private var mediaPlayer: MediaPlayer? = null

    var STATE_OF_SERVICE = InitStatusMediaPlayer.IDLE
        set(value) {
            field = value
            broadcastTellNewStatus()
        }
    private var audioManager: AudioManager? = null

    private var onIncomingCall = false
    private var phoneStateListener: PhoneStateListener? = null
    private var telephonyManager: TelephonyManager? = null

    private var mediaSessionManager: MediaSessionManager? = null
    private var mediaSession: MediaSessionCompat? = null
    private var transportControls: MediaControllerCompat.TransportControls? = null

    private fun initMediaPlayer() {
        Log.d("MyLog", "initMediaPlayer start $STATE_OF_SERVICE")
        STATE_OF_SERVICE = InitStatusMediaPlayer.INITIALISATION
        mediaPlayer = MediaPlayer()
        mediaPlayer?.apply {
            setOnCompletionListener(this@RadioPlayerService)
            setOnErrorListener(this@RadioPlayerService)
            setOnPreparedListener(this@RadioPlayerService)
            setOnBufferingUpdateListener(this@RadioPlayerService)
            setOnSeekCompleteListener(this@RadioPlayerService)
            setOnInfoListener(this@RadioPlayerService)
            reset()
            setAudioAttributes(
                AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            if (urlString != null) setDataSource(urlString)
        }
        mediaPlayer?.prepareAsync()
    }

    private fun initMediaSession() {
        if (mediaSessionManager != null) return
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        mediaSession = MediaSessionCompat(applicationContext, "RadioPlayer")
        transportControls = mediaSession?.controller?.transportControls
        mediaSession?.isActive = true
        mediaSession?.setFlags(MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        updateMetaData()

        mediaSession?.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                super.onPlay()
                playMedia()
                updateMetaData()
                buildNotification(Playbackstatus.PLAYING)
            }

            override fun onPause() {
                super.onPause()
                pauseMedia()
                buildNotification(Playbackstatus.PAUSED)
            }

            override fun onStop() {
                super.onStop()
                stopMedia()
                removeNotification()
                stopSelf()
            }
        })
        Log.d("MyLog", "initMediaSession")
    }

    private fun updateMetaData() {
        Log.d("MyLog", "updateMetaData")
        val metaDataBuilder = MediaMetadataCompat.Builder().apply {
            //putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
            putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Artist")
            putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "ALBUM")
            putString(MediaMetadataCompat.METADATA_KEY_TITLE, "Title")
        }
        mediaSession?.setMetadata(metaDataBuilder.build())

    }

    private fun buildNotification(playbackstatus: Playbackstatus) {
        var notificationAction = android.R.drawable.ic_media_pause
        var playpauseAction: PendingIntent? = null
        var titleButton: String = ""
        var notRemoveOnSwipe = true

        val contentIntent = Intent(applicationContext, MainActivity::class.java)
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val name = "Channel"
        val descriptionText = "DescriptionChannel"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(channel)

        if (playbackstatus == Playbackstatus.PLAYING) {
            notificationAction = android.R.drawable.ic_media_pause
            playpauseAction = playbackAction(1)
            titleButton = "Play"
            notRemoveOnSwipe = true
        } else if (playbackstatus == Playbackstatus.PAUSED) {
            notificationAction = android.R.drawable.ic_media_play
            playpauseAction = playbackAction(0)
            titleButton = "Pause"
            notRemoveOnSwipe = false
        }

        val cancelDrawable = android.R.drawable.ic_menu_close_clear_cancel
        val largeIconDrawble = R.drawable.totemanimal_ivon
        val largeIcon: Bitmap = BitmapFactory.decodeResource(resources, largeIconDrawble)

        val notificationBuilder: NotificationCompat.Builder =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setShowWhen(false)
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession?.sessionToken)
                        .setShowActionsInCompactView(0, 1)
                )
                .setDefaults(0)
                .setColor(Color.GREEN)
                .setColorized(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setLargeIcon(largeIcon)
                .setSmallIcon(notificationAction)
                .setContentText("N:Text")
                .setContentTitle("N:Title")
                .addAction(notificationAction, titleButton, playpauseAction)
                .addAction(cancelDrawable, "Cancel", playbackAction(10))
                .setOngoing(notRemoveOnSwipe)
                .setContentIntent(PendingIntent.getActivity(this, 0, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT))

        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
        updateMetaData()
    }

    private fun playbackAction(actionNumber: Int): PendingIntent? {
        val playbackAction = Intent(this, RadioPlayerService::class.java)
        when (actionNumber) {
            10 -> {
                playbackAction.action = ACTION_CANCEL
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
            0 -> {
                playbackAction.action = ACTION_PLAY
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
            1 -> {
                playbackAction.action = ACTION_PAUSE
                return PendingIntent.getService(this, actionNumber, playbackAction, 0)
            }
        }
        return null
    }

    private fun handleIncomingAction(playbackAction: Intent) {
        if (playbackAction.action == null) return
        when (playbackAction.action!!) {
            ACTION_PLAY -> {
                transportControls?.play()
            }
            ACTION_PAUSE -> {
                transportControls?.pause()
            }
            ACTION_CANCEL -> {
                transportControls?.stop()
            }
        }
    }

    private fun removeNotification() {
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
    }

    override fun onCompletion(mp: MediaPlayer?) {
        stopMedia()
        stopSelf()
    }

    override fun onPrepared(mp: MediaPlayer?) {
        if (mediaPlayer == null) return
        STATE_OF_SERVICE = InitStatusMediaPlayer.INIT_COMPLETE
        playMedia()
        Log.d("MyLog", "Service ready for play")
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        STATE_OF_SERVICE = InitStatusMediaPlayer.IDLE
        Log.d("MyLog", "onError what:  $what + extra : $extra")
        when (what) {
            MediaPlayer.MEDIA_ERROR_NOT_VALID_FOR_PROGRESSIVE_PLAYBACK -> {
                Log.d("MyLog", "error : not valid progressive playback + $extra")
                Toast.makeText(this, "Неверный формат аудио", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
            MediaPlayer.MEDIA_ERROR_SERVER_DIED -> {
                Log.d("MyLog", "error : server died + $extra")
                Toast.makeText(this, "Сервер недоступен", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
            MediaPlayer.MEDIA_ERROR_UNKNOWN -> {
                Log.d("MyLog", "error : unknow + $extra")
                mediaPlayer = null
                Toast.makeText(this, "Ошибка соединения", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
            MediaPlayer.MEDIA_ERROR_TIMED_OUT -> {
                Log.d("MyLog", "error : time out")
                mediaPlayer = null
                Toast.makeText(this, "Превышено время ожидания ответа сервера", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
            else -> {
                Log.d("MyLog", "error : -38 + $extra")
                mediaPlayer = null
                Toast.makeText(this, "Ошибка загрузки", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        }
        return false
    }

    override fun onSeekComplete(mp: MediaPlayer?) {
    }

    override fun onInfo(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        return false
    }

    override fun onBufferingUpdate(mp: MediaPlayer?, percent: Int) {
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (mediaPlayer == null) {
                    initMediaPlayer()
                } else if (STATE_OF_SERVICE == InitStatusMediaPlayer.INIT_COMPLETE) {
                    playMedia()
                    mediaPlayer!!.setVolume(1.0f, 1.0f)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                //todo может оставить на паузе???
                stopMedia()
                mediaPlayer?.reset()
                STATE_OF_SERVICE = InitStatusMediaPlayer.IDLE
                mediaPlayer = null

            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (STATE_OF_SERVICE == InitStatusMediaPlayer.PLAYING) {
                    pauseMedia()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (STATE_OF_SERVICE == InitStatusMediaPlayer.PLAYING) {
                    mediaPlayer?.setVolume(0.1f, 0.1f)
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        val result = audioManager?.requestAudioFocus(
            this,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return true
        }
        return false
    }

    private fun removeAudioFocus(): Boolean {
        return AudioManager.AUDIOFOCUS_REQUEST_GRANTED == audioManager?.abandonAudioFocus(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            urlString = intent?.extras?.getString("url")
        } catch (e: NullPointerException) {
            stopSelf()
        }
        Log.d("MyLog, ", "onStartCommand requestAudioFocus: ${requestAudioFocus()}")
        if (!requestAudioFocus()) {
            stopSelf()
        }
        if (mediaSessionManager == null) {
            try {
                initMediaSession()
                initMediaPlayer()
            } catch (e: RemoteException) {
                e.printStackTrace()
                stopSelf()
            }
            buildNotification(Playbackstatus.PAUSED)
        }
        if (intent != null) handleIncomingAction(intent)

        return super.onStartCommand(intent, flags, startId)
    }

//----------------------------MediaPlayerControl-------------------------------------------

    fun playMedia() {
        if (mediaPlayer == null) return
        if (STATE_OF_SERVICE == InitStatusMediaPlayer.INIT_COMPLETE) {
            mediaPlayer?.start()
            STATE_OF_SERVICE = InitStatusMediaPlayer.PLAYING
            buildNotification(Playbackstatus.PLAYING)
        }
    }

    fun stopMedia() {
        if (mediaPlayer == null) return
        mediaPlayer?.stop()
        STATE_OF_SERVICE = InitStatusMediaPlayer.IDLE
        removeNotification()
    }

    fun pauseMedia() {
        if (mediaPlayer == null) return
        if (STATE_OF_SERVICE == InitStatusMediaPlayer.PLAYING) {
            mediaPlayer?.pause()
            resumePosition = mediaPlayer!!.currentPosition
            STATE_OF_SERVICE = InitStatusMediaPlayer.INIT_COMPLETE
            buildNotification(Playbackstatus.PAUSED)
        }
    }

    fun resumeMedia() {
        if (mediaPlayer == null) return
        if (STATE_OF_SERVICE == InitStatusMediaPlayer.INIT_COMPLETE) {
            mediaPlayer!!.seekTo(resumePosition)
            mediaPlayer!!.start()
            STATE_OF_SERVICE = InitStatusMediaPlayer.PLAYING
            buildNotification(Playbackstatus.PLAYING)
        }
    }

//------------------------------------------------------------------------

    private val broadcastReceiverNewAudio = object : BroadcastReceiverForPlayerService() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                urlString = intent!!.getStringExtra(TAG_NEW_AUDIO_URL)
            } catch (e: NullPointerException) {
                stopSelf()
            }
            stopMedia()
            mediaPlayer?.reset()
            initMediaPlayer()
            buildNotification(Playbackstatus.PLAYING)
        }
    }

    private fun broadcastTellNewStatus(){
        val broadcastIntent: Intent = Intent(Broadcast_STATE_SERVICE)
        broadcastIntent.putExtra(TAG_STATE_SERVICE, STATE_OF_SERVICE)
        sendBroadcast(broadcastIntent)
    }

    private fun registerPlayNewAudio() {
        val filter = IntentFilter(Broadcast_NEW_AUDIO)
        registerReceiver(broadcastReceiverNewAudio, filter)
    }

    private fun callStateListener() {
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                    }
                    TelephonyManager.CALL_STATE_RINGING -> {
                        if (mediaPlayer != null) {
                            pauseMedia()
                            onIncomingCall = true
                        }
                    }
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (mediaPlayer != null) {
                            if (onIncomingCall) {
                                onIncomingCall = false
                                playMedia()
                            }
                        }
                    }
                }
            }
        }
        telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    override fun onCreate() {
        super.onCreate()
        callStateListener()
        registerPlayNewAudio()
    }

    override fun onDestroy() {
        removeNotification()
        if (mediaPlayer != null) {
            stopMedia()
            mediaPlayer!!.release()
        }
        STATE_OF_SERVICE = InitStatusMediaPlayer.IDLE
        removeAudioFocus()
        if (phoneStateListener != null) {
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
        unregisterReceiver(broadcastReceiverNewAudio)
        super.onDestroy()
    }

}