package com.littlebird.interpolation

import android.app.DatePickerDialog
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TankCalculatorScreen() } }
    }
}

private data class Calc(val atTrimLow: Double, val atTrimHigh: Double, val trimVolume: Double, val heelCorrection: Double, val finalVolume: Double)
private data class TankInfo(val name: String, val totalCapacity: String, val content: String, val maxSounding: String, val density: String)
private data class TankRecord(val tank: String, val measurement: String, val kind: String, val trim: String, val volume: String, val content: String = "")
private data class DatedRecord(val date: String, val record: TankRecord)

private fun lerp(x: Double, x1: Double, x2: Double, y1: Double, y2: Double): Double {
    require(x1 != x2) { "Reference values must be different." }
    return y1 + (x - x1) / (x2 - x1) * (y2 - y1)
}
private fun runCalc(level: Double, l1: Double, l2: Double, trim: Double, t1: Double, t2: Double,
                    a: Double, b: Double, c: Double, d: Double, heel: Double, h1: Double, h2: Double,
                    hc1: Double, hc2: Double): Calc {
    require(l1 != l2) { "Lower and upper measurement references must be different." }
    val atLower = kotlin.math.abs(level - l1) < 0.0000001
    val atUpper = kotlin.math.abs(level - l2) < 0.0000001
    val lowTrim: Double
    val highTrim: Double
    val trimVol: Double
    if (trim == 0.0) {
        trimVol = if (atLower || atUpper) a else lerp(level, l1, l2, a, b)
        lowTrim = trimVol
        highTrim = trimVol
    } else {
        require(t1 != t2) { "Lower and upper trim references must be different." }
        lowTrim = when { atLower -> a; atUpper -> b; else -> lerp(level, l1, l2, a, b) }
        highTrim = when { atLower -> c; atUpper -> d; else -> lerp(level, l1, l2, c, d) }
        trimVol = lerp(trim, t1, t2, lowTrim, highTrim)
    }
    val heelCorr = lerp(heel, h1, h2, hc1, hc2)
    return Calc(lowTrim, highTrim, trimVol, heelCorr, trimVol + heelCorr)
}

@Composable
private fun TankCalculatorScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var page by remember { mutableStateOf("Calculator") }
    var tanks by remember { mutableStateOf(loadTankInfos(context)) }
    var tankName by remember { mutableStateOf("") }
    var tankMenuExpanded by remember { mutableStateOf(false) }
    var tankDialogMode by remember { mutableStateOf<String?>(null) }
    var tankDraft by remember { mutableStateOf("") }
    var tankCapacityDraft by remember { mutableStateOf("") }
    var tankContentDraft by remember { mutableStateOf("") }
    var tankMaxSoundingDraft by remember { mutableStateOf("") }
    var tankDensityDraft by remember { mutableStateOf("") }
    var tankDialogError by remember { mutableStateOf<String?>(null) }

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
    var heel by remember { mutableStateOf("0") }
    var h1 by remember { mutableStateOf("0.0") }
    var h2 by remember { mutableStateOf("0.5") }
    var hc1 by remember { mutableStateOf("0.0") }
    var hc2 by remember { mutableStateOf("-5.5") }
    var heelExpanded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Calc?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    var selectedDate by remember { mutableStateOf(todayKey()) }
    var entryDate by remember { mutableStateOf(todayKey()) }
    var duplicateRecord by remember { mutableStateOf<TankRecord?>(null) }
    var duplicateDate by remember { mutableStateOf<String?>(null) }
    var recordMode by remember { mutableStateOf("Date") }
    var recordTank by remember { mutableStateOf("") }
    var recordContent by remember { mutableStateOf("") }
    var recordContentMenuExpanded by remember { mutableStateOf(false) }
    var recordTankMenuExpanded by remember { mutableStateOf(false) }
    var contentRangeStart by remember { mutableStateOf(dateOffsetKey(-30)) }
    var contentRangeEnd by remember { mutableStateOf(todayKey()) }
    var selectedContentTanks by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allRecords by remember { mutableStateOf(loadAllRecords(context)) }
    var exportDialogOpen by remember { mutableStateOf(false) }
    var exportRows by remember { mutableStateOf<List<DatedRecord>>(emptyList()) }
    var exportLabel by remember { mutableStateOf("Tank readings") }
    var exportIncludeTotal by remember { mutableStateOf(true) }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    val csvExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri: Uri? ->
        if (uri != null) {
            exportMessage = runCatching { writeReadingsXlsx(context, uri, exportRows, tanks, exportLabel, exportIncludeTotal); "Excel workbook saved." }
                .getOrElse { "Could not save CSV: ${it.message ?: "Unknown error"}" }
        }
    }
    val pdfExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        if (uri != null) {
            exportMessage = runCatching { writeReadingsPdf(context, uri, exportRows, tanks, exportLabel, exportIncludeTotal); "PDF report saved." }
                .getOrElse { "Could not save PDF: ${it.message ?: "Unknown error"}" }
        }
    }

    fun refreshTanks() { tanks = loadTankInfos(context) }
    fun refreshRecords() { allRecords = loadAllRecords(context) }
    fun chooseDate() {
        val parsed = parseDate(selectedDate)
        DatePickerDialog(context, { _, year, month, day ->
            val cal = Calendar.getInstance().apply { set(year, month, day) }
            selectedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        }, parsed.get(Calendar.YEAR), parsed.get(Calendar.MONTH), parsed.get(Calendar.DAY_OF_MONTH)).show()
    }
    fun chooseEntryDate() {
        val parsed = parseDate(entryDate)
        DatePickerDialog(context, { _, year, month, day ->
            val cal = Calendar.getInstance().apply { set(year, month, day) }
            entryDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        }, parsed.get(Calendar.YEAR), parsed.get(Calendar.MONTH), parsed.get(Calendar.DAY_OF_MONTH)).show()
    }
    fun chooseContentRangeDate(isStart: Boolean) {
        val current = if (isStart) contentRangeStart else contentRangeEnd
        val parsed = parseDate(current)
        DatePickerDialog(context, { _, year, month, day ->
            val cal = Calendar.getInstance().apply { set(year, month, day) }
            val chosen = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
            if (isStart) contentRangeStart = chosen else contentRangeEnd = chosen
        }, parsed.get(Calendar.YEAR), parsed.get(Calendar.MONTH), parsed.get(Calendar.DAY_OF_MONTH)).show()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tank Volume Calculator", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = page == "Calculator",
                    onClick = { page = "Calculator"; entryDate = todayKey() },
                    label = { Text("Calculator", maxLines = 1) }
                )
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = page == "Missed entry",
                    onClick = { page = "Missed entry" },
                    label = { Text("Missed entry", maxLines = 1) }
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = page == "Records",
                    onClick = { page = "Records"; refreshRecords() },
                    label = { Text("View records", maxLines = 1) }
                )
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = page == "Tanks",
                    onClick = { page = "Tanks"; refreshTanks() },
                    label = { Text("Tanks", maxLines = 1) }
                )
            }
        }

        when (page) {
            "Calculator", "Missed entry" -> {
                if (page == "Missed entry") {
                    Panel("Missed entry date") {
                        Text("Choose the date when this tank reading was actually taken.")
                        OutlinedButton(onClick = { chooseEntryDate() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Reading date: $entryDate  •  Change date")
                        }
                    }
                }
                Panel("Tank details") {
                    Box {
                        OutlinedButton(onClick = { tankMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (tankName.isBlank()) "Select tank" else tankName)
                        }
                        DropdownMenu(expanded = tankMenuExpanded, onDismissRequest = { tankMenuExpanded = false }) {
                            tanks.sortedBy { it.name.lowercase(Locale.getDefault()) }.forEach { info ->
                                DropdownMenuItem(text = { Text(info.name) }, onClick = {
                                    tankName = info.name; tankMenuExpanded = false; result = null
                                })
                            }
                            if (tanks.isEmpty()) DropdownMenuItem(text = { Text("No tanks added yet") }, onClick = { tankMenuExpanded = false })
                        }
                    }
                    Text("Add or manage tank names in the Tanks tab.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                val actualTrimIsZero = trim.toDoubleOrNull() == 0.0
                val actualLevel = level.toDoubleOrNull()
                val lowerLevel = l1.toDoubleOrNull()
                val upperLevel = l2.toDoubleOrNull()
                val levelAtLowerRef = actualLevel != null && lowerLevel != null && kotlin.math.abs(actualLevel - lowerLevel) < 0.0000001
                val levelAtUpperRef = actualLevel != null && upperLevel != null && kotlin.math.abs(actualLevel - upperLevel) < 0.0000001
                Panel("2. Trim references") {
                    Field("Actual trim (m)", trim, { trim = it; result = null })
                    if (!actualTrimIsZero) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field("Lower trim (m)", t1, { t1 = it; result = null }, Modifier.weight(1f))
                            Field("Upper trim (m)", t2, { t2 = it; result = null }, Modifier.weight(1f))
                        }
                    } else Text("Zero trim: trim reference values are not required.", style = MaterialTheme.typography.bodySmall)
                }
                Panel("3. Tank table volumes (m³)") {
                    if (actualTrimIsZero) {
                        Text("Enter tank-table volume(s) at zero trim.", style = MaterialTheme.typography.bodySmall)
                        when {
                            levelAtLowerRef -> Field("Volume at " + l1 + " cm (zero trim)", a, { a = it; result = null }, Modifier.fillMaxWidth())
                            levelAtUpperRef -> Field("Volume at " + l2 + " cm (zero trim)", a, { a = it; result = null }, Modifier.fillMaxWidth())
                            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Field(l1 + " cm (zero trim)", a, { a = it; result = null }, Modifier.weight(1f))
                                Field(l2 + " cm (zero trim)", b, { b = it; result = null }, Modifier.weight(1f))
                            }
                        }
                    } else {
                        Text("Enter values at both trim references.", style = MaterialTheme.typography.bodySmall)
                        Text("At lower trim: " + t1 + " m", fontWeight = FontWeight.SemiBold)
                        when {
                            levelAtLowerRef -> Field("Volume at " + l1 + " cm", a, { a = it; result = null }, Modifier.fillMaxWidth())
                            levelAtUpperRef -> Field("Volume at " + l2 + " cm", b, { b = it; result = null }, Modifier.fillMaxWidth())
                            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Field(l1 + " cm", a, { a = it; result = null }, Modifier.weight(1f))
                                Field(l2 + " cm", b, { b = it; result = null }, Modifier.weight(1f))
                            }
                        }
                        Text("At upper trim: " + t2 + " m", fontWeight = FontWeight.SemiBold)
                        when {
                            levelAtLowerRef -> Field("Volume at " + l1 + " cm", c, { c = it; result = null }, Modifier.fillMaxWidth())
                            levelAtUpperRef -> Field("Volume at " + l2 + " cm", d, { d = it; result = null }, Modifier.fillMaxWidth())
                            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Field(l1 + " cm", c, { c = it; result = null }, Modifier.weight(1f))
                                Field(l2 + " cm", d, { d = it; result = null }, Modifier.weight(1f))
                            }
                        }
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
                        } else Text("Hidden. Heel correction values will still be used in the calculation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(onClick = {
                    try {
                        require(tankName.trim().isNotEmpty()) { "Select a tank in Tank details." }
                        val actualLevelValue = level.num()
                        val lowerRef = l1.num()
                        val upperRef = l2.num()
                        val actualTrimValue = trim.num()
                        val atLower = kotlin.math.abs(actualLevelValue - lowerRef) < 0.0000001
                        val atUpper = kotlin.math.abs(actualLevelValue - upperRef) < 0.0000001
                        val zeroTrim = actualTrimValue == 0.0
                        val volumeA = if (atUpper && !zeroTrim) 0.0 else a.num()
                        val volumeB = if (!atLower && !atUpper) b.num() else if (zeroTrim) {
                            if (atLower || atUpper) 0.0 else b.num()
                        } else if (atUpper) b.num() else 0.0
                        val volumeC = if (zeroTrim || atUpper) 0.0 else c.num()
                        val volumeD = if (zeroTrim || atLower) 0.0 else d.num()
                        result = runCalc(actualLevelValue, lowerRef, upperRef, actualTrimValue,
                            if (zeroTrim) 0.0 else t1.num(), if (zeroTrim) 1.0 else t2.num(),
                            volumeA, volumeB, volumeC, volumeD, heel.num(), h1.num(), h2.num(), hc1.num(), hc2.num())
                        error = null; notice = null
                    } catch (e: Exception) {
                        result = null; error = e.message ?: "Check the entered values."
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
                        val tankInfo = tanks.firstOrNull { it.name.equals(tankName.trim(), true) }
                        val rec = TankRecord(tankName.trim(), level.trim(), kind, trim.trim(), r.finalVolume.fmt(), tankInfo?.content ?: "")
                        val day = if (page == "Missed entry") entryDate else todayKey()
                        val existing = findTankRecord(context, day, rec.tank)
                        if (existing != null) {
                            duplicateRecord = rec
                            duplicateDate = day
                        } else {
                            saveRecord(context, day, rec)
                            selectedDate = day
                            refreshRecords()
                            notice = "Reading for ${rec.tank} saved under $day."
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Record this reading") }
                }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Text("Use values from the vessel's approved tank calibration tables and verify results before operational use.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            "Tanks" -> {
                Panel("Manage tanks") {
                    Text("Maintain the tank particulars used by the calculator and content-wise reports.")
                    Button(onClick = { tankDialogMode = "Add"; tankDraft = ""; tankCapacityDraft = ""; tankContentDraft = ""; tankMaxSoundingDraft = ""; tankDensityDraft = ""; tankDialogError = null }, modifier = Modifier.fillMaxWidth()) { Text("Add tank") }
                    if (tanks.isEmpty()) Text("No tanks have been added.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    tanks.sortedBy { it.name.lowercase(Locale.getDefault()) }.forEach { info ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(info.name, fontWeight = FontWeight.SemiBold)
                            Text("Capacity: ${info.totalCapacity.ifBlank { "—" }} m³  •  Content: ${info.content.ifBlank { "—" }}")
                            Text("Max sounding: ${info.maxSounding.ifBlank { "—" }} cm  •  Density: ${info.density.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { tankName = info.name; tankDraft = info.name; tankDraftOriginalName = info.name; tankCapacityDraft = info.totalCapacity; tankContentDraft = info.content; tankMaxSoundingDraft = info.maxSounding; tankDensityDraft = info.density; tankDialogMode = "Edit"; tankDialogError = null }) { Text("Edit") }
                                TextButton(onClick = { tankDraft = info.name; tankDialogMode = "Delete"; tankDialogError = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            else -> {
                Panel("Saved tank readings") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = recordMode == "Date", onClick = { recordMode = "Date" }, label = { Text("By date") })
                            FilterChip(selected = recordMode == "Tank", onClick = { recordMode = "Tank" }, label = { Text("By tank") })
                        }
                        Row {
                            FilterChip(selected = recordMode == "Content", onClick = { recordMode = "Content" }, label = { Text("By content") })
                        }
                    }
                    if (recordMode == "Date") {
                        OutlinedButton(onClick = { chooseDate() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Date: $selectedDate  •  Change date")
                        }
                    } else if (recordMode == "Tank") {
                        val recordTankOptions = (tanks.map { it.name } + allRecords.map { it.record.tank })
                            .distinctBy { it.lowercase(Locale.getDefault()) }
                            .sortedBy { it.lowercase(Locale.getDefault()) }
                        Box {
                            OutlinedButton(onClick = { recordTankMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (recordTank.isBlank()) "Select tank" else recordTank)
                            }
                            DropdownMenu(expanded = recordTankMenuExpanded, onDismissRequest = { recordTankMenuExpanded = false }) {
                                recordTankOptions.forEach { name ->
                                    DropdownMenuItem(text = { Text(name) }, onClick = { recordTank = name; recordTankMenuExpanded = false })
                                }
                            }
                        }
                    }
                    if (recordMode == "Content") {
                        Text("Choose a date range. The summary compares the end date with the start date.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { chooseContentRangeDate(true) }, modifier = Modifier.weight(1f)) {
                                Text("From: $contentRangeStart", maxLines = 1)
                            }
                            OutlinedButton(onClick = { chooseContentRangeDate(false) }, modifier = Modifier.weight(1f)) {
                                Text("To: $contentRangeEnd", maxLines = 1)
                            }
                        }
                        val selectableTankNames = (tanks.map { it.name } + allRecords.map { it.record.tank })
                            .distinctBy { it.lowercase(Locale.getDefault()) }
                            .sortedBy { it.lowercase(Locale.getDefault()) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Select tanks (\${selectedContentTanks.size} selected)", fontWeight = FontWeight.SemiBold)
                            Row {
                                TextButton(onClick = { selectedContentTanks = selectableTankNames.toSet() }) { Text("All") }
                                TextButton(onClick = { selectedContentTanks = emptySet() }) { Text("Clear") }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            selectableTankNames.forEach { tank ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Checkbox(
                                        checked = selectedContentTanks.contains(tank),
                                        onCheckedChange = { checked ->
                                            selectedContentTanks = if (checked) selectedContentTanks + tank
                                                else selectedContentTanks - tank
                                        }
                                    )
                                    Text(tank, modifier = Modifier.padding(top = 12.dp))
                                }
                            }
                        }
                        if (contentRangeStart > contentRangeEnd) {
                            Text("The start date must be on or before the end date.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    val visibleRecords = when (recordMode) {
                        "Date" -> allRecords.filter { it.date == selectedDate }.sortedBy { it.record.tank.lowercase(Locale.getDefault()) }
                        "Tank" -> allRecords.filter { it.record.tank.equals(recordTank, ignoreCase = true) }.sortedByDescending { it.date }
                        else -> allRecords.filter { item ->
                            item.date >= contentRangeStart && item.date <= contentRangeEnd &&
                                selectedContentTanks.any { it.equals(item.record.tank, ignoreCase = true) }
                        }.sortedWith(compareBy<DatedRecord> { it.date }.thenBy { it.record.tank.lowercase(Locale.getDefault()) })
                    }
                    if (recordMode == "Content" && contentRangeStart > contentRangeEnd) {
                        Text("Correct the date range to view readings.", color = MaterialTheme.colorScheme.error)
                    } else if (recordMode == "Content" && selectedContentTanks.isEmpty()) {
                        Text("Select at least one tank to view its content summary.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (visibleRecords.isEmpty()) {
                        Text(when (recordMode) { "Date" -> "No readings saved for this date."; "Tank" -> "No readings found for this tank."; else -> "No readings found for the selected tanks and date range." }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("${visibleRecords.size} reading(s)", fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = {
                            exportRows = visibleRecords
                            exportIncludeTotal = recordMode != "Tank"
                            exportLabel = when (recordMode) {
                                "Date" -> "Tank readings $selectedDate"
                                "Tank" -> "Tank readings $recordTank"
                                else -> "Content readings $contentRangeStart to $contentRangeEnd"
                            }
                            exportMessage = null
                            exportDialogOpen = true
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Download current view")
                        }
                        exportMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            Row {
                                if (recordMode == "Tank" || recordMode == "Content") TableCell("Date", true, 105.dp)
                                TableCell("Tank", true, 112.dp)
                                if (recordMode != "Tank") TableCell("Content", true, 105.dp)
                                if (recordMode == "Content") {
                                    TableCell("Capacity m³", true, 105.dp)
                                    TableCell("Max sounding cm", true, 125.dp)
                                    TableCell("Density kg/m³", true, 115.dp)
                                }
                                TableCell("Measured", true, 95.dp)
                                TableCell("Type", true, 92.dp)
                                TableCell("Trim (m)", true, 82.dp)
                                TableCell("Corrected m³", true, 112.dp)
                            }
                            visibleRecords.forEach { item ->
                                Row {
                                    if (recordMode == "Tank" || recordMode == "Content") TableCell(item.date, false, 105.dp)
                                    TableCell(item.record.tank, false, 112.dp)
                                    if (recordMode != "Tank") {
                                        val savedContent = item.record.content.ifBlank {
                                            tanks.firstOrNull { it.name.equals(item.record.tank, true) }?.content ?: ""
                                        }
                                        TableCell(savedContent, false, 105.dp)
                                    }
                                    if (recordMode == "Content") {
                                        val info = tanks.firstOrNull { it.name.equals(item.record.tank, true) }
                                        TableCell(info?.totalCapacity ?: "—", false, 105.dp)
                                        TableCell(info?.maxSounding ?: "—", false, 125.dp)
                                        TableCell(info?.density ?: "—", false, 115.dp)
                                    }
                                    TableCell(item.record.measurement, false, 95.dp)
                                    TableCell(item.record.kind, false, 92.dp)
                                    TableCell(item.record.trim, false, 82.dp)
                                    TableCell(item.record.volume, false, 112.dp)
                                }
                            }
                        }
                        if (recordMode == "Content" && visibleRecords.isNotEmpty()) {
                            val groupedByContent = visibleRecords.groupBy { recordContentFor(it.record, tanks).ifBlank { "Unspecified content" } }
                            HorizontalDivider()
                            Text("Content summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Current volume is calculated on $contentRangeEnd. Generated volume is the change from $contentRangeStart to $contentRangeEnd.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            groupedByContent.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (contentName, contentRows) ->
                                val endRows = contentRows.filter { it.date == contentRangeEnd }
                                val startRows = contentRows.filter { it.date == contentRangeStart }
                                val startByTank = startRows.associateBy { it.record.tank.lowercase(Locale.getDefault()) }
                                val comparable = endRows.mapNotNull { endItem ->
                                    val startItem = startByTank[endItem.record.tank.lowercase(Locale.getDefault())]
                                    if (startItem == null) null else
                                        (endItem.record.volume.toDoubleOrNull() ?: 0.0) -
                                            (startItem.record.volume.toDoubleOrNull() ?: 0.0)
                                }
                                val currentVolume = endRows.sumOf { it.record.volume.toDoubleOrNull() ?: 0.0 }
                                val generatedVolume = comparable.sum()
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(contentName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        OutputLine("Current volume ($contentRangeEnd)", "\${currentVolume.fmt()} m³")
                                        OutputLine("Total generated / change", "\${if (generatedVolume > 0) "+" else ""}\${generatedVolume.fmt()} m³")
                                        Text("Compared across \${comparable.size} tank(s) with readings on both dates; \${endRows.size} tank(s) have end-date readings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (exportDialogOpen) {
        AlertDialog(
            onDismissRequest = { exportDialogOpen = false },
            title = { Text("Download current readings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Export ${exportRows.size} reading(s) from the current view.")
                    Text("Choose a file format:")
                    Text("• Excel (.xlsx) – an editable spreadsheet workbook.")
                    Text("• PDF – formatted report for viewing and sharing.")
                }
            },
            confirmButton = {
                Column {
                    TextButton(onClick = {
                        exportDialogOpen = false
                        csvExportLauncher.launch("${safeFileName(exportLabel)}.xlsx")
                    }) { Text("Excel (.xlsx)") }
                    TextButton(onClick = {
                        exportDialogOpen = false
                        pdfExportLauncher.launch("${safeFileName(exportLabel)}.pdf")
                    }) { Text("PDF") }
                }
            },
            dismissButton = { TextButton(onClick = { exportDialogOpen = false }) { Text("Cancel") } }
        )
    }

    if (duplicateRecord != null && duplicateDate != null) {
        val proposed = duplicateRecord!!
        val day = duplicateDate!!
        val original = findTankRecord(context, day, proposed.tank)
        AlertDialog(
            onDismissRequest = { duplicateRecord = null; duplicateDate = null },
            title = { Text("Reading already exists") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A reading for ${proposed.tank} is already saved on $day.")
                    Text("Previously saved volume: ${original?.volume ?: "Unknown"} m³", fontWeight = FontWeight.SemiBold)
                    Text("New calculated volume: ${proposed.volume} m³")
                    Text("Would you like to overwrite the existing reading?")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    saveRecord(context, day, proposed)
                    selectedDate = day
                    refreshRecords()
                    notice = "Reading for ${proposed.tank} on $day was overwritten."
                    duplicateRecord = null
                    duplicateDate = null
                }) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = {
                    duplicateRecord = null
                    duplicateDate = null
                    notice = "Original reading was kept."
                }) { Text("Keep original") }
            }
        )
    }

    if (tankDialogMode != null) {
        val mode = tankDialogMode!!
        AlertDialog(
            onDismissRequest = { tankDialogMode = null },
            title = { Text(when (mode) { "Add" -> "Add tank"; "Edit" -> "Edit tank details"; else -> "Delete tank" }) },
            text = {
                if (mode == "Delete") Text("Delete tank '$tankDraft' from the tank list? Previously saved readings will not be deleted.")
                else Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = tankDraft, onValueChange = { tankDraft = it; tankDialogError = null }, label = { Text("Tank name / number") }, singleLine = true)
                    OutlinedTextField(value = tankCapacityDraft, onValueChange = { tankCapacityDraft = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Total capacity (m³)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(value = tankContentDraft, onValueChange = { tankContentDraft = it }, label = { Text("Content") }, singleLine = true)
                    OutlinedTextField(value = tankMaxSoundingDraft, onValueChange = { tankMaxSoundingDraft = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Maximum sounding (cm)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(value = tankDensityDraft, onValueChange = { tankDensityDraft = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Content density (kg/m³)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    tankDialogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (mode) {
                        "Delete" -> {
                            saveTankInfos(context, tanks.filterNot { it.name.equals(tankDraft, true) })
                            if (tankName.equals(tankDraft, true)) tankName = ""
                            refreshTanks(); tankDialogMode = null
                        }
                        else -> {
                            val name = tankDraft.trim()
                            val duplicate = if (mode == "Add") tanks.any { it.name.equals(name, true) } else tanks.any { it.name.equals(name, true) && !it.name.equals(tankDraftOriginalName, true) }
                            when {
                                name.isEmpty() -> tankDialogError = "Enter a tank name."
                                duplicate -> tankDialogError = "This tank already exists."
                                tankCapacityDraft.toDoubleOrNull() == null || tankCapacityDraft.toDouble() <= 0.0 -> tankDialogError = "Enter a valid total capacity."
                                tankContentDraft.trim().isEmpty() -> tankDialogError = "Enter the tank content."
                                tankMaxSoundingDraft.toDoubleOrNull() == null || tankMaxSoundingDraft.toDouble() <= 0.0 -> tankDialogError = "Enter a valid maximum sounding."
                                tankDensityDraft.toDoubleOrNull() == null || tankDensityDraft.toDouble() <= 0.0 -> tankDialogError = "Enter a valid content density."
                                else -> {
                                    val info = TankInfo(name, tankCapacityDraft.trim(), tankContentDraft.trim(), tankMaxSoundingDraft.trim(), tankDensityDraft.trim())
                                    val updated = if (mode == "Add") tanks + info else tanks.map { if (it.name.equals(tankDraftOriginalName, true)) info else it }
                                    saveTankInfos(context, updated)
                                    if (mode == "Edit" && !name.equals(tankDraftOriginalName, true)) {
                                        renameTankInRecords(context, tankDraftOriginalName, name)
                                        if (tankName.equals(tankDraftOriginalName, true)) tankName = name
                                    } else if (mode == "Add") tankName = name
                                    refreshTanks(); refreshRecords(); tankDialogMode = null
                                }
                            }
                        }
                    }
                }) { Text(if (mode == "Delete") "Delete" else "Save") }
            },
            dismissButton = { TextButton(onClick = { tankDialogMode = null }) { Text("Cancel") } }
        )
    }
}

private var tankDraftOriginalName: String = ""


private fun safeFileName(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "Tank_readings" }

private fun tankInfoFor(tank: String, tanks: List<TankInfo>): TankInfo? =
    tanks.firstOrNull { it.name.equals(tank, ignoreCase = true) }

private fun recordContentFor(record: TankRecord, tanks: List<TankInfo>): String =
    record.content.ifBlank { tankInfoFor(record.tank, tanks)?.content ?: "" }

private fun csvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""


private fun xmlEscape(value: String): String = value
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&apos;")

private fun excelColumn(index: Int): String {
    var n = index + 1
    var result = ""
    while (n > 0) {
        val rem = (n - 1) % 26
        result = ('A'.code + rem).toChar() + result
        n = (n - 1) / 26
    }
    return result
}

private fun writeReadingsXlsx(
    context: android.content.Context,
    uri: Uri,
    rows: List<DatedRecord>,
    tanks: List<TankInfo>,
    reportTitle: String,
    includeTotal: Boolean
) {
    val sheetRows = mutableListOf<List<String>>()
    sheetRows.add(listOf(reportTitle))
    sheetRows.add(listOf("Date", "Tank", "Content", "Total capacity (m³)", "Maximum sounding (cm)", "Density (kg/m³)", "Measurement", "Measurement type", "Trim (m)", "Corrected volume (m³)"))
    rows.forEach { item ->
        val info = tankInfoFor(item.record.tank, tanks)
        sheetRows.add(listOf(
            item.date, item.record.tank, recordContentFor(item.record, tanks),
            info?.totalCapacity ?: "", info?.maxSounding ?: "", info?.density ?: "",
            item.record.measurement, item.record.kind, item.record.trim, item.record.volume
        ))
    }
    if (includeTotal && rows.isNotEmpty()) {
        sheetRows.add(emptyList())
        sheetRows.add(listOf("Total corrected volume (m³)", rows.sumOf { it.record.volume.toDoubleOrNull() ?: 0.0 }.fmt()))
    }
    val sheetXml = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        sheetRows.forEachIndexed { rowIndex, cells ->
            append("""<row r="${rowIndex + 1}">""")
            cells.forEachIndexed { colIndex, value ->
                if (value.isNotEmpty()) {
                    val ref = excelColumn(colIndex) + (rowIndex + 1)
                    append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${xmlEscape(value)}</t></is></c>""")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }
    val workbookXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Tank Readings" sheetId="1" r:id="rId1"/></sheets></workbook>"""
    val workbookRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
    val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
    val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""
    context.contentResolver.openOutputStream(uri)?.use { output ->
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", contentTypes)
            entry("_rels/.rels", rootRels)
            entry("xl/workbook.xml", workbookXml)
            entry("xl/_rels/workbook.xml.rels", workbookRels)
            entry("xl/worksheets/sheet1.xml", sheetXml)
        }
    } ?: error("Unable to open the selected file.")
}

private fun writeReadingsCsv(
    context: android.content.Context,
    uri: Uri,
    rows: List<DatedRecord>,
    tanks: List<TankInfo>,
    reportTitle: String
) {
    val output = buildString {
        append("\uFEFF")
        appendLine(csvCell(reportTitle))
        appendLine(listOf("Date", "Tank", "Content", "Total capacity (m³)", "Maximum sounding (cm)", "Density (kg/m³)", "Measurement", "Measurement type", "Trim (m)", "Corrected volume (m³)").joinToString(",") { csvCell(it) })
        rows.forEach { item ->
            val info = tankInfoFor(item.record.tank, tanks)
            val values = listOf(
                item.date, item.record.tank, recordContentFor(item.record, tanks),
                info?.totalCapacity ?: "", info?.maxSounding ?: "", info?.density ?: "",
                item.record.measurement, item.record.kind, item.record.trim, item.record.volume
            )
            appendLine(values.joinToString(",") { csvCell(it) })
        }
        if (rows.isNotEmpty()) {
            appendLine()
            appendLine(csvCell("Total corrected volume (m³)") + "," + csvCell(rows.sumOf { it.record.volume.toDoubleOrNull() ?: 0.0 }.fmt()))
        }
    }
    context.contentResolver.openOutputStream(uri)?.use { it.write(output.toByteArray(Charsets.UTF_8)) }
        ?: error("Unable to open the selected file.")
}

private fun writeReadingsPdf(
    context: android.content.Context,
    uri: Uri,
    rows: List<DatedRecord>,
    tanks: List<TankInfo>,
    reportTitle: String,
    includeTotal: Boolean
) {
    val document = PdfDocument()
    val pageWidth = 842
    val pageHeight = 595
    val margin = 28f
    val rowHeight = 28f
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8.5f }
    val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f }
    val headers = listOf("Date", "Tank", "Content", "Capacity", "Max sound.", "Density", "Measured", "Type", "Trim", "Corrected m³")
    val widths = listOf(65f, 72f, 75f, 65f, 65f, 60f, 62f, 55f, 48f, 75f)
    val rowsPerPage = 15
    val pageCount = maxOf(1, (rows.size + rowsPerPage - 1) / rowsPerPage)
    for (pageIndex in 0 until pageCount) {
        val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create())
        val canvas = page.canvas
        var y = 38f
        canvas.drawText(reportTitle, margin, y, titlePaint)
        y += 18f
        canvas.drawText("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}    Readings: ${rows.size}", margin, y, mutedPaint)
        y += 22f
        var x = margin
        headers.forEachIndexed { index, header ->
            canvas.drawText(header, x, y, headerPaint)
            x += widths[index]
        }
        y += 8f
        canvas.drawLine(margin, y, pageWidth - margin, y, bodyPaint)
        y += 15f
        val start = pageIndex * rowsPerPage
        val end = minOf(rows.size, start + rowsPerPage)
        for (i in start until end) {
            val item = rows[i]
            val info = tankInfoFor(item.record.tank, tanks)
            val values = listOf(
                item.date, item.record.tank, recordContentFor(item.record, tanks),
                info?.totalCapacity ?: "—", info?.maxSounding ?: "—", info?.density ?: "—",
                item.record.measurement, item.record.kind, item.record.trim, item.record.volume
            )
            x = margin
            values.forEachIndexed { index, value ->
                val maxChars = (widths[index] / 5f).toInt().coerceAtLeast(5)
                canvas.drawText(if (value.length > maxChars) value.take(maxChars - 1) + "…" else value, x, y, bodyPaint)
                x += widths[index]
            }
            y += rowHeight
            canvas.drawLine(margin, y - 12f, pageWidth - margin, y - 12f, mutedPaint)
        }
        if (includeTotal && pageIndex == pageCount - 1) {
            y += 8f
            val total = rows.sumOf { it.record.volume.toDoubleOrNull() ?: 0.0 }
            val totalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            canvas.drawText("Total corrected volume: ${total.fmt()} m³", margin, y, totalPaint)
        }
        document.finishPage(page)
    }
    try {
        context.contentResolver.openOutputStream(uri)?.use { document.writeTo(it) }
            ?: error("Unable to open the selected file.")
    } finally {
        document.close()
    }
}

private fun dateOffsetKey(days: Int): String {
    val calendar = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, days) }
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)
}
private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
private fun parseDate(value: String): Calendar {
    val date = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value) }.getOrNull() ?: Date()
    return Calendar.getInstance().apply { time = date }
}
private fun loadTankInfos(context: android.content.Context): List<TankInfo> {
    val raw = context.getSharedPreferences("tank_list", android.content.Context.MODE_PRIVATE).getString("tanks", "[]") ?: "[]"
    return runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).mapNotNull { i ->
            when (val item = a.get(i)) {
                is String -> TankInfo(item, "", "", "", "")
                is JSONObject -> TankInfo(item.optString("name"), item.optString("totalCapacity"), item.optString("content"), item.optString("maxSounding"), item.optString("density"))
                else -> null
            }
        }
    }.getOrDefault(emptyList())
}
private fun saveTankInfos(context: android.content.Context, tanks: List<TankInfo>) {
    val array = JSONArray()
    tanks.distinctBy { it.name.lowercase(Locale.getDefault()) }.forEach { info ->
        array.put(JSONObject().put("name", info.name).put("totalCapacity", info.totalCapacity).put("content", info.content).put("maxSounding", info.maxSounding).put("density", info.density))
    }
    context.getSharedPreferences("tank_list", android.content.Context.MODE_PRIVATE).edit().putString("tanks", array.toString()).apply()
}
private fun loadRecords(context: android.content.Context, date: String): List<TankRecord> {
    val raw = context.getSharedPreferences("tank_readings", android.content.Context.MODE_PRIVATE).getString(date, "[]") ?: "[]"
    return parseRecords(raw)
}
private fun parseRecords(raw: String): List<TankRecord> = runCatching {
    val array = JSONArray(raw)
    (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        TankRecord(o.optString("tank"), o.optString("measurement"), o.optString("kind"), o.optString("trim"), o.optString("volume"), o.optString("content"))
    }
}.getOrDefault(emptyList())
private fun loadAllRecords(context: android.content.Context): List<DatedRecord> {
    val prefs = context.getSharedPreferences("tank_readings", android.content.Context.MODE_PRIVATE)
    return prefs.all.mapNotNull { (date, value) ->
        val raw = value as? String ?: return@mapNotNull null
        parseRecords(raw).map { DatedRecord(date, it) }
    }.flatten()
}
private fun renameTankInRecords(context: android.content.Context, oldName: String, newName: String) {
    val prefs = context.getSharedPreferences("tank_readings", android.content.Context.MODE_PRIVATE)
    val editor = prefs.edit()
    prefs.all.forEach { (date, value) ->
        val raw = value as? String ?: return@forEach
        val updated = parseRecords(raw).map { rec ->
            if (rec.tank.equals(oldName, true)) rec.copy(tank = newName) else rec
        }
        val array = JSONArray()
        updated.forEach { rec ->
            array.put(JSONObject().put("tank", rec.tank).put("measurement", rec.measurement)
                .put("kind", rec.kind).put("trim", rec.trim).put("volume", rec.volume).put("content", rec.content))
        }
        editor.putString(date, array.toString())
    }
    editor.apply()
}
private fun findTankRecord(context: android.content.Context, date: String, tank: String): TankRecord? =
    loadRecords(context, date).firstOrNull { it.tank.equals(tank, ignoreCase = true) }

private fun saveRecord(context: android.content.Context, date: String, record: TankRecord) {
    val prefs = context.getSharedPreferences("tank_readings", android.content.Context.MODE_PRIVATE)
    val existing = runCatching { JSONArray(prefs.getString(date, "[]")) }.getOrDefault(JSONArray())
    val array = JSONArray()
    for (i in 0 until existing.length()) {
        val item = existing.optJSONObject(i) ?: continue
        if (!item.optString("tank").equals(record.tank, ignoreCase = true)) array.put(item)
    }
    array.put(JSONObject().put("tank", record.tank).put("measurement", record.measurement)
        .put("kind", record.kind).put("trim", record.trim).put("volume", record.volume).put("content", record.content))
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
        keyboardOptions = KeyboardOptions(keyboardType = if (allowText) KeyboardType.Text else KeyboardType.Decimal))
}
@Composable private fun OutputLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label); Text(value, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
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
