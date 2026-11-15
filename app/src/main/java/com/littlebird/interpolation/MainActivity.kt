package com.littlebird.interpolation

import android.app.DatePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TankCalculatorScreen() } }
    }
}

private data class Calc(val atTrimLow: Double, val atTrimHigh: Double, val trimVolume: Double, val heelCorrection: Double, val finalVolume: Double)
private data class TankRecord(val tank: String, val measurement: String, val kind: String, val trim: String, val volume: String)

private fun lerp(x: Double, x1: Double, x2: Double, y1: Double, y2: Double): Double {
    require(x1 != x2) { "Reference values must be different." }
    return y1 + (x - x1) / (x2 - x1) * (y2 - y1)
}
private fun runCalc(level: Double, l1: Double, l2: Double, trim: Double, t1: Double, t2: Double,
                    a: Double, b: Double, c: Double, d: Double, heel: Double, h1: Double, h2: Double,
                    hc1: Double, hc2: Double): Calc {
    val lowTrim = lerp(level, l1, l2, a, b)
    val highTrim = lerp(level, l1, l2, c, d)
    val trimVol = lerp(trim, t1, t2, lowTrim, highTrim)
    val heelCorr = lerp(heel, h1, h2, hc1, hc2)
    return Calc(lowTrim, highTrim, trimVol, heelCorr, trimVol + heelCorr)
}

@Composable
private fun TankCalculatorScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var page by remember { mutableStateOf("Calculator") }
    var tankName by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("Sounding") }
    var level by remember { mutableStateOf("510") }
    var l1 by remember { mutableStateOf("500") }
    var l2 by remember { mutableStateOf("550") }
    var trim by remember { mutableStateOf("0.15") }
    var t1 by remember { mutableStateOf("0.1") }
    var t2 by remember { mutableStateOf("0.2") }
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    var d by remember { mutableStateOf("") }
    var heel by remember { mutableStateOf("0.1") }
    var h1 by remember { mutableStateOf("0.0") }
    var h2 by remember { mutableStateOf("0.5") }
    var hc1 by remember { mutableStateOf("0.0") }
    var hc2 by remember { mutableStateOf("-5.5") }
    var heelExpanded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Calc?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selectedDate by remember { mutableStateOf(todayKey()) }
    var records by remember { mutableStateOf(loadRecords(context, selectedDate)) }

    fun chooseDate() {
        val parsed = parseDate(selectedDate)
        DatePickerDialog(context, { _, year, month, day ->
            val cal = Calendar.getInstance().apply { set(year, month, day) }
            selectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
            records = loadRecords(context, selectedDate)
        }, parsed.get(Calendar.YEAR), parsed.get(Calendar.MONTH), parsed.get(Calendar.DAY_OF_MONTH)).show()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tank Volume Calculator", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = page == "Calculator", onClick = { page = "Calculator" }, label = { Text("Calculator") })
            FilterChip(selected = page == "Records", onClick = { page = "Records"; records = loadRecords(context, selectedDate) }, label = { Text("View records") })
        }

        if (page == "Calculator") {
            Panel("Tank details") {
                Field("Tank name / number", tankName, { tankName = it; result = null }, Modifier.fillMaxWidth(), allowText = true)
            }
            Panel("1. Actual tank measurement") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == "Sounding", onClick = { kind = "Sounding"; result = null }, label = { Text("Sounding") })
                    FilterChip(selected = kind == "Ullage", onClick = { kind = "Ullage"; result = null }, label = { Text("Ullage") })
                }
                Field("Actual " + kind + " (cm)", level, { level = it; result = null })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Lower reference (cm)", l1, { l1 = it; result = null }, Modifier.weight(1f))
                    Field("Upper reference (cm)", l2, { l2 = it; result = null }, Modifier.weight(1f))
                }
            }
            Panel("2. Trim references") {
                Field("Actual trim (m)", trim, { trim = it; result = null })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field("Lower trim (m)", t1, { t1 = it; result = null }, Modifier.weight(1f))
                    Field("Upper trim (m)", t2, { t2 = it; result = null }, Modifier.weight(1f))
                }
            }
            Panel("3. Tank table volumes (m³)") {
                Text("Enter four corner values from the tank table.", style = MaterialTheme.typography.bodySmall)
                Text("At lower trim: " + t1 + " m", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(l1 + " cm", a, { a = it; result = null }, Modifier.weight(1f))
                    Field(l2 + " cm", b, { b = it; result = null }, Modifier.weight(1f))
                }
                Text("At upper trim: " + t2 + " m", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(l1 + " cm", c, { c = it; result = null }, Modifier.weight(1f))
                    Field(l2 + " cm", d, { d = it; result = null }, Modifier.weight(1f))
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("4. Heel correction", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = { heelExpanded = !heelExpanded }) { Text(if (heelExpanded) "Hide" else "Show") }
                    }
                    if (heelExpanded) {
                        Field("Actual heel (°)", heel, { heel = it; result = null })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field("Lower heel (°)", h1, { h1 = it; result = null }, Modifier.weight(1f))
                            Field("Correction (m³)", hc1, { hc1 = it; result = null }, Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field("Upper heel (°)", h2, { h2 = it; result = null }, Modifier.weight(1f))
                            Field("Correction (m³)", hc2, { hc2 = it; result = null }, Modifier.weight(1f))
                        }
                    } else {
                        Text("Hidden. Heel correction values will still be used in the calculation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Button(onClick = {
                try {
                    require(tankName.trim().isNotEmpty()) { "Enter the tank name or number." }
                    result = runCalc(level.num(), l1.num(), l2.num(), trim.num(), t1.num(), t2.num(),
                        a.num(), b.num(), c.num(), d.num(), heel.num(), h1.num(), h2.num(), hc1.num(), hc2.num())
                    error = null
                    notice = null
                } catch (e: Exception) {
                    result = null
                    error = e.message ?: "Check the entered values."
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Calculate corrected volume") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            result?.let { r ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("FINAL CORRECTED VOLUME", style = MaterialTheme.typography.labelLarge)
                        Text(r.finalVolume.fmt() + " m³", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        HorizontalDivider()
                        OutputLine("Volume at lower trim", r.atTrimLow.fmt() + " m³")
                        OutputLine("Volume at upper trim", r.atTrimHigh.fmt() + " m³")
                        OutputLine("Volume after trim interpolation", r.trimVolume.fmt() + " m³")
                        OutputLine("Heel correction", r.heelCorrection.fmt() + " m³")
                        OutputLine("Final volume", r.finalVolume.fmt() + " m³", true)
                    }
                }
                Button(onClick = {
                    val rec = TankRecord(tankName.trim(), level.trim(), kind, trim.trim(), r.finalVolume.fmt())
                    val day = todayKey()
                    saveRecord(context, day, rec)
                    selectedDate = day
                    records = loadRecords(context, day)
                    notice = "Reading for ${rec.tank} saved under $day."
                }, modifier = Modifier.fillMaxWidth()) { Text("Record this reading") }
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Text("Use values from the vessel's approved tank calibration tables and verify results before operational use.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Panel("Saved tank readings") {
                Text("Select a date to view all readings saved for that day.")
                OutlinedButton(onClick = { chooseDate() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Date: $selectedDate  •  Change date")
                }
                if (records.isEmpty()) {
                    Text("No readings saved for this date.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("${records.size} reading(s)", fontWeight = FontWeight.SemiBold)
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        Row {
                            TableCell("Tank", true, 112.dp)
                            TableCell("Measured", true, 95.dp)
                            TableCell("Type", true, 92.dp)
                            TableCell("Trim (m)", true, 82.dp)
                            TableCell("Corrected m³", true, 112.dp)
                        }
                        records.forEach { rec ->
                            Row {
                                TableCell(rec.tank, false, 112.dp)
                                TableCell(rec.measurement, false, 95.dp)
                                TableCell(rec.kind, false, 92.dp)
                                TableCell(rec.trim, false, 82.dp)
                                TableCell(rec.volume, false, 112.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
private fun parseDate(value: String): Calendar {
    val date = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value) }.getOrNull() ?: Date()
    return Calendar.getInstance().apply { time = date }
}
private fun loadRecords(context: android.content.Context, date: String): List<TankRecord> {
    val raw = context.getSharedPreferences("tank_readings", android.content.Context.MODE_PRIVATE).getString(date, "[]") ?: "[]"
    return runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            TankRecord(o.optString("tank"), o.optString("measurement"), o.optString("kind"), o.optString("trim"), o.optString("volume"))
        }
    }.getOrDefault(emptyList())
}
private fun saveRecord(context: android.content.Context, date: String, record: TankRecord) {
    val prefs = context.getSharedPreferences("tank_readings", android.content.Context.MODE_PRIVATE)
    val array = runCatching { JSONArray(prefs.getString(date, "[]")) }.getOrDefault(JSONArray())
    array.put(JSONObject().put("tank", record.tank).put("measurement", record.measurement)
        .put("kind", record.kind).put("trim", record.trim).put("volume", record.volume))
    prefs.edit().putString(date, array.toString()).apply()
}

@Composable private fun Panel(title: String, body: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            body()
        }
    }
}
@Composable private fun Field(label: String, value: String, change: (String) -> Unit, modifier: Modifier = Modifier, allowText: Boolean = false) {
    OutlinedTextField(value, { next -> change(if (allowText) next else next.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' }) },
        label = { Text(label) }, modifier = modifier, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}
@Composable private fun OutputLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}
@Composable private fun TableCell(value: String, header: Boolean, width: androidx.compose.ui.unit.Dp) {
    Surface(color = if (header) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.width(width)) {
        Text(value, modifier = Modifier.padding(8.dp), style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
            fontWeight = if (header) FontWeight.Bold else FontWeight.Normal)
    }
}
private fun String.num(): Double = trim().toDoubleOrNull() ?: error("Enter a valid number in every field.")
private fun Double.fmt(): String = String.format(Locale.getDefault(), "%.3f", this)
