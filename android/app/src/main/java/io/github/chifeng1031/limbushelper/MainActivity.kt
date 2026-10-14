package io.github.chifeng1031.limbushelper

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.chifeng1031.limbushelper.core.Pipeline
import io.github.chifeng1031.limbushelper.core.TaskInfo

class MainActivity : Activity() {
    private lateinit var a11yStatus: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val checks = ArrayList<Pair<TaskInfo, CheckBox>>()
    private val prefs by lazy { getSharedPreferences("tasks", MODE_PRIVATE) }
    private val onLog: (String) -> Unit = { line ->
        logView.append(line + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(TextView(this).apply {
            text = "Limbus 助手"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "遊戲請設為英文；任務列表第一個建議勾選「启动游戏」。開始後系統會詢問錄製畫面，請選「整個螢幕」。"
            setPadding(0, pad / 2, 0, pad / 2)
        })

        a11yStatus = TextView(this)
        col.addView(a11yStatus)
        col.addView(Button(this).apply {
            text = "開啟無障礙服務設定"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        col.addView(TextView(this).apply {
            text = "任務"
            textSize = 18f
            setPadding(0, pad, 0, 0)
        })
        val tasks = Pipeline.parseTasks(assets.open("pi/interface.json").bufferedReader().readText())
        for (t in tasks) {
            val cb = CheckBox(this).apply {
                text = t.name
                isChecked = prefs.getBoolean(t.entry, t.entry == "LimbusStartGame")
                setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(t.entry, v).apply() }
            }
            checks += t to cb
            col.addView(cb)
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "開始"
            setOnClickListener { onStartClicked() }
        })
        buttons.addView(Button(this).apply {
            text = "停止"
            setOnClickListener { RunService.stop(this@MainActivity) }
        })
        buttons.addView(Button(this).apply {
            text = "複製日誌"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("log", LogBus.snapshot()))
                Toast.makeText(this@MainActivity, "已複製", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(buttons)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            text = LogBus.snapshot().let { if (it.isEmpty()) it else it + "\n" }
        }
        col.addView(logView)

        logScroll = ScrollView(this).apply { addView(col) }
        setContentView(logScroll)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        LogBus.listen(onLog)
    }

    override fun onDestroy() {
        LogBus.unlisten(onLog)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        a11yStatus.text = if (isA11yEnabled()) "無障礙服務：已開啟 ✔" else "無障礙服務：未開啟 ✘（請先開啟）"
    }

    private fun isA11yEnabled(): Boolean {
        if (TapService.instance != null) return true
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains("$packageName/")
    }

    private fun onStartClicked() {
        if (RunService.running) {
            Toast.makeText(this, "已在執行中", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isA11yEnabled()) {
            Toast.makeText(this, "請先開啟無障礙服務", Toast.LENGTH_LONG).show()
            return
        }
        if (checks.none { it.second.isChecked }) {
            Toast.makeText(this, "請至少勾選一個任務", Toast.LENGTH_SHORT).show()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14+: ask for the whole screen directly instead of offering a single app
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_CAPTURE)
    }

    @Deprecated("Activity result API needs androidx; plain Activity keeps the app dependency-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "需要錄製畫面權限才能辨識遊戲畫面", Toast.LENGTH_LONG).show()
            return
        }
        val selected = checks.filter { it.second.isChecked }.map { it.first }
        val svc = Intent(this, RunService::class.java)
            .setAction(RunService.ACTION_START)
            .putExtra(RunService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(RunService.EXTRA_DATA, data)
            .putStringArrayListExtra(RunService.EXTRA_ENTRIES, ArrayList(selected.map { it.entry }))
            .putStringArrayListExtra(RunService.EXTRA_NAMES, ArrayList(selected.map { it.name }))
        startForegroundService(svc)
        LogBus.log("已開始，共 ${selected.size} 個任務")
    }

    companion object {
        private const val REQUEST_CAPTURE = 1
    }
}
