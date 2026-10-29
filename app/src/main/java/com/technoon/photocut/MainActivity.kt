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
 * PhotoCut Offline v4 - clean-room, fully offline photo editor.
 * v4 adds only the remaining offline-feasible gaps from the complete
 * function list: curvature/text-curve approximations, perspective
 * approximations, halftone/line-screen approximations, disc/hexagonal
 * blur approximations, 3D shadow approximation, multi-line text with
 * line spacing, and batch resize modes expand/fill/fit. Everything
 * from v3 is kept. No AI, no network, no account.
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
    private lateinit var spFilter: Spinner
    private lateinit var spBlend: Spinner
    private lateinit var spBlur: Spinner
    private lateinit var spBatchResize: Spinner
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
    private var shadowMove = 0f
    private var pendingBlur = 0 // 0 none, 1 box, 2 gaussian, 3 pixel, 4 motion
    private var cropMode = false
    private var cropStartX = 0f
    private var cropStartY = 0f
    private var textSpacing = 0
    private var textBold = false
    private var curveAmount = 50
    private var lineSpacing = 10
    private var batchResizeMode = 0 // 0 fit, 1 fill, 2 expand
    private var sharpAmount = 50
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
    private val reqReplace = 107
    private val reqReplaceCamera = 108

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
        R.id.panelLogo, R.id.panelLayers, R.id.panelExport, R.id.panelEdit
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
        spFilter = findViewById(R.id.spFilter)
        spBlend = findViewById(R.id.spBlend)
        spBlur = findViewById(R.id.spBlur)
        spBatchResize = findViewById(R.id.spBatchResize)
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
        findViewById<Button>(R.id.btnToolEdit).setOnClickListener { showPanel(R.id.panelEdit) }

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
        setupSpinner(spShadow, arrayOf("Shadow: None", "Shadow: Soft", "Shadow: Hard", "Shadow: Floating", "Shadow: 3D")) { pos ->
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
        setupSpinner(spFilter, arrayOf("Filter: None", "Filter: Sepia", "Filter: Mono", "Filter: Noir", "Filter: Tonal", "Filter: Fade", "Filter: Chrome", "Filter: Process")) { pos ->
            selectedObj()?.let { it.filter = pos; sceneView.invalidate(); updateLiveSize() }
        }
        setupSpinner(spBlend, arrayOf("Blend: Normal", "Blend: Multiply", "Blend: Screen", "Blend: Darken", "Blend: Lighten")) { pos ->
            selectedObj()?.let { it.blendMode = pos; sceneView.invalidate(); updateLiveSize() }
        }
        setupSpinner(spBlur, arrayOf("Blur: None", "Blur: Box", "Blur: Gaussian", "Blur: Pixel", "Blur: Motion", "Blur: Disc", "Blur: Hexagonal")) { pos ->
            pendingBlur = pos
        }
        setupSpinner(spBatchResize, arrayOf("Batch resize: Fit", "Batch resize: Fill", "Batch resize: Expand")) { pos ->
            batchResizeMode = pos
        }
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
        findViewById<Button>(R.id.btnUnlockAll).setOnClickListener { unlockAll() }

        // Edit panel: filters, blur, rotate, align, lock, opacity, reflection,
        // outline, tile, crop, replace, save-as-template, warmth, sharpness.
        findViewById<Button>(R.id.btnApplyBlur).setOnClickListener { applyBlurToSelected() }
        findViewById<Button>(R.id.btnPosterize).setOnClickListener { applyPosterizeToSelected() }
        findViewById<Button>(R.id.btnSharpen).setOnClickListener { applySharpenToSelected() }
        findViewById<Button>(R.id.btnImproveLight).setOnClickListener { improveLightSelected() }
        findViewById<Button>(R.id.btnCurve).setOnClickListener { applyCurveToSelected() }
        findViewById<Button>(R.id.btnPerspH).setOnClickListener { applyPerspectiveToSelected(true) }
        findViewById<Button>(R.id.btnPerspV).setOnClickListener { applyPerspectiveToSelected(false) }
        findViewById<Button>(R.id.btnHalftone).setOnClickListener { applyHalftoneToSelected() }
        findViewById<Button>(R.id.btnLineScreen).setOnClickListener { applyLineScreenToSelected() }
        findViewById<Button>(R.id.btnRotateCW).setOnClickListener { rotateSelected(90f) }
        findViewById<Button>(R.id.btnRotateCCW).setOnClickListener { rotateSelected(-90f) }
        findViewById<Button>(R.id.btnAlignLeft).setOnClickListener { alignSelected(0) }
        findViewById<Button>(R.id.btnAlignCenter).setOnClickListener { alignSelected(1) }
        findViewById<Button>(R.id.btnAlignRight).setOnClickListener { alignSelected(2) }
        findViewById<Button>(R.id.btnAlignTop).setOnClickListener { alignSelected(3) }
        findViewById<Button>(R.id.btnAlignMiddle).setOnClickListener { alignSelected(4) }
        findViewById<Button>(R.id.btnAlignBottom).setOnClickListener { alignSelected(5) }
        findViewById<Button>(R.id.btnLock).setOnClickListener { lockSelected() }
        findViewById<Button>(R.id.btnReflection).setOnClickListener { addReflection() }
        findViewById<Button>(R.id.btnOutline).setOnClickListener { addOutline() }
        findViewById<Button>(R.id.btnTile).setOnClickListener { tileSelected() }
        findViewById<Button>(R.id.btnCrop).setOnClickListener { startCropMode() }
        findViewById<Button>(R.id.btnReplaceGallery).setOnClickListener { pickImage(reqReplace) }
        findViewById<Button>(R.id.btnReplaceCamera).setOnClickListener {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (intent.resolveActivity(packageManager) != null) startActivityForResult(intent, reqReplaceCamera)
            else Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSaveTemplate).setOnClickListener { saveAsTemplate() }
        findViewById<Button>(R.id.btnLoadTemplate).setOnClickListener { loadSavedTemplate() }
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
        setSeekListener(R.id.seekWarmth) { v -> selectedObj()?.let { it.warmth = v - 100; sceneView.invalidate(); updateLiveSize() } }
        setSeekListener(R.id.seekOpacity) { v -> selectedObj()?.let { it.opacity = v; sceneView.invalidate(); updateLiveSize() } }
        setSeekListener(R.id.seekSharp) { sharpAmount = it }
        setSeekListener(R.id.seekShadowMove) { v -> shadowMove = v.toFloat(); syncSceneBg() }
        setSeekListener(R.id.seekTextSize) { textSizeProgress = it }
        setSeekListener(R.id.seekTextSpacing) { textSpacing = it }
        setSeekListener(R.id.seekLineSpacing) { lineSpacing = it }
        setSeekListener(R.id.seekCurve) { curveAmount = it }
        findViewById<CheckBox>(R.id.chkTextBold).setOnCheckedChangeListener { _, checked -> textBold = checked }

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
        sceneView.shadowMove = shadowMove
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
                reqReplace -> {
                    val uri = data.data ?: return
                    val bmp = decodeScaled(uri, 1024) ?: return
                    replaceSelectedBitmap(bmp)
                }
                reqReplaceCamera -> {
                    val bmp = data.extras?.get("data") as? Bitmap
                    if (bmp != null) replaceSelectedBitmap(bmp.copy(Bitmap.Config.ARGB_8888, true))
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
            findViewById<SeekBar>(R.id.seekWarmth).progress = obj.warmth + 100
            findViewById<SeekBar>(R.id.seekOpacity).progress = obj.opacity
            spFilter.setSelection(obj.filter)
            spBlend.setSelection(obj.blendMode)
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
            val hidden = (if (o.visible) "" else " (hidden)") + (if (o.locked) " (locked)" else "")
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
        if (cropMode) {
            cropStartX = cx
            cropStartY = cy
            sceneView.cropMode = true
            sceneView.cropRect = android.graphics.RectF(cx, cy, cx, cy)
            sceneView.invalidate()
            return
        }
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
        if (cropMode) {
            sceneView.cropRect = android.graphics.RectF(
                min(cropStartX, cx), min(cropStartY, cy),
                max(cropStartX, cx), max(cropStartY, cy)
            )
            sceneView.invalidate()
            return
        }
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
        if (cropMode) {
            applyCrop()
            cropMode = false
            sceneView.cropMode = false
            sceneView.cropRect = null
            sceneView.invalidate()
            return
        }
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

    /** Build a text bitmap. Multi-line: one line per Enter, with the
     *  line-spacing slider between lines. Used for new text objects and
     *  for rebuilding a text object when a curve is applied. */
    private fun buildTextBitmap(text: String): Bitmap {
        val lines = text.split("\n")
        val paint = Paint()
        paint.isAntiAlias = true
        paint.color = textColor
        paint.textSize = 82f * (textSizeProgress / 48f)
        paint.textAlign = Paint.Align.CENTER
        paint.letterSpacing = textSpacing / 100f
        paint.isFakeBoldText = textBold
        var maxW = 0f
        for (line in lines) maxW = max(maxW, paint.measureText(line))
        val lineH = paint.textSize * 1.3f + lineSpacing
        val w = (maxW + 40).toInt().coerceAtLeast(64)
        val h = (lineH * lines.size + 40).toInt().coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        var y = 20f + paint.textSize
        for (line in lines) {
            canvas.drawText(line, w / 2f, y, paint)
            y += lineH
        }
        return bmp
    }

    private fun addTextObject() {
        val text = editText.text.toString()
        if (text.isEmpty()) {
            Toast.makeText(this, "Type some text first", Toast.LENGTH_SHORT).show(); return
        }
        val existing = selectedObj()?.takeIf { it.kind == "TEXT" }
        val bmp = buildTextBitmap(text)
        if (existing != null) {
            existing.bitmap = bmp
            existing.orig = bmp.copy(Bitmap.Config.ARGB_8888, true)
            existing.textContent = text
            select(existing.id)
        } else {
            val obj = addObject("TEXT", "Text: " + text.replace("\n", " ").take(20), bmp)
            obj.textContent = text
        }
        statusText.text = "Text object added (multi-line: use Enter for a new line). Tap it on the canvas to select, then drag to move it."
    }

    /** Draw the stored text along an arc (text-curve approximation). */
    private fun buildCurvedTextBitmap(text: String, curvePct: Int): Bitmap {
        val lines = text.split("\n")
        val paint = Paint()
        paint.isAntiAlias = true
        paint.color = textColor
        paint.textSize = 82f * (textSizeProgress / 48f)
        paint.textAlign = Paint.Align.CENTER
        paint.letterSpacing = textSpacing / 100f
        paint.isFakeBoldText = textBold
        // Arc: curvePct 0 = straight, 100 = a half-circle-ish bend.
        val totalAngle = (curvePct - 50) * 0.024f // -1.2 .. +1.2 radians
        val size = 1024
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f + 160f
        val radius = 420f
        var lineY = 0f
        for (line in lines) {
            if (line.isEmpty()) { lineY += paint.textSize * 1.3f + lineSpacing; continue }
            val n = line.length
            for (i in line.indices) {
                val ch = line[i].toString()
                val frac = if (n <= 1) 0f else (i.toFloat() / (n - 1) - 0.5f)
                val angle = frac * totalAngle
                val x = cx + kotlin.math.sin(angle) * radius
                val y = cy - kotlin.math.cos(angle) * radius + radius + lineY
                val saveCount = canvas.save()
                canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat(), x, y)
                canvas.drawText(ch, x, y, paint)
                canvas.restore(saveCount)
            }
            lineY += paint.textSize * 1.3f + lineSpacing
        }
        return bmp
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
        if (o.warmth != 0) {
            val w = o.warmth / 100f
            cm.postConcat(ColorMatrix(floatArrayOf(
                1f + w * 0.25f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, 1f - w * 0.25f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        if (o.filter != 0) cm.postConcat(filterMatrix(o.filter))
        p.colorFilter = ColorMatrixColorFilter(cm)
        p.alpha = (o.opacity * 255 / 100).coerceIn(0, 255)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            p.blendMode = when (o.blendMode) {
                1 -> android.graphics.BlendMode.MULTIPLY
                2 -> android.graphics.BlendMode.SCREEN
                3 -> android.graphics.BlendMode.DARKEN
                4 -> android.graphics.BlendMode.LIGHTEN
                else -> null
            }
        }
        return p
    }

    private fun filterMatrix(f: Int): ColorMatrix {
        return when (f) {
            1 -> ColorMatrix(floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            2 -> ColorMatrix().apply { setSaturation(0f) }
            3 -> {
                val m = ColorMatrix().apply { setSaturation(0f) }
                m.postConcat(ColorMatrix(floatArrayOf(
                    1.3f, 0f, 0f, 0f, -38f,
                    0f, 1.3f, 0f, 0f, -38f,
                    0f, 0f, 1.3f, 0f, -38f,
                    0f, 0f, 0f, 1f, 0f
                )))
                m
            }
            4 -> ColorMatrix(floatArrayOf(
                1.15f, 0f, 0f, 0f, 0f,
                0f, 1.1f, 0f, 0f, 0f,
                0f, 0f, 0.95f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            5 -> ColorMatrix(floatArrayOf(
                0.85f, 0f, 0f, 0f, 25f,
                0f, 0.85f, 0f, 0f, 25f,
                0f, 0f, 0.85f, 0f, 25f,
                0f, 0f, 0f, 1f, 0f
            ))
            6 -> {
                val m = ColorMatrix().apply { setSaturation(1.4f) }
                m.postConcat(ColorMatrix(floatArrayOf(
                    1.25f, 0f, 0f, 0f, -32f,
                    0f, 1.25f, 0f, 0f, -32f,
                    0f, 0f, 1.25f, 0f, -32f,
                    0f, 0f, 0f, 1f, 0f
                )))
                m
            }
            else -> {
                val m = ColorMatrix().apply { setSaturation(1.2f) }
                m.postConcat(ColorMatrix(floatArrayOf(
                    1.2f, 0f, 0f, 0f, 0f,
                    0f, 1.1f, 0f, 0f, 0f,
                    0f, 0f, 0.95f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )))
                m
            }
        }
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
                if (shadowMode == 4) {
                    // 3D shadow approximation (same layered extrusion as the preview).
                    for (layer in 1..6) {
                        val a = (100 - layer * 10).coerceAtLeast(20)
                        val spixels = IntArray(w * h)
                        for (i in pixels.indices) spixels[i] = ((Color.alpha(pixels[i]) * a / 255) shl 24)
                        val shadow = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        shadow.setPixels(spixels, 0, w, 0, 0, w, h)
                        val blurred = blurBitmap(shadow, 16)
                        val scaledShadow = Bitmap.createScaledBitmap(blurred, max(1, (o.w() * sx).toInt()), max(1, (o.h() * sy).toInt()), true)
                        val off = layer * 6f + shadowMove
                        canvas.drawBitmap(scaledShadow, (o.x + off) * sx, (o.y + off) * sy, null)
                    }
                } else {
                val alphaScale = when (shadowMode) { 2 -> 140; 3 -> 110; else -> 90 }
                val spixels = IntArray(w * h)
                for (i in pixels.indices) spixels[i] = ((Color.alpha(pixels[i]) * alphaScale / 255) shl 24)
                val shadow = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                shadow.setPixels(spixels, 0, w, 0, 0, w, h)
                val factor = when (shadowMode) { 2 -> 8; 3 -> 16; else -> 32 }
                val offX = when (shadowMode) { 2 -> 8f; 3 -> 16f; else -> 12f } + shadowMove
                val offY = when (shadowMode) { 2 -> 12f; 3 -> 28f; else -> 18f } + shadowMove
                val blurred = blurBitmap(shadow, factor)
                val scaledShadow = Bitmap.createScaledBitmap(blurred, max(1, (o.w() * sx).toInt()), max(1, (o.h() * sy).toInt()), true)
                canvas.drawBitmap(scaledShadow, (o.x + offX) * sx, (o.y + offY) * sy, null)
                }
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
                // Batch resize modes (v4): Fit keeps the v3 behaviour
                // (composite scaled to the requested size). Fill composites
                // at cover size, then centre-crops to the requested size.
                // Expand composites at fit size, then pads to the requested
                // size with the current background colour.
                val reqW = exportW
                val reqH = exportH
                if (batchResizeMode != 0) {
                    val cw = sceneView.canvasW.toFloat()
                    val ch = sceneView.canvasH.toFloat()
                    val scale = if (batchResizeMode == 1) max(reqW / cw, reqH / ch) else min(reqW / cw, reqH / ch)
                    exportW = (cw * scale).toInt().coerceAtLeast(1)
                    exportH = (ch * scale).toInt().coerceAtLeast(1)
                }
                val rawComp = composite(true)
                exportW = reqW
                exportH = reqH
                val comp = rawComp?.let { applyBatchResizeMode(it, reqW, reqH) } ?: continue
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

    // ---- Edit panel: filters, blur, rotate, align, lock, opacity, reflection,
    // outline, tile, crop, replace, save-as-template, warmth, sharpness ----

    private fun requireSelected(): SceneObj? {
        val obj = selectedObj()
        if (obj == null) Toast.makeText(this, "Select an object first", Toast.LENGTH_SHORT).show()
        return obj
    }

    /** Offsets inside a circle of the given radius (disc kernel). */
    private fun discOffsets(r: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        for (dy in -r..r) for (dx in -r..r) {
            if (dx * dx + dy * dy <= r * r) out.add(Pair(dx, dy))
        }
        return out
    }

    /** Offsets inside a hexagon of the given radius (axial-coordinate hex). */
    private fun hexOffsets(r: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        for (dy in -r..r) for (dx in -r..r) {
            val dz = -dx - dy
            if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy), kotlin.math.abs(dz)) <= r) out.add(Pair(dx, dy))
        }
        return out
    }

    /** Average each pixel over the given kernel offsets (edge-clamped). */
    private fun kernelBlur(src: Bitmap, offsets: List<Pair<Int, Int>>): Bitmap {
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb = 0; var sa = 0
                for ((dx, dy) in offsets) {
                    val nx = (x + dx).coerceIn(0, w - 1)
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val c = pixels[ny * w + nx]
                    sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); sa += Color.alpha(c)
                }
                val n = offsets.size
                result[y * w + x] = (sa / n shl 24) or (sr / n shl 16) or (sg / n shl 8) or (sb / n)
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(result, 0, w, 0, 0, w, h)
        return out
    }

    private fun applyBlurToSelected() {
        val obj = requireSelected() ?: return
        if (pendingBlur == 0) {
            Toast.makeText(this, "Pick a blur type first", Toast.LENGTH_SHORT).show(); return
        }
        val src = obj.bitmap
        val out = when (pendingBlur) {
            1 -> blurBitmap(src, 8) // box blur (downscale/upscale average)
            2 -> { // gaussian approximation: repeated box blurs
                var b = blurBitmap(src, 12)
                b = blurBitmap(b, 12)
                blurBitmap(b, 12)
            }
            3 -> { // pixel (mosaic): down then up with no filtering
                val small = Bitmap.createScaledBitmap(src, max(1, src.width / 12), max(1, src.height / 12), false)
                Bitmap.createScaledBitmap(small, src.width, src.height, false)
            }
            5 -> kernelBlur(src, discOffsets(3)) // disc blur approximation: circular kernel
            6 -> kernelBlur(src, hexOffsets(3)) // hexagonal blur approximation
            else -> { // motion approximation: horizontal average, radius 6, step 2
                val w = src.width; val h = src.height
                val pixels = IntArray(w * h)
                src.getPixels(pixels, 0, w, 0, 0, w, h)
                val result = IntArray(w * h)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        var sr = 0; var sg = 0; var sb = 0; var sa = 0; var n = 0
                        var dx = -6
                        while (dx <= 6) {
                            val nx = (x + dx).coerceIn(0, w - 1)
                            val c = pixels[y * w + nx]
                            sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); sa += Color.alpha(c); n++
                            dx += 2
                        }
                        result[y * w + x] = (sa / n shl 24) or (sr / n shl 16) or (sg / n shl 8) or (sb / n)
                    }
                }
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.setPixels(result, 0, w, 0, 0, w, h)
                bmp
            }
        }
        obj.bitmap = out
        sceneView.invalidate()
        statusText.text = "Blur applied to the selected object."
    }

    // ---- v4: curvature, perspective, halftone, line-screen (approximations) ----

    private fun applyCurveToSelected() {
        val obj = requireSelected() ?: return
        if (obj.kind == "TEXT" && obj.textContent != null) {
            val curved = buildCurvedTextBitmap(obj.textContent!!, curveAmount)
            obj.bitmap = curved
            obj.orig = curved.copy(Bitmap.Config.ARGB_8888, true)
            sceneView.invalidate()
            statusText.text = "Text curve applied (arc approximation)."
            return
        }
        // Photo bend approximation: sine-wave vertical displacement.
        val src = obj.bitmap
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = IntArray(w * h)
        val amp = (curveAmount - 50) * h / 500f // -0.1h .. +0.1h
        for (y in 0 until h) {
            for (x in 0 until w) {
                val shift = (amp * kotlin.math.sin(Math.PI * x / max(1, w))).toInt()
                val sy = (y - shift).coerceIn(0, h - 1)
                result[y * w + x] = pixels[sy * w + x]
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(result, 0, w, 0, 0, w, h)
        obj.bitmap = out
        sceneView.invalidate()
        statusText.text = "Curve applied (bend approximation)."
    }

    private fun applyPerspectiveToSelected(horizontal: Boolean) {
        val obj = requireSelected() ?: return
        val src = obj.bitmap
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = IntArray(w * h)
        // Trapezoid approximation: one edge is 25% narrower than the other.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx: Int
                val sy: Int
                if (horizontal) {
                    val t = y.toFloat() / max(1, h)
                    val scale = 0.75f + 0.25f * t // top narrower, bottom full
                    sx = ((x - w / 2f) / scale + w / 2f).toInt().coerceIn(0, w - 1)
                    sy = y
                } else {
                    val t = x.toFloat() / max(1, w)
                    val scale = 0.75f + 0.25f * t // left narrower, right full
                    sx = x
                    sy = ((y - h / 2f) / scale + h / 2f).toInt().coerceIn(0, h - 1)
                }
                result[y * w + x] = pixels[sy * w + sx]
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(result, 0, w, 0, 0, w, h)
        obj.bitmap = out
        sceneView.invalidate()
        statusText.text = "Perspective applied (" + (if (horizontal) "horizontal" else "vertical") + ", trapezoid approximation)."
    }

    private fun applyHalftoneToSelected() {
        val obj = requireSelected() ?: return
        val src = obj.bitmap
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = IntArray(w * h) { (255 shl 24) or 0x00FFFFFF }
        val cell = 10
        var cy = 0
        while (cy < h) {
            var cx = 0
            while (cx < w) {
                var sr = 0; var sg = 0; var sb = 0; var sa = 0; var n = 0
                for (y in cy until min(h, cy + cell)) {
                    for (x in cx until min(w, cx + cell)) {
                        val c = pixels[y * w + x]
                        sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); sa += Color.alpha(c); n++
                    }
                }
                if (n > 0 && sa / n > 10) {
                    val lum = (sr / n + sg / n + sb / n) / 3f
                    val radius = ((1f - lum / 255f) * cell / 2f)
                    val ccx = cx + cell / 2f
                    val ccy = cy + cell / 2f
                    val dotColor = (255 shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                    for (y in cy until min(h, cy + cell)) {
                        for (x in cx until min(w, cx + cell)) {
                            val dx = x - ccx; val dy = y - ccy
                            if (dx * dx + dy * dy <= radius * radius) result[y * w + x] = dotColor
                        }
                    }
                }
                cx += cell
            }
            cy += cell
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(result, 0, w, 0, 0, w, h)
        obj.bitmap = out
        sceneView.invalidate()
        statusText.text = "Halftone applied (dot pattern approximation)."
    }

    private fun applyLineScreenToSelected() {
        val obj = requireSelected() ?: return
        val src = obj.bitmap
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = IntArray(w * h) { (255 shl 24) or 0x00FFFFFF }
        val band = 8
        var y0 = 0
        while (y0 < h) {
            var sr = 0; var sg = 0; var sb = 0; var sa = 0; var n = 0
            for (y in y0 until min(h, y0 + band)) {
                for (x in 0 until w) {
                    val c = pixels[y * w + x]
                    sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); sa += Color.alpha(c); n++
                }
            }
            if (n > 0 && sa / n > 10) {
                val lum = (sr / n + sg / n + sb / n) / 3f
                val thickness = ((1f - lum / 255f) * band).toInt().coerceIn(0, band)
                val dotColor = (255 shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                for (y in y0 until min(h, y0 + thickness)) {
                    for (x in 0 until w) result[y * w + x] = dotColor
                }
            }
            y0 += band
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(result, 0, w, 0, 0, w, h)
        obj.bitmap = out
        sceneView.invalidate()
        statusText.text = "Line screen applied (line pattern approximation)."
    }

    /** Batch resize post-process: comp is the composite at the requested
     *  size (fit/stretch). Fit: uniform scale inside, centred on the
     *  current background. Fill: uniform scale to cover, centre-cropped.
     *  Expand: pad the composite to the requested size with the background. */
    private fun applyBatchResizeMode(comp: Bitmap, reqW: Int, reqH: Int): Bitmap {
        if (batchResizeMode == 0) return comp
        val bg = if (bgMode == 0) Color.TRANSPARENT else if (bgMode == 1) solidColor else Color.WHITE
        if (batchResizeMode == 2) {
            // Expand: pad (comp already fits the requested size in one
            // dimension when its aspect differs; pad the other).
            val out = Bitmap.createBitmap(reqW, reqH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            if (bg != Color.TRANSPARENT) canvas.drawColor(bg)
            canvas.drawBitmap(comp, (reqW - comp.width) / 2f, (reqH - comp.height) / 2f, null)
            return out
        }
        // Fill: centre-crop the composite to the requested aspect, then scale.
        val targetAspect = reqW.toFloat() / max(1, reqH)
        val compAspect = comp.width.toFloat() / max(1, comp.height)
        val cropped: Bitmap
        if (compAspect > targetAspect) {
            val cw = (comp.height * targetAspect).toInt().coerceAtLeast(1)
            val left = (comp.width - cw) / 2
            cropped = Bitmap.createBitmap(comp, left, 0, cw, comp.height)
        } else {
            val ch = (comp.width / targetAspect).toInt().coerceAtLeast(1)
            val top = (comp.height - ch) / 2
            cropped = Bitmap.createBitmap(comp, 0, top, comp.width, ch)
        }
        return Bitmap.createScaledBitmap(cropped, reqW, reqH, true)
    }

    private fun applyPosterizeToSelected() {
        val obj = requireSelected() ?: return
        val src = obj.bitmap
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (Color.red(c) / 64) * 85
            val g = (Color.green(c) / 64) * 85
            val b = (Color.blue(c) / 64) * 85
            pixels[i] = (Color.alpha(c) shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        obj.bitmap = out
        sceneView.invalidate()
        statusText.text = "Posterize applied (4 colour levels, approximation)."
    }

    private fun applySharpenToSelected() {
        val obj = requireSelected() ?: return
        val src = obj.bitmap
        val blurred = blurBitmap(src, 8)
        val w = src.width; val h = src.height
        val orig = IntArray(w * h)
        val blur = IntArray(w * h)
        src.getPixels(orig, 0, w, 0, 0, w, h)
        blurred.getPixels(blur, 0, w, 0, 0, w, h)
        val amount = sharpAmount / 50f
        val result = IntArray(w * h)
        for (i in orig.indices) {
            val r = (Color.red(orig[i]) + (Color.red(orig[i]) - Color.red(blur[i])) * amount).toInt().coerceIn(0, 255)
            val g = (Color.green(orig[i]) + (Color.green(orig[i]) - Color.green(blur[i])) * amount).toInt().coerceIn(0, 255)
            val b = (Color.blue(orig[i]) + (Color.blue(orig[i]) - Color.blue(blur[i])) * amount).toInt().coerceIn(0, 255)
            result[i] = (Color.alpha(orig[i]) shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(result, 0, w, 0, 0, w, h)
        obj.bitmap = out
        sceneView.invalidate()
        statusText.text = "Sharpness applied (unsharp-mask approximation)."
    }

    private fun improveLightSelected() {
        val obj = requireSelected() ?: return
        obj.bright = min(200, obj.bright + 15)
        obj.contrast = min(200, obj.contrast + 5)
        obj.white = min(100, obj.white + 10)
        select(obj.id)
        statusText.text = "Improve light applied to the selected object (approximation)."
    }

    private fun rotateSelected(degrees: Float) {
        val obj = requireSelected() ?: return
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degrees)
        val rotated = Bitmap.createBitmap(obj.bitmap, 0, 0, obj.bitmap.width, obj.bitmap.height, matrix, true)
        val rotatedOrig = Bitmap.createBitmap(obj.orig, 0, 0, obj.orig.width, obj.orig.height, matrix, true)
        obj.bitmap = rotated
        obj.orig = rotatedOrig
        sceneView.invalidate()
        updateLiveSize()
        statusText.text = "Object rotated by " + degrees.toInt() + " degrees."
    }

    private fun alignSelected(mode: Int) {
        val obj = requireSelected() ?: return
        val cw = sceneView.canvasW.toFloat()
        val ch = sceneView.canvasH.toFloat()
        when (mode) {
            0 -> obj.x = 0f
            1 -> obj.x = (cw - obj.w()) / 2f
            2 -> obj.x = cw - obj.w()
            3 -> obj.y = 0f
            4 -> obj.y = (ch - obj.h()) / 2f
            5 -> obj.y = ch - obj.h()
        }
        sceneView.invalidate()
        updateLiveSize()
        statusText.text = "Object aligned."
    }

    private fun lockSelected() {
        val obj = requireSelected() ?: return
        obj.locked = true
        select(-1)
        updateLayersText()
        statusText.text = "Object locked. Use Unlock all in Layers to unlock it."
    }

    private fun unlockAll() {
        for (o in sceneView.objects) o.locked = false
        sceneView.invalidate()
        updateLayersText()
        statusText.text = "All objects unlocked."
    }

    private fun addReflection() {
        val obj = requireSelected() ?: return
        val src = obj.bitmap
        val matrix = android.graphics.Matrix()
        matrix.postScale(1f, -1f)
        val flipped = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        // Fade the reflection from ~50% alpha at the top to 0 at the bottom.
        val w = flipped.width; val h = flipped.height
        val pixels = IntArray(w * h)
        flipped.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val fade = ((1f - y.toFloat() / max(1, h)) * 128).toInt()
            for (x in 0 until w) {
                val i = y * w + x
                val a = (Color.alpha(pixels[i]) * fade / 255)
                pixels[i] = (a shl 24) or (pixels[i] and 0x00FFFFFF)
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        val refl = SceneObj(nextObjId++, "REFLECTION", "Reflection of " + obj.name, out, out.copy(Bitmap.Config.ARGB_8888, true))
        refl.x = obj.x
        refl.y = obj.y + obj.h()
        refl.scale = obj.scale
        sceneView.objects.add(refl)
        select(refl.id)
        statusText.text = "Reflection added below the selected object (simple flipped faded copy)."
    }

    private fun addOutline() {
        val obj = requireSelected() ?: return
        val src = obj.bitmap
        val w = src.width; val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val a = Color.alpha(pixels[i])
            pixels[i] = (a shl 24) or (solidColor and 0x00FFFFFF)
        }
        val silhouette = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        silhouette.setPixels(pixels, 0, w, 0, 0, w, h)
        val outline = SceneObj(nextObjId++, "OUTLINE", "Outline of " + obj.name, silhouette, silhouette.copy(Bitmap.Config.ARGB_8888, true))
        outline.x = obj.x - obj.w() * 0.04f
        outline.y = obj.y - obj.h() * 0.04f
        outline.scale = obj.scale * 1.08f
        // Insert directly behind the selected object in layer order.
        val idx = sceneView.objects.indexOf(obj)
        if (idx >= 0) sceneView.objects.add(idx, outline) else sceneView.objects.add(outline)
        select(obj.id)
        statusText.text = "Outline added behind the selected object (silhouette approximation)."
    }

    private fun tileSelected() {
        val obj = requireSelected() ?: return
        val cw = sceneView.canvasW.toFloat()
        val ch = sceneView.canvasH.toFloat()
        val tileW = cw / 2f
        val tileH = ch / 2f
        val scaleX = tileW / max(1, obj.bitmap.width)
        val scaleY = tileH / max(1, obj.bitmap.height)
        val s = min(scaleX, scaleY)
        val positions = arrayOf(Pair(0f, 0f), Pair(tileW, 0f), Pair(0f, tileH), Pair(tileW, tileH))
        obj.scale = s
        obj.x = positions[0].first
        obj.y = positions[0].second
        for (k in 1 until positions.size) {
            val copy = SceneObj(nextObjId++, obj.kind, obj.name + " tile " + (k + 1), obj.bitmap.copy(Bitmap.Config.ARGB_8888, true), obj.orig.copy(Bitmap.Config.ARGB_8888, true))
            copy.scale = s
            copy.x = positions[k].first
            copy.y = positions[k].second
            copy.bright = obj.bright; copy.contrast = obj.contrast; copy.saturation = obj.saturation
            copy.filter = obj.filter; copy.opacity = obj.opacity
            sceneView.objects.add(copy)
        }
        select(obj.id)
        statusText.text = "Object tiled into a 2x2 grid (approximation)."
    }

    private fun startCropMode() {
        val obj = selectedObj()
        if (obj == null || (obj.kind != "MAIN" && obj.kind != "INSERTED")) {
            Toast.makeText(this, "Select a photo object first, then drag on it to crop", Toast.LENGTH_SHORT).show()
            return
        }
        cropMode = true
        statusText.text = "Crop: drag on the photo to draw the crop rectangle, then release."
    }

    private fun applyCrop() {
        val rect = sceneView.cropRect ?: return
        val obj = selectedObj() ?: return
        if (rect.width() < 20f || rect.height() < 20f) {
            statusText.text = "Crop cancelled: rectangle too small."
            return
        }
        val lx = ((rect.left - obj.x) / obj.scale).toInt().coerceIn(0, obj.bitmap.width - 1)
        val ly = ((rect.top - obj.y) / obj.scale).toInt().coerceIn(0, obj.bitmap.height - 1)
        val rw = ((rect.width() / obj.scale).toInt()).coerceIn(1, obj.bitmap.width - lx)
        val rh = ((rect.height() / obj.scale).toInt()).coerceIn(1, obj.bitmap.height - ly)
        obj.bitmap = Bitmap.createBitmap(obj.bitmap, lx, ly, rw, rh)
        obj.orig = Bitmap.createBitmap(obj.orig, lx, ly, rw, rh)
        obj.x = rect.left
        obj.y = rect.top
        sceneView.invalidate()
        updateLiveSize()
        statusText.text = "Cropped to " + rw + "x" + rh + " px."
    }

    private fun replaceSelectedBitmap(bmp: Bitmap) {
        val obj = selectedObj()
        if (obj == null) {
            Toast.makeText(this, "Select an object first, then replace it", Toast.LENGTH_SHORT).show()
            return
        }
        val oldW = obj.w()
        val oldH = obj.h()
        val copy = bmp.copy(Bitmap.Config.ARGB_8888, true)
        obj.orig = copy.copy(Bitmap.Config.ARGB_8888, true)
        obj.bitmap = copy
        obj.scale = min(oldW / max(1, copy.width), oldH / max(1, copy.height))
        if (obj.scale <= 0f) obj.scale = 1f
        sceneView.invalidate()
        updateLiveSize()
        statusText.text = "Object replaced from " + (if (obj.kind == "MAIN") "the new image" else "gallery/camera") + ". Adjustments and layers kept."
    }

    private fun saveAsTemplate() {
        val prefs = getSharedPreferences("photocut", MODE_PRIVATE)
        prefs.edit()
            .putInt("savedBgMode", bgMode)
            .putInt("savedSolid", solidColor)
            .putInt("savedShadow", shadowMode)
            .putBoolean("savedShadowOn", shadowEnabled)
            .putInt("savedExportW", exportW)
            .putInt("savedExportH", exportH)
            .apply()
        statusText.text = "Current look saved as a template on this device (background, shadow, size)."
        Toast.makeText(this, "Template saved", Toast.LENGTH_SHORT).show()
    }

    private fun loadSavedTemplate() {
        val prefs = getSharedPreferences("photocut", MODE_PRIVATE)
        if (!prefs.contains("savedBgMode")) {
            statusText.text = "No saved template yet. Save one first."
            return
        }
        bgMode = prefs.getInt("savedBgMode", 0)
        solidColor = prefs.getInt("savedSolid", Color.WHITE)
        shadowMode = prefs.getInt("savedShadow", 1)
        shadowEnabled = prefs.getBoolean("savedShadowOn", false)
        exportW = prefs.getInt("savedExportW", exportW)
        exportH = prefs.getInt("savedExportH", exportH)
        updatingSpinners = true
        spBgMode.setSelection(bgMode)
        spShadow.setSelection(shadowMode)
        updatingSpinners = false
        chkShadow.isChecked = shadowEnabled
        syncSceneBg()
        updateExportInfo()
        statusText.text = "Saved template applied."
    }
}
