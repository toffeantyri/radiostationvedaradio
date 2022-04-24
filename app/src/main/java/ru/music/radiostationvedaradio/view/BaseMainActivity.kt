package ru.music.radiostationvedaradio.view

import android.app.ActivityManager
import android.content.*
import android.net.Uri
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import kotlinx.android.synthetic.main.activity_main.*
import ru.music.radiostationvedaradio.R
import ru.music.radiostationvedaradio.services.*
import ru.music.radiostationvedaradio.viewmodel.ViewModelMainActivity

open class BaseMainActivity : AppCompatActivity() {

    protected val dataModel: ViewModelMainActivity by viewModels()
    protected var STATE_OF_SERVICE_A = InitStatusMediaPlayer.IDLE
        set(value) {
            field = value
            dataModel.statusMediaPlayer.value = value
        }

    protected var serviceBound = false
        set(value) {
            field = value
            Log.d("MyLog", "serviceBound -> $value")
        }

    protected var mediaService: RadioPlayerService? = null

    private var myMenu: Menu? = null
    private lateinit var btnPlay: MenuItem
    private lateinit var btnRefresh: MenuItem

    protected var url: String = ""
        set(value) {
            field = value
            updateCheckGroupQuality(value)
            Log.d("MyLog", "Activity : url -> $value")
        }

    private fun updateCheckGroupQuality(url: String) {
        if (myMenu == null) return
        myMenu?.apply {
            findItem(R.id.action_low_quality)?.isChecked = false
            findItem(R.id.action_medium_quality)?.isChecked = false
            findItem(R.id.action_high_quality)?.isChecked = false
        }
        when (url) {
            getString(R.string.veda_radio_stream_link_low) -> {
                myMenu?.findItem(R.id.action_low_quality)?.isChecked = true
            }
            getString(R.string.veda_radio_stream_link_medium) -> {
                myMenu?.findItem(R.id.action_medium_quality)?.isChecked = true
            }
            getString(R.string.veda_radio_stream_link_high) -> {
                myMenu?.findItem(R.id.action_high_quality)?.isChecked = true
            }
        }
    }

    private fun <T> Context.isServiceRunning(service: Class<T>) =
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == service.name }

    protected val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RadioPlayerService.LocalBinder
            mediaService = binder.getService()
            serviceBound = true
            STATE_OF_SERVICE_A = mediaService?.getStatusMediaMplayer() ?: InitStatusMediaPlayer.IDLE
            url = mediaService?.getPlayingURL() ?: ""
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
            mediaService = null
        }
    }

    protected fun setUpActionBar() {
        supportActionBar?.apply {
            setHomeButtonEnabled(true)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_menu_black_24dp)
        }
    }

    private val broadcastStateServiceListener = object : BroadcastReceiverForPlayerService() {
        override fun onReceive(context: Context?, intent: Intent?) {
            STATE_OF_SERVICE_A =
                intent?.getSerializableExtra(TAG_STATE_SERVICE) as InitStatusMediaPlayer
        }
    }

    protected fun registerBroadcastStateService() {
        registerReceiver(broadcastStateServiceListener, IntentFilter(Broadcast_STATE_SERVICE))
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        if (menu == null) {
            return super.onCreateOptionsMenu(menu)
        }
        myMenu = menu
        updateCheckGroupQuality(url)
        btnPlay = menu.findItem(R.id.action_play)
        btnRefresh = menu.findItem(R.id.action_refresh)

        dataModel.statusMediaPlayer.observe(this) {
            if (it == InitStatusMediaPlayer.PLAYING) btnPlay.setIcon(R.drawable.ic_baseline_pause_circle_filled_24)
            else if (it != InitStatusMediaPlayer.PLAYING) btnPlay.setIcon(R.drawable.ic_baseline_play_circle_filled_24)
        }
        dataModel.statusMediaPlayer.observe(this) {
            if (it == InitStatusMediaPlayer.INITIALISATION) {
                btnRefresh.setActionView(R.layout.action_progressbar)
                btnRefresh.expandActionView()
            } else btnRefresh.actionView = null

        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_play && mediaService != null) {
            Log.d("MyLog", "action_play click. isPlaying: ${dataModel.statusMediaPlayer.value}")
            when (dataModel.statusMediaPlayer.value) {
                InitStatusMediaPlayer.INIT_COMPLETE -> mediaService?.playMedia()
                InitStatusMediaPlayer.PLAYING -> mediaService?.pauseMedia()
                InitStatusMediaPlayer.IDLE -> playAudio(url)
                InitStatusMediaPlayer.INITIALISATION -> {
                    Toast.makeText(this, "Loading", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (item.itemId == R.id.action_refresh) {
            playAudio(url)
        } else if (item.itemId == R.id.action_low_quality) {
            url = getString(R.string.veda_radio_stream_link_low)
            playAudio(url)
        } else if (item.itemId == R.id.action_medium_quality) {
            url = getString(R.string.veda_radio_stream_link_medium)
            playAudio(url)
        } else if (item.itemId == R.id.action_high_quality) {
            url = getString(R.string.veda_radio_stream_link_high)
            playAudio(url)
        } else if (item.itemId == android.R.id.home) {
            if (drawer_menu.isDrawerOpen(GravityCompat.START)) drawer_menu.closeDrawer(GravityCompat.START)
            else drawer_menu.openDrawer(GravityCompat.START)
        }
        return super.onOptionsItemSelected(item)
    }

    protected fun playAudio(urlStream: String) {
        if (!serviceBound) {
            Log.d("MyLog", "StartService")
            val playerIntent = Intent(this, RadioPlayerService::class.java)
            if (this.isServiceRunning(RadioPlayerService::class.java)) {
                playerIntent.putExtra(TAG_FIRST_RUN, false)
            } else {
                playerIntent.putExtra(TAG_FIRST_RUN, true)
                playerIntent.putExtra(TAG_NEW_AUDIO_URL, urlStream)
            }
            applicationContext.startForegroundService(playerIntent)
            bindService(playerIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        } else {
            Log.d("MyLog", "service is already bound")
            val broadcastIntent: Intent = Intent(Broadcast_NEW_AUDIO)
            broadcastIntent.putExtra(TAG_NEW_AUDIO_URL, urlStream)
            broadcastIntent.putExtra(TAG_FIRST_RUN, false)
            sendBroadcast(broadcastIntent)
        }
    }

    protected fun setUpOnItemClickDrawerMenu() {
        draw_navView.setNavigationItemSelectedListener {
            when (it.itemId) {
                R.id.nav_item_exit -> {
                    val aDialog = AlertDialog.Builder(this)
                    aDialog.apply {
                        setMessage(R.string.alert_mes_exit)
                            .setCancelable(true)
                            .setNeutralButton(
                                R.string.alert_mes_yes,
                                DialogInterface.OnClickListener { dialog, id -> super.onBackPressed() })
                    }
                    aDialog.setPositiveButton(
                        R.string.alert_mes_yes_all,
                        DialogInterface.OnClickListener { _, _ ->
                            mediaService?.stopForeground(true)
                            mediaService?.stopSelf()
                            super.onBackPressed()
                        })
                    aDialog.setNegativeButton(
                        R.string.alert_mes_no,
                        DialogInterface.OnClickListener { dialog, id -> dialog.cancel() })
                    val alert = aDialog.create()
                    alert.show()
                }

                R.id.nav_item_rate_app -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.link_on_this_app)))
                    startActivity(intent)
                }

            }
            return@setNavigationItemSelectedListener true
        }
    }

}