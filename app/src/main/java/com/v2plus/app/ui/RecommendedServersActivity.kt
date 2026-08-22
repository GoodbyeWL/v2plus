package com.v2plus.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.v2plus.app.AppConfig
import com.v2plus.app.R
import com.v2plus.app.databinding.ActivityRecommendedServersBinding
import com.v2plus.app.databinding.ItemRecommendedServerBinding
import com.v2plus.app.handler.AngConfigManager
import com.v2plus.app.handler.CustomizationManager
import com.v2plus.app.extension.toast
import com.v2plus.app.util.MessageUtil

class RecommendedServersActivity : BaseActivity() {
    private val binding by lazy { ActivityRecommendedServersBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.recommended_servers))
        
        setupRecyclerView()
        applyCardStyles()
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = RecommendedServersAdapter()
    }
    
    private fun applyCardStyles() {
        binding.recyclerView.postDelayed({
            for (i in 0 until binding.recyclerView.childCount) {
                val card = binding.recyclerView.getChildAt(i)
                CustomizationManager.applyCardStyle(card)
            }
        }, 100)
    }

    inner class RecommendedServersAdapter : RecyclerView.Adapter<RecommendedServersAdapter.ViewHolder>() {
        private val servers = listOf(
            RecommendedServer(
                name = "ByeWhiteLists 2",
                description = "Бесплатные серверы для обхода блокировок",
                telegramUrl = "https://t.me/ByeWhiteLists2",
                subscriptionUrl = "https://raw.githubusercontent.com/ByeWhiteLists/ByeWhiteLists2/refs/heads/main/ByeWhiteLists2.txt"
            ),
            RecommendedServer(
                name = "EtoNeYa",
                description = "Бесплатные серверы для обхода блокировок",
                telegramUrl = "https://t.me/YoutubeUnBlockRu",
                subscriptionUrl = "https://alley.serv00.net/1"
            ),
            RecommendedServer(
                name = "WL RUS",
                description = "Бесплатные серверы для обхода блокировок",
                telegramUrl = "https://t.me/wlrustg",
                subscriptionUrl = "https://gitverse.ru/api/repos/bywarm/rser/raw/branch/master/selected.txt"
            ),
            RecommendedServer(
                name = "#РКП",
                description = "Бесплатные серверы для обхода блокировок",
                telegramUrl = "https://t.me/RKP_channel",
                subscriptionUrl = "https://raw.githubusercontent.com/RKPchannel/RKP_bypass_configs/refs/heads/main/configs/url_work.txt"
            ),
            RecommendedServer(
                name = "Lowik_Live",
                description = "Бесплатные серверы для обхода блокировок",
                telegramUrl = "https://t.me/LowiK_Live",
                subscriptionUrl = "https://raw.githubusercontent.com/LowiKLive/BypassWhitelistRu/refs/heads/main/WhiteList-Bypass_Ru.txt"
            )
        )

        inner class ViewHolder(private val binding: ItemRecommendedServerBinding) : RecyclerView.ViewHolder(binding.root) {
            fun bind(server: RecommendedServer) {
                binding.tvServerName.text = server.name
                binding.tvServerDescription.text = server.description
                
                // Apply theme styles to buttons
                CustomizationManager.applyButtonStyle(binding.btnTelegram, false)
                CustomizationManager.applyButtonStyle(binding.btnAddSubscription, true)
                
                binding.btnTelegram.setOnClickListener {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(server.telegramUrl))
                    startActivity(intent)
                }
                
                binding.btnAddSubscription.setOnClickListener {
                    addSubscription(server.subscriptionUrl, server.name)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemRecommendedServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(servers[position])
        }

        override fun getItemCount(): Int = servers.size
    }

    private fun addSubscription(url: String, name: String) {
        try {
            val (count, countSub) = AngConfigManager.importBatchConfig(this, url, "", true)
            
            // Always send message to update UI when something is added
            if (countSub > 0 || count > 0) {
                // Send message to update server list
                MessageUtil.sendMsg2UI(this, AppConfig.MSG_RELOAD_SERVER_LIST, "")
                
                // Try to update the subscription immediately
                if (countSub > 0) {
                    val subscriptions = com.v2plus.app.handler.MmkvManager.decodeSubscriptions()
                    val addedSubscription = subscriptions.find { it.subscription.url == url }
                    if (addedSubscription != null) {
                        // Fire and forget - subscription will update via WorkManager
                        try {
                            AngConfigManager.updateConfigViaSub(addedSubscription)
                        } catch (_: Exception) {
                            // Ignore errors, WorkManager will retry
                        }
                    }
                }
                
                toast(getString(R.string.subscription_added_successfully))
                finish()
            } else {
                toast(getString(R.string.failed_to_add_subscription))
            }
        } catch (e: Exception) {
            toast(getString(R.string.failed_to_add_subscription))
        }
    }

    data class RecommendedServer(
        val name: String,
        val description: String,
        val telegramUrl: String,
        val subscriptionUrl: String
    )
}