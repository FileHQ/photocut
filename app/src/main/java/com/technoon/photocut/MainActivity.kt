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
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
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
 * PhotoCut Offline - clean-room, fully offline photo editor.
 * No AI, no ML model, no network code, no INTERNET permission.
 * Automatic background removal is classical only: border colour sampling
 * plus flood-fill by colour distance, with brush erase/restore for fixes.
 * MP is calculated from pixels (width * height / 1,000,000), not read
 * from camera metadata.
 */
class MainActivity : Activity() {

    private lateinit var preview: ImageView
    private lateinit var statusText: TextView
    private lateinit var txtOriginalSize: TextView
    private lateinit var txtExportInfo: TextView
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

    private var workBitmap: Bitmap? = null
    private var maskedBitmap: Bitmap? = null
    private var bgPhoto: Bitmap? = null
    private var logoBitmap: Bitmap? = null
    private var maskAlpha: IntArray? = null

    private var originalW = 0
    private var originalH = 0
    private var showOriginal = false
    private var brushMode = 0 // 0 none, 1 erase, 2 restore
    private var pickColorMode = false
    private var tolerance = 35
    private var brushRadius = 24
    private var bright = 100
    private var contrast = 100
    private var saturation = 100
    private var bgMode = 0 // 0 transparent, 1 solid, 2 gradient, 3 blurred, 4 photo
    private var solidColor = Color.WHITE
    private var textColor = Color.BLACK
    private var textSizeProgress = 48
    private var overlayText = ""
    private var textX = 0.5f
    private var textY = 0.85f
    private var logoX = 0.5f
    private var logoY = 0.15f
    private var dragWhat = 0 // 0 none, 1 text, 2 logo
    private var exportPresetIndex = 0
    private var shadowMode = 1 // 0 none, 1 soft, 2 hard, 3 floating
    private var hueShift = 0 // degrees, -180..180
    private var templateInitDone = false
    private var marketplaceInitDone = false
    private var logoText = ""
    private var logoColor = Color.BLACK
    private var logoShape = 0 // 0 none, 1 circle, 2 square
    private val batchUris = mutableListOf<android.net.Uri>()
    private val recentExports = mutableListOf<String>()
    private val reqBatch = 105
    private var customW = 0
    private var customH = 0
    private var updatingSpinners = false

    private val reqPick = 101
    private val reqCamera = 102
    private val reqBg = 103
    private val reqLogo = 104

    private val presetW = intArrayOf(0, 1080, 1080, 1920, 2048, 3000, 4000, -1)
    private val presetH = intArrayOf(0, 1080, 1350, 1080, 2048, 3000, 3000, -1)
    private val presetLabels = arrayOf(
        "Original",
        "1080x1080 (1.2 MP)",
        "1080x1350 (1.5 MP)",
        "1920x1080 (2.1 MP)",
        "2048x2048 (4.2 MP)",
        "3000x3000 (9.0 MP)",
        "4000x3000 (12.0 MP)",
        "Custom..."
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        statusText = findViewById(R.id.statusText)
        txtOriginalSize = findViewById(R.id.txtOriginalSize)
        txtExportInfo = findViewById(R.id.txtExportInfo)
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

        setupSpinner(spBgMode, arrayOf("Transparent", "Solid colour", "Gradient", "Blurred original", "Photo background", "White background", "Studio look", "Flat Lay")) { pos ->
            bgMode = pos; refreshPreview()
        }
        setupSpinner(spSolid, solidNames) { pos ->
            solidColor = solidColors[pos]; refreshPreview()
        }
        setupSpinner(spTextColor, textColorNames) { pos ->
            textColor = textColors[pos]; refreshPreview()
        }
        setupSpinner(spAspect, arrayOf("Free (use pixel size)", "1:1", "4:5", "9:16", "16:9")) { pos ->
            if (updatingSpinners) return@setupSpinner
            when (pos) {
                1 -> applyAspect(1080, 1080)
                2 -> applyAspect(1080, 1350)
                3 -> applyAspect(1080, 1920)
                4 -> applyAspect(1920, 1080)
            }
        }
        setupSpinner(spExportSize, presetLabels) { pos ->
            exportPresetIndex = pos; updateExportInfo()
        }

        findViewById<Button>(R.id.btnPick).setOnClickListener { pickImage(reqPick) }
        findViewById<Button>(R.id.btnCamera).setOnClickListener {
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (intent.resolveActivity(packageManager) != null) {
                startActivityForResult(intent, reqCamera)
            } else {
                Toast.makeText(this, "No camera app found", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<Button>(R.id.btnAuto).setOnClickListener { autoRemove() }
        findViewById<Button>(R.id.btnPickColor).setOnClickListener {
            pickColorMode = true; brushMode = 0
            statusText.text = "Tap the background colour on the photo."
        }
        findViewById<Button>(R.id.btnErase).setOnClickListener {
            brushMode = 1; pickColorMode = false; statusText.text = "Brush: erase - drag on the photo."
        }
        findViewById<Button>(R.id.btnRestore).setOnClickListener {
            brushMode = 2; pickColorMode = false; statusText.text = "Brush: restore - drag on the photo."
        }
        findViewById<Button>(R.id.btnToggle).setOnClickListener {
            showOriginal = !showOriginal; refreshPreview()
        }
        findViewById<Button>(R.id.btnBgPhoto).setOnClickListener { pickImage(reqBg) }
        findViewById<Button>(R.id.btnLogo).setOnClickListener { pickImage(reqLogo) }
        findViewById<Button>(R.id.btnAddText).setOnClickListener {
            overlayText = editText.text.toString(); refreshPreview()
        }
        findViewById<Button>(R.id.btnExportPng).setOnClickListener { exportImage(true) }
        findViewById<Button>(R.id.btnExportJpg).setOnClickListener { exportImage(false) }

        setSeekListener(R.id.seekTolerance) { tolerance = it; }
        setSeekListener(R.id.seekBrush) { brushRadius = max(4, it); }
        setSeekListener(R.id.seekBright) { bright = it; refreshPreview(); }
        setSeekListener(R.id.seekContrast) { contrast = it; refreshPreview(); }
        setSeekListener(R.id.seekSaturation) { saturation = it; refreshPreview(); }
        setSeekListener(R.id.seekTextSize) { textSizeProgress = it; refreshPreview(); }
        chkShadow.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        spShadow = findViewById(R.id.spShadow)
        spTemplate = findViewById(R.id.spTemplate)
        spMarketplace = findViewById(R.id.spMarketplace)
        spLogoColor = findViewById(R.id.spLogoColor)
        spLogoShape = findViewById(R.id.spLogoShape)
        editLogoText = findViewById(R.id.editLogoText)
        txtBatchInfo = findViewById(R.id.txtBatchInfo)
        txtRecent = findViewById(R.id.txtRecent)
        setupSpinner(spShadow, arrayOf("Shadow: None", "Shadow: Soft", "Shadow: Hard", "Shadow: Floating")) { pos ->
            shadowMode = pos; refreshPreview()
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
        findViewById<Button>(R.id.btnFill).setOnClickListener {
            brushMode = 3; pickColorMode = false; statusText.text = "Brush: fill - drag on an erased area to fill it with nearby colours."
        }
        findViewById<Button>(R.id.btnBuildLogo).setOnClickListener { buildLogoBitmap() }
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
        setSeekListener(R.id.seekHue) { hueShift = it - 180; refreshPreview(); }
        loadRecent()

        val customWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { updateCustomMp() }
        }
        editCustomW.addTextChangedListener(customWatcher)
        editCustomH.addTextChangedListener(customWatcher)

        preview.setOnTouchListener { _, event -> onPreviewTouch(event) }

        updateOriginalInfo()
        updateExportInfo()
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
                    refreshPreview()
                    statusText.text = "Background photo loaded."
                }
                reqLogo -> {
                    val uri = data.data ?: return
                    logoBitmap = decodeScaled(uri, 512)
                    refreshPreview()
                    statusText.text = "Logo loaded. Drag it on the photo to move it."
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
                        workBitmap = bmp.copy(Bitmap.Config.ARGB_8888, true)
                        resetMask()
                        updateOriginalInfo(); updateExportInfo(); refreshPreview()
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
        workBitmap = decodeScaled(uri, 1024)
        resetMask()
        showOriginal = false
        updateOriginalInfo(); updateExportInfo(); refreshPreview()
        statusText.text = "Photo loaded. Tap Auto Remove, or Tap BG colour, then fix edges with the brush."
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

    private fun resetMask() {
        val bmp = workBitmap ?: return
        maskAlpha = IntArray(bmp.width * bmp.height) { 255 }
        maskedBitmap = bmp.copy(Bitmap.Config.ARGB_8888, true)
    }

    // ---- MP and size selection ----

    private fun mpText(w: Int, h: Int): String {
        if (w <= 0 || h <= 0) return "—"
        return String.format(Locale.US, "%.1f MP", w * h / 1000000.0)
    }

    private fun updateOriginalInfo() {
        if (originalW > 0 && originalH > 0) {
            txtOriginalSize.text = "Original size: " + originalW + "x" + originalH + " (" + mpText(originalW, originalH) + "). MP is calculated from pixels, not camera metadata."
        } else {
            txtOriginalSize.text = "Original size: not loaded yet"
        }
    }

    private fun updateCustomMp() {
        val wText = editCustomW.text.toString().trim()
        val hText = editCustomH.text.toString().trim()
        if (wText.isEmpty() || hText.isEmpty()) {
            txtCustomMp.text = "Custom: enter width and height to see MP"
            updateExportInfo()
            return
        }
        val w = wText.toIntOrNull() ?: 0
        val h = hText.toIntOrNull() ?: 0
        if (w <= 0 || h <= 0) {
            txtCustomMp.text = "Error: width and height must be greater than 0"
            customW = 0; customH = 0
        } else if (w > 8000 || h > 8000) {
            txtCustomMp.text = "Error: max 8000 px per side (you entered " + w + "x" + h + ")"
            customW = w; customH = h
        } else {
            txtCustomMp.text = "Custom: " + w + "x" + h + " (" + mpText(w, h) + ")"
            customW = w; customH = h
        }
        updateExportInfo()
    }

    private fun getExportSize(): Pair<Int, Int>? {
        return when (exportPresetIndex) {
            0 -> {
                if (originalW > 0 && originalH > 0) Pair(originalW, originalH)
                else workBitmap?.let { Pair(it.width, it.height) }
            }
            7 -> {
                if (customW > 0 && customH > 0 && customW <= 8000 && customH <= 8000) Pair(customW, customH) else null
            }
            else -> Pair(presetW[exportPresetIndex], presetH[exportPresetIndex])
        }
    }

    private fun updateExportInfo() {
        val size = getExportSize()
        if (size == null) {
            txtExportInfo.text = "Export size: invalid custom size (both sides must be 1 to 8000 px)"
        } else {
            txtExportInfo.text = "Export size: " + size.first + "x" + size.second + " (" + mpText(size.first, size.second) + ")"
        }
    }

    private fun applyAspect(w: Int, h: Int) {
        customW = w; customH = h
        updatingSpinners = true
        editCustomW.setText(w.toString())
        editCustomH.setText(h.toString())
        updatingSpinners = false
        // Select the matching preset if it exists, otherwise Custom.
        var matched = 7
        for (i in 1..6) {
            if (presetW[i] == w && presetH[i] == h) { matched = i; break }
        }
        updatingSpinners = true
        spExportSize.setSelection(matched)
        updatingSpinners = false
        exportPresetIndex = matched
        updateCustomMp()
        updateExportInfo()
    }

    // ---- Classical (non-AI) background removal ----

    private fun colorDistance(c1: Int, c2: Int): Double {
        val dr = (Color.red(c1) - Color.red(c2)).toDouble()
        val dg = (Color.green(c1) - Color.green(c2)).toDouble()
        val db = (Color.blue(c1) - Color.blue(c2)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun autoRemove() {
        val bmp = workBitmap ?: run {
            Toast.makeText(this, "Pick a photo first", Toast.LENGTH_SHORT).show(); return
        }
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
        if (count == 0L) return
        val bg = Color.rgb((sr / count).toInt(), (sg / count).toInt(), (sb / count).toInt())
        val threshold = 20.0 + tolerance * 2.2
        val mask = IntArray(w * h) { 255 }
        val visited = BooleanArray(w * h)
        val queue: ArrayDeque<Int> = ArrayDeque()
        for (x in 0 until w) {
            for (yy in intArrayOf(0, h - 1)) {
                val idx = yy * w + x
                if (!visited[idx] && colorDistance(pixels[idx], bg) < threshold) {
                    visited[idx] = true; mask[idx] = 0; queue.add(idx)
                }
            }
        }
        for (y in 0 until h) {
            for (xx in intArrayOf(0, w - 1)) {
                val idx = y * w + xx
                if (!visited[idx] && colorDistance(pixels[idx], bg) < threshold) {
                    visited[idx] = true; mask[idx] = 0; queue.add(idx)
                }
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
                if (!visited[nidx] && colorDistance(pixels[nidx], bg) < threshold) {
                    visited[nidx] = true; mask[nidx] = 0; queue.add(nidx)
                }
            }
        }
        maskAlpha = featherMask(mask, w, h)
        rebuildMasked()
        showOriginal = false
        refreshPreview()
        statusText.text = "Auto Remove done (classical, no AI). Best on plain backgrounds; fix edges with Brush Erase / Restore."
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

    private fun rebuildMasked() {
        val bmp = workBitmap ?: return
        val mask = maskAlpha ?: return
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            pixels[i] = (mask[i] shl 24) or (pixels[i] and 0x00FFFFFF)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        maskedBitmap = out
    }

    private fun pickColorAt(bx: Int, by: Int) {
        val bmp = workBitmap ?: return
        if (bx !in 0 until bmp.width || by !in 0 until bmp.height) return
        val target = bmp.getPixel(bx, by)
        val threshold = 20.0 + tolerance * 2.2
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val mask = maskAlpha ?: IntArray(w * h) { 255 }
        for (i in pixels.indices) {
            if (colorDistance(pixels[i], target) < threshold) mask[i] = 0
        }
        maskAlpha = featherMask(mask, w, h)
        rebuildMasked()
        pickColorMode = false
        showOriginal = false
        refreshPreview()
        statusText.text = "Background colour removed. Fix edges with the brush if needed."
    }

    private fun brushAt(bx: Int, by: Int) {
        val bmp = workBitmap ?: return
        val mask = maskAlpha ?: return
        val masked = maskedBitmap ?: return
        val w = bmp.width; val h = bmp.height
        val r = brushRadius
        for (dy in -r..r) {
            for (dx in -r..r) {
                if (dx * dx + dy * dy > r * r) continue
                val nx = bx + dx; val ny = by + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val idx = ny * w + nx
                mask[idx] = if (brushMode == 1) 0 else 255
                val orig = bmp.getPixel(nx, ny)
                if (brushMode == 3) {
                    var sr = 0; var sg = 0; var sb = 0; var n = 0
                    for (ddy in -2..2) { for (ddx in -2..2) {
                        val qx = nx + ddx; val qy = ny + ddy
                        if (qx in 0 until w && qy in 0 until h) { val c = bmp.getPixel(qx, qy); sr += Color.red(c); sg += Color.green(c); sb += Color.blue(c); n++ }
                    } }
                    if (n > 0) masked.setPixel(nx, ny, (255 shl 24) or Color.rgb(sr / n, sg / n, sb / n)) else masked.setPixel(nx, ny, (255 shl 24) or (orig and 0x00FFFFFF))
                }
                if (brushMode != 3) masked.setPixel(nx, ny, (mask[idx] shl 24) or (orig and 0x00FFFFFF))
            }
        }
        refreshPreview()
    }


    // ---- v2: templates, marketplace, logo builder, brand kit, batch, recent ----

    private fun applyTemplate(pos: Int) {
        showOriginal = false
        when (pos) {
            0 -> { bgMode = 5; shadowMode = 1; spShadow.setSelection(1) }
            1 -> { bgMode = 1; solidColor = Color.BLACK; spSolid.setSelection(1) }
            2 -> { bgMode = 0 }
            3 -> { showOriginal = true }
            4 -> { bgMode = 2; solidColor = Color.parseColor("#1565C0") }
            5 -> { bgMode = 2; solidColor = Color.parseColor("#2E7D32") }
            6 -> { bgMode = 1; solidColor = Color.parseColor("#FFF3E0") }
            7 -> { bgMode = 1; solidColor = Color.parseColor("#E3F2FD") }
        }
        updatingSpinners = true
        spBgMode.setSelection(bgMode)
        updatingSpinners = false
        refreshPreview()
        statusText.text = "Template applied (own template, not a copied premium template)."
    }

    private fun applyMarketplace(pos: Int) {
        if (pos == 0) return
        val dims = arrayOf(Pair(2048, 2048), Pair(2000, 2000), Pair(2700, 2025), Pair(1080, 1080), Pair(1080, 1920), Pair(1200, 630), Pair(1080, 1920), Pair(1280, 720), Pair(1080, 1080))
        val d = dims[pos - 1]
        customW = d.first; customH = d.second
        editCustomW.setText(d.first.toString())
        editCustomH.setText(d.second.toString())
        exportPresetIndex = 7
        updateCustomMp(); updateExportInfo()
        statusText.text = "Marketplace size set: " + d.first + "x" + d.second + " (" + mpText(d.first, d.second) + ")"
    }

    private fun buildLogoBitmap() {
        logoText = editLogoText.text.toString().trim()
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
        logoBitmap = bmp
        logoX = 0.5f; logoY = 0.15f
        refreshPreview()
        statusText.text = "Text logo built (own logo builder, no AI generation). Drag it on the photo to move it."
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
        val logo = logoBitmap
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
            logoBitmap = BitmapFactory.decodeStream(inp)
            inp.close()
        } catch (e: Exception) { }
        refreshPreview()
        statusText.text = "Brand kit loaded from this device."
    }

    private fun loadRecent() {
        val prefs = getSharedPreferences("photocut", MODE_PRIVATE)
        val saved = prefs.getString("recent", "") ?: ""
        recentExports.clear()
        if (saved.isNotEmpty()) recentExports.addAll(saved.split("|"))
        updateRecentText()
    }

    private fun updateRecentText() {
        if (recentExports.isEmpty()) {
            txtRecent.text = "Recent exports: none yet"
        } else {
            txtRecent.text = "Recent exports:\n" + recentExports.take(5).joinToString("\n")
        }
    }

    private fun recordRecent(name: String, size: Pair<Int, Int>) {
        val entry = name + " (" + size.first + "x" + size.second + ", " + mpText(size.first, size.second) + ")"
        recentExports.add(0, entry)
        while (recentExports.size > 10) recentExports.removeAt(recentExports.size - 1)
        val prefs = getSharedPreferences("photocut", MODE_PRIVATE)
        prefs.edit().putString("recent", recentExports.joinToString("|")).apply()
        updateRecentText()
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

    private fun batchExport() {
        if (batchUris.isEmpty()) {
            Toast.makeText(this, "Pick batch photos first", Toast.LENGTH_SHORT).show(); return
        }
        val size = getExportSize()
        if (size == null) {
            statusText.text = "Batch blocked: custom size is invalid"; return
        }
        var done = 0
        for ((index, uri) in batchUris.withIndex()) {
            try {
                val bounds = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                var stream = contentResolver.openInputStream(uri)
                BitmapFactory.decodeStream(stream, null, bounds)
                stream?.close()
                originalW = bounds.outWidth; originalH = bounds.outHeight
                workBitmap = decodeScaled(uri, 1024)
                if (workBitmap == null) continue
                maskAlpha = autoRemoveMask(workBitmap!!)
                rebuildMasked()
                showOriginal = false
                val comp = composite(true) ?: continue
                val scaled = Bitmap.createScaledBitmap(comp, size.first, size.second, true)
                val finalBmp: Bitmap = if (bgMode == 0) {
                    val white = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(white); canvas.drawColor(Color.WHITE); canvas.drawBitmap(scaled, 0f, 0f, null); white
                } else scaled
                val name = "PhotoCut_batch_" + (index + 1) + "_" + System.currentTimeMillis() + ".png"
                val values = ContentValues()
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name)
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhotoCut")
                val outUri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (outUri != null) {
                    val out = contentResolver.openOutputStream(outUri)
                    if (out != null) { finalBmp.compress(Bitmap.CompressFormat.PNG, 100, out); out.close() }
                    recordRecent(name, size); done++
                }
            } catch (e: Exception) { continue }
        }
        txtBatchInfo.text = "Batch: exported " + done + " of " + batchUris.size + " photo(s) to Pictures/PhotoCut."
        statusText.text = "Batch done: " + done + " of " + batchUris.size + " exported."
        refreshPreview()
    }

    // ---- Preview, touch, composite ----

    private fun bitmapPoint(viewX: Float, viewY: Float): Pair<Int, Int>? {
        val bmp = workBitmap ?: return null
        val vw = preview.width.toFloat(); val vh = preview.height.toFloat()
        if (vw <= 0f || vh <= 0f) return null
        val scale = min(vw / bmp.width, vh / bmp.height)
        val dw = bmp.width * scale; val dh = bmp.height * scale
        val ox = (vw - dw) / 2f; val oy = (vh - dh) / 2f
        val bx = ((viewX - ox) / scale).toInt()
        val by = ((viewY - oy) / scale).toInt()
        if (bx !in 0 until bmp.width || by !in 0 until bmp.height) return null
        return Pair(bx, by)
    }

    private fun onPreviewTouch(event: MotionEvent): Boolean {
        val bmp = workBitmap ?: return false
        val pt = bitmapPoint(event.x, event.y) ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (pickColorMode) { pickColorAt(pt.first, pt.second); return true }
                if (brushMode != 0) { brushAt(pt.first, pt.second); return true }
                val tx = (textX * bmp.width).toInt(); val ty = (textY * bmp.height).toInt()
                val lx = (logoX * bmp.width).toInt(); val ly = (logoY * bmp.height).toInt()
                val nearText = overlayText.isNotEmpty() && kotlin.math.abs(pt.first - tx) < 120 && kotlin.math.abs(pt.second - ty) < 60
                val nearLogo = logoBitmap != null && kotlin.math.abs(pt.first - lx) < 120 && kotlin.math.abs(pt.second - ly) < 120
                dragWhat = when {
                    nearText -> 1
                    nearLogo -> 2
                    else -> 0
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (brushMode != 0) { brushAt(pt.first, pt.second); return true }
                if (dragWhat == 1) {
                    textX = pt.first.toFloat() / bmp.width; textY = pt.second.toFloat() / bmp.height; refreshPreview()
                } else if (dragWhat == 2) {
                    logoX = pt.first.toFloat() / bmp.width; logoY = pt.second.toFloat() / bmp.height; refreshPreview()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragWhat = 0; return true
            }
        }
        return false
    }

    private fun blurredCopy(src: Bitmap): Bitmap {
        return blurBitmap(src, 32)
    }

    private fun blurBitmap(src: Bitmap, factor: Int): Bitmap {
        val smallW = max(16, src.width / factor)
        val smallH = max(16, src.height / factor)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    private fun hueMatrix(degrees: Float): ColorMatrix {
        val rad = Math.toRadians(degrees.toDouble()).toFloat()
        val cos = kotlin.math.cos(rad); val sin = kotlin.math.sin(rad)
        val lumR = 0.213f; val lumG = 0.715f; val lumB = 0.072f
        return ColorMatrix(floatArrayOf(
            lumR + cos * (1 - lumR) + sin * (-lumR), lumG + cos * (-lumG) + sin * (-lumG), lumB + cos * (-lumB) + sin * (1 - lumB), 0f, 0f,
            lumR + cos * (-lumR) + sin * (0.143f), lumG + cos * (1 - lumG) + sin * (0.140f), lumB + cos * (-lumB) + sin * (-0.283f), 0f, 0f,
            lumR + cos * (-lumR) + sin * (-(1 - lumR)), lumG + cos * (-lumG) + sin * (lumG), lumB + cos * (1 - lumB) + sin * (lumB), 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    private fun composite(forExport: Boolean): Bitmap? {
        val bmp = workBitmap ?: return null
        val masked = maskedBitmap ?: bmp
        val w = bmp.width; val h = bmp.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        when (bgMode) {
            0 -> {
                if (!forExport) {
                    val sq = 32
                    var row = 0
                    var y = 0
                    while (y < h) {
                        var col = 0
                        var x = 0
                        while (x < w) {
                            paint.color = if ((row + col) % 2 == 0) Color.WHITE else Color.parseColor("#CCCCCC")
                            canvas.drawRect(x.toFloat(), y.toFloat(), min(w, x + sq).toFloat(), min(h, y + sq).toFloat(), paint)
                            x += sq; col++
                        }
                        y += sq; row++
                    }
                }
            }
            1 -> canvas.drawColor(solidColor)
            2 -> {
                paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), solidColor, Color.WHITE, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
                paint.shader = null
            }
            3 -> {
                val blurred = blurredCopy(bmp)
                canvas.drawBitmap(blurred, 0f, 0f, null)
            }
            4 -> {
                val bg = bgPhoto
                if (bg != null) {
                    val scale = max(w.toFloat() / bg.width, h.toFloat() / bg.height)
                    val sw = (bg.width * scale).toInt(); val sh = (bg.height * scale).toInt()
                    val scaled = Bitmap.createScaledBitmap(bg, sw, sh, true)
                    canvas.drawBitmap(scaled, (w - sw) / 2f, (h - sh) / 2f, null)
                } else {
                    canvas.drawColor(Color.WHITE)
                }
            }
            5 -> canvas.drawColor(Color.WHITE)
            6 -> {
                canvas.drawColor(Color.WHITE)
                // Studio look: clean white background; soft shadow is drawn below.
            }
            7 -> {
                paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.parseColor("#F5F5F5"), Color.parseColor("#E0E0E0"), Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
                paint.shader = null
            }
        }
        if (chkShadow.isChecked && shadowMode != 0) {
            val mask = maskAlpha
            if (mask != null) {
                val alphaScale = when (shadowMode) { 2 -> 140; 3 -> 110; else -> 90 }
                val shadow = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val spixels = IntArray(w * h)
                for (i in spixels.indices) spixels[i] = ((mask[i] * alphaScale / 255) shl 24)
                shadow.setPixels(spixels, 0, w, 0, 0, w, h)
                val factor = when (shadowMode) { 2 -> 8; 3 -> 16; else -> 32 }
                val offX = when (shadowMode) { 2 -> 8f; 3 -> 16f; else -> 12f }
                val offY = when (shadowMode) { 2 -> 12f; 3 -> 28f; else -> 18f }
                canvas.drawBitmap(blurBitmap(shadow, factor), offX, offY, null)
            }
        }
        val cm = ColorMatrix()
        cm.setSaturation(saturation / 100f)
        val c = contrast / 100f
        val brightnessOffset = (bright - 100) * 1.28f
        val offset = (1f - c) * 128f + brightnessOffset
        val cm2 = ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, offset,
            0f, c, 0f, 0f, offset,
            0f, 0f, c, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        ))
        cm.postConcat(cm2)
        if (hueShift != 0) cm.postConcat(hueMatrix(hueShift.toFloat()))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        if (bgMode == 7) {
            val sw = (w * 0.9f).toInt(); val sh = (h * 0.9f).toInt()
            val scaledMasked = Bitmap.createScaledBitmap(masked, sw, sh, true)
            canvas.drawBitmap(scaledMasked, (w - sw) / 2f, (h - sh) / 2f, paint)
        } else {
            canvas.drawBitmap(masked, 0f, 0f, paint)
        }
        paint.colorFilter = null
        if (overlayText.isNotEmpty()) {
            val tp = Paint()
            tp.isAntiAlias = true
            tp.color = textColor
            tp.textSize = max(12f, textSizeProgress * (w / 1024f))
            tp.textAlign = Paint.Align.CENTER
            canvas.drawText(overlayText, textX * w, textY * h, tp)
        }
        val logo = logoBitmap
        if (logo != null) {
            val lw = (w * 0.25f).toInt()
            val lh = (lw * logo.height / max(1, logo.width))
            val left = (logoX * w - lw / 2).toInt()
            val top = (logoY * h - lh / 2).toInt()
            canvas.drawBitmap(Bitmap.createScaledBitmap(logo, lw, lh, true), left.toFloat(), top.toFloat(), null)
        }
        return result
    }

    private fun refreshPreview() {
        val bmp = workBitmap ?: return
        if (showOriginal) {
            preview.setImageBitmap(bmp)
        } else {
            preview.setImageBitmap(composite(false))
        }
    }

    // ---- Export ----

    private fun exportImage(isPng: Boolean) {
        val bmp = workBitmap ?: run {
            Toast.makeText(this, "Pick a photo first", Toast.LENGTH_SHORT).show(); return
        }
        val size = getExportSize()
        if (size == null) {
            val msg = "Export blocked: custom size is invalid (both sides must be 1 to 8000 px)"
            statusText.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        if (size.first > 8000 || size.second > 8000) {
            val msg = "Export blocked: max 8000 px per side"
            statusText.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val comp = composite(true) ?: bmp
            val scaled = Bitmap.createScaledBitmap(comp, size.first, size.second, true)
            val finalBmp: Bitmap = if (!isPng && bgMode == 0) {
                val white = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(white)
                canvas.drawColor(Color.WHITE)
                canvas.drawBitmap(scaled, 0f, 0f, null)
                white
            } else scaled
            val ext = if (isPng) "png" else "jpg"
            val name = "PhotoCut_" + System.currentTimeMillis() + "." + ext
            val values = ContentValues()
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name)
            values.put(MediaStore.Images.Media.MIME_TYPE, if (isPng) "image/png" else "image/jpeg")
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhotoCut")
            val uri: Uri? = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                statusText.text = "Export failed: could not create file"
                return
            }
            val out = contentResolver.openOutputStream(uri)
            if (out != null) {
                if (isPng) finalBmp.compress(Bitmap.CompressFormat.PNG, 100, out) else finalBmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                out.close()
            }
            recordRecent(name, size)
            val msg = "Exported " + size.first + "x" + size.second + " (" + mpText(size.first, size.second) + ") to Pictures/PhotoCut as " + name
            statusText.text = msg
            Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            statusText.text = "Export failed: " + e.message
            Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
        }
    }
}
