package com.qisi.netshield.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.qisi.netshield.BuildConfig
import com.qisi.netshield.R
import com.qisi.netshield.common.Config

class MainActivity : AppCompatActivity() {

    private lateinit var swAlwaysOn: MaterialSwitch
    private lateinit var swEnabled: MaterialSwitch
    private lateinit var swForceHttps: MaterialSwitch
    private lateinit var swBlockPlain: MaterialSwitch
    private lateinit var swUseDoh: MaterialSwitch
    private lateinit var swDebug: MaterialSwitch
    private lateinit var spProvider: Spinner
    private lateinit var tvCustomDoh: TextView
    private lateinit var etCustomDoh: EditText
    private lateinit var btnAddTrusted: Button
    private lateinit var btnClearTrusted: Button
    private lateinit var tvTrusted: TextView
    private lateinit var tvStatus: TextView

    private val prefs by lazy { Config.prefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swAlwaysOn = findViewById(R.id.swAlwaysOn)
        swEnabled = findViewById(R.id.swEnabled)
        swForceHttps = findViewById(R.id.swForceHttps)
        swBlockPlain = findViewById(R.id.swBlockPlain)
        swUseDoh = findViewById(R.id.swUseDoh)
        swDebug = findViewById(R.id.swDebug)
        spProvider = findViewById(R.id.spProvider)
        tvCustomDoh = findViewById(R.id.tvCustomDoh)
        etCustomDoh = findViewById(R.id.etCustomDoh)
        btnAddTrusted = findViewById(R.id.btnAddTrusted)
        btnClearTrusted = findViewById(R.id.btnClearTrusted)
        tvTrusted = findViewById(R.id.tvTrusted)
        tvStatus = findViewById(R.id.tvStatus)

        loadToUi()
        setupUi()
        requestNeededPermissions()
        NetworkMonitorService.start(this)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        // 离开界面时统一同步一次（主要为了自定义 DoH 地址输入）
        Config.syncOut(this)
    }

    private fun loadToUi() {
        swEnabled.isChecked = prefs.getBoolean(Config.K_ENABLED, true)
        swAlwaysOn.isChecked = prefs.getBoolean(Config.K_ALWAYS_ON, false)
        swForceHttps.isChecked = prefs.getBoolean(Config.K_FORCE_HTTPS, true)
        swBlockPlain.isChecked = prefs.getBoolean(Config.K_BLOCK_PLAIN, true)
        swUseDoh.isChecked = prefs.getBoolean(Config.K_USE_DOH, true)
        swDebug.isChecked = prefs.getBoolean(Config.K_DEBUG_LOG, true)
        etCustomDoh.setText(prefs.getString(Config.K_DOH_CUSTOM, "") ?: "")
        val provider = prefs.getString(Config.K_DOH_PROVIDER, "cloudflare") ?: "cloudflare"
        spProvider.setSelection(Config.DOH_PROVIDERS.indexOf(provider).coerceAtLeast(0))
        refreshStatus()
    }

    private fun setupUi() {
        val providerAdapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Cloudflare (1.1.1.1)", "Google (8.8.8.8)", "AliDNS (阿里公共 DNS)",
                getString(R.string.custom_doh_item)))
        spProvider.adapter = providerAdapter
        spProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                updateCustomDohVisibility(pos)
                prefs.edit().putString(Config.K_DOH_PROVIDER, Config.DOH_PROVIDERS[pos]).apply()
                Config.syncOut(this@MainActivity)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        // 自定义 DoH 地址：输入时仅写入本地 prefs，onPause 统一同步（避免每次按键都触发 su/远程写）
        etCustomDoh.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString(Config.K_DOH_CUSTOM, s?.toString()?.trim() ?: "").apply()
            }
        })

        for (sw in listOf(swEnabled, swAlwaysOn, swForceHttps, swBlockPlain, swUseDoh, swDebug)) {
            sw.setOnCheckedChangeListener { _, _ -> persist() }
        }

        btnAddTrusted.setOnClickListener { showAddSsidDialog() }
        btnClearTrusted.setOnClickListener {
            prefs.edit().putString(Config.K_TRUSTED, "").apply()
            Config.syncOut(this)
            refreshStatus()
            Toast.makeText(this, "白名单已清空：所有 Wi-Fi 都按公共网络处理", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCustomDohVisibility(pos: Int) {
        val show = pos == Config.DOH_PROVIDERS.indexOf("custom")
        tvCustomDoh.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
        etCustomDoh.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun showAddSsidDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.hint_ssid)
            setText(currentSsid().removeSurrounding("\""))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_trusted)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val s = input.text.toString().trim()
                if (s.isNotEmpty()) {
                    val list = Config.stringToList(prefs.getString(Config.K_TRUSTED, "")).toMutableList()
                    if (s !in list) list.add(s)
                    prefs.edit().putString(Config.K_TRUSTED, Config.listToString(list)).apply()
                    Config.syncOut(this)
                    refreshStatus()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun persist() {
        prefs.edit()
            .putBoolean(Config.K_ENABLED, swEnabled.isChecked)
            .putBoolean(Config.K_ALWAYS_ON, swAlwaysOn.isChecked)
            .putBoolean(Config.K_FORCE_HTTPS, swForceHttps.isChecked)
            .putBoolean(Config.K_BLOCK_PLAIN, swBlockPlain.isChecked)
            .putBoolean(Config.K_USE_DOH, swUseDoh.isChecked)
            .putBoolean(Config.K_DEBUG_LOG, swDebug.isChecked)
            .apply()
        Config.syncOut(this)
        refreshStatus()
    }

    private fun currentSsid(): String = prefs.getString(Config.K_STATE_SSID, "") ?: ""

    /** 状态展示：与 Hook 侧逻辑一致 */
    private fun refreshStatus() {
        val enabled = prefs.getBoolean(Config.K_ENABLED, true)
        val onWifi = prefs.getBoolean(Config.K_STATE_ON_WIFI, false)
        val ssid = currentSsid()
        val trusted = Config.stringToList(prefs.getString(Config.K_TRUSTED, ""))
            .map { it.trim().removeSurrounding("\"").lowercase() }
        val alwaysOn = prefs.getBoolean(Config.K_ALWAYS_ON, false)

        val state = when {
            !enabled -> "总开关已关闭，任何网络都不干预"
            alwaysOn -> "始终开启模式：所有网络强制加密"
            !onWifi -> "当前不在 Wi-Fi（蜂窝网络视为可信）"
            trusted.contains(ssid.removeSurrounding("\"").lowercase()) -> "已连接受信任 Wi-Fi「$ssid」，保护未激活"
            else -> "当前 Wi-Fi「$ssid」被视作公共网络，保护已激活 🛡"
        }
        val masked = if (ssid.isBlank()) "（无定位权限或未连接，读取不到 SSID）" else ssid
        tvStatus.text = getString(R.string.status_fmt, state, masked)
        tvTrusted.text = getString(R.string.trusted_fmt,
            Config.stringToList(prefs.getString(Config.K_TRUSTED, ""))
                .ifEmpty { listOf("（空）") }.joinToString("、"))
    }

    private fun requestNeededPermissions() {
        val wanted = ArrayList<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            wanted.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (wanted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, wanted.toTypedArray(), 1001)
        }
        Toast.makeText(this,
            "若需识别 Wi-Fi 名称，请授予定位权限并保持系统「位置服务」开启（v" + BuildConfig.VERSION_NAME + "）",
            Toast.LENGTH_LONG).show()
    }
}
