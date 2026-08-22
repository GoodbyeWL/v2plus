package com.v2plus.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.v2plus.app.AppConfig
import com.v2plus.app.R
import com.v2plus.app.contracts.BaseAdapterListener
import com.v2plus.app.databinding.ActivitySubSettingBinding
import com.v2plus.app.databinding.ItemQrcodeBinding
import com.v2plus.app.extension.toast
import com.v2plus.app.handler.AngConfigManager
import com.v2plus.app.handler.MmkvManager
import com.v2plus.app.helper.SimpleItemTouchHelperCallback
import com.v2plus.app.util.QRCodeDecoder
import com.v2plus.app.util.Utils
import com.v2plus.app.viewmodel.SubscriptionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SubSettingActivity : BaseActivity() {
    private val binding by lazy { ActivitySubSettingBinding.inflate(layoutInflater) }
    private val ownerActivity: SubSettingActivity
        get() = this
    private val viewModel: SubscriptionsViewModel by viewModels()
    private lateinit var adapter: SubSettingRecyclerAdapter
    private var mItemTouchHelper: ItemTouchHelper? = null
    private val share_method: Array<out String> by lazy {
        resources.getStringArray(R.array.share_sub_method)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(binding.root)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_sub_setting))

        adapter = SubSettingRecyclerAdapter(viewModel, ActivityAdapterListener())

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        mItemTouchHelper = ItemTouchHelper(
            object : SimpleItemTouchHelperCallback(adapter, false) {
                override fun isDragAllowed(viewHolder: RecyclerView.ViewHolder): Boolean {
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos == 0 && viewModel.hasUnassignedHeaderAtTop()) return false
                    return super.isDragAllowed(viewHolder)
                }
            }
        )
        mItemTouchHelper?.attachToRecyclerView(binding.recyclerView)
    }

    override fun onResume() {
        super.onResume()
        refreshData()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.action_sub_setting, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.add_config -> {
            startActivity(Intent(this, SubEditActivity::class.java))
            true
        }

        R.id.sub_update -> {
            showLoading()

            lifecycleScope.launch(Dispatchers.IO) {
                val result = AngConfigManager.updateConfigViaSubAll(this@SubSettingActivity)
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
                    hideLoading()
                    refreshData()
                }
            }

            true
        }

        else -> super.onOptionsItemSelected(item)

    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        viewModel.reload()
        adapter.notifyDataSetChanged()
    }

    private inner class ActivityAdapterListener : BaseAdapterListener {
        override fun onEdit(guid: String, position: Int) {
            startActivity(
                Intent(ownerActivity, SubEditActivity::class.java)
                    .putExtra("subId", guid)
            )
        }

        override fun onRemove(guid: String, position: Int) {
            if (guid == AppConfig.UNASSIGNED_SUBSCRIPTION_LIST_ID) {
                val n = MmkvManager.decodeServerList("").size
                if (n == 0) {
                    val runHide = {
                        viewModel.remove(guid)
                        refreshData()
                    }
                    if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
                        AlertDialog.Builder(ownerActivity)
                            .setMessage(R.string.sub_unassigned_hide_row_confirm)
                            .setPositiveButton(android.R.string.ok) { _, _ -> runHide() }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else {
                        runHide()
                    }
                } else {
                    val msg = getString(R.string.sub_unassigned_delete_confirm, n)
                    if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
                        AlertDialog.Builder(ownerActivity)
                            .setMessage(msg)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                viewModel.remove(guid)
                                refreshData()
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else {
                        viewModel.remove(guid)
                        refreshData()
                    }
                }
                return
            }
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
                AlertDialog.Builder(ownerActivity)
                    .setMessage(R.string.del_config_comfirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        viewModel.remove(guid)
                        refreshData()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                viewModel.remove(guid)
                refreshData()
            }
        }

        override fun onShare(url: String) {
            AlertDialog.Builder(ownerActivity)
                .setItems(share_method.asList().toTypedArray()) { _, i ->
                    try {
                        when (i) {
                            0 -> {
                                val ivBinding =
                                    ItemQrcodeBinding.inflate(LayoutInflater.from(ownerActivity))
                                ivBinding.ivQcode.setImageBitmap(
                                    QRCodeDecoder.createQRCode(
                                        url

                                    )
                                )
                                AlertDialog.Builder(ownerActivity).setView(ivBinding.root).show()
                            }

                            1 -> {
                                Utils.setClipboard(ownerActivity, url)
                            }

                            else -> ownerActivity.toast("else")
                        }
                    } catch (e: Exception) {
                        Log.e(AppConfig.TAG, "Share subscription failed", e)
                    }
                }.show()
        }

        override fun onRefreshData() {
            refreshData()
        }
    }
}
