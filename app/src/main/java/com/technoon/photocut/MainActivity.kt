package com.technoon.photocut

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * PhotoCut Offline v3 - clean-room, fully offline photo editor.
 * No AI, no ML model, no network code, no INTERNET permission, no account.
 * Structure (own design, from the functional inventory in recon-apktool.md):
 * Home (tabs + local your-content list) -> Editor (canvas on top, tool row
 * below: Remove BG, Mask, Insert, Template, Background, Shadow, Resize,
 * Text, Logo, Layers, Export) -> Export/Resize (presets, custom size, MP).
 * Automatic background removal is classical only: border colour sampling
 * plus flood-fill by colour distance, with brush erase/restore/fill fixes.
 * MP = width * height / 1,000,000.
 * OUT: AI generation/backgrounds/shadows/retouch/fill/expand/upscale,
 * on-model fashion, video generation, shop sync, subscriptions, team,
 * login, notifications, ads/analytics.
 */
class MainActivity : Activity(), SceneTouchListener {

    private lateinit var sceneView: SceneView
    private lateinit var statusText: TextView
    private lateinit var txtOriginalSize: TextView
    private lateinit var txtExportInfo: TextView
    private lateinit var txtLiveSize: TextView
    private lateinit var txtSelected: TextView
    private lateinit var txtLayers: TextView
    private lateinit var txtZoom: TextView
    private lateinit var txtCustomMp: TextView
    private lateinit var editCustomW: EditText
    private lateinit var editCustomH: EditText
    private lateinit var spExportSize: Spinner
    private lateinit var spAspect: Spinner
    private lateinit var spBgMode: Spinner
    private lateinit var spSolid: Spinner
    private lateinit var spTextColor: Spinner
    private lateinit var chkShadow: CheckBox
    private lateinit var editText: EditText
    private lateinit var spShadow: Spinner
    private lateinit var spTemplate: Spinner
    private lateinit var spMarketplace: Spinner
    private lateinit var spLogoColor: Spinner
    private lateinit var spLogoShape: Spinner
    private lateinit var editLogoText: EditText
    private lateinit var txtBatchInfo: TextView
    private lateinit var txtRecent: TextView
    private lateinit var txtYourContent: TextView
    private lateinit var homePanel: View
    private lateinit var editorPanel: View

    private var origBitmap: Bitmap? = null
    private var bgPhoto: Bitmap? = null
    private var insertOrig: Bitmap? = null
    private var insertBitmap: Bitmap? = null
    private var insertTapMode = false
    private var nextObjId = 1
    private var originalW = 0
    private var originalH = 0
    private var showOriginal = false
    private var brushMode = 0 // 0 none, 1 erase, 2 restore, 3 fill
    private var pickColorMode = false
    private var tolerance = 35
    private var brushRadius = 24
    private var bgMode = 0
    private var solidColor = Color.WHITE
    private var textColor = Color.BLACK
    private var textSizeProgress = 48
    private var shadowMode = 1
    private var shadowEnabled = false
    private var logoColor = Color.BLACK
    private var logoShape = 0
    private var templateInitDone = false
    private var marketplaceInitDone = false
    private var updatingSpinners = false
    private var dragObjId = -1
    private val batchUris = mutableListOf<Uri>()
    private val recentExports = mutableListOf<String>()
    private var customW = 0
    private var customH = 0
    private var exportW = 1024
    private var exportH = 1024

    private val reqPick = 101
    private val reqCamera = 102
    private val reqBg = 103
    private val reqLogo = 104
    private val reqBatch = 105
    private val reqInsert = 106

    private val presetW = intArrayOf(0, 1080, 1080, 1920, 2048, 3000, 4000, -1)
    private val presetH = intArrayOf(0, 1080, 1350, 1080, 2048, 3000, 3000, -1)
    private val presetLabels = arrayOf(
        "Original", "1080x1080 (1.2 MP)", "1080x1350 (1.5 MP)", "1920x1080 (2.1 MP)",
        "2048x2048 (4.2 MP)", "3000x3000 (9.0 MP)", "4000x3000 (12.0 MP)", "Custom..."
    )
    private val solidNames = arrayOf("White", "Black", "Light grey", "Green", "Blue", "Red", "Yellow", "Pink")
    private val solidColors = intArrayOf(
        Color.WHITE, Color.BLACK, Color.LTGRAY,
        Color.parseColor("#2E7D32"), Color.parseColor("#1565C0"),
        Color.parseColor("#C62828"), Color.parseColor("#F9A825"),
        Color.parseColor("#EC407A")
    )
    private val textColorNames = arrayOf("Black", "White", "Red", "Green", "Blue", "Yellow")
    private val textColors = intArrayOf(
        Color.BLACK, Color.WHITE, Color.parseColor("#C62828"),
        Color.parseColor("#2E7D32"), Color.parseColor("#1565C0"),
        Color.parseColor("#F9A825")
    )
    private val panelIds = intArrayOf(
        R.id.panelBg, R.id.panelMask, R.id.panelInsert, R.id.panelTemplate,
        R.id.panelBackground, R.id.panelShadow, R.id.panelResize, R.id.panelText,
        R.id.panelLogo, R.id.panelLayers, R.id.panelExport
    )

    private fun selectedObj(): SceneObj? =
        sceneView.objects.firstOrNull { it.id == sceneView.selectedId }

    private fun showPanel(id: Int) {
        for (pid in panelIds) findViewById<View>(pid).visibility = if (pid == id) View.VISIBLE else View.GONE
    }

    private fun showHome() {
        homePanel.visibility = View.VISIBLE
        editorPanel.visibility = View.GONE
        updateYourContent()
    }

    private fun showEditor() {
        if (origBitmap == null) {
            statusText.text = "Pick a photo first to open the editor."
            Toast.makeText(this, "Pick a photo first", Toast.LENGTH_SHORT).show()
            return
        }
        homePanel.visibility = View.GONE
        editorPanel.visibility = View.VISIBLE
        sceneView.invalidate()
        updateLiveSize()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sceneView = findViewById(R.id.sceneView)
        sceneView.touchListener = this
        statusText = findViewById(R.id.statusText)
        txtOriginalSize = findViewById(R.id.txtOriginalSize)
        txtExportInfo = findViewById(R.id.txtExportInfo)
        txtLiveSize = findViewById(R.id.txtLiveSize)
        txtSelected = findViewById(R.id.txtSelected)
        txtLayers = findViewById(R.id.txtLayers)
        txtZoom = findViewById(R.id.txtZoom)
        txtCustomMp = findViewById(R.id.txtCustomMp)
        editCustomW = findViewById(R.id.editCustomW)
        editCustomH = findViewById(R.id.editCustomH)
        spExportSize = findViewById(R.id.spExportSize)
        spAspect = findViewById(R.id.spAspect)
        spBgMode = findViewById(R.id.spBgMode)
        spSolid = findViewById(R.id.spSolid)
        spTextColor = findViewById(R.id.spTextColor)
        chkShadow = findViewById(R.id.chkShadow)
        editText = findViewById(R.id.editText)
        spShadow = findViewById(R.id.spShadow)
        spTemplate = findViewById(R.id.spTemplate)
        spMarketplace = findViewById(R.id.spMarketplace)
        spLogoColor = findViewById(R.id.spLogoColor)
        spLogoShape = findViewById(R.id.spLogoShape)
        editLogoText = findViewById(R.id.editLogoText)
        txtBatchInfo = findViewById(R.id.txtBatchInfo)
        txtRecent = findViewById(R.id.txtRecent)
        txtYourContent = findViewById(R.id.txtYourContent)
        homePanel = findViewById(R.id.homePanel)
        editorPanel = findViewById(R.id.editorPanel)

        findViewById<Button>(R.id.btnTabHome).setOnClickListener { showHome() }
        findViewById<Button>(R.id.btnTabEditor).setOnClickListener { showEditor() }
        findViewById<Button>(R.id.btnNewProject).setOnClickListener { pickImage(reqPick) }
        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (intent.resolveActivity(packageManager) != null) startActivityForResult(intent, reqCamera)
            else Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnToolBg).setOnClickListener { showPanel(R.id.panelBg) }
        findViewById<Button>(R.id.btnToolMask).setOnClickListener { showPanel(R.id.panelMask) }
        findViewById<Button>(R.id.btnToolInsert).setOnClickListener { showPanel(R.id.panelInsert) }
        findViewById<Button>(R.id.btnToolTemplate).setOnClickListener { showPanel(R.id.panelTemplate) }
        findViewById<Button>(R.id.btnToolBackground).setOnClickListener { showPanel(R.id.panelBackground) }
        findViewById<Button>(R.id.btnToolShadow).setOnClickListener { showPanel(R.id.panelShadow) }
        findViewById<Button>(R.id.btnToolResize).setOnClickListener { showPanel(R.id.panelResize) }
        findViewById<Button>(R.id.btnToolText).setOnClickListener { showPanel(R.id.panelText) }
        findViewById<Button>(R.id.btnToolLogo).setOnClickListener { showPanel(R.id.panelLogo) }
        findViewById<Button>(R.id.btnToolLayers).setOnClickListener { updateLayersText(); showPanel(R.id.panelLayers) }
        findViewById<Button>(R.id.btnToolExport).setOnClickListener { showPanel(R.id.panelExport) }

        setupSpinner(spBgMode, arrayOf("Transparent", "Solid colour", "Gradient", "Blurred original", "Photo background", "White background", "Studio look", "Flat Lay")) { pos ->
            bgMode = pos; syncSceneBg(); updateLiveSize()
        }
        setupSpinner(spSolid, solidNames) { pos -> solidColor = solidColors[pos]; syncSceneBg() }
        setupSpinner(spTextColor, textColorNames) { pos -> textColor = textColors[pos] }
        setupSpinner(spAspect, arrayOf("Free (use pixel size)", "1:1", "4:5", "9:16", "16:9")) { pos ->
            if (updatingSpinners) return@setupSpinner
            when (pos) {
                1 -> applyAspect(1080, 1080)
                2 -> applyAspect(1080, 1350)
                3 -> applyAspect(1080, 1920)
                4 -> applyAspect(1920, 1080)
            }
        }
        setupSpinner(spExportSize, presetLabels) { pos -> applyPreset(pos) }
        setupSpinner(spShadow, arrayOf("Shadow: None", "Shadow: Soft", "Shadow: Hard", "Shadow: Floating")) { pos ->
            shadowMode = pos; syncSceneBg()
        }
        spShadow.setSelection(1)
        setupSpinner(spTemplate, arrayOf("Template: White", "Template: Black", "Template: Transparent", "Template: Original", "Template: Blue gradient", "Template: Green gradient", "Template: Warm", "Template: Cool")) { pos ->
            if (!templateInitDone) { templateInitDone = true; return@setupSpinner }
            applyTemplate(pos)
        }
        setupSpinner(spMarketplace, arrayOf("Marketplace: (none)", "Shopify 2048x2048 (4.2 MP)", "Amazon 2000x2000 (4.0 MP)", "Etsy 2700x2025 (5.5 MP)", "Instagram post 1080x1080 (1.2 MP)", "Instagram story 1080x1920 (2.1 MP)", "Facebook 1200x630 (0.8 MP)", "TikTok 1080x1920 (2.1 MP)", "YouTube 1280x720 (0.9 MP)", "Depop / Poshmark 1080x1080 (1.2 MP)")) { pos ->
            if (!marketplaceInitDone) { marketplaceInitDone = true; return@setupSpinner }
            applyMarketplace(pos)
        }
        setupSpinner(spLogoColor, textColorNames) { pos -> logoColor = textColors[pos] }
        setupSpinner(spLogoShape, arrayOf("Logo shape: None", "Logo shape: Circle", "Logo shape: Square")) { pos -> logoShape = pos }

        findViewById<Button>(R.id.btnAuto).setOnClickListener { autoRemoveOnSelected() }
        findViewById<Button>(R.id.btnPickColor).setOnClickListener {
            pickColorMode = true; brushMode = 0
            statusText.text = "Tap the background colour on the photo."
        }
        findViewById<Button>(R.id.btnToggle).setOnClickListener {
            showOriginal = !showOriginal; sceneView.showOriginal = showOriginal; sceneView.invalidate()
        }
        findViewById<Button>(R.id.btnErase).setOnClickListener { brushMode = 1; pickColorMode = false; statusText.text = "Brush: erase - drag on the photo." }
        findViewById<Button>(R.id.btnRestore).setOnClickListener { brushMode = 2; pickColorMode = false; statusText.text = "Brush: restore - drag on the photo." }
        findViewById<Button>(R.id.btnFill).setOnClickListener { brushMode = 3; pickColorMode = false; statusText.text = "Brush: fill - drag on an erased area to fill it with nearby colours." }
        findViewById<Button>(R.id.btnBgPhoto).setOnClickListener { pickImage(reqBg) }
        findViewById<Button>(R.id.btnLogo).setOnClickListener { pickImage(reqLogo) }
        findViewById<Button>(R.id.btnAddText).setOnClickListener { addTextObject() }
        findViewById<Button>(R.id.btnBuildLogo).setOnClickListener { buildLogoObject() }
        findViewById<Button>(R.id.btnExportPng).setOnClickListener { exportImage(true) }
        findViewById<Button>(R.id.btnExportJpg).setOnClickListener { exportImage(false) }
        findViewById<Button>(R.id.btnSaveBrand).setOnClickListener { saveBrand() }
        findViewById<Button>(R.id.btnLoadBrand).setOnClickListener { loadBrand() }
        findViewById<Button>(R.id.btnPickBatch).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "image/*"
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            startActivityForResult(intent, reqBatch)
        }
        findViewById<Button>(R.id.btnBatchExport).setOnClickListener { batchExport() }

        findViewById<Button>(R.id.btnInsertPick).setOnClickListener { pickImage(reqInsert) }
        findViewById<Button>(R.id.btnInsertAuto).setOnClickListener { insertAutoRemove() }
        findViewById<Button>(R.id.btnInsertTap).setOnClickListener {
            insertTapMode = true; brushMode = 0
            statusText.text = "Tap the inserted photo's background colour."
        }
        findViewById<Button>(R.id.btnInsertErase).setOnClickListener { brushMode = 1; statusText.text = "Insert brush: erase - drag on the inserted photo." }
        findViewById<Button>(R.id.btnInsertRestore).setOnClickListener { brushMode = 2; statusText.text = "Insert brush: restore - drag on the inserted photo." }
        findViewById<Button>(R.id.btnInsertDone).setOnClickListener { finishInsert() }
        findViewById<Button>(R.id.btnInsertCancel).setOnClickListener { cancelInsert() }

        findViewById<Button>(R.id.btnLayerUp).setOnClickListener { moveLayer(1) }
        findViewById<Button>(R.id.btnLayerDown).setOnClickListener { moveLayer(-1) }
        findViewById<Button>(R.id.btnLayerFront).setOnClickListener { moveLayerToEnd(true) }
        findViewById<Button>(R.id.btnLayerBack).setOnClickListener { moveLayerToEnd(false) }
        findViewById<Button>(R.id.btnLayerDelete).setOnClickListener { deleteSelected() }
        findViewById<Button>(R.id.btnLayerHide).setOnClickListener { toggleHideSelected() }
        findViewById<Button>(R.id.btnObjBigger).setOnClickListener { scaleSelected(1.15f) }
        findViewById<Button>(R.id.btnObjSmaller).setOnClickListener { scaleSelected(0.87f) }

        setSeekListener(R.id.seekTolerance) { tolerance = it }
        setSeekListener(R.id.seekBrush) { brushRadius = max(4, it) }
        setSeekListener(R.id.seekZoom) { progress ->
            sceneView.zoom = 1f + progress / 100f
            txtZoom.text = "Zoom: " + (100 + progress) + "% (pinch or slider)"
            sceneView.invalidate()
        }
        setSeekListener(R.id.seekBright) { v -> selectedObj()?.let { it.bright = v; sceneView.invalidate(); updateLiveSize() } }
        setSeekListener(R.id.seekContrast) { v -> selectedObj()?.let { it.contrast = v; sceneView.invalidate(); updateLiveSize() } }
        setSeekListener(R.id.seekSaturation) { v -> selectedObj()?.let { it.saturation = v; sceneView.invalidate(); updateLiveSize() } }
        setSeekListener(R.id.seekWhite) { v -> selectedObj()?.let { it.white = v; sceneView.invalidate(); updateLiveSize() } }
        setSeekListener(R.id.seekBlack) { v -> selectedObj()?.let { it.black = v; sceneView.invalidate(); updateLiveSize() } }
        setSeekListener(R.id.seekHue) { v -> selectedObj()?.let { it.hue = v - 180; sceneView.invalidate(); updateLiveSize() } }
        setSeekListener(R.id.seekTextSize) { textSizeProgress = it }

        chkShadow.setOnCheckedChangeListener { _, checked -> shadowEnabled = checked; syncSceneBg() }

        val customWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateCustomMp() }
        }
        editCustomW.addTextChangedListener(customWatcher)
        editCustomH.addTextChangedListener(customWatcher)

        loadRecent()
        updateOriginalInfo()
        updateExportInfo()
        updateLiveSize()
        showPanel(R.id.panelBg)
    }

    private fun setupSpinner(spinner: Spinner, items: Array<String>, onSelect: (Int) -> Unit) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) { onSelect(pos) }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setSeekListener(id: Int, onChange: (Int) -> Unit) {
        val seek = findViewById<SeekBar>(id)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun syncSceneBg() {
        sceneView.bgMode = bgMode
        sceneView.bgSolidColor = solidColor
        sceneView.bgPhoto = bgPhoto
        sceneView.shadowEnabled = shadowEnabled
        sceneView.shadowMode = shadowMode
        sceneView.invalidate()
    }

    private fun pickImage(request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "image/*"
        startActivityForResult(intent, request)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        try {
            when (requestCode) {
                reqPick -> {
                    val uri = data.data ?: return
                    loadPhoto(uri)
                }
                reqBg -> {
                    val uri = data.data ?: return
                    bgPhoto = decodeScaled(uri, 1024)
                    bgMode = 4
                    updatingSpinners = true
                    spBgMode.setSelection(4)
                    updatingSpinners = false
                    syncSceneBg()
                    statusText.text = "Background photo loaded."
                }
                reqLogo -> {
                    val uri = data.data ?: return
                    val bmp = decodeScaled(uri, 512) ?: return
                    addObject("LOGO", "Logo image", bmp)
                    statusText.text = "Logo loaded. Tap it on the canvas to select, then drag to move it."
                }
                reqInsert -> {
                    val uri = data.data ?: return
                    val bmp = decodeScaled(uri, 1024) ?: return
                    insertOrig = bmp.copy(Bitmap.Config.ARGB_8888, true)
                    insertBitmap = bmp.copy(Bitmap.Config.ARGB_8888, true)
                    sceneView.insertBitmap = insertBitmap
                    sceneView.insertMode = true
                    brushMode = 0
                    sceneView.invalidate()
                    statusText.text = "Inserted photo ready. Remove its background here, then tap Place inserted object."
                }
                reqBatch -> {
                    batchUris.clear()
                    val clip = data.clipData
                    if (clip != null) {
                        for (i in 0 until clip.itemCount) {
                            val u = clip.getItemAt(i).uri
                            if (u != null) batchUris.add(u)
                        }
                    } else if (data.data != null) {
                        batchUris.add(data.data!!)
                    }
                    txtBatchInfo.text = "Batch: " + batchUris.size + " photo(s) selected. Current template, size and text/logo position will be applied to all."
                    statusText.text = "Batch photos selected: " + batchUris.size
                }
                reqCamera -> {
                    val bmp = data.extras?.get("data") as? Bitmap
                    if (bmp != null) {
                        originalW = bmp.width
                        originalH = bmp.height
                        loadBitmapAsMain(bmp.copy(Bitmap.Config.ARGB_8888, true))
                        statusText.text = "Camera photo loaded (thumbnail from camera app)."
                    }
                }
            }
        } catch (e: Exception) {
            statusText.text = "Could not load image: " + e.message
            Toast.makeText(this, "Could not load image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPhoto(uri: Uri) {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        var stream = contentResolver.openInputStream(uri)
        BitmapFactory.decodeStream(stream, null, bounds)
        stream?.close()
        originalW = bounds.outWidth
        originalH = bounds.outHeight
        val bmp = decodeScaled(uri, 1024) ?: return
        loadBitmapAsMain(bmp)
        statusText.text = "Photo loaded. Tap Auto Remove, or Tap BG colour, then fix edges with the brush."
    }

    private fun loadBitmapAsMain(bmp: Bitmap) {
        origBitmap = bmp.copy(Bitmap.Config.ARGB_8888, true)
        sceneView.objects.clear()
        sceneView.canvasW = bmp.width
        sceneView.canvasH = bmp.height
        sceneView.origBitmap = origBitmap
        sceneView.showOriginal = false
        showOriginal = false
        val main = SceneObj(nextObjId++, "MAIN", "Main cutout", bmp.copy(Bitmap.Config.ARGB_8888, true), origBitmap!!)
        main.x = 0f; main.y = 0f; main.scale = 1f
        sceneView.objects.add(main)
        select(main.id)
        applyExportSizeToScene()
        updateOriginalInfo()
        updateExportInfo()
        updateLiveSize()
        updateLayersText()
        showEditor()
    }

    private fun addObject(kind: String, name: String, bmp: Bitmap): SceneObj {
        val obj = SceneObj(nextObjId++, kind, name, bmp.copy(Bitmap.Config.ARGB_8888, true), bmp.copy(Bitmap.Config.ARGB_8888, true))
        val cw = sceneView.canvasW.toFloat()
        val ch = sceneView.canvasH.toFloat()
        obj.scale = min(1f, min(cw / max(1, bmp.width), ch / max(1, bmp.height)) * 0.6f)
        if (obj.scale <= 0f) obj.scale = 1f
        obj.x = (cw - obj.w()) / 2f
        obj.y = (ch - obj.h()) / 2f
        sceneView.objects.add(obj)
        select(obj.id)
        updateLayersText()
        updateLiveSize()
        return obj
    }

    private fun decodeScaled(uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        var stream = contentResolver.openInputStream(uri)
        BitmapFactory.decodeStream(stream, null, bounds)
        stream?.close()
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDim) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        stream = contentResolver.openInputStream(uri)
        val decoded = BitmapFactory.decodeStream(stream, null, opts)
        stream?.close()
        return decoded?.copy(Bitmap.Config.ARGB_8888, true)
    }

    // ---- Selection, touch, layers ----

    private fun select(id: Int) {
        sceneView.selectedId = id
        val obj = selectedObj()
        if (obj != null) {
            // Programmatic set does not fire fromUser listeners, so this is safe.
            findViewById<SeekBar>(R.id.seekBright).progress = obj.bright
            findViewById<SeekBar>(R.id.seekContrast).progress = obj.contrast
            findViewById<SeekBar>(R.id.seekSaturation).progress = obj.saturation
            findViewById<SeekBar>(R.id.seekWhite).progress = obj.white
            findViewById<SeekBar>(R.id.seekBlack).progress = obj.black
            findViewById<SeekBar>(R.id.seekHue).progress = obj.hue + 180
            txtSelected.text = "Selected: " + obj.name + " (" + obj.kind + "), " +
                obj.w().toInt() + "x" + obj.h().toInt() + " px on canvas"
        } else {
            txtSelected.text = "Selected: none (tap an object to select it)"
        }
        sceneView.invalidate()
        updateLayersText()
        updateLiveSize()
    }

    private fun scaleSelected(factor: Float) {
        val obj = selectedObj() ?: run {
            Toast.makeText(this, "Select an object first", Toast.LENGTH_SHORT).show(); return
        }
        obj.scale = (obj.scale * factor).coerceIn(0.1f, 5f)
        select(obj.id)
    }

    private fun updateLayersText() {
        if (sceneView.objects.isEmpty()) {
            txtLayers.text = "Layers: no objects yet"
            return
        }
        val lines = mutableListOf<String>()
        // Top of list = top of canvas = last in sceneView.objects.
        for (i in sceneView.objects.indices.reversed()) {
            val o = sceneView.objects[i]
            val mark = if (o.id == sceneView.selectedId) " > " else "   "
            val hidden = if (o.visible) "" else " (hidden)"
            lines.add(mark + o.name + " (" + o.kind + ")" + hidden)
        }
        txtLayers.text = "Layers (top first):\n" + lines.joinToString("\n")
    }

    private fun moveLayer(delta: Int) {
        val obj = selectedObj() ?: run {
            Toast.makeText(this, "Select an object first", Toast.LENGTH_SHORT).show(); return
        }
        val list = sceneView.objects
        val idx = list.indexOf(obj)
        val target = (idx + delta).coerceIn(0, list.size - 1)
        if (target != idx) {
            list.removeAt(idx)
            list.add(target, obj)
        }
        select(obj.id)
    }

    private fun moveLayerToEnd(toFront: Boolean) {
        val obj = selectedObj() ?: run {
            Toast.makeText(this, "Select an object first", Toast.LENGTH_SHORT).show(); return
        }
        sceneView.objects.remove(obj)
        if (toFront) sceneView.objects.add(obj) else sceneView.objects.add(0, obj)
        select(obj.id)
    }

    private fun deleteSelected() {
        val obj = selectedObj() ?: run {
            Toast.makeText(this, "Select an object first", Toast.LENGTH_SHORT).show(); return
        }
        if (obj.kind == "MAIN") {
            Toast.makeText(this, "The main cutout cannot be deleted; hide it instead", Toast.LENGTH_SHORT).show()
            return
        }
        sceneView.objects.remove(obj)
        select(-1)
        statusText.text = "Object deleted."
    }

    private fun toggleHideSelected() {
        val obj = selectedObj() ?: run {
            Toast.makeText(this, "Select an object first", Toast.LENGTH_SHORT).show(); return
        }
        obj.visible = !obj.visible
        select(obj.id)
    }

    // SceneTouchListener: all coordinates here are already canvas coordinates
    // from SceneView.viewToCanvas(), i.e. the exact inverse of the zoom/pan
    // transform, so brush strokes and drags land where the user sees them.
    override fun onSceneDown(cx: Float, cy: Float) {
        dragObjId = -1
        if (sceneView.insertMode) {
            val ib = insertBitmap
            if (ib != null) {
                if (insertTapMode) {
                    pickColorOnBitmap(insertBitmap!!, insertOrig!!, cx.toInt(), cy.toInt())
                    insertTapMode = false
                } else if (brushMode != 0) {
                    brushOnBitmap(insertBitmap!!, insertOrig!!, cx, cy, 1f)
                }
            }
            sceneView.invalidate()
            return
        }
        if (pickColorMode) {
            val obj = selectedObj() ?: sceneView.objects.firstOrNull { it.kind == "MAIN" }
            if (obj != null) {
                val lx = ((cx - obj.x) / obj.scale).toInt()
                val ly = ((cy - obj.y) / obj.scale).toInt()
                pickColorOnBitmap(obj.bitmap, obj.orig, lx, ly)
            }
            pickColorMode = false
            sceneView.invalidate()
            return
        }
        if (brushMode != 0) {
            val obj = selectedObj() ?: sceneView.objects.firstOrNull { it.kind == "MAIN" }
            if (obj != null && (obj.kind == "MAIN" || obj.kind == "INSERTED")) {
                val lx = (cx - obj.x) / obj.scale
                val ly = (cy - obj.y) / obj.scale
                brushOnBitmap(obj.bitmap, obj.orig, lx, ly, obj.scale)
            }
            sceneView.invalidate()
            return
        }
        // Tap-to-select: topmost object first (last in list = top).
        var hit: SceneObj? = null
        for (o in sceneView.objects.asReversed()) {
            if (o.contains(cx, cy)) { hit = o; break }
        }
        if (hit != null) {
            select(hit.id)
            dragObjId = hit.id
        } else {
            select(-1)
        }
    }

    override fun onSceneMove(cx: Float, cy: Float, dx: Float, dy: Float) {
        if (sceneView.insertMode) {
            if (brushMode != 0 && insertBitmap != null) {
                brushOnBitmap(insertBitmap!!, insertOrig!!, cx, cy, 1f)
                sceneView.invalidate()
            }
            return
        }
        if (brushMode != 0) {
            val obj = selectedObj() ?: sceneView.objects.firstOrNull { it.kind == "MAIN" }
            if (obj != null && (obj.kind == "MAIN" || obj.kind == "INSERTED")) {
                val lx = (cx - obj.x) / obj.scale
                val ly = (cy - obj.y) / obj.scale
                brushOnBitmap(obj.bitmap, obj.orig, lx, ly, obj.scale)
                sceneView.invalidate()
            }
            return
        }
        if (dragObjId >= 0) {
            val obj = sceneView.objects.firstOrNull { it.id == dragObjId }
            if (obj != null) {
                obj.x += dx
                obj.y += dy
                sceneView.invalidate()
            }
        }
    }

    override fun onSceneUp(cx: Float, cy: Float, moved: Boolean) {
        if (dragObjId >= 0) {
            dragObjId = -1
            updateLiveSize()
        }
    }

    override fun onZoomChanged(zoom: Float) {
        val pct = (zoom * 100).toInt()
        txtZoom.text = "Zoom: " + pct + "% (pinch or slider)"
        findViewById<SeekBar>(R.id.seekZoom).progress = ((zoom - 1f) * 100f).toInt().coerceIn(0, 300)
    }

    // ---- Size, templates, marketplace ----

    private fun mpText(w: Int, h: Int): String {
        val mp = w.toDouble() * h.toDouble() / 1000000.0
        return String.format(Locale.US, "%.1f MP", mp)
    }

    private fun updateOriginalInfo() {
        txtOriginalSize.text = if (originalW > 0) {
            "Original size: " + originalW + "x" + originalH + " (" + mpText(originalW, originalH) + ")"
        } else {
            "Original size: not loaded yet"
        }
    }

    private fun applyExportSizeToScene() {
        sceneView.exportW = exportW
        sceneView.exportH = exportH
        sceneView.invalidate()
        updateLiveSize()
    }

    private fun updateExportInfo() {
        txtExportInfo.text = "Export size: " + exportW + "x" + exportH + " (" + mpText(exportW, exportH) + ")"
        applyExportSizeToScene()
    }

    private fun updateLiveSize() {
        val sel = selectedObj()
        val selText = if (sel != null) {
            ", selected " + sel.name + " " + sel.w().toInt() + "x" + sel.h().toInt() + " px"
        } else {
            ", no object selected"
        }
        txtLiveSize.text = "Live size: canvas " + sceneView.canvasW + "x" + sceneView.canvasH +
            ", export " + exportW + "x" + exportH + " (" + mpText(exportW, exportH) + ")" +
            ", " + sceneView.objects.size + " object(s)" + selText
    }

    private fun updateCustomMp() {
        val w = editCustomW.text.toString().toIntOrNull() ?: 0
        val h = editCustomH.text.toString().toIntOrNull() ?: 0
        if (w > 0 && h > 0 && w <= 8000 && h <= 8000) {
            customW = w; customH = h
            txtCustomMp.text = "Custom: " + w + "x" + h + " (" + mpText(w, h) + ")"
            exportW = w; exportH = h
            updateExportInfo()
        } else if (w > 0 || h > 0) {
            txtCustomMp.text = "Custom: enter a valid width and height (max 8000 per side)"
        }
    }

    private fun applyPreset(pos: Int) {
        if (pos == 7) {
            // Custom: keep whatever custom size was typed, if valid.
            if (customW > 0 && customH > 0) { exportW = customW; exportH = customH }
            updateExportInfo()
            return
        }
        if (pos == 0) {
            exportW = sceneView.canvasW
            exportH = sceneView.canvasH
        } else {
            exportW = presetW[pos]
            exportH = presetH[pos]
        }
        updateExportInfo()
    }

    private fun applyAspect(w: Int, h: Int) {
        customW = w; customH = h
        editCustomW.setText(w.toString())
        editCustomH.setText(h.toString())
        exportW = w; exportH = h
        updatingSpinners = true
        spExportSize.setSelection(7)
        updatingSpinners = false
        updateCustomMp()
        updateExportInfo()
        statusText.text = "Aspect size set: " + w + "x" + h + " (" + mpText(w, h) + ")"
    }

    private fun applyTemplate(pos: Int) {
        showOriginal = false
        sceneView.showOriginal = false
        when (pos) {
            0 -> { bgMode = 5; shadowMode = 1; spShadow.setSelection(1) }
            1 -> { bgMode = 1; solidColor = Color.BLACK; spSolid.setSelection(1) }
            2 -> { bgMode = 0 }
            3 -> { showOriginal = true; sceneView.showOriginal = true }
            4 -> { bgMode = 2; solidColor = Color.parseColor("#1565C0") }
            5 -> { bgMode = 2; solidColor = Color.parseColor("#2E7D32") }
            6 -> { bgMode = 1; solidColor = Color.parseColor("#FFF3E0") }
            7 -> { bgMode = 1; solidColor = Color.parseColor("#E3F2FD") }
        }
        updatingSpinners = true
        spBgMode.setSelection(bgMode)
        updatingSpinners = false
        syncSceneBg()
        statusText.text = "Template applied (own template, not a copied premium template)."
    }

    private fun applyMarketplace(pos: Int) {
        if (pos == 0) return
        val dims = arrayOf(
            Pair(2048, 2048), Pair(2000, 2000), Pair(2700, 2025), Pair(1080, 1080),
            Pair(1080, 1920), Pair(1200, 630), Pair(1080, 1920), Pair(1280, 720), Pair(1080, 1080)
        )
        val d = dims[pos - 1]
        customW = d.first; customH = d.second
        editCustomW.setText(d.first.toString())
        editCustomH.setText(d.second.toString())
        exportW = d.first; exportH = d.second
        updateCustomMp()
        updateExportInfo()
        statusText.text = "Marketplace size set: " + d.first + "x" + d.second + " (" + mpText(d.first, d.second) + ")"
    }

    private fun addTextObject() {
        val text = editText.text.toString()
        if (text.isEmpty()) {
            Toast.makeText(this, "Type some text first", Toast.LENGTH_SHORT).show(); return
        }
        val existing = selectedObj()?.takeIf { it.kind == "TEXT" }
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        paint.isAntiAlias = true
        paint.color = textColor
        paint.textSize = size * 0.16f * (textSizeProgress / 48f)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, size / 2f, size / 2f, paint)
        if (existing != null) {
            existing.bitmap = bmp
            existing.orig = bmp.copy(Bitmap.Config.ARGB_8888, true)
            select(existing.id)
        } else {
            addObject("TEXT", "Text: " + text.take(20), bmp)
        }
        statusText.text = "Text object added. Tap it on the canvas to select, then drag to move it."
    }

    private fun buildLogoObject() {
        val logoText = editLogoText.text.toString().trim()
        if (logoText.isEmpty()) {
            Toast.makeText(this, "Type a logo text first", Toast.LENGTH_SHORT).show(); return
        }
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        paint.isAntiAlias = true
        if (logoShape == 1) {
            paint.color = logoColor
            canvas.drawCircle(size / 2f, size / 2f, size * 0.42f, paint)
            paint.color = Color.WHITE
        } else if (logoShape == 2) {
            paint.color = logoColor
            canvas.drawRect(size * 0.1f, size * 0.1f, size * 0.9f, size * 0.9f, paint)
            paint.color = Color.WHITE
        } else {
            paint.color = logoColor
        }
        paint.textSize = size * 0.16f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(logoText, size / 2f, size / 2f + size * 0.06f, paint)
        addObject("LOGO", "Logo: " + logoText.take(20), bmp)
        statusText.text = "Text logo object built (own logo builder, no AI generation). Drag it on the canvas to move it."
    }

    private fun saveBrand() {
        val prefs = getSharedPreferences("brandkit", MODE_PRIVATE)
        val ed = prefs.edit()
        ed.putInt("solidColor", solidColor)
        ed.putInt("textColor", textColor)
        ed.putString("logoText", editLogoText.text.toString())
        ed.putInt("logoColor", logoColor)
        ed.putInt("logoShape", logoShape)
        ed.putInt("shadowMode", shadowMode)
        ed.apply()
        val logo = selectedObj()?.takeIf { it.kind == "LOGO" }?.bitmap
        if (logo != null) {
            try {
                val out = openFileOutput("brand_logo.png", MODE_PRIVATE)
                logo.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.close()
            } catch (e: Exception) { }
        }
        statusText.text = "Brand kit saved on this device only (no cloud sync)."
        Toast.makeText(this, "Brand kit saved", Toast.LENGTH_SHORT).show()
    }

    private fun loadBrand() {
        val prefs = getSharedPreferences("brandkit", MODE_PRIVATE)
        if (!prefs.contains("solidColor") && !prefs.contains("logoText")) {
            statusText.text = "No saved brand kit yet. Save one first."
            return
        }
        solidColor = prefs.getInt("solidColor", Color.WHITE)
        textColor = prefs.getInt("textColor", Color.BLACK)
        logoColor = prefs.getInt("logoColor", Color.BLACK)
        logoShape = prefs.getInt("logoShape", 0)
        shadowMode = prefs.getInt("shadowMode", 1)
        editLogoText.setText(prefs.getString("logoText", ""))
        spShadow.setSelection(shadowMode)
        try {
            val inp = openFileInput("brand_logo.png")
            val bmp = BitmapFactory.decodeStream(inp)
            inp.close()
            if (bmp != null) addObject("LOGO", "Brand logo", bmp.copy(Bitmap.Config.ARGB_8888, true))
        } catch (e: Exception) { }
        syncSceneBg()
        statusText.text = "Brand kit loaded from this device."
    }

    private fun loadRecent() {
        val prefs = getSharedPreferences("photocut", MODE_PRIVATE)
        val saved = prefs.getString("recent", "") ?: ""
        recentExports.clear()
        if (saved.isNotEmpty()) recentExports.addAll(saved.split("|"))
        updateRecentText()
        updateYourContent()
    }

    private fun updateRecentText() {
        txtRecent.text = if (recentExports.isEmpty()) {
            "Recent exports: none yet"
        } else {
            "Recent exports:\n" + recentExports.take(5).joinToString("\n")
        }
    }

    private fun updateYourContent() {
        txtYourContent.text = if (recentExports.isEmpty()) {
            "No projects yet. Start a new project below."
        } else {
            "Your content (local exports):\n" + recentExports.take(10).joinToString("\n")
        }
    }

    private fun recordRecent(name: String, size: Pair<Int, Int>) {
        val entry = name + " (" + size.first + "x" + size.second + ", " + mpText(size.first, size.second) + ")"
        recentExports.add(0, entry)
        while (recentExports.size > 10) recentExports.removeAt(recentExports.size - 1)
        val prefs = getSharedPreferences("photocut", MODE_PRIVATE)
        prefs.edit().putString("recent", recentExports.joinToString("|")).apply()
        updateRecentText()
        updateYourContent()
    }

    // ---- Classical (non-AI) background removal, on any photo bitmap ----

    private fun colorDistance(c1: Int, c2: Int): Double {
        val dr = (Color.red(c1) - Color.red(c2)).toDouble()
        val dg = (Color.green(c1) - Color.green(c2)).toDouble()
        val db = (Color.blue(c1) - Color.blue(c2)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun featherMask(mask: IntArray, w: Int, h: Int): IntArray {
        val radius = 2
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0; var n = 0
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx in 0 until w) { sum += mask[y * w + nx]; n++ }
                }
                tmp[y * w + x] = sum / max(1, n)
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0; var n = 0
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny in 0 until h) { sum += tmp[ny * w + x]; n++ }
                }
                out[y * w + x] = sum / max(1, n)
            }
        }
        return out
    }

    private fun autoRemoveMask(bmp: Bitmap): IntArray {
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var sr = 0L; var sg = 0L; var sb = 0L; var count = 0L
        for (x in 0 until w step 4) {
            for (yy in intArrayOf(0, h - 1)) {
                val c = pixels[yy * w + x]; sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); count++
            }
        }
        for (y in 0 until h step 4) {
            for (xx in intArrayOf(0, w - 1)) {
                val c = pixels[y * w + xx]; sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); count++
            }
        }
        if (count == 0L) return IntArray(w * h) { 255 }
        val bg = Color.rgb((sr / count).toInt(), (sg / count).toInt(), (sb / count).toInt())
        val threshold = 20.0 + tolerance * 2.2
        val mask = IntArray(w * h) { 255 }
        val visited = BooleanArray(w * h)
        val queue: ArrayDeque<Int> = ArrayDeque()
        for (x in 0 until w) {
            for (yy in intArrayOf(0, h - 1)) {
                val idx = yy * w + x
                if (!visited[idx] && colorDistance(pixels[idx], bg) < threshold) { visited[idx] = true; mask[idx] = 0; queue.add(idx) }
            }
        }
        for (y in 0 until h) {
            for (xx in intArrayOf(0, w - 1)) {
                val idx = y * w + xx
                if (!visited[idx] && colorDistance(pixels[idx], bg) < threshold) { visited[idx] = true; mask[idx] = 0; queue.add(idx) }
            }
        }
        val dx = intArrayOf(1, -1, 0, 0)
        val dy = intArrayOf(0, 0, 1, -1)
        while (!queue.isEmpty()) {
            val cur = queue.removeFirst()
            val cx = cur % w; val cy = cur / w
            for (k in 0..3) {
                val nx = cx + dx[k]; val ny = cy + dy[k]
                if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                val nidx = ny * w + nx
                if (!visited[nidx] && colorDistance(pixels[nidx], bg) < threshold) { visited[nidx] = true; mask[nidx] = 0; queue.add(nidx) }
            }
        }
        return featherMask(mask, w, h)
    }

    private fun applyMaskToBitmap(target: Bitmap, orig: Bitmap, mask: IntArray) {
        val w = target.width; val h = target.height
        val pixels = IntArray(w * h)
        orig.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            pixels[i] = (mask[i] shl 24) or (pixels[i] and 0x00FFFFFF)
        }
        target.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun autoRemoveOnSelected() {
        val obj = selectedObj() ?: sceneView.objects.firstOrNull { it.kind == "MAIN" }
        if (obj == null) {
            Toast.makeText(this, "Pick a photo first", Toast.LENGTH_SHORT).show(); return
        }
        if (obj.kind != "MAIN" && obj.kind != "INSERTED") {
            Toast.makeText(this, "Auto Remove works on photo objects only", Toast.LENGTH_SHORT).show(); return
        }
        val mask = autoRemoveMask(obj.orig)
        applyMaskToBitmap(obj.bitmap, obj.orig, mask)
        showOriginal = false
        sceneView.showOriginal = false
        sceneView.invalidate()
        statusText.text = "Auto Remove done (classical, no AI). Best on plain backgrounds; fix edges with the Mask brush."
    }

    private fun pickColorOnBitmap(target: Bitmap, orig: Bitmap, bx: Int, by: Int) {
        if (bx !in 0 until orig.width || by !in 0 until orig.height) return
        val targetColor = orig.getPixel(bx, by)
        val threshold = 20.0 + tolerance * 2.2
        val w = orig.width; val h = orig.height
        val pixels = IntArray(w * h)
        orig.getPixels(pixels, 0, w, 0, 0, w, h)
        val current = IntArray(w * h)
        target.getPixels(current, 0, w, 0, 0, w, h)
        val mask = IntArray(w * h) { i -> Color.alpha(current[i]) }
        for (i in pixels.indices) {
            if (colorDistance(pixels[i], targetColor) < threshold) mask[i] = 0
        }
        applyMaskToBitmap(target, orig, featherMask(mask, w, h))
        showOriginal = false
        sceneView.showOriginal = false
        statusText.text = "Background colour removed. Fix edges with the Mask brush if needed."
    }

    private fun brushOnBitmap(target: Bitmap, orig: Bitmap, lx: Float, ly: Float, objScale: Float) {
        // lx/ly are in the bitmap's own pixel space; brush radius is in
        // canvas space, so divide by the object's scale to stay consistent.
        val r = max(1, (brushRadius / max(0.1f, objScale)).toInt())
        val bx = lx.toInt()
        val by = ly.toInt()
        val w = target.width; val h = target.height
        for (dy in -r..r) {
            for (dx in -r..r) {
                if (dx * dx + dy * dy > r * r) continue
                val nx = bx + dx; val ny = by + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val origPx = orig.getPixel(nx, ny)
                if (brushMode == 3) {
                    var sr = 0; var sg = 0; var sb = 0; var n = 0
                    for (ddy in -2..2) { for (ddx in -2..2) {
                        val qx = nx + ddx; val qy = ny + ddy
                        if (qx in 0 until w && qy in 0 until h) { val c = orig.getPixel(qx, qy); sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); n++ }
                    } }
                    val fill = if (n > 0) Color.rgb(sr / n, sg / n, sb / n) else (origPx and 0x00FFFFFF)
                    target.setPixel(nx, ny, (255 shl 24) or fill)
                } else {
                    val alpha = if (brushMode == 1) 0 else 255
                    target.setPixel(nx, ny, (alpha shl 24) or (origPx and 0x00FFFFFF))
                }
            }
        }
    }

    // ---- Insert flow ----

    private fun insertAutoRemove() {
        val ib = insertBitmap ?: run {
            Toast.makeText(this, "Pick a photo to insert first", Toast.LENGTH_SHORT).show(); return
        }
        val orig = insertOrig ?: return
        val mask = autoRemoveMask(orig)
        applyMaskToBitmap(ib, orig, mask)
        sceneView.insertBitmap = ib
        sceneView.invalidate()
        statusText.text = "Inserted photo background removed (classical, no AI). Tap Place inserted object when done."
    }

    private fun finishInsert() {
        val ib = insertBitmap ?: run {
            Toast.makeText(this, "Pick a photo to insert first", Toast.LENGTH_SHORT).show(); return
        }
        val orig = insertOrig ?: return
        sceneView.insertMode = false
        sceneView.insertBitmap = null
        insertBitmap = null
        insertOrig = null
        val obj = addObject("INSERTED", "Inserted photo", ib)
        obj.orig = orig.copy(Bitmap.Config.ARGB_8888, true)
        statusText.text = "Inserted object placed. Tap it to select, drag to move, adjust it, or reorder it in Layers."
        showPanel(R.id.panelLayers)
        updateLayersText()
    }

    private fun cancelInsert() {
        sceneView.insertMode = false
        sceneView.insertBitmap = null
        insertBitmap = null
        insertOrig = null
        insertTapMode = false
        sceneView.invalidate()
        statusText.text = "Insert cancelled."
    }

    // ---- Export composite (own rendering, never the outline/handles) ----

    private fun hueMatrix(degrees: Float): ColorMatrix {
        val rad = Math.toRadians(degrees.toDouble()).toFloat()
        val cos = kotlin.math.cos(rad)
        val sin = kotlin.math.sin(rad)
        val lumR = 0.213f
        val lumG = 0.715f
        val lumB = 0.072f
        return ColorMatrix(floatArrayOf(
            lumR + cos * (1 - lumR) + sin * (-lumR), lumG + cos * (-lumG) + sin * (-lumG), lumB + cos * (-lumB) + sin * (1 - lumB), 0f, 0f,
            lumR + cos * (-lumR) + sin * 0.143f, lumG + cos * (1 - lumG) + sin * 0.140f, lumB + cos * (-lumB) + sin * (-0.283f), 0f, 0f,
            lumR + cos * (-lumR) + sin * (-(1 - lumR)), lumG + cos * (-lumG) + sin * lumG, lumB + cos * (1 - lumB) + sin * lumB, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    private fun perObjectPaint(o: SceneObj): Paint {
        val p = Paint()
        p.isAntiAlias = true
        val cm = ColorMatrix()
        cm.setSaturation(o.saturation / 100f)
        val c = o.contrast / 100f
        val offset = (1f - c) * 128f + (o.bright - 100) * 1.28f + o.white * 1.28f - o.black * 1.28f
        cm.postConcat(ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, offset,
            0f, c, 0f, 0f, offset,
            0f, 0f, c, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )))
        if (o.hue != 0) cm.postConcat(hueMatrix(o.hue.toFloat()))
        p.colorFilter = ColorMatrixColorFilter(cm)
        return p
    }

    private fun blurBitmap(src: Bitmap, factor: Int): Bitmap {
        val smallW = max(16, src.width / factor)
        val smallH = max(16, src.height / factor)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    private fun composite(isPng: Boolean): Bitmap? {
        val ob = origBitmap ?: return null
        val cw = sceneView.canvasW
        val ch = sceneView.canvasH
        val ew = exportW
        val eh = exportH
        val out = Bitmap.createBitmap(ew, eh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        if (!isPng) canvas.drawColor(Color.WHITE)
        if (showOriginal) {
            canvas.drawBitmap(Bitmap.createScaledBitmap(ob, ew, eh, true), 0f, 0f, null)
            return out
        }
        val sx = ew.toFloat() / cw
        val sy = eh.toFloat() / ch
        paint.shader = null
        paint.colorFilter = null
        when (bgMode) {
            0 -> { if (!isPng) canvas.drawColor(Color.WHITE) }
            1 -> { paint.color = solidColor; canvas.drawRect(0f, 0f, ew.toFloat(), eh.toFloat(), paint) }
            2 -> {
                paint.shader = LinearGradient(0f, 0f, 0f, eh.toFloat(), solidColor, Color.WHITE, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, ew.toFloat(), eh.toFloat(), paint)
                paint.shader = null
            }
            3 -> {
                canvas.drawBitmap(blurBitmap(Bitmap.createScaledBitmap(ob, ew, eh, true), 32), 0f, 0f, null)
            }
            4 -> {
                val bg = bgPhoto
                if (bg != null) {
                    val s = max(ew.toFloat() / bg.width, eh.toFloat() / bg.height)
                    val sw = (bg.width * s).toInt()
                    val sh = (bg.height * s).toInt()
                    canvas.drawBitmap(Bitmap.createScaledBitmap(bg, sw, sh, true), (ew - sw) / 2f, (eh - sh) / 2f, null)
                } else canvas.drawColor(Color.WHITE)
            }
            5, 6 -> canvas.drawColor(Color.WHITE)
            7 -> {
                paint.shader = LinearGradient(0f, 0f, 0f, eh.toFloat(), Color.parseColor("#F5F5F5"), Color.parseColor("#E0E0E0"), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, ew.toFloat(), eh.toFloat(), paint)
                paint.shader = null
            }
        }
        for (o in sceneView.objects) {
            if (!o.visible) continue
            if (shadowEnabled && shadowMode != 0 && o.kind == "MAIN") {
                val w = o.bitmap.width
                val h = o.bitmap.height
                val pixels = IntArray(w * h)
                o.bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                val alphaScale = when (shadowMode) { 2 -> 140; 3 -> 110; else -> 90 }
                val spixels = IntArray(w * h)
                for (i in pixels.indices) spixels[i] = ((Color.alpha(pixels[i]) * alphaScale / 255) shl 24)
                val shadow = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                shadow.setPixels(spixels, 0, w, 0, 0, w, h)
                val factor = when (shadowMode) { 2 -> 8; 3 -> 16; else -> 32 }
                val offX = when (shadowMode) { 2 -> 8f; 3 -> 16f; else -> 12f }
                val offY = when (shadowMode) { 2 -> 12f; 3 -> 28f; else -> 18f }
                val blurred = blurBitmap(shadow, factor)
                val scaledShadow = Bitmap.createScaledBitmap(blurred, max(1, (o.w() * sx).toInt()), max(1, (o.h() * sy).toInt()), true)
                canvas.drawBitmap(scaledShadow, (o.x + offX) * sx, (o.y + offY) * sy, null)
            }
            val scaled = Bitmap.createScaledBitmap(o.bitmap, max(1, (o.w() * sx).toInt()), max(1, (o.h() * sy).toInt()), true)
            canvas.drawBitmap(scaled, o.x * sx, o.y * sy, perObjectPaint(o))
        }
        return out
    }

    private fun exportImage(isPng: Boolean) {
        val comp = composite(isPng)
        if (comp == null) {
            Toast.makeText(this, "Pick a photo first", Toast.LENGTH_SHORT).show(); return
        }
        try {
            val name = "PhotoCut_" + System.currentTimeMillis() + if (isPng) ".png" else ".jpg"
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name)
            values.put(MediaStore.Images.Media.MIME_TYPE, if (isPng) "image/png" else "image/jpeg")
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhotoCut")
            val outUri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (outUri != null) {
                val out = contentResolver.openOutputStream(outUri)
                if (out != null) {
                    if (isPng) comp.compress(Bitmap.CompressFormat.PNG, 100, out) else comp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    out.close()
                }
                recordRecent(name, Pair(exportW, exportH))
                statusText.text = "Exported " + name + " (" + exportW + "x" + exportH + ") to Pictures/PhotoCut."
                Toast.makeText(this, "Exported to Pictures/PhotoCut", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            statusText.text = "Export failed: " + e.message
        }
    }

    private fun batchExport() {
        if (batchUris.isEmpty()) {
            Toast.makeText(this, "Pick batch photos first", Toast.LENGTH_SHORT).show(); return
        }
        var done = 0
        for ((index, uri) in batchUris.withIndex()) {
            try {
                val bmp = decodeScaled(uri, 1024) ?: continue
                val savedObjects = sceneView.objects.toList()
                val savedOrig = origBitmap
                val savedCanvasW = sceneView.canvasW
                val savedCanvasH = sceneView.canvasH
                val savedSelectedId = sceneView.selectedId
                val savedShowOriginal = showOriginal
                loadBitmapAsMain(bmp)
                val main = sceneView.objects.firstOrNull { it.kind == "MAIN" }
                if (main != null) {
                    val mask = autoRemoveMask(main.orig)
                    applyMaskToBitmap(main.bitmap, main.orig, mask)
                }
                val comp = composite(true) ?: continue
                val name = "PhotoCut_batch_" + (index + 1) + "_" + System.currentTimeMillis() + ".png"
                val values = ContentValues()
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name)
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhotoCut")
                val outUri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (outUri != null) {
                    val out = contentResolver.openOutputStream(outUri)
                    if (out != null) { comp.compress(Bitmap.CompressFormat.PNG, 100, out); out.close() }
                    recordRecent(name, Pair(exportW, exportH))
                    done++
                }
                // Restore the user's project (batch must not destroy it).
                sceneView.objects.clear()
                sceneView.objects.addAll(savedObjects)
                origBitmap = savedOrig
                sceneView.origBitmap = savedOrig
                sceneView.canvasW = savedCanvasW
                sceneView.canvasH = savedCanvasH
                sceneView.selectedId = savedSelectedId
                showOriginal = savedShowOriginal
                sceneView.showOriginal = savedShowOriginal
                sceneView.invalidate()
            } catch (e: Exception) { continue }
        }
        txtBatchInfo.text = "Batch: exported " + done + " of " + batchUris.size + " photo(s) to Pictures/PhotoCut."
        statusText.text = "Batch done: " + done + " of " + batchUris.size + " exported."
        updateLiveSize()
    }
}
