package com.v2plus.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import android.graphics.drawable.GradientDrawable
import com.v2plus.app.AppConfig
import com.v2plus.app.BuildConfig
import com.v2plus.app.R
import com.v2plus.app.databinding.ActivityMainBinding
import com.v2plus.app.dto.CheckUpdateResult
import com.v2plus.app.handler.CustomizationManager
import com.v2plus.app.enums.EConfigType
import com.v2plus.app.enums.PermissionType
import com.v2plus.app.extension.toast
import com.v2plus.app.extension.toastError
import com.v2plus.app.handler.AngConfigManager
import com.v2plus.app.handler.MmkvManager
import com.v2plus.app.handler.SettingsChangeManager
import com.v2plus.app.handler.SettingsManager
import com.v2plus.app.handler.UpdateCheckerManager
import com.v2plus.app.handler.V2RayServiceManager
import com.v2plus.app.dto.TestServiceMessage
import com.v2plus.app.util.MessageUtil
import com.v2plus.app.util.Utils
import com.v2plus.app.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : HelperBaseActivity() {
    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null
    private val groupPageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            if (isFinishing || isDestroyed) return
            val id = groupPagerAdapter.groups.getOrNull(position)?.id ?: return
            mainViewModel.subscriptionIdChanged(id)
        }
    }
    private var startupUpdatePromptShown = false
    private var updateDownloadJob: Job? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.app_name))

        // setup viewpager and tablayout
        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true
        binding.viewPager.registerOnPageChangeCallback(groupPageChangeCallback)

        // setup navigation drawer
        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        setupDrawerClickListeners()
        

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }

        setupGroupTab()
        setupViewModel()
        mainViewModel.reloadServerList()
        applyCustomColors()
        checkForUpdatesOnStartup()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun applyCustomColors() {
        val mainContent = binding.drawerLayout.getChildAt(0)
        CustomizationManager.applyBackgroundGradient(mainContent)

        CustomizationManager.applyAccentToFab(binding.fab)
        CustomizationManager.applyAccentToTabIndicator(binding.tabGroup)
        binding.tabGroup.setTabTextColors(
            android.graphics.Color.parseColor("#A1A1AA"),
            CustomizationManager.getAccentOpaque()
        )

        binding.layoutTest.background = CustomizationManager.createCardDrawable(20f)
        binding.tabGroup.background = CustomizationManager.createCardDrawable(20f)
        binding.progressBar.setIndicatorColor(CustomizationManager.getAccentOpaque())

        val drawerRoot = binding.drawerContent.root
        CustomizationManager.applyBackgroundGradient(drawerRoot)
        CustomizationManager.tintAllDrawerIcons(drawerRoot)
        binding.drawerContent.drawerLogo.clearColorFilter()

        val headerView = (drawerRoot as? android.view.ViewGroup)?.getChildAt(0)
        headerView?.background = CustomizationManager.createHeaderDrawable()

        applyDrawerCardBgs(drawerRoot)
        applyDrawerIconBgs(drawerRoot)
    }

    private fun applyDrawerCardBgs(root: android.view.View) {
        if (CustomizationManager.isCompatibilityMode()) return
        if (root !is android.view.ViewGroup) return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            val bg = child.background
            if (bg is android.graphics.drawable.GradientDrawable || child.tag == "drawer_card") {
                // skip
            }
            if (child is android.view.ViewGroup) {
                applyDrawerCardBgs(child)
            }
        }
        val ids = intArrayOf(
            R.id.drawer_sub_setting, R.id.drawer_per_app_proxy,
            R.id.drawer_routing, R.id.drawer_user_asset,
            R.id.drawer_customization, R.id.drawer_settings,
            R.id.drawer_check_update, R.id.drawer_content_transfer,
            R.id.drawer_get_servers
        )
        for (id in ids) {
            val item = root.findViewById<android.view.View>(id) ?: continue
            val parent = item.parent as? android.view.ViewGroup ?: continue
            parent.background = CustomizationManager.createDrawerCardDrawable()
        }
    }

    private fun applyDrawerIconBgs(root: android.view.View) {
        if (CustomizationManager.isCompatibilityMode()) return
        if (root !is android.view.ViewGroup) return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is android.widget.FrameLayout && child.layoutParams.width > 0) {
                val lp = child.layoutParams
                val dp36 = (36 * resources.displayMetrics.density).toInt()
                if (lp.width == dp36 && lp.height == dp36) {
                    child.background = CustomizationManager.createIconBgDrawable()
                }
            }
            if (child is android.view.ViewGroup) {
                applyDrawerIconBgs(child)
            }
        }
    }

    private fun setupViewModel() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(false, isRunning)
        }
        mainViewModel.isPinging.observe(this) { isPinging ->
            binding.progressBar.isVisible = isPinging
            binding.ivTestCancel.isVisible = isPinging
        }
        
        binding.ivTestCancel.setOnClickListener {
            MessageUtil.sendMsg2TestService(
                this,
                TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
            )
            mainViewModel.isPinging.value = false
            setTestState(getString(R.string.connection_test_pending))
        }
        mainViewModel.setupGroupTabAction.observe(this) {
            setupGroupTab()
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun setupGroupTab() {
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            val allGroups = mainViewModel.getSubscriptions(this)
            
            // Filter out group headers for tabs (only show actual subscriptions)
            val tabGroups = allGroups.filter { !it.isGroup }
            groupPagerAdapter.update(tabGroups)

            tabMediator?.detach()
            tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
                groupPagerAdapter.groups.getOrNull(position)?.let {
                    tab.text = it.remarks
                    tab.tag = it.id
                }
            }.also { it.attach() }

            val targetIndex = tabGroups.indexOfFirst { it.id == mainViewModel.subscriptionId }.takeIf { it >= 0 } ?: (tabGroups.size - 1)
            binding.viewPager.setCurrentItem(targetIndex, false)

            binding.tabGroup.isVisible = tabGroups.size > 1
        }
    }

    private fun handleFabAction() {
        applyRunningState(isLoading = true, isRunning = false)

        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        } else {
            // service not running: keep existing no-op (could show a message if desired)
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private  fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            return
        }

        if (isRunning) {
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(CustomizationManager.opaque(CustomizationManager.getAccent()))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
        } else {
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
        }
    }

    override fun onResume() {
        super.onResume()
        checkPendingUpdate()
    }

    private fun checkPendingUpdate() {
        if (!BuildConfig.IN_APP_UPDATE_ENABLED) return
        val pendingUrl = MmkvManager.getPendingUpdateUrl() ?: return
        val pendingVersion = MmkvManager.getPendingUpdateVersion() ?: return
        val apkFile = File(cacheDir, "update.apk")

        if (apkFile.exists() && apkFile.length() > 0) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                toast(R.string.update_install_permission_required)
                return
            }
            MmkvManager.clearPendingUpdate()
            installDownloadedApk(apkFile)
        } else if (updateDownloadJob == null || !updateDownloadJob!!.isActive) {
            showPendingUpdateDialog(pendingUrl, pendingVersion)
        }
    }

    private fun showPendingUpdateDialog(downloadUrl: String, version: String) {
        if (isFinishing) return
        val message = getString(
            R.string.update_modal_message,
            BuildConfig.VERSION_NAME,
            version
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_new_version_found, version))
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ ->
                startUpdateDownload(downloadUrl, version)
            }
            .setNegativeButton(R.string.update_skip) { _, _ ->
                MmkvManager.clearPendingUpdate()
            }
            .setCancelable(false)
            .show()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            val savedQuery = mainViewModel.keywordFilter

            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })

            searchView.setOnCloseListener {
                false
            }

            searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
                override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                    if (mainViewModel.keywordFilter.isNotEmpty()) {
                        searchView.setQuery(mainViewModel.keywordFilter, false)
                    }
                    return true
                }

                override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                    searchView.clearFocus()
                    return true
                }
            })

            if (savedQuery.isNotEmpty()) {
                searchView.setQuery(savedQuery, false)
                searchView.clearFocus()
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.import_qrcode -> {
            importQRcode()
            true
        }

        R.id.import_clipboard -> {
            importClipboard()
            true
        }

        R.id.import_local -> {
            importConfigLocal()
            true
        }

        R.id.import_manually_policy_group -> {
            importManually(EConfigType.POLICYGROUP.value)
            true
        }

        R.id.import_manually_vmess -> {
            importManually(EConfigType.VMESS.value)
            true
        }

        R.id.import_manually_vless -> {
            importManually(EConfigType.VLESS.value)
            true
        }

        R.id.import_manually_ss -> {
            importManually(EConfigType.SHADOWSOCKS.value)
            true
        }

        R.id.import_manually_socks -> {
            importManually(EConfigType.SOCKS.value)
            true
        }

        R.id.import_manually_http -> {
            importManually(EConfigType.HTTP.value)
            true
        }

        R.id.import_manually_trojan -> {
            importManually(EConfigType.TROJAN.value)
            true
        }

        R.id.import_manually_wireguard -> {
            importManually(EConfigType.WIREGUARD.value)
            true
        }

        R.id.import_manually_hysteria -> {
            importManually(EConfigType.HYSTERIA.value)
            true
        }

        R.id.import_manually_hysteria2 -> {
            importManually(EConfigType.HYSTERIA2.value)
            true
        }

        R.id.import_manually_tuic -> {
            importManually(EConfigType.TUIC.value)
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        R.id.locate_selected_config -> {
            locateSelectedServer()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    /**
     * import config from qrcode
     */
    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    /**
     * import config from clipboard
     */
    private fun importClipboard()
            : Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(this@MainActivity, server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    /**
     * import config from local config file
     */
    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }


    /**
     * import config from sub
     */
    fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    toast(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                } else {
                    toast(
                        getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                }
                hideLoading()
            }
        }
        return true
    }

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0)
                    toast(getString(R.string.title_export_config_count, ret))
                else
                    toastError(R.string.toast_failure)
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                //do noting
            }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    /**
     * show file chooser
     */
    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }

            readContentFromUri(uri)
        }
    }

    /**
     * read content from uri
     */
    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    /**
     * Locates and scrolls to the currently selected server.
     * If the selected server is in a different group, automatically switches to that group first.
     */
    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect()
        if (targetSubscriptionId.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }

        val targetGroupIndex = groupPagerAdapter.groups.indexOfFirst { it.id == targetSubscriptionId }
        if (targetGroupIndex < 0) {
            toast(R.string.toast_server_not_found_in_group)
            return
        }

        // Switch to target group if needed, then scroll to the server
        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else {
            scrollToSelectedServer(targetGroupIndex)
        }
    }

    /**
     * Scrolls to the selected server in the specified fragment.
     * @param groupIndex The index of the group/fragment to scroll in
     */
    private fun scrollToSelectedServer(groupIndex: Int) {
        val itemId = groupPagerAdapter.getItemId(groupIndex)
        val fragment = supportFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment

        if (fragment?.isAdded == true && fragment.view != null) {
            fragment.scrollToSelectedServer()
        } else {
            toast(R.string.toast_fragment_not_available)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }


    private fun setupDrawerClickListeners() {
        val drawer = binding.drawerContent
        drawer.drawerCheckUpdate.isVisible = BuildConfig.IN_APP_UPDATE_ENABLED

        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            drawer.tvDrawerVersion.text = "v$versionName"
        } catch (_: Exception) {
        }

        fun onDrawerItemClick(action: () -> Unit) {
            action()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }

        drawer.drawerGetServers.setOnClickListener {
            onDrawerItemClick { requestActivityLauncher.launch(Intent(this, RecommendedServersActivity::class.java)) }
        }
        drawer.drawerOurTelegram.setOnClickListener {
            onDrawerItemClick { Utils.openUri(this, "https://t.me/GoodbyeWLALT") }
        }
        drawer.drawerSubSetting.setOnClickListener {
            onDrawerItemClick { requestActivityLauncher.launch(Intent(this, SubSettingActivity::class.java)) }
        }
        drawer.drawerPerAppProxy.setOnClickListener {
            onDrawerItemClick { requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java)) }
        }
        drawer.drawerRouting.setOnClickListener {
            onDrawerItemClick { requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java)) }
        }
        drawer.drawerBridgeSetting.setOnClickListener {
            onDrawerItemClick { requestActivityLauncher.launch(Intent(this, BridgeActivity::class.java)) }
        }
        drawer.drawerUserAsset.setOnClickListener {
            onDrawerItemClick { requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java)) }
        }
        drawer.drawerCustomization.setOnClickListener {
            onDrawerItemClick { requestActivityLauncher.launch(Intent(this, CustomizationActivity::class.java)) }
        }
        drawer.drawerSettings.setOnClickListener {
            onDrawerItemClick { requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java)) }
        }
        drawer.drawerLogcat.setOnClickListener {
            onDrawerItemClick { requestActivityLauncher.launch(Intent(this, LogcatActivity::class.java)) }
        }
        drawer.drawerCheckUpdate.setOnClickListener {
            onDrawerItemClick { requestActivityLauncher.launch(Intent(this, CheckUpdateActivity::class.java)) }
        }
        drawer.drawerContentTransfer.setOnClickListener {
            onDrawerItemClick { requestActivityLauncher.launch(Intent(this, ContentTransferActivity::class.java)) }
        }
    }

    private fun checkForUpdatesOnStartup() {
        if (!BuildConfig.IN_APP_UPDATE_ENABLED) return
        if (startupUpdatePromptShown) return
        startupUpdatePromptShown = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { UpdateCheckerManager.checkForUpdate() }
                if (result.hasUpdate && !result.downloadUrl.isNullOrBlank() && !isFinishing) {
                    showUpdateFoundDialog(result)
                }
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "Startup update check failed: ${e.message}")
            }
        }
    }

    private fun showUpdateFoundDialog(result: CheckUpdateResult) {
        val latestVersion = result.latestVersion ?: return
        val message = getString(
            R.string.update_modal_message,
            BuildConfig.VERSION_NAME,
            latestVersion
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_new_version_found, latestVersion))
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ ->
                startUpdateDownload(result.downloadUrl.orEmpty(), latestVersion)
            }
            .setNegativeButton(R.string.update_skip, null)
            .setCancelable(false)
            .show()
    }

    private var updateWakeLock: PowerManager.WakeLock? = null

    private fun startUpdateDownload(downloadUrl: String, latestVersion: String) {
        if (downloadUrl.isBlank()) return
        updateDownloadJob?.cancel()

        MmkvManager.savePendingUpdate(downloadUrl, latestVersion)

        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, 0)
        }

        val progressBar = android.widget.ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
        }
        val tvProgress = android.widget.TextView(this)
        val tvSize = android.widget.TextView(this)
        val tvSpeed = android.widget.TextView(this)
        val tvEta = android.widget.TextView(this)
        tvProgress.text = getString(R.string.update_download_progress, 0)
        tvSize.text = getString(R.string.update_download_size, "0 B", getString(R.string.update_size_unknown))
        tvSpeed.text = getString(R.string.update_download_speed, "0 B")
        tvEta.text = getString(R.string.update_download_eta, getString(R.string.update_eta_unknown))

        content.addView(progressBar)
        content.addView(tvProgress)
        content.addView(tvSize)
        content.addView(tvSpeed)
        content.addView(tvEta)

        var skipped = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_downloading_title, latestVersion))
            .setView(content)
            .setNegativeButton(R.string.update_skip) { _, _ ->
                skipped = true
                updateDownloadJob?.cancel()
            }
            .setCancelable(false)
            .create()
        dialog.show()

        // Acquire wake lock for the duration of the download (no timeout)
        updateWakeLock = (getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "v2plus:apk_download")
            ?.apply { acquire() }

        updateDownloadJob = lifecycleScope.launch {
            var downloadErrorMessage: String? = null
            val apkFile = withContext(Dispatchers.IO) {
                UpdateCheckerManager.downloadApk(
                    context = this@MainActivity,
                    downloadUrl = downloadUrl,
                    onProgress = { progress ->
                        runOnUiThread {
                            progressBar.progress = progress.percent
                            tvProgress.text = getString(R.string.update_download_progress, progress.percent)

                            val totalText = if (progress.totalBytes > 0) {
                                formatBytes(progress.totalBytes)
                            } else {
                                getString(R.string.update_size_unknown)
                            }
                            tvSize.text = getString(
                                R.string.update_download_size,
                                formatBytes(progress.downloadedBytes),
                                totalText
                            )
                            tvSpeed.text = getString(
                                R.string.update_download_speed,
                                formatBytes(progress.speedBytesPerSec)
                            )
                            val etaText = if (progress.etaSeconds >= 0) {
                                formatEta(progress.etaSeconds)
                            } else {
                                getString(R.string.update_eta_unknown)
                            }
                            tvEta.text = getString(R.string.update_download_eta, etaText)
                        }
                    },
                    onError = { err ->
                        downloadErrorMessage = err
                    }
                )
            }

            updateWakeLock?.let { if (it.isHeld) it.release() }
            updateWakeLock = null

            if (dialog.isShowing) {
                dialog.dismiss()
            }
            if (apkFile != null) {
                installDownloadedApk(apkFile)
            } else if (!skipped) {
                val message = downloadErrorMessage?.takeIf { it.isNotBlank() }?.let {
                    "${getString(R.string.update_download_failed)}: $it"
                } ?: getString(R.string.update_download_failed)
                toastError(message)
            }
        }
    }

    private fun installDownloadedApk(apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                val permissionIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(permissionIntent)
                toastError(R.string.update_install_permission_required)
                // Don't clear pending update - keep it so user can retry after granting permission
                return
            }
            MmkvManager.clearPendingUpdate()
            val uri = FileProvider.getUriForFile(
                this,
                BuildConfig.APPLICATION_ID + ".cache",
                apkFile
            )
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            toastError(e.message ?: getString(R.string.update_download_failed))
        }
    }



    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }

    private fun formatEta(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val mins = safe / 60
        val secs = safe % 60
        return if (mins > 0) {
            String.format(Locale.US, "%dm %02ds", mins, secs)
        } else {
            String.format(Locale.US, "%ds", secs)
        }
    }

    override fun onDestroy() {
        updateDownloadJob?.cancel()
        binding.viewPager.unregisterOnPageChangeCallback(groupPageChangeCallback)
        tabMediator?.detach()
        super.onDestroy()
    }
}
