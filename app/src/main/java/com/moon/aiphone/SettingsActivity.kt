package com.moon.aiphone

import android.content.Context
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.yalantis.ucrop.UCrop
import okhttp3.*
import java.io.File
import java.io.IOException

class SettingsActivity : AppCompatActivity() {

    // 生图设置控件（类成员，跨方法可见）
    private lateinit var editImgApiUrl: EditText
    private lateinit var editImgApiKey: EditText
    private lateinit var editImgModel: android.widget.AutoCompleteTextView
    private lateinit var switchMoments: android.widget.Switch
    private lateinit var switchChat: android.widget.Switch
    private lateinit var switchHacker: android.widget.Switch
    private lateinit var switchDreamHouse: android.widget.Switch
    private lateinit var editImgNegativePrompt: EditText
    private lateinit var editImgUserPrompt: EditText
    // ⚡ 抽出脑浆（导出存档）的引渡专员 —— 改为【导出整个 ZIP（含数据库+设置）】
    private val exportDbLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            try {
                val dbFile = getDatabasePath("AiPhone.db")
                if (!dbFile.exists()) {
                    Toast.makeText(this, "还没造出世界呢，没有存档可导！", Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                contentResolver.openOutputStream(uri)?.use { output ->
                    val zos = java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(output))
                    // 1) 写入数据库文件 AiPhone.db
                    zos.putNextEntry(java.util.zip.ZipEntry("AiPhone.db"))
                    java.io.FileInputStream(dbFile).use { it.copyTo(zos) }
                    zos.closeEntry()
                    // 2) 写入 shared_prefs 下的全部设置 xml（角色/API/记忆配置全在里面）
                    val prefsDir = File(applicationInfo.dataDir, "shared_prefs")
                    if (prefsDir.exists()) {
                        prefsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.forEach { pf ->
                            zos.putNextEntry(java.util.zip.ZipEntry("shared_prefs/${pf.name}"))
                            java.io.FileInputStream(pf).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    zos.finish()
                    zos.flush()
                }
                Toast.makeText(this, "✅ 全量存档导出成功！已包含角色+记忆+设置", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "导出暴毙: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 🧨 注入前世（导入存档）的夺舍专员
    // 关键修复：不用 displayName 判断文件类型（Android 13+ 隐私限制常拿不到真实文件名），
    // 改用读文件头 magic bytes 100% 准确识别：
    //   ZIP = "PK"          |  SQLite db = "SQLite format ..."  |  XML = 第一个字节 '<'
    private val importDbLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris == null || uris.isEmpty()) return@registerForActivityResult
        try {
            val dbFile = getDatabasePath("AiPhone.db")
            val prefsDir = File(applicationInfo.dataDir, "shared_prefs")
            if (!prefsDir.exists()) prefsDir.mkdirs()
            var restoredDb = false
            var restoredPrefsCount = 0
            var skippedCount = 0

            // 尝试从 ContentResolver 拿真实文件名（有些老设备能拿到），拿不到就用占位符
            fun tryGetName(uri: Uri): String {
                return try {
                    contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) ?: "" else ""
                    } ?: ""
                } catch (_: Exception) { "" }
            }

            for (uri in uris) {
                val displayName = tryGetName(uri)
                // 另外从 Uri 路径里也尝试抠一下（Content://com.android.providers... 没用，但 FILE:// 有用）
                val fallbackName = uri.lastPathSegment ?: ""

                // 用临时文件读文件头（避免 InputStream 只能读一次的尴尬）
                val tmp = java.io.File(cacheDir, "import_probe_${System.currentTimeMillis()}")
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        java.io.FileOutputStream(tmp).use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) { continue }

                if (!tmp.exists() || tmp.length() < 4) { skippedCount++; continue }

                val head = ByteArray(8)
                tmp.inputStream().use { it.read(head) }

                // ① ZIP 整包：PK magic
                if (head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) {
                    java.util.zip.ZipInputStream(tmp.inputStream().buffered()).use { zis ->
                        var entry: java.util.zip.ZipEntry?
                        while (zis.nextEntry.also { entry = it } != null) {
                            val name = entry!!.name
                            when {
                                name.equals("AiPhone.db", ignoreCase = true) -> {
                                    java.io.FileOutputStream(dbFile).use { out -> zis.copyTo(out) }
                                    restoredDb = true
                                }
                                name.endsWith("AiPhone.db", ignoreCase = true) -> {
                                    java.io.FileOutputStream(dbFile).use { out -> zis.copyTo(out) }
                                    restoredDb = true
                                }
                                name.contains("shared_prefs") && name.endsWith(".xml") -> {
                                    val fname = name.substringAfterLast('/')
                                    if (fname.isNotEmpty()) {
                                        java.io.FileOutputStream(File(prefsDir, fname)).use { out -> zis.copyTo(out) }
                                        restoredPrefsCount++
                                    }
                                }
                                name.endsWith(".xml", ignoreCase = true) -> {
                                    val fname = name.substringAfterLast('/')
                                    if (fname.isNotEmpty() && !fname.endsWith("/")) {
                                        java.io.FileOutputStream(File(prefsDir, fname)).use { out -> zis.copyTo(out) }
                                        restoredPrefsCount++
                                    }
                                }
                            }
                            zis.closeEntry()
                        }
                    }
                    tmp.delete(); continue
                }

                // ② SQLite db：magic = "SQLite format"
                if (tmp.length() >= 16 &&
                    head[0] == 0x53.toByte() && head[1] == 0x51.toByte() && head[2] == 0x4C.toByte() && head[3] == 0x69.toByte() &&
                    tmp.inputStream().use { it.skip(6); val b = ByteArray(2); it.read(b); b[0] == 0x66.toByte() && b[1] == 0x6F.toByte() }) {
                    tmp.copyTo(dbFile, overwrite = true)
                    restoredDb = true
                    tmp.delete(); continue
                }

                // ③ XML：第一个字节就是 '<' (0x3C) —— shared_prefs 里的配置文件全是这个开头
                if (head[0] == 0x3C.toByte()) {
                    // 优先用真实文件名，拿不到就用 fallback，再不行用占位
                    val baseName = when {
                        displayName.isNotBlank() -> displayName.substringAfterLast('/')
                        fallbackName.isNotBlank() -> fallbackName
                        else -> "pref_${System.currentTimeMillis()}.xml"
                    }
                    val fname = if (baseName.endsWith(".xml", ignoreCase = true)) baseName else "$baseName.xml"
                    // shared_prefs 的 xml 必须叫 "XXX.xml" 才能被 getSharedPreferences("XXX") 读到！
                    // 优先从 XML 内容里抠 name 属性（Android 默认会写 android:name 或 name）
                    val content = tmp.readText()
                    val realName = Regex("""(?:android:name|name)\s*=\s*"([^"]+)"""").find(content)?.groupValues?.get(1)
                        ?: fname.removeSuffix(".xml")
                    // 确保以 .xml 结尾
                    val finalFileName = if (realName.endsWith(".xml", ignoreCase = true)) realName else "$realName.xml"
                    tmp.copyTo(File(prefsDir, finalFileName), overwrite = true)
                    restoredPrefsCount++
                    tmp.delete(); continue
                }

                // 啥也不是，跳过
                skippedCount++
                tmp.delete()
            }

            val msg = buildString {
                append("🔥 读档完成！")
                if (restoredDb) append(" 数据库✓")
                if (restoredPrefsCount > 0) append(" 配置(含MCP/门状态/API) x${restoredPrefsCount}✓")
                if (!restoredDb && restoredPrefsCount == 0) append("（但没识别出可读内容，选个 db/xml/zip 哦）")
                append(" 即将自杀重启以强行生效...")
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            Thread {
                Thread.sleep(1500)
                kotlin.system.exitProcess(0)
            }.start()
        } catch (e: Exception) {
            Toast.makeText(this, "导入暴毙: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ⚡ 第二步：接住切好的 9:16 竖屏壁纸！
    private val cropWallpaperLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val resultUri = UCrop.getOutput(result.data!!)
            if (resultUri != null) {
                val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
                try {
                    val fileName = "wallpaper_${System.currentTimeMillis()}.jpg"
                    val destFile = java.io.File(filesDir, fileName)
                    contentResolver.openInputStream(resultUri)?.use { input ->
                        destFile.outputStream().use { output -> input.copyTo(output) }
                    }
                    sharedPref.edit()
                        .putString("wallpaperUri", destFile.absolutePath)
                        .apply()
                } catch (_: Exception) {
                    sharedPref.edit()
                        .putString("wallpaperUri", resultUri.toString())
                        .apply()
                }
                Toast.makeText(this, "🎉 壁纸装修成功！", Toast.LENGTH_SHORT).show()
                Toast.makeText(this, "🎉 绝美竖屏壁纸装修成功！", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ⚡ 第一步：选完照片直接强行押送进 9:16 手术室！
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val destinationUri = android.net.Uri.fromFile(File(cacheDir, "crop_setting_wallpaper_${System.currentTimeMillis()}.jpg"))
            val options = UCrop.Options()
            options.setCircleDimmedLayer(false)
            options.setShowCropGrid(true)
            options.setToolbarTitle("裁剪手机主壁纸")

            val uCropIntent = UCrop.of(uri, destinationUri)
                .withAspectRatio(9f, 16f)
                .withMaxResultSize(1080, 1920)
                .withOptions(options)
                .getIntent(this)

            cropWallpaperLauncher.launch(uCropIntent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager()) {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                }
            } else {
                androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
        } catch (e: Exception) {}

        findViewById<TextView>(R.id.btnSetWallpaper).setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }
        findViewById<TextView>(R.id.btnMcpManager).setOnClickListener {
            startActivity(android.content.Intent(this, McpSettingsActivity::class.java))
        }

        val editTtsKey = findViewById<EditText>(R.id.editTtsApiKey)
        val sharedPref = getSharedPreferences("AppConfig", Context.MODE_PRIVATE)
        editTtsKey.setText(sharedPref.getString("ttsApiKey", ""))
// ── 朋友的 TTS 服务器：读取已保存的设置 ──
        val switchUseGuannan = findViewById<android.widget.Switch>(R.id.switchUseGuannan)
        val editGuannanKey   = findViewById<EditText>(R.id.editGuannanKey)
        val editGuannanUrl   = findViewById<EditText>(R.id.editGuannanUrl)
        val switchVoiceMood = findViewById<android.widget.Switch>(R.id.switchVoiceMood)
        val editVoiceMoodApiUrl = findViewById<EditText>(R.id.editVoiceMoodApiUrl)
        val editVoiceMoodApiKey = findViewById<EditText>(R.id.editVoiceMoodApiKey)
        val editVoiceMoodModel = findViewById<EditText>(R.id.editVoiceMoodModel)

        switchUseGuannan.isChecked = sharedPref.getString("ttsProvider", "siliconflow") == "guannan"
        editGuannanKey.setText(sharedPref.getString("guannanApiKey", ""))
        editGuannanUrl.setText(sharedPref.getString(
            "guannanApiUrl", "http://47.83.255.223:8080/guannan"
        ))
        switchVoiceMood.isChecked = sharedPref.getBoolean("voiceMoodEnable", false)
        editVoiceMoodApiUrl.setText(sharedPref.getString(
            "voiceMoodApiUrl", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
        ))
        editVoiceMoodApiKey.setText(sharedPref.getString("voiceMoodApiKey", ""))
        editVoiceMoodModel.setText(sharedPref.getString("voiceMoodModel", "qwen-omni-turbo"))
        // 默认声音 / 语言不再在此页设置：音色与语言改在「角色设置」里按角色单独填写。
        // TTSManager 读不到时会回落到内置默认（voice=krueger / lang=en）。

        // ── 电台新闻搜索API设置 ──────────────────────────────────────────
        val editRadioSearchType = findViewById<AutoCompleteTextView>(R.id.editRadioSearchType)
        val editRadioSearchKey = findViewById<EditText>(R.id.editRadioSearchKey)
        val btnSaveRadioSearch = findViewById<TextView>(R.id.btnSaveRadioSearch)

        val radioSearchTypes = arrayOf("Serper（谷歌）", "Bing Search", "Tavily")
        val radioSearchKeys = arrayOf("serper", "bing", "tavily")

        editRadioSearchType.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, radioSearchTypes)
        )
        editRadioSearchType.threshold = 0

        val savedRadioType = sharedPref.getString("radioSearchType", "serper") ?: "serper"
        val savedRadioIndex = radioSearchKeys.indexOf(savedRadioType).coerceAtLeast(0)
        editRadioSearchType.setText(radioSearchTypes[savedRadioIndex], false)
        editRadioSearchKey.setText(sharedPref.getString("radioSearchKey", "") ?: "")

        editRadioSearchType.setOnClickListener { editRadioSearchType.showDropDown() }
        editRadioSearchType.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) editRadioSearchType.showDropDown()
        }
        editRadioSearchType.setOnItemClickListener { _, _, position, _ ->
            sharedPref.edit()
                .putString("radioSearchType", radioSearchKeys[position])
                .apply()
        }

        fun getRadioSearchTypeKey(): String {
            return when (editRadioSearchType.text.toString().trim()) {
                "Bing Search", "bing" -> "bing"
                "Tavily", "tavily" -> "tavily"
                "Serper（谷歌）", "serper" -> "serper"
                else -> sharedPref.getString("radioSearchType", "serper") ?: "serper"
            }
        }

        btnSaveRadioSearch.setOnClickListener {
            sharedPref.edit()
                .putString("radioSearchType", getRadioSearchTypeKey())
                .putString("radioSearchKey", editRadioSearchKey.text.toString().trim())
                .apply()
            Toast.makeText(this, "✅ 电台搜索API已保存", Toast.LENGTH_SHORT).show()
        }

        val seekBarTemperature = findViewById<SeekBar>(R.id.seekBarTemp)
        val tvTemperatureValue = findViewById<TextView>(R.id.tvTemperatureValue)
        val seekBarDepth = findViewById<SeekBar>(R.id.seekBarDepth)
        val tvDepthValue = findViewById<TextView>(R.id.tvDepthValue)
        val btnBack = findViewById<TextView>(R.id.btnBack)
        val btnSave = findViewById<TextView>(R.id.btnSave)
        val editApiUrl = findViewById<EditText>(R.id.editApiUrl)
        val editApiKey = findViewById<EditText>(R.id.editApiKey)
        val editSelectModel = findViewById<AutoCompleteTextView>(R.id.editSelectModel)
        val btnFetchModel = findViewById<TextView>(R.id.btnFetchModel)
        val btnDropdownArrow = findViewById<TextView>(R.id.btnDropdownArrow)

        editApiUrl.setText(sharedPref.getString("apiUrl", ""))
        editApiKey.setText(sharedPref.getString("apiKey", ""))
        editSelectModel.setText(sharedPref.getString("modelName", "gemini-3.1-pro"))

        // ── 向量记忆（长期记忆）设置 ──
        val switchEmbEnable = findViewById<android.widget.Switch>(R.id.switchEmbEnable)
        val editEmbApiUrl = findViewById<EditText>(R.id.editEmbApiUrl)
        val editEmbApiKey = findViewById<EditText>(R.id.editEmbApiKey)
        val editEmbModel = findViewById<EditText>(R.id.editEmbModel)
        switchEmbEnable.isChecked = sharedPref.getBoolean("embEnable", false)
        editEmbApiUrl.setText(sharedPref.getString("embApiUrl", "https://api.openai.com"))
        editEmbApiKey.setText(sharedPref.getString("embApiKey", ""))
        editEmbModel.setText(sharedPref.getString("embModel", "text-embedding-3-small"))

        val savedTemp = sharedPref.getInt("temperature", 6)
        seekBarTemperature.progress = savedTemp
        tvTemperatureValue.text = (savedTemp / 10.0).toString()

        val savedDepth = sharedPref.getInt("inferenceDepth", 50)
        seekBarDepth.progress = savedDepth
        tvDepthValue.text = savedDepth.toString()

        val initialModels = arrayOf("gemini-3.1-pro", "gemini-3.1-flash", "gpt-4o")
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, initialModels)
        editSelectModel.setAdapter(adapter)
        btnDropdownArrow.setOnClickListener { editSelectModel.showDropDown() }
        editSelectModel.setOnClickListener { editSelectModel.showDropDown() }

        btnFetchModel.setOnClickListener {
            var url = editApiUrl.text.toString().trim()
            val key = editApiKey.text.toString().trim()
            if (url.isEmpty() || key.isEmpty()) {
                Toast.makeText(this, "请先填好 API 链接和 Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            while (url.endsWith("/")) url = url.substring(0, url.length - 1)
            val finalUrl = if (url.contains("/v1")) "$url/models" else "$url/v1/models"
            Toast.makeText(this, "正在同步模型列表...", Toast.LENGTH_SHORT).show()

            val client = Http.client
            val request = Request.Builder()
                .url(finalUrl)
                .addHeader("Authorization", "Bearer $key")
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        Toast.makeText(this@SettingsActivity, "连接失败：网络或链接错误", Toast.LENGTH_LONG).show()
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string()
                    runOnUiThread {
                        if (response.isSuccessful && body != null) {
                            try {
                                val jsonResponse = org.json.JSONObject(body)
                                val dataArray = jsonResponse.getJSONArray("data")
                                val newModelList = mutableListOf<String>()
                                for (i in 0 until dataArray.length()) {
                                    val modelId = dataArray.getJSONObject(i).getString("id")
                                    newModelList.add(modelId)
                                }
                                val newAdapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_dropdown_item_1line, newModelList)
                                editSelectModel.setAdapter(newAdapter)
                                Toast.makeText(this@SettingsActivity, "拉取成功：共 ${newModelList.size} 个模型", Toast.LENGTH_SHORT).show()
                                editSelectModel.showDropDown()
                            } catch (e: Exception) {
                                Toast.makeText(this@SettingsActivity, "数据解析失败，请检查链接是否标准", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(this@SettingsActivity, "服务器返回错误: ${response.code}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            })
        }

        seekBarTemperature.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvTemperatureValue.text = (progress / 10.0).toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekBarDepth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvDepthValue.text = progress.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnSave.setOnClickListener {
            val ttsKey = findViewById<EditText>(R.id.editTtsApiKey).text.toString()
            sharedPref.edit().putString("ttsApiKey", ttsKey).apply()
// 保存朋友服务器的设置
            sharedPref.edit()
                .putString("ttsProvider", if (switchUseGuannan.isChecked) "guannan" else "siliconflow")
                .putString("guannanApiKey", editGuannanKey.text.toString().trim())
                .putString("guannanApiUrl", editGuannanUrl.text.toString().trim())
                .apply()
            val editor = sharedPref.edit()
            editor.putString("apiUrl", editApiUrl.text.toString().trim())
            editor.putString("apiKey", editApiKey.text.toString().trim())
            editor.putString("modelName", editSelectModel.text.toString().trim())
            editor.putInt("temperature", seekBarTemperature.progress)
            editor.putInt("inferenceDepth", seekBarDepth.progress)
            editor.putBoolean("voiceMoodEnable", switchVoiceMood.isChecked)
            editor.putString("voiceMoodApiUrl", editVoiceMoodApiUrl.text.toString().trim())
            editor.putString("voiceMoodApiKey", editVoiceMoodApiKey.text.toString().trim())
            editor.putString("voiceMoodModel", editVoiceMoodModel.text.toString().trim().ifEmpty { "qwen-omni-turbo" })
            // 保存电台新闻搜索API设置
            editor.putString("radioSearchType", getRadioSearchTypeKey())
            editor.putString("radioSearchKey", editRadioSearchKey.text.toString().trim())
            // 保存生图设置
            editor.putString("imgApiUrl",  editImgApiUrl.text.toString().trim())
            editor.putString("imgApiKey",  editImgApiKey.text.toString().trim())
            editor.putString("imgModel",   editImgModel.text.toString().trim())
            editor.putBoolean("imgEnable_moments",    switchMoments.isChecked)
            editor.putBoolean("imgEnable_chat",       switchChat.isChecked)
            editor.putBoolean("imgEnable_hacker",     switchHacker.isChecked)
            editor.putBoolean("imgEnable_dreamhouse", switchDreamHouse.isChecked)
            editor.putString("imgNegativePrompt", editImgNegativePrompt.text.toString().trim())
            editor.putString("imgUserPrompt",     editImgUserPrompt.text.toString().trim())
            // 保存向量记忆设置
            editor.putBoolean("embEnable", switchEmbEnable.isChecked)
            editor.putString("embApiUrl", editEmbApiUrl.text.toString().trim())
            editor.putString("embApiKey", editEmbApiKey.text.toString().trim())
            editor.putString("embModel", editEmbModel.text.toString().trim().ifEmpty { "text-embedding-3-small" })
            editor.apply()
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
// ── AI 生图设置 ──────────────────────────────────────────
// 改成赋值，不要 val/var
        editImgApiUrl    = findViewById(R.id.editImgApiUrl)
        editImgApiKey    = findViewById(R.id.editImgApiKey)
        editImgModel     = findViewById(R.id.editImgModel)
        val btnFetchImgModel = findViewById<TextView>(R.id.btnFetchImgModel)
        val btnImgDropdown   = findViewById<TextView>(R.id.btnImgDropdownArrow)
        switchMoments     = findViewById(R.id.switchImgMoments)
        switchChat        = findViewById(R.id.switchImgChat)
        switchHacker      = findViewById(R.id.switchImgHacker)
        switchDreamHouse  = findViewById(R.id.switchImgDreamHouse)
        editImgNegativePrompt = findViewById(R.id.editImgNegativePrompt)
        editImgUserPrompt     = findViewById(R.id.editImgUserPrompt)

// 读取已保存的值
        editImgApiUrl.setText(sharedPref.getString("imgApiUrl", "https://api.siliconflow.cn"))
        editImgApiKey.setText(sharedPref.getString("imgApiKey", ""))
        editImgModel.setText(sharedPref.getString("imgModel", ""))
        editImgNegativePrompt.setText(sharedPref.getString("imgNegativePrompt", ""))
        editImgUserPrompt.setText(sharedPref.getString("imgUserPrompt", ""))
        switchMoments.isChecked    = sharedPref.getBoolean("imgEnable_moments", false)
        switchChat.isChecked       = sharedPref.getBoolean("imgEnable_chat", false)
        switchHacker.isChecked     = sharedPref.getBoolean("imgEnable_hacker", false)
        switchDreamHouse.isChecked = sharedPref.getBoolean("imgEnable_dreamhouse", false)

// 下拉箭头
        btnImgDropdown.setOnClickListener { editImgModel.showDropDown() }
        editImgModel.setOnClickListener   { editImgModel.showDropDown() }

// 拉取生图模型列表
        btnFetchImgModel.setOnClickListener {
            var imgUrl = editImgApiUrl.text.toString().trim().trimEnd('/')
            val imgKey = editImgApiKey.text.toString().trim()
            if (imgUrl.isEmpty() || imgKey.isEmpty()) {
                Toast.makeText(this, "请先填好生图 API 链接和 Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val modelsUrl = if (imgUrl.contains("/v1")) "$imgUrl/models" else "$imgUrl/v1/models"
            Toast.makeText(this, "正在拉取生图模型...", Toast.LENGTH_SHORT).show()
            Http.client.newBuilder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .newCall(
                    Request.Builder().url(modelsUrl)
                        .addHeader("Authorization", "Bearer $imgKey").build()
                ).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread { Toast.makeText(this@SettingsActivity, "连接失败", Toast.LENGTH_SHORT).show() }
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val body = response.body?.string()
                        runOnUiThread {
                            if (response.isSuccessful && body != null) {
                                try {
                                    val arr = org.json.JSONObject(body).getJSONArray("data")
                                    val models = mutableListOf<String>()
                                    for (i in 0 until arr.length()) {
                                        val id = arr.getJSONObject(i).getString("id")
                                        // 只保留图片相关模型，过滤掉对话模型
                                        if (id.contains("stable", true) || id.contains("flux", true) ||
                                            id.contains("sd", true) || id.contains("imagen", true) ||
                                            id.contains("dall", true) || id.contains("cogview", true) ||
                                            id.contains("wanx", true)) {
                                            models.add(id)
                                        }
                                    }
                                    // 如果过滤后没有，就显示全部让用户自己选
                                    val finalList = models.ifEmpty {
                                        (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
                                    }
                                    editImgModel.setAdapter(
                                        ArrayAdapter(this@SettingsActivity,
                                            android.R.layout.simple_dropdown_item_1line, finalList)
                                    )
                                    Toast.makeText(this@SettingsActivity,
                                        "拉取成功：${finalList.size} 个可用模型", Toast.LENGTH_SHORT).show()
                                    editImgModel.showDropDown()
                                } catch (e: Exception) {
                                    Toast.makeText(this@SettingsActivity, "解析失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                })
        }
        btnBack.setOnClickListener { finish() }

        // ✅ 改回旧版那套简单粗暴的方式：直接读写 Downloads/AiPhone_Backup
        // 不用系统文件选择器（在用户手机上 OpenMultipleDocuments 不稳定）
        // 只需要 MANAGE_EXTERNAL_STORAGE 权限（或者老的 WRITE_EXTERNAL_STORAGE）
        findViewById<TextView>(R.id.btnExportDatabase).setOnClickListener {
            Toast.makeText(this, "📤 正在导出到 Downloads/AiPhone_Backup ...", Toast.LENGTH_SHORT).show()
            CyberBackupManager.backupAll(this)
        }
        findViewById<TextView>(R.id.btnImportDatabase).setOnClickListener {
            Toast.makeText(this, "🧨 正在从 Downloads/AiPhone_Backup 载入...", Toast.LENGTH_SHORT).show()
            CyberBackupManager.restoreAll(this)
        }
    }
}

object CyberBackupManager {
    fun backupAll(context: android.content.Context) {
        try {
            val backupDir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "AiPhone_Backup")
            if (!backupDir.exists()) backupDir.mkdirs()

            val dbFile = context.getDatabasePath("AiPhone.db")
            if (dbFile.exists()) dbFile.copyTo(java.io.File(backupDir, "AiPhone.db"), overwrite = true)

            val prefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
            if (prefsDir.exists()) {
                val backupPrefsDir = java.io.File(backupDir, "shared_prefs")
                if (!backupPrefsDir.exists()) backupPrefsDir.mkdirs()
                prefsDir.listFiles()?.forEach { file ->
                    file.copyTo(java.io.File(backupPrefsDir, file.name), overwrite = true)
                }
            }
            android.widget.Toast.makeText(context, "✅ 全量存档成功！已死死锁进 Downloads/AiPhone_Backup 文件夹！", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "存档失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun restoreAll(context: android.content.Context) {
        try {
            val backupDir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "AiPhone_Backup")
            if (!backupDir.exists()) {
                android.widget.Toast.makeText(context, "❌ 没找到存档文件！你是不是还没存过？", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            val backupDb = java.io.File(backupDir, "AiPhone.db")
            val dbFile = context.getDatabasePath("AiPhone.db")
            if (backupDb.exists()) backupDb.copyTo(dbFile, overwrite = true)

            val backupPrefsDir = java.io.File(backupDir, "shared_prefs")
            val prefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
            if (backupPrefsDir.exists()) {
                if (!prefsDir.exists()) prefsDir.mkdirs()
                backupPrefsDir.listFiles()?.forEach { file ->
                    file.copyTo(java.io.File(prefsDir, file.name), overwrite = true)
                }
            }
            android.widget.Toast.makeText(context, "🔥 读档成功！APP即将自杀重启以强行生效...", android.widget.Toast.LENGTH_LONG).show()

            Thread {
                Thread.sleep(1500)
                kotlin.system.exitProcess(0)
            }.start()
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "读档失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
