package com.mashallah.billing

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

data class BillItem(val name: String, val qty: Int, val rate: Int) {
    val amount: Int get() = qty * rate
}

/** One closed (printed) bill, kept for history and daily reporting. */
data class BillRecord(val billNo: Int, val time: Long, val items: List<BillItem>) {
    val total: Int get() = items.sumOf { it.amount }
}

class MainActivity : Activity() {

    private val items = mutableListOf<BillItem>()
    private val history = mutableListOf<BillRecord>()
    private lateinit var list: LinearLayout
    private lateinit var totalView: TextView
    private lateinit var todayView: TextView
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var printer: IPrintBlePrinter
    private val prefs by lazy { getSharedPreferences("billing", MODE_PRIVATE) }
    private val historyFile by lazy { File(filesDir, "bills.jsonl") }
    private val main = Handler(Looper.getMainLooper())
    private val logLines = ArrayDeque<String>()
    private var billNo = 1

    private val total get() = items.sumOf { it.amount }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadBill()
        loadHistory()
        setContentView(buildUi())
        printer = IPrintBlePrinter(this) { msg -> log(msg) }
        refresh()
    }

    /** Har printer message status line aur debug log dono mein jata hai. */
    private fun log(msg: String) {
        main.post {
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logLines.addLast("$stamp  $msg")
            while (logLines.size > 150) logLines.removeFirst()
            if (::status.isInitialized) {
                status.text = msg
                logView.text = logLines.toList().takeLast(5).joinToString("\n")
            }
        }
    }

    override fun onDestroy() {
        if (::printer.isInitialized) printer.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val root = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        root.addView(page)

        page.addView(TextView(this).apply {
            text = "MashAllah BIRYANI & Bargar"
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply {
            text = "Printer: connect nahi hai"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(10))
        }
        page.addView(status)

        logView = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, dp(8))
        }
        page.addView(logView)

        val name = EditText(this).apply {
            hint = "Item name (e.g. Zinger Burger)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
        }
        val qty = EditText(this).apply {
            hint = "Quantity"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val rate = EditText(this).apply {
            hint = "Rate (Rs)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        page.addView(name, lp())
        val qtyRateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        qtyRateRow.addView(qty, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        qtyRateRow.addView(rate, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        page.addView(qtyRateRow, lp())

        page.addView(button("Add item") {
            val itemName = name.text.toString().replace("\n", " ").trim()
            val q = qty.text.toString().toIntOrNull() ?: 0
            val r = rate.text.toString().toIntOrNull() ?: -1
            when {
                itemName.isBlank() -> toast("Item ka naam likhein")
                q <= 0 -> toast("Quantity 1 ya us se zyada honi chahiye")
                r < 0 -> toast("Rate durust likhein")
                else -> {
                    items.add(BillItem(itemName, q, r))
                    persist()
                    refresh()
                    name.text.clear(); qty.text.clear(); rate.text.clear()
                    name.requestFocus()
                }
            }
        }, lp())

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(list, lp())
        totalView = TextView(this).apply {
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(2))
        }
        page.addView(totalView)
        todayView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(4))
        }
        page.addView(todayView)

        page.addView(button("SC03H Printer Connect") { connectPrinter() }, lp())
        page.addView(button("Debug log") { showDebugLog() }, lp())

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actionRow.addView(button("Test Print") { printBill(test = true) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionRow.addView(button("Print Bill") { printBill(test = false) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        page.addView(actionRow, lp())

        page.addView(button("History & Backup") { showHistory() }, lp())
        page.addView(button("Clear Bill") { confirmClear() }, lp())
        return root
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun lp() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(6) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------- bill state

    private fun refresh() {
        list.removeAllViews()
        items.forEachIndexed { index, item ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = "${item.name}\n${item.qty} x Rs ${fmt(item.rate)} = Rs ${fmt(item.amount)}"
                textSize = 16f
                setPadding(0, dp(8), dp(8), dp(8))
            }
            row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "x"
                setOnClickListener {
                    items.removeAt(index)
                    persist()
                    refresh()
                }
            }, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
            list.addView(row)
        }
        totalView.text = if (items.isEmpty()) "TOTAL: Rs 0"
        else "TOTAL (${items.size} items): Rs ${fmt(total)}"
        val (todaySum, todayCount) = todayTotals()
        todayView.text = if (todayCount == 0) "Aaj ki sale: Rs 0"
        else "Aaj ki sale: Rs ${fmt(todaySum)}  (${todayCount} bills)"
    }

    private fun confirmClear() {
        if (items.isEmpty()) {
            toast("Bill khali hai")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Bill clear karein?")
            .setMessage("Sab items hata diye jayenge aur naya bill number shuru hoga.")
            .setPositiveButton("Clear") { _, _ ->
                items.clear()
                billNo += 1
                persist()
                refresh()
                toast("Naya bill #$billNo")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun persist() {
        val data = items.joinToString("\n") { "${it.name}\u0001${it.qty}\u0001${it.rate}" }
        prefs.edit().putString(KEY_ITEMS, data).putInt(KEY_BILL_NO, billNo).apply()
    }

    private fun loadBill() {
        billNo = prefs.getInt(KEY_BILL_NO, 1)
        items.clear()
        val raw = prefs.getString(KEY_ITEMS, null) ?: return
        raw.split("\n").forEach { line ->
            val parts = line.split("\u0001")
            if (parts.size == 3) {
                val q = parts[1].toIntOrNull()
                val r = parts[2].toIntOrNull()
                if (parts[0].isNotBlank() && q != null && q > 0 && r != null && r >= 0) {
                    items.add(BillItem(parts[0], q, r))
                }
            }
        }
    }

    // ------------------------------------------------------------- history

    private fun saveBill(record: BillRecord) {
        history.removeAll { it.billNo == record.billNo }
        history.add(record)
        persistHistory()
    }

    private fun persistHistory() {
        try {
            val text = history.joinToString("\n") { record ->
                JSONObject().apply {
                    put("billNo", record.billNo)
                    put("time", record.time)
                    val arr = JSONArray()
                    record.items.forEach { item ->
                        arr.put(JSONArray().apply {
                            put(item.name)
                            put(item.qty)
                            put(item.rate)
                        })
                    }
                    put("items", arr)
                }.toString()
            }
            historyFile.writeText(text)
        } catch (e: Exception) {
            toast("History save nahi hui: ${e.message}")
        }
    }

    private fun loadHistory() {
        history.clear()
        if (!historyFile.exists()) return
        try {
            historyFile.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val obj = JSONObject(line)
                val arr = obj.getJSONArray("items")
                val list = mutableListOf<BillItem>()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONArray(i)
                    list.add(BillItem(item.getString(0), item.getInt(1), item.getInt(2)))
                }
                history.add(BillRecord(obj.getInt("billNo"), obj.getLong("time"), list))
            }
            history.sortBy { it.billNo }
        } catch (e: Exception) {
            history.clear()
        }
    }

    private fun todayTotals(): Pair<Int, Int> {
        val now = Calendar.getInstance()
        val today = history.filter { sameDay(it.time, now) }
        return today.sumOf { it.total } to today.size
    }

    private fun sameDay(time: Long, reference: Calendar): Boolean {
        val c = Calendar.getInstance().apply { timeInMillis = time }
        return c.get(Calendar.YEAR) == reference.get(Calendar.YEAR) &&
            c.get(Calendar.DAY_OF_YEAR) == reference.get(Calendar.DAY_OF_YEAR)
    }

    private fun stamp(time: Long): String =
        SimpleDateFormat("dd-MM-yyyy hh:mm a", Locale.US).format(Date(time))

    private fun showHistory() {
        if (history.isEmpty()) {
            toast("Abhi koi bill save nahi hua")
            return
        }
        val (todaySum, todayCount) = todayTotals()
        val body = StringBuilder()
        history.sortedByDescending { it.time }.take(40).forEach { record ->
            body.append("Bill #${record.billNo}   ${stamp(record.time)}\n")
            record.items.forEach { item ->
                body.append("  ${item.qty} x ${item.name} @ ${fmt(item.rate)} = Rs ${fmt(item.amount)}\n")
            }
            body.append("  TOTAL: Rs ${fmt(record.total)}\n\n")
        }
        body.append("------------------------------\n")
        body.append("Aaj ki sale: Rs ${fmt(todaySum)}  ($todayCount bills)\n")
        body.append("Kul bills save: ${history.size}\n")

        val content = TextView(this).apply {
            text = body.toString()
            textSize = 13f
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this)
            .setTitle("Bill History  (${history.size})")
            .setView(scroll)
            .setPositiveButton("Backup / Share") { _, _ -> shareBackup() }
            .setNegativeButton("Band", null)
            .show()
    }

    private fun shareBackup() {
        if (history.isEmpty()) {
            toast("Backup ke liye history khali hai")
            return
        }
        val (todaySum, todayCount) = todayTotals()
        val csv = StringBuilder("Bill No,Date,Item,Qty,Rate,Amount\n")
        history.sortedBy { it.time }.forEach { record ->
            record.items.forEach { item ->
                val safeName = item.name.replace("\"", "'")
                csv.append("${record.billNo},${stamp(record.time)},\"$safeName\",${item.qty},${item.rate},${item.amount}\n")
            }
        }
        csv.append("\nAaj ki sale,Rs ${fmt(todaySum)},$todayCount bills\n")
        csv.append("Kul sale,Rs ${fmt(history.sumOf { it.total })},${history.size} bills\n")

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "MashAllah Billing Backup ${stamp(System.currentTimeMillis())}")
            putExtra(Intent.EXTRA_TEXT, csv.toString())
        }
        startActivity(Intent.createChooser(intent, "Backup kahan bhejein?"))
    }

    // ------------------------------------------------------------ printing

    private fun printBill(test: Boolean) {
        if (!printer.isReady) {
            log("Print nahi hua: printer ready nahi hai — pehle SC03H Printer Connect dabayein")
            toast("Pehle SC03H printer connect karein")
            return
        }
        if (!test && items.isEmpty()) {
            toast("Bill mein pehle item add karein")
            return
        }
        val snapshot = items.map { it.copy() }
        val number = billNo
        printer.printBitmap(renderReceipt(test)) { sent ->
            if (sent && !test && snapshot.isNotEmpty()) {
                saveBill(BillRecord(number, System.currentTimeMillis(), snapshot))
                toast("Bill #$number history mein save ho gaya")
                refresh()
            }
        }
    }

    private fun renderReceipt(test: Boolean): Bitmap {
        val rows = if (test) testRows() else billRows()
        return drawRows(rows)
    }

    private fun billRows(): List<Row> {
        val stamp = SimpleDateFormat("dd-MM-yyyy  hh:mm a", Locale.US).format(Date())
        val rows = mutableListOf<Row>()
        rows.add(Row("MashAllah", size = 32f, bold = true, center = true))
        rows.add(Row("BIRYANI & Bargar", size = 26f, bold = true, center = true))
        rows.add(Row("Bill No: $billNo", size = 20f, gapBefore = 8f))
        rows.add(Row(stamp, size = 20f))
        rows.add(Row("", divider = true))
        items.forEach { item ->
            rows.add(Row(item.name, size = 22f, gapBefore = 6f))
            rows.add(Row("${item.qty} x ${fmt(item.rate)}", right = "Rs ${fmt(item.amount)}", size = 22f))
        }
        rows.add(Row("", divider = true, gapBefore = 4f))
        rows.add(Row("TOTAL", right = "Rs ${fmt(total)}", size = 28f, bold = true, gapBefore = 4f))
        rows.add(Row("${items.size} item(s)", size = 18f))
        rows.add(Row("Shukriya! Dobara tashreef laayen", size = 20f, center = true, gapBefore = 12f))
        return rows
    }

    private fun testRows(): List<Row> {
        val stamp = SimpleDateFormat("dd-MM-yyyy  hh:mm a", Locale.US).format(Date())
        return listOf(
            Row("MashAllah BIRYANI", size = 28f, bold = true, center = true),
            Row("PRINTER TEST", size = 24f, bold = true, center = true, gapBefore = 8f),
            Row(stamp, size = 20f, center = true),
            Row("", divider = true, gapBefore = 8f),
            Row("Agar yeh line saaf padhi ja rahi hai to printer theek hai.", size = 20f, gapBefore = 8f),
            Row("1234567890", size = 22f, center = true, gapBefore = 8f)
        )
    }

    private data class Row(
        val text: String,
        val right: String? = null,
        val size: Float = 22f,
        val bold: Boolean = false,
        val center: Boolean = false,
        val gapBefore: Float = 0f,
        val divider: Boolean = false
    )

    /** Text-only receipt rendered at the printer's fixed 384-dot width. */
    private fun drawRows(rows: List<Row>): Bitmap {
        val width = 384
        val margin = 10f
        val usable = width - margin * 2
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        class Line(val text: String, val right: String?, val paint: Paint, val center: Boolean, val baseline: Float, val divider: Boolean)

        val lines = mutableListOf<Line>()
        var y = margin + 6f
        rows.forEach { row ->
            y += row.gapBefore
            if (row.divider) {
                y += 12f
                lines.add(Line("", null, Paint().apply {
                    color = Color.BLACK
                    style = Paint.Style.STROKE
                    strokeWidth = 1.4f
                }, false, y, true))
                y += 12f
                return@forEach
            }
            paint.textSize = row.size
            paint.typeface = Typeface.create("sans-serif", if (row.bold) Typeface.BOLD else Typeface.NORMAL)
            val rightWidth = row.right?.let { paint.measureText(it) } ?: 0f
            val leftWidth = if (row.right != null) (usable - rightWidth - 14f).coerceAtLeast(70f) else usable
            val wrapped = wrap(row.text, paint, leftWidth)
            val lineHeight = row.size * 1.35f
            wrapped.forEachIndexed { i, text ->
                y += lineHeight
                lines.add(Line(text, if (i == 0) row.right else null, Paint(paint), row.center, y, false))
            }
        }

        val height = (y + margin + 6f).toInt().coerceAtLeast(90)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        lines.forEach { line ->
            if (line.divider) {
                canvas.drawLine(margin, line.baseline, width - margin, line.baseline, line.paint)
            } else {
                val x = if (line.center) ((width - line.paint.measureText(line.text)) / 2f).coerceAtLeast(margin) else margin
                canvas.drawText(line.text, x, line.baseline, line.paint)
                line.right?.let { r ->
                    canvas.drawText(r, width - margin - line.paint.measureText(r), line.baseline, line.paint)
                }
            }
        }
        return bitmap
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val cleaned = text.replace("\n", " ").trim()
        if (cleaned.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        var current = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) {
                out.add(current.toString())
                current = StringBuilder()
            }
        }
        cleaned.split(' ').forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            when {
                paint.measureText(candidate) <= maxWidth -> current = StringBuilder(candidate)
                else -> {
                    flush()
                    if (paint.measureText(word) <= maxWidth) {
                        current = StringBuilder(word)
                    } else {
                        var piece = StringBuilder()
                        word.forEach { ch ->
                            if (paint.measureText(piece.toString() + ch) <= maxWidth) piece.append(ch)
                            else {
                                out.add(piece.toString())
                                piece = StringBuilder().append(ch)
                            }
                        }
                        current = piece
                    }
                }
            }
        }
        flush()
        return if (out.isEmpty()) listOf("") else out
    }

    // --------------------------------------------------------- permissions

    private val blePermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun missingPermissions() = blePermissions.filter {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    private fun connectPrinter() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            log("Bluetooth permission maangi ja rahi hai")
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            return
        }
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (adapter == null) {
            toast("Is phone mein Bluetooth nahi hai")
            return
        }
        if (!adapter.isEnabled) {
            toast("Bluetooth off hai — on karein")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
            return
        }
        printer.scanAndConnect()
    }

    /** Poora printer log — WhatsApp par khud ko bhej kar share kiya ja sakta hai. */
    private fun showDebugLog() {
        val body = TextView(this).apply {
            text = if (logLines.isEmpty()) "(abhi koi log nahi)" else logLines.joinToString("\n")
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle("Debug log")
            .setView(scroll)
            .setPositiveButton("Share") { _, _ ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "MashAllah Billing — printer log")
                    putExtra(Intent.EXTRA_TEXT, logLines.joinToString("\n"))
                }
                startActivity(Intent.createChooser(intent, "Log kahan bhejein?"))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        if (missingPermissions().isEmpty()) connectPrinter()
        else toast("Bluetooth permission ke baghair printer connect nahi ho sakta")
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE_BT) {
            if (resultCode == RESULT_OK) connectPrinter()
            else toast("Bluetooth on karna zaroori hai")
        }
    }

    private companion object {
        const val REQ_PERMISSIONS = 44
        const val REQ_ENABLE_BT = 55
        const val KEY_ITEMS = "items"
        const val KEY_BILL_NO = "bill_no"
        fun fmt(v: Int): String = String.format(Locale.US, "%,d", v)
    }
}

/**
 * SC03H / iPrint BLE printer driver.
 * Protocol: 51 78 <op> 00 <len16le> <payload> <crc8(payload)> FF
 */
class IPrintBlePrinter(private val context: Context, private val report: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val seenNames = LinkedHashSet<String>()

    @Volatile private var ready = false
    @Volatile private var printing = false
    @Volatile private var inFlight = false
    private var ackWarned = false
    /** true -> write-with-response (flow control ack se), false -> no-response (timer se). */
    private var writeNeedsResponse = true

    private var mtu = 23
    private var chunkSize = 20
    private var job: ByteArray? = null
    /** Un byte offsets par printer ko saans lene ka waqt dena hai (packet boundaries). */
    private var burstEnds: IntArray = IntArray(0)
    private var offset = 0
    private var lastPct = -1
    private var lastBattery = -1
    private var statusFrames = 0
    /** Agli chunk se pehle burst pause dena hai? */
    private var pendingPause = false
    private var onJobComplete: ((Boolean) -> Unit)? = null
    private var discoveryFinished = false

    /** GATT allows only one outstanding operation, so CCCD writes are queued. */
    private val pendingDescriptors =
        ArrayDeque<Pair<BluetoothGattCharacteristic, BluetoothGattDescriptor>>()

    private val nextChunk = Runnable { sendNext() }

    /** write-with-response ke ack ka intezaar; 3s tak kuch na aaye to job fail kar dein. */
    private val ackWatchdog = Runnable { onAckTimeout() }

    private val readyFallback = Runnable { gatt?.let { finishDiscovery(it) } }

    private val connectTimeout = Runnable {
        if (writeChar == null) {
            report("Printer ne services nahi diye — dobara connect karein")
            close()
        }
    }

    private val scanTimeout = Runnable {
        stopScan()
        if (writeChar == null) {
            val names = if (seenNames.isEmpty()) "koi device nahi mila"
            else seenNames.joinToString(", ").take(120)
            report("SC03H nahi mila. Qareeb ke devices: $names")
        }
    }

    val isReady: Boolean get() = ready

    // ------------------------------------------------------------- connect

    @SuppressLint("MissingPermission")
    fun scanAndConnect() {
        if (printing) {
            report("Print chal raha hai — thori dair baad try karein")
            return
        }
        if (gatt != null) {
            report(if (ready) "Printer pehle se connected hai" else "Connection chal raha hai…")
            return
        }
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            report("Is phone mein Bluetooth nahi hai")
            return
        }
        if (!adapter.isEnabled) {
            report("Bluetooth off hai — pehle on karein")
            return
        }
        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            report("BLE scanner shuru nahi ho saka — Bluetooth dobara on/off karein")
            return
        }
        stopScan()
        seenNames.clear()
        report("SC03H scan ho raha hai… (15 second)")

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val advertised = record?.serviceUuids?.any { it.uuid == SERVICE_ID } == true
                val name = try {
                    result.device.name ?: record?.deviceName ?: ""
                } catch (e: SecurityException) {
                    ""
                }
                if (name.isNotBlank()) seenNames.add(name)
                if (!advertised && !isPrinterName(name)) return
                stopScan()
                report("${name.ifBlank { "Printer" }} mil gaya — connect ho raha hai…")
                connect(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan()
                report("Bluetooth scan error: $errorCode")
            }
        }
        scanCallback = callback
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()
            scanner?.startScan(null, settings, callback)
            main.removeCallbacks(scanTimeout)
            main.postDelayed(scanTimeout, 15_000)
        } catch (e: SecurityException) {
            report("Bluetooth permission nahi mili")
            stopScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        val callback = scanCallback ?: return
        try {
            scanner?.stopScan(callback)
        } catch (e: SecurityException) {
            // permission revoked mid-scan — nothing to do
        }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        report("Printer se connect ho raha hai…")
        val connection = try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            report("Bluetooth permission nahi mili")
            return
        }
        if (connection == null) {
            report("Connection shuru nahi hui — printer ko dobara try karein")
            return
        }
        gatt = connection
        // A fresh link starts at the default MTU until negotiation completes.
        mtu = 23
        chunkSize = 20
        lastBattery = -1
        ackWarned = false
        inFlight = false
        statusFrames = 0
        discoveryFinished = false
        pendingDescriptors.clear()
        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, 12_000)
    }

    @SuppressLint("MissingPermission")
    fun close() {
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(scanTimeout)
        main.removeCallbacks(ackWatchdog)
        main.removeCallbacks(nextChunk)
        main.removeCallbacksAndMessages(null)
        stopScan()
        val wasPrinting = printing
        ready = false
        printing = false
        inFlight = false
        writeNeedsResponse = true
        discoveryFinished = false
        pendingDescriptors.clear()
        job = null
        burstEnds = IntArray(0)
        offset = 0
        try {
            gatt?.disconnect()
        } catch (e: SecurityException) {
            // already revoked
        }
        gatt?.close()
        gatt = null
        writeChar = null
        if (wasPrinting) notifyComplete(false)
    }

    // ------------------------------------------------------------ callbacks

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            // Callback kabhi connectGatt() ke return se pehle bhi aa sakta hai.
            if (gatt == null) gatt = g
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                report("Bluetooth connect hua — services dhoond raha hai…")
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false
                printing = false
                inFlight = false
                report("Printer disconnect ho gaya")
                close()
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                report("Connect fail (status $status) — printer paas la kar try karein")
                close()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (gatt == null) gatt = g
            main.removeCallbacks(connectTimeout)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                report("Services discovery fail (status $status)")
                close()
                return
            }
            val service = g.getService(SERVICE_ID)
            // Prefer the documented ae01 write characteristic, else any writable one.
            val candidate = service?.getCharacteristic(WRITE_ID) ?: allWritable(g).firstOrNull()
            if (candidate == null) {
                report("Printer ka write channel nahi mila — firmware check karein")
                close()
                return
            }
            writeChar = candidate
            writeNeedsResponse =
                (candidate.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
            val mode = if (writeNeedsResponse) "write" else "write no-response"
            report("Printer channel: ${shortUuid(candidate.uuid)} ($mode)")
            subscribeNotifications(g)
            main.removeCallbacks(readyFallback)
            main.postDelayed(readyFallback, 3_000)
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23
            chunkSize = (mtu - 3).coerceIn(20, MAX_CHUNK)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (!printing) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                abortJob("Print fail (status $status) — printer ko off/on kar ke dobara connect karein")
                return
            }
            if (!writeNeedsResponse) return // no-response mode timer se chalta hai
            main.removeCallbacks(ackWatchdog)
            if (!inFlight) return // watchdog pehle hi agla chunk bhej chuka hai
            inFlight = false
            scheduleNext(if (pendingPause) BURST_PAUSE else delayFor(job?.size ?: 0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleStatus(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleStatus(characteristic.value ?: ByteArray(0))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) report("Notify subscribe fail (status $status)")
            writeNextDescriptor(g)
        }
    }

    @SuppressLint("MissingPermission")
    private fun allWritable(g: BluetoothGatt): List<BluetoothGattCharacteristic> =
        g.services.flatMap { it.characteristics }.filter {
            val p = it.properties
            (p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        }

    @SuppressLint("MissingPermission")
    private fun subscribeNotifications(g: BluetoothGatt) {
        pendingDescriptors.clear()
        g.services.flatMap { it.characteristics }
            .filter {
                val p = it.properties
                (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                    (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            }
            .sortedBy { if (it.uuid == NOTIFY_ID) 0 else 1 }
            .forEach { ch ->
                g.setCharacteristicNotification(ch, true)
                val descriptor = ch.getDescriptor(CCCD) ?: return@forEach
                pendingDescriptors.addLast(ch to descriptor)
            }
        writeNextDescriptor(g)
    }

    @SuppressLint("MissingPermission")
    private fun writeNextDescriptor(g: BluetoothGatt) {
        if (gatt !== g) return // stale callback from an old connection
        val next = pendingDescriptors.removeFirstOrNull()
        if (next == null) {
            finishDiscovery(g)
            return
        }
        val (characteristic, descriptor) = next
        val enable = if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, enable) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = enable
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
        if (!started) writeNextDescriptor(g) // skip a descriptor the stack refused
    }

    @SuppressLint("MissingPermission")
    private fun finishDiscovery(g: BluetoothGatt) {
        if (gatt == null) gatt = g
        if (discoveryFinished || gatt !== g) return
        discoveryFinished = true
        main.removeCallbacks(readyFallback)
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (!g.requestMtu(517)) {
            mtu = 23
            chunkSize = 20
        }
        ready = true
        report("SC03H ready hai — Test Print try karein")
    }

    private fun handleStatus(raw: ByteArray) {
        if (raw.size < 8 || raw[0] != 0x51.toByte() || raw[1] != 0x78.toByte()) return
        val length = (raw[4].toInt() and 0xff) or ((raw[5].toInt() and 0xff) shl 8)
        if (raw.size < 6 + length || length == 0) return
        if (statusFrames < 3) {
            statusFrames++
            report("Printer ka jawab: ${hex(raw)}")
        }
        val data = raw.copyOfRange(6, 6 + length)
        val flags = data[0].toInt() and 0xff
        val battery = if (data.size > 1) data[1].toInt() and 0xff else -1
        if (battery in 1..100 && battery != lastBattery) {
            lastBattery = battery
            report("Printer battery: $battery%")
        }
        if ((flags and 0x04) != 0) report("Paper khatam hai — naya roll lagayein")
    }

    // ------------------------------------------------------------- printing

    fun printBitmap(bitmap: Bitmap, onComplete: (Boolean) -> Unit = {}) {
        if (!ready) {
            report("Pehle printer connect karein")
            onComplete(false)
            return
        }
        if (printing) {
            report("Print pehle se chal raha hai — intezaar karein")
            onComplete(false)
            return
        }
        printing = true
        inFlight = false
        ackWarned = false
        lastPct = -1
        onJobComplete = onComplete
        report("Bill taiyar ho raha hai…")
        Thread {
            val built = try {
                buildJob(bitmap)
            } catch (e: Exception) {
                printing = false
                report("Bill image banane mein masla: ${e.message}")
                notifyComplete(false)
                return@Thread
            }
            main.post {
                job = built.first
                burstEnds = built.second
                offset = 0
                report("Printer ko bheja ja raha hai (${built.first.size / 1024 + 1} KB)…")
                sendNext()
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun sendNext() {
        if (!printing) return
        if (inFlight) return // ack/watchdog khud agli chunk bhejega
        val data = job ?: return
        val g = gatt
        val ch = writeChar
        if (g == null || ch == null) {
            abortJob("Printer connection chala gaya — dobara connect karein")
            return
        }
        if (offset >= data.size) {
            finishJob()
            return
        }
        val end = minOf(data.size, offset + chunkSize)
        val slice = data.copyOfRange(offset, end)
        val previous = offset
        offset = end

        val started = try {
            writeChunk(g, ch, slice)
        } catch (e: SecurityException) {
            abortJob("Bluetooth permission khatam ho gayi")
            return
        }
        if (!started) {
            abortJob("Printer write shuru nahi hui — connection dobara banayein")
            return
        }
        val pct = offset * 100 / data.size
        if (pct >= lastPct + 25) {
            lastPct = pct
            if (pct < 100) report("Print ho raha hai… $pct%")
        }

        val pause = burstEnds.any { it > previous && it <= offset }
        pendingPause = pause
        if (writeNeedsResponse) {
            inFlight = true
            main.removeCallbacks(ackWatchdog)
            main.postDelayed(ackWatchdog, ACK_TIMEOUT)
        } else {
            scheduleNext(if (pause) BURST_PAUSE else delayFor(data.size))
        }
    }

    private fun scheduleNext(delay: Long) {
        main.removeCallbacks(nextChunk)
        main.postDelayed(nextChunk, delay)
    }

    private fun onAckTimeout() {
        if (!printing || !inFlight) return
        if (ackWarned) {
            abortJob("Printer write ka jawab nahi de raha — printer off/on kar ke dobara connect karein")
            return
        }
        ackWarned = true
        report("Printer write ka jawab nahi de raha — intezaar…")
        main.postDelayed(ackWatchdog, 3_000)
    }

    private fun delayFor(size: Int): Long = if (size > 20_000) 25L else 10L

    private fun finishJob() {
        printing = false
        inFlight = false
        job = null
        offset = 0
        main.removeCallbacks(ackWatchdog)
        report("Print bhej diya — printer 3 second mein parchi nikaal dega")
        notifyComplete(true)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun writeChunk(g: BluetoothGatt, ch: BluetoothGattCharacteristic, slice: ByteArray): Boolean {
        val type = if (writeNeedsResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, slice, type) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.writeType = type
            ch.value = slice
            g.writeCharacteristic(ch)
        }
    }

    private fun abortJob(message: String) {
        printing = false
        inFlight = false
        job = null
        burstEnds = IntArray(0)
        offset = 0
        main.removeCallbacks(ackWatchdog)
        main.removeCallbacks(nextChunk)
        report(message)
        notifyComplete(false)
    }

    private fun notifyComplete(success: Boolean) {
        val callback = onJobComplete ?: return
        onJobComplete = null
        main.post { callback(success) }
    }

    /**
     * Rasterises the bitmap and wraps it in the SC03H packet stream.
     * Byte stream ke saath un offsets ko bhi return karta hai jahan burst khatam hota hai
     * (packet boundary par, taake printer ko adhoora packet na mile).
     */
    private fun buildJob(source: Bitmap): Pair<ByteArray, IntArray> {
        val bitmap = if (source.width == 384) source else Bitmap.createScaledBitmap(
            source, 384, (source.height * 384f / source.width).roundToInt().coerceAtLeast(1), true
        )
        val out = ByteArrayOutputStream()
        val bursts = ArrayList<Int>()
        var sinceBurst = 0

        fun emit(bytes: ByteArray) {
            out.write(bytes)
            sinceBurst += bytes.size
            if (sinceBurst >= BURST_BYTES) {
                bursts.add(out.size())
                sinceBurst = 0
            }
        }

        emit(packet(0xA3, byteArrayOf(0x00))) // wake
        emit(packet(0xA4, byteArrayOf(0x33))) // quality
        emit(packet(0xAF, byteArrayOf(0x70, 0x44))) // energy 17520
        emit(packet(0xBE, byteArrayOf(0x00))) // 1-bit image mode
        emit(packet(0xBD, byteArrayOf(0x23))) // print speed

        val w = minOf(384, bitmap.width)
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val row = ByteArray(48)
        for (y in 0 until h) {
            row.fill(0.toByte())
            val base = y * w
            for (x in 0 until w) {
                val c = pixels[base + x]
                val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                if (lum < 140) {
                    row[x ushr 3] = (row[x ushr 3].toInt() or (1 shl (7 - (x and 7)))).toByte()
                }
            }
            emit(packet(0xA2, row))
        }
        repeat(50) { emit(packet(0xBD, byteArrayOf(0x23))) } // tear-bar feed
        return out.toByteArray() to bursts.toIntArray()
    }

    private fun packet(op: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        out[0] = 0x51
        out[1] = 0x78
        out[2] = op.toByte()
        out[3] = 0
        out[4] = payload.size.toByte()
        out[5] = (payload.size shr 8).toByte()
        payload.copyInto(out, 6)
        out[6 + payload.size] = crc8(payload).toByte()
        out[7 + payload.size] = 0xFF.toByte()
        return out
    }

    /** CRC-8/ATM (poly 0x07, init 0x00) over the payload only. */
    private fun crc8(data: ByteArray): Int {
        var crc = 0
        data.forEach { b ->
            crc = crc xor (b.toInt() and 0xff)
            repeat(8) {
                crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xff else (crc shl 1) and 0xff
            }
        }
        return crc
    }

    private fun isPrinterName(name: String): Boolean {
        if (name.isBlank()) return false
        val n = name.lowercase(Locale.US)
        return NAME_HINTS.any { n.contains(it) }
    }

    private fun shortUuid(uuid: UUID): String =
        uuid.toString().substring(4, 8).lowercase(Locale.US)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format(Locale.US, "%02X", it.toInt() and 0xff) }

    private companion object {
        val SERVICE_ID: UUID = UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb")
        val WRITE_ID: UUID = UUID.fromString("0000ae01-0000-1000-8000-00805f9b34fb")
        val NOTIFY_ID: UUID = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val MAX_CHUNK = 180
        const val BURST_BYTES = 4096
        const val BURST_PAUSE = 600L
        const val ACK_TIMEOUT = 500L
        val NAME_HINTS = listOf(
            "sc03", "iprint", "fc02", "gb01", "gb02", "walkprint", "funprint",
            "pocket", "thermal", "print"
        )
    }
}
