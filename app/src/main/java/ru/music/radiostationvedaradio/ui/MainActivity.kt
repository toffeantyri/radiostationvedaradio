package ru.music.radiostationvedaradio.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.BaseAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.yandex.mobile.ads.banner.BannerAdEventListener
import com.yandex.mobile.ads.banner.BannerAdSize
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.music.radiostationvedaradio.R
import ru.music.radiostationvedaradio.data.model.menus.ExpandableChildItem
import ru.music.radiostationvedaradio.data.model.menus.ExpandableMenuItem
import ru.music.radiostationvedaradio.data.model.menus.SimpleMenuItem
import ru.music.radiostationvedaradio.databinding.ActivityMainBinding
import ru.music.radiostationvedaradio.services.RadioMediaService
import ru.music.radiostationvedaradio.ui.adapters.OnFilterClickListener
import ru.music.radiostationvedaradio.ui.adapters.expandableList.ExpandableListAdapterForNavView
import ru.music.radiostationvedaradio.ui.adapters.filter_adapter.MenuArrayAdapter
import ru.music.radiostationvedaradio.ui.adapters.listview.ListViewAdapter
import ru.music.radiostationvedaradio.ui.controller.PlayerStateListener
import ru.music.radiostationvedaradio.ui.screens.TAG_WEB_URL
import ru.music.radiostationvedaradio.ui.viewmodel.ViewModelMainActivity
import ru.music.radiostationvedaradio.utils.LOG_TAG
import ru.music.radiostationvedaradio.utils.exitDialog
import ru.music.radiostationvedaradio.utils.openIntentUrl


class MainActivity : AppCompatActivity(), OnFilterClickListener {

    private var controllerJob: Job? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private lateinit var controller: MediaController
    private val playerListener by lazy { PlayerStateListener(controller) }


    private val qualityAdapter by lazy {
        MenuArrayAdapter(
            this,
            this.resources.getStringArray(R.array.array_quality_list).toMutableList(),
            this
        )
    }
    private lateinit var binding: ActivityMainBinding
    private val viewModel: ViewModelMainActivity by viewModels()
    var webUrl: String? = "" // url для WebFragment public для webFragment

    private val mMenuAdapter: ExpandableListAdapterForNavView by lazy {
        ExpandableListAdapterForNavView(this, listDataHeader, listDataChild)
    }
    private val listDataHeader: List<ExpandableMenuItem> by lazy {
        listOf(ExpandableMenuItem(getString(R.string.link_header_name), R.drawable.ic_bookmark))
    }
    private val listDataChild: HashMap<ExpandableMenuItem, List<ExpandableChildItem>> by lazy {
        hashMapOf(
            Pair(
                listDataHeader[0],
                listOf(
                    ExpandableChildItem(R.string.name_veda_radio_site, R.string.veda_radio_site),
                    ExpandableChildItem(R.string.name_torsunov_site, R.string.torsunov_site),
                    ExpandableChildItem(R.string.name_provedy_site, R.string.provedy_site)
                )
            )
        )
    }
    private val listViewData: List<SimpleMenuItem> by lazy {
        listOf(
            SimpleMenuItem(getString(R.string.bad_advice_header_name), R.drawable.ic_note),
            SimpleMenuItem(getString(R.string.item_about_app), R.drawable.ic_star_rate),
            SimpleMenuItem(getString(R.string.item_exit), R.drawable.ic_exit)
        )
    }
    private val adapterListView: BaseAdapter by lazy { ListViewAdapter(listViewData) }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        initView()
        loadAndShowBanner()
    }


    @SuppressLint("RepeatOnLifecycleWrongUsage")
    private fun collectorPlayerState() {
        controllerJob?.cancel()
        controllerJob = lifecycleScope.launch {
            playerListener.playStateFlow.collect { isPlaying ->
                Log.d(LOG_TAG, "VIEW COLLECTOR is Play $isPlaying")
                with(binding) {
                    if (isPlaying) {
                        toolbarContainer.actionPlay.setImageResource(R.drawable.ic_pause)
                        toolbarContainer.actionPlay.setOnClickListener {
                            controller.pause()
                        }
                    } else {
                        toolbarContainer.actionPlay.setImageResource(R.drawable.ic_play_filled)
                        toolbarContainer.actionPlay.setOnClickListener {
                            controller.play()
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, RadioMediaService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controllerFuture?.let {
                controller = it.get()
                binding.slidingPanelPlayer.playerView.player = controller
                controller.addListener(playerListener)
                collectorPlayerState()
                initQualityChooser()
                if (controller.isPlaying.not()) {
                    pushUrl(getUrlByPos(qualityAdapter.checkedPosition))
                }
            }
        }, MoreExecutors.directExecutor())


    }

    override fun onStop() {
        controller.removeListener(playerListener)
        controllerFuture?.let { controller ->
            MediaController.releaseFuture(controller)
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        volumeControlStream = AudioManager.STREAM_MUSIC
    }


    override fun onDestroy() {
        binding.mainBanner.destroy()
        super.onDestroy()
    }

    override fun onItemFilterClick(position: Int) {
        pushUrl(getUrlByPos(position))
        qualityAdapter.checkedPosition = position
        qualityAdapter.notifyDataSetChanged()
    }

    private fun getUrlByPos(pos: Int): String {
        return when (pos) {
            0 -> getString(R.string.veda_radio_stream_link_low)
            1 -> getString(R.string.veda_radio_stream_link_medium)
            2 -> getString(R.string.veda_radio_stream_link_high)
            else -> getString(R.string.veda_radio_stream_link_low)
        }
    }

    private fun getPosByUrl(url: String?): Int {
        return when (url) {
            getString(R.string.veda_radio_stream_link_low) -> 0
            getString(R.string.veda_radio_stream_link_medium) -> 1
            getString(R.string.veda_radio_stream_link_high) -> 2
            else -> 0
        }
    }


    private fun loadAndShowBanner() {
        with(binding) {
            mainBanner.apply {
                setAdUnitId(getString(R.string.yandex_banner_desc_id_test))
                setAdSize(BannerAdSize.inlineSize(context, 320, 50))
            }
            val adRequest = AdRequest.Builder().build()
            mainBanner.setBannerAdEventListener(object : BannerAdEventListener {
                override fun onAdLoaded() {}
                override fun onAdFailedToLoad(p0: AdRequestError) {}
                override fun onAdClicked() {}
                override fun onLeftApplication() {}
                override fun onReturnedToApplication() {}
                override fun onImpression(p0: ImpressionData?) {}
            })
            mainBanner.loadAd(adRequest)
        }
    }


    private var doubleBackPress = false

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.main_nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        if (navController.currentDestination?.id != navController.graph.startDestinationId) {
            super.onBackPressed()
            return
        }
        if (doubleBackPress) super.onBackPressed()
        doubleBackPress = true
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({ doubleBackPress = false }, 2000)
        this.exitDialog(onFullExit = {})
    }


    private fun navigateWebFragmentWithUrl(webUrl: String) {
        val bundle = Bundle()
        bundle.putString(TAG_WEB_URL, webUrl)
        findNavController(R.id.main_nav_host_fragment).navigate(R.id.webViewFragment, bundle)
    }


    private fun initMenuList() {
        with(binding) {
            listviewNavMenu.adapter = adapterListView
            listviewNavMenu.setOnItemClickListener { _, _, position, _ ->
                when (position) {
                    0 -> {
                        drawerMenu.closeDrawer(GravityCompat.START)
                        Handler(Looper.getMainLooper()).postDelayed({
                            findNavController(R.id.main_nav_host_fragment).navigate(R.id.badAdviceFragment)
                        }, 300)
                    }

                    1 -> openIntentUrl(getString(R.string.link_on_this_app))
                    2 -> exitDialog(onFullExit = {})
                }
            }


            drawNavView.setNavigationItemSelectedListener { item ->
                item.isChecked = true
                drawerMenu.closeDrawers()
                true
            }

            expListNavMenu.setAdapter(mMenuAdapter)
            expListNavMenu.setOnChildClickListener { _, _, groupPosition, childPosition, _ ->
                val key = listDataHeader[groupPosition]
                val list = listDataChild[key]
                list?.get(childPosition)?.let {
                    val newUrl = getString(it.linkUrl)
                    webUrl = newUrl
                    navigateWebFragmentWithUrl(newUrl)
                    drawerMenu.closeDrawer(GravityCompat.START)
                }
                true
            }
        }
    }

    private fun initView() {
        initMenuList()
        with(binding) {
            toolbarContainer.actionHome.setOnClickListener {
                CoroutineScope(Dispatchers.Main).launch {
                    if (binding.drawerMenu.isDrawerOpen(GravityCompat.START)) binding.drawerMenu.closeDrawer(
                        GravityCompat.START
                    )
                    else binding.drawerMenu.openDrawer(GravityCompat.START)
                }
            }
        }
    }


    private fun initQualityChooser() {
        with(binding.toolbarContainer) {
            val playingUrl = if (controller.mediaItemCount > 0) {
                controller.getMediaItemAt(0).mediaId
            } else {
                null
            }
            qualityAdapter.checkedPosition = getPosByUrl(playingUrl)
            qualityAdapter.setHeaderViewVisibility(false)
            qualityAdapter.setArrowViewVisibility(false)
            qualitySpinner.adapter = qualityAdapter
            qualitySpinner.setOnTouchListener(qualityAdapter.getUserSelectionClickListener())
            qualitySpinner.onItemSelectedListener = qualityAdapter.getUserSelectionClickListener()
        }
    }

    private fun pushUrl(urlStream: String) {
        controller.apply {
            val uri = Uri.parse(urlStream)
            val newItem = MediaItem.Builder().setMediaId(urlStream).setUri(uri).build()
            setMediaItem(newItem)
            prepare()
            controller.playWhenReady = true
        }
    }

}