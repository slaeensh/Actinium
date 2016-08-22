package com.v2ray.actinium.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v4.app.ActivityCompat
import android.support.v4.content.res.ResourcesCompat
import android.support.v7.widget.AppCompatEditText
import android.support.v7.widget.LinearLayoutManager
import android.view.Menu
import android.view.MenuItem
import com.eightbitlab.rxbus.Bus
import com.eightbitlab.rxbus.registerInBus
import com.v2ray.actinium.R
import com.v2ray.actinium.event.V2RayStatusEvent
import com.v2ray.actinium.event.VpnPrepareEvent
import com.v2ray.actinium.extension.alert
import com.v2ray.actinium.service.V2RayService
import com.v2ray.actinium.util.ConfigManager
import com.v2ray.actinium.util.ConfigUtil
import kotlinx.android.synthetic.main.activity_main.*
import org.jetbrains.anko.*
import java.io.FileInputStream
import java.io.InputStream

class MainActivity : BaseActivity() {


    companion object {
        private const val REQUEST_CODE_VPN_PREPARE = 0
        private const val REQUEST_CODE_FILE_SELECT = 1

        private const val REQUEST_CODE_PERMISSION_STORAGE = 2
    }

    var fabChecked = false
        set(value) {
            field = value
            if (value) {
                fab.setIcon(ResourcesCompat.getDrawable(resources, R.drawable.ic_check_24dp, null), false)
            } else {
                fab.setIcon(ResourcesCompat.getDrawable(resources, R.drawable.ic_action_logo, null), false)
            }

        }

    private lateinit var vpnPrepareCallback: (Boolean) -> Unit

    private val adapter by lazy { MainRecyclerAdapter(this, ConfigManager.configs) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fab.setOnClickListener {
            if (fabChecked) {
                V2RayService.stopV2Ray()
                Snackbar.make(it, R.string.service_has_been_stopped, Snackbar.LENGTH_SHORT).show()
            } else {
                if (adapter.actionMode != null)
                    adapter.actionMode?.finish()

                V2RayService.startV2Ray(ctx)
                Snackbar.make(it, R.string.service_has_been_started, Snackbar.LENGTH_SHORT).show()
            }
        }

        recycler_view.layoutManager = LinearLayoutManager(this)
        recycler_view.adapter = adapter

        Bus.observe<VpnPrepareEvent>()
                .subscribe {
                    vpnPrepareCallback = it.callback
                    startActivityForResult(it.intent, REQUEST_CODE_VPN_PREPARE)
                }
                .registerInBus(this)

        Bus.observe<V2RayStatusEvent>()
                .subscribe {
                    fabChecked = it.isRunning
                    adapter.changeable = !it.isRunning

                }
        V2RayService.checkStatusEvent {
            fabChecked = it
            adapter.changeable = !it
        }

        if (intent.action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            handlerNewConfigFile(FileInputStream(uri.path))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Bus.unregister(this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_CODE_VPN_PREPARE ->
                vpnPrepareCallback(resultCode == Activity.RESULT_OK)

            REQUEST_CODE_FILE_SELECT -> {
                if (resultCode == Activity.RESULT_OK) {
                    val uri = data!!.data
                    val rawInputStream = contentResolver.openInputStream(uri)
                    handlerNewConfigFile(rawInputStream)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_PERMISSION_STORAGE -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    importConfigFromFile()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            handlerNewConfigFile(FileInputStream(uri.path))
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.add_config -> {
            importConfigFromFile()
            true
        }
        R.id.settings -> {
            startActivity<SettingsActivity>()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (adapter.actionMode != null)
            adapter.actionMode?.finish()
        else
            super.onBackPressed()
    }

    private fun storeConfigFile(rawConfig: String, name: String) {
        val retFile = ConfigManager.getConfigFileByName(name)

        if (ConfigUtil.isConfigCompatible(rawConfig)) {
            val formatted = ConfigUtil.formatJSON(rawConfig)
            retFile.writeText(formatted)

            if (!fabChecked)
                defaultSharedPreferences.edit().putString(ConfigManager.PREF_CURR_CONFIG, name).apply()

            adapter.updateConfigList()
        } else {
            alert(R.string.msg_dialog_convert_config, R.string.title_dialog_convert_config) {
                positiveButton(android.R.string.ok) {
                    val retConfig = ConfigUtil.convertConfig(rawConfig)
                    val formatted = ConfigUtil.formatJSON(retConfig)
                    retFile.writeText(formatted)

                    if (!fabChecked)
                        defaultSharedPreferences.edit().putString(ConfigManager.PREF_CURR_CONFIG, name).apply()

                    adapter.updateConfigList()
                }

                negativeButton()

                show()
            }
        }

    }

    private fun handlerNewConfigFile(ins: InputStream) {
        val rawConfig = ins.bufferedReader().readText()

        if (!ConfigUtil.validConfig(rawConfig)) {
            toast(R.string.toast_config_file_invalid)
            return
        }

        alert(R.string.title_dialog_input_config_name) {
            val input = AppCompatEditText(this@MainActivity)
            input.singleLine = true
            customView(input)

            positiveButton(android.R.string.ok) {
                val name = input.text.toString()
                storeConfigFile(rawConfig, name)
            }

            negativeButton(android.R.string.cancel)

            show()
        }
    }

    private fun importConfigFromFile() {
        val permission = Manifest.permission.READ_EXTERNAL_STORAGE
        val check = ActivityCompat.checkSelfPermission(this, permission)

        if (check == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "*/*"
            intent.addCategory(Intent.CATEGORY_OPENABLE)

            try {
                startActivityForResult(
                        Intent.createChooser(intent, getString(R.string.title_file_chooser)),
                        REQUEST_CODE_FILE_SELECT)
            } catch (ex: android.content.ActivityNotFoundException) {
                toast(R.string.toast_require_file_manager)
            }
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission),
                    REQUEST_CODE_PERMISSION_STORAGE)
        }
    }

}