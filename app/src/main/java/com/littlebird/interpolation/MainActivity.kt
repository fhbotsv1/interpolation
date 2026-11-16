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

private data class Calc(val atTrimLow: Double, val atTrimHigh: Double, val trimVolume: Double, val heelCorrection: Double, val finalVolume: Double, val linearMass: Double? = null, val astmMass: Double? = null, val astmVcf: Double? = null, val temperature: Double? = null, val density15: Double? = null)
private data class TankInfo(val name: String, val totalCapacity: String, val content: String, val maxSounding: String, val density: String, val density15: String = "")
private data class TankRecord(val tank: String, val measurement: String, val kind: String, val trim: String, val volume: String, val content: String = "", val temperature: String = "", val density15: String = "", val linearMass: String = "", val astmMass: String = "", val astmVcf: String = "")
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
    var isBunker by remember { mutableStateOf(false) }
    val tankPrefsName = if (isBunker) "bunker_tank_list" else "tank_list"
    val recordPrefsName = if (isBunker) "bunker_tank_readings" else "tank_readings"
    var tanks by remember { mutableStateOf(loadTankInfos(context)) }
    var tankName by remember { mutableStateOf("") }
    var tankMenuExpanded by remember { mutableStateOf(false) }
    var tankDialogMode by remember { mutableStateOf<String?>(null) }
    var tankDraft by remember { mutableStateOf("") }
    var tankCapacityDraft by remember { mutableStateOf("") }
    var tankContentDraft by remember { mutableStateOf("") }
    var tankMaxSoundingDraft by remember { mutableStateOf("") }
    var tankDensityDraft by remember { mutableStateOf("") }
    var tankDensity15Draft by remember { mutableStateOf("") }
    var bunkerTemperature by remember { mutableStateOf("15") }
    var tankDialogError by remember { mutableStateOf<String?>(null) }

    var kind by remember { mutableStateOf("Sounding") }
    var level by remember { mutableStateOf("510") }
    val calculatorSettings = remember { context.getSharedPreferences("calculator_settings", android.content.Context.MODE_PRIVATE) }
    var referenceIntervalCm by remember { mutableIntStateOf(calculatorSettings.getInt("reference_interval_cm", 50).takeIf { it > 0 } ?: 50) }
    var referenceIntervals by remember { mutableStateOf(loadReferenceIntervals(context)) }
    var intervalMenuExpanded by remember { mutableStateOf(false) }
    var addIntervalDialogOpen by remember { mutableStateOf(false) }
    var customIntervalDraft by remember { mutableStateOf("") }
    var customIntervalError by remember { mutableStateOf<String?>(null) }
    var volumePrecision by remember { mutableIntStateOf(calculatorSettings.getInt("volume_precision", 3).coerceIn(0, 4)) }
    var precisionMenuExpanded by remember { mutableStateOf(false) }
    var trim by remember { mutableStateOf("0.0") }
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
    var selectedContents by remember { mutableStateOf<Set<String>>(emptySet()) }
    var allRecords by remember { mutableStateOf(loadAllRecords(context)) }
    var exportDialogOpen by remember { mutableStateOf(false) }
    var exportRows by remember { mutableStateOf<List<DatedRecord>>(emptyList()) }
    var exportLabel by remember { mutableStateOf("Tank readings") }
    var exportIncludeTotal by remember { mutableStateOf(true) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var deleteRecordsConfirmStage by remember { mutableStateOf(0) }

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

    LaunchedEffect(referenceIntervalCm, referenceIntervals, volumePrecision) {
        calculatorSettings.edit()
            .putInt("reference_interval_cm", referenceIntervalCm)
            .putString("reference_intervals_cm", referenceIntervals.joinToString(","))
            .putInt("volume_precision", volumePrecision)
            .apply()
    }

    fun refreshTanks() { tanks = loadTankInfos(context, tankPrefsName) }
    fun refreshRecords() { allRecords = loadAllRecords(context, recordPrefsName) }
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
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = !isBunker, onClick = { if (isBunker) { isBunker = false; tanks = loadTankInfos(context, "tank_list"); allRecords = loadAllRecords(context, "tank_readings"); result = null; notice = null } }, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)) { Text("Engine Room") }
            SegmentedButton(selected = isBunker, onClick = { if (!isBunker) { isBunker = true; tanks = loadTankInfos(context, "bunker_tank_list"); allRecords = loadAllRecords(context, "bunker_tank_readings"); result = null; notice = null } }, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)) { Text("Bunker") }
        }
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { intervalMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Interval: $referenceIntervalCm cm")
                            }
                            DropdownMenu(expanded = intervalMenuExpanded, onDismissRequest = { intervalMenuExpanded = false }) {
                                referenceIntervals.forEach { option ->
                                    DropdownMenuItem(text = { Text("$option cm") }, onClick = {
                                        referenceIntervalCm = option
                                        intervalMenuExpanded = false
                                        result = null
                                    })
                                }
                                Divider()
                                DropdownMenuItem(
                                    text = { Text("＋ Add custom interval") },
                                    onClick = {
                                        intervalMenuExpanded = false
                                        customIntervalDraft = ""
                                        customIntervalError = null
                                        addIntervalDialogOpen = true
                                    }
                                )
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { precisionMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Decimals: $volumePrecision")
                            }
                            DropdownMenu(expanded = precisionMenuExpanded, onDismissRequest = { precisionMenuExpanded = false }) {
                                (0..4).forEach { option ->
                                    DropdownMenuItem(text = { Text("$option decimal places") }, onClick = {
                                        volumePrecision = option
                                        precisionMenuExpanded = false
                                        result = null
                                    })
                                }
                            }
                        }
                    }
                    val actualLevelForRefs = level.toDoubleOrNull()
                    val lowerRefValue = actualLevelForRefs?.let { kotlin.math.floor(it / referenceIntervalCm.toDouble()) * referenceIntervalCm }
                    val upperRefValue = lowerRefValue?.plus(referenceIntervalCm)
                    Text(
                        if (lowerRefValue != null && upperRefValue != null)
                            "Automatically selected references: $lowerRefValue cm – $upperRefValue cm"
                        else "Enter a valid measurement to calculate reference values.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val actualTrimIsZero = trim.toDoubleOrNull() == 0.0
                val actualLevel = level.toDoubleOrNull()
                val lowerLevel = actualLevel?.let { kotlin.math.floor(it / referenceIntervalCm.toDouble()) * referenceIntervalCm }
                val upperLevel = lowerLevel?.plus(referenceIntervalCm)
                val l1 = lowerLevel?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: "0"
                val l2 = upperLevel?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: referenceIntervalCm.toString()
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
                if (isBunker) {
                    Panel("Bunker temperature and density") {
                        Field("Current tank temperature (°C)", bunkerTemperature, { bunkerTemperature = it; result = null })
                        val selectedTankDensity = tanks.firstOrNull { it.name.equals(tankName.trim(), true) }?.density15.orEmpty()
                        Text("Tank density at 15°C: ${selectedTankDensity.ifBlank { "Not entered — update tank particulars" }} kg/m³", style = MaterialTheme.typography.bodyMedium)
                    }
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
                        require(actualLevelValue >= 0.0) { "Sounding/ullage must be zero or greater." }
                        val lowerRef = kotlin.math.floor(actualLevelValue / referenceIntervalCm.toDouble()) * referenceIntervalCm
                        val upperRef = lowerRef + referenceIntervalCm
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
                        val baseResult = runCalc(actualLevelValue, lowerRef, upperRef, actualTrimValue,
                            if (zeroTrim) 0.0 else t1.num(), if (zeroTrim) 1.0 else t2.num(),
                            volumeA, volumeB, volumeC, volumeD, heel.num(), h1.num(), h2.num(), hc1.num(), hc2.num())
                        result = if (isBunker) {
                            val density15 = tanks.firstOrNull { it.name.equals(tankName.trim(), true) }?.density15?.toDoubleOrNull()
                                ?: error("Enter the tank density at 15°C in the Tanks section.")
                            require(density15 in 839.0..1075.0) { "The selected API/ASTM Table 54B fuel-oil correlation applies to density 839–1075 kg/m³ at 15°C. Check the density or product category." }
                            val temp = bunkerTemperature.num()
                            require(temp in -50.0..150.0) { "Temperature must be between -50°C and 150°C for this calculation." }
                            val dt = temp - 15.0
                            val alpha = 186.9696 / (density15 * density15) + 0.4862 / density15
                            val vcf = kotlin.math.exp(-alpha * dt * (1.0 + 0.8 * alpha * dt))
                            val linearDensity = density15 * (1.0 - 0.00064 * dt)
                            baseResult.copy(linearMass = baseResult.finalVolume * linearDensity / 1000.0,
                                astmMass = baseResult.finalVolume * vcf * density15 / 1000.0,
                                astmVcf = vcf, temperature = temp, density15 = density15)
                        } else baseResult
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
                            Text(r.finalVolume.fmt(volumePrecision) + " m³", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            HorizontalDivider()
                            OutputLine("Volume at lower trim", r.atTrimLow.fmt(volumePrecision) + " m³")
                            OutputLine("Volume at upper trim", r.atTrimHigh.fmt(volumePrecision) + " m³")
                            OutputLine("Volume after trim interpolation", r.trimVolume.fmt(volumePrecision) + " m³")
                            OutputLine("Heel correction", r.heelCorrection.fmt(volumePrecision) + " m³")
                            OutputLine("Final volume", r.finalVolume.fmt(volumePrecision) + " m³", true)
                            if (isBunker) {
                                HorizontalDivider()
                                Text("Bunker mass calculation", fontWeight = FontWeight.Bold)
                                OutputLine("Current temperature", "${r.temperature?.fmt(2)} °C")
                                OutputLine("Density at 15°C", "${r.density15?.fmt(2)} kg/m³")
                                OutputLine("Linear coefficient mass", "${r.linearMass?.fmt(3)} MT", true)
                                OutputLine("API/ASTM Table 54B VCF", r.astmVcf?.fmt(6) ?: "—")
                                OutputLine("API/ASTM fuel-oil mass", "${r.astmMass?.fmt(3)} MT", true)
                                OutputLine("Mass difference", "${((r.linearMass ?: 0.0) - (r.astmMass ?: 0.0)).fmt(3)} MT")
                                Text("API/ASTM Table 54B generalized fuel-oil correlation; temperature correction only, atmospheric pressure assumed. Verify against approved onboard tables and cargo documentation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Button(onClick = {
                        val tankInfo = tanks.firstOrNull { it.name.equals(tankName.trim(), true) }
                        val rec = TankRecord(tankName.trim(), level.trim(), kind, trim.trim(), r.finalVolume.fmt(volumePrecision), tankInfo?.content ?: "",
                            if (isBunker) r.temperature?.fmt(2).orEmpty() else "", if (isBunker) r.density15?.fmt(2).orEmpty() else "",
                            if (isBunker) r.linearMass?.fmt(3).orEmpty() else "", if (isBunker) r.astmMass?.fmt(3).orEmpty() else "", if (isBunker) r.astmVcf?.fmt(6).orEmpty() else "")
                        val day = if (page == "Missed entry") entryDate else todayKey()
                        val existing = findTankRecord(context, day, rec.tank, recordPrefsName)
                        if (existing != null) {
                            duplicateRecord = rec
                            duplicateDate = day
                        } else {
                            saveRecord(context, day, rec, recordPrefsName)
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
                    Text(if (isBunker) "Maintain bunker tank particulars, including density at the 15°C reference temperature." else "Maintain the tank particulars used by the calculator and content-wise reports.")
                    Button(onClick = { tankDialogMode = "Add"; tankDraft = ""; tankCapacityDraft = ""; tankContentDraft = ""; tankMaxSoundingDraft = ""; tankDensityDraft = ""; tankDensity15Draft = ""; tankDialogError = null }, modifier = Modifier.fillMaxWidth()) { Text("Add tank") }
                    if (tanks.isEmpty()) Text("No tanks have been added.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    tanks.sortedBy { it.name.lowercase(Locale.getDefault()) }.forEach { info ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(info.name, fontWeight = FontWeight.SemiBold)
                            Text("Capacity: ${info.totalCapacity.ifBlank { "—" }} m³  •  Content: ${info.content.ifBlank { "—" }}")
                            Text("Max sounding: ${info.maxSounding.ifBlank { "—" }} cm  •  Density: ${if (isBunker) info.density15.ifBlank { "—" } else info.density.ifBlank { "—" }} kg/m³", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { tankName = info.name; tankDraft = info.name; tankDraftOriginalName = info.name; tankCapacityDraft = info.totalCapacity; tankContentDraft = info.content; tankMaxSoundingDraft = info.maxSounding; tankDensityDraft = info.density; tankDensity15Draft = info.density15; tankDialogMode = "Edit"; tankDialogError = null }) { Text("Edit") }
                                TextButton(onClick = { tankDraft = info.name; tankDialogMode = "Delete"; tankDialogError = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            else -> {
                Panel("Saved tank readings") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = { deleteRecordsConfirmStage = 1 },
                            enabled = allRecords.isNotEmpty(),
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Delete all records")
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = recordMode == "Date", onClick = { recordMode = "Date" }, label = { Text("By date") })
                            if (!isBunker) FilterChip(selected = recordMode == "Tank", onClick = { recordMode = "Tank" }, label = { Text("By tank") })
                        }
                        Row {
                            FilterChip(selected = recordMode == "Content", onClick = {
                                recordMode = "Content"
                                if (selectedContents.isEmpty()) {
                                    selectedContents = (tanks.map { it.content } + allRecords.map { recordContentFor(it.record, tanks) })
                                        .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                                }
                            }, label = { Text("By content") })
                        }
                    }
                    if (recordMode == "Date") {
                        OutlinedButton(onClick = { chooseDate() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Date: $selectedDate  •  Change date")
                        }
                    } else if (recordMode == "Tank" && !isBunker) {
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
                        Text("Choose a date range to view readings for this tank.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { chooseContentRangeDate(true) }, modifier = Modifier.weight(1f)) {
                                Text("From: $contentRangeStart", maxLines = 1)
                            }
                            OutlinedButton(onClick = { chooseContentRangeDate(false) }, modifier = Modifier.weight(1f)) {
                                Text("To: $contentRangeEnd", maxLines = 1)
                            }
                        }
                    }
                    if (recordMode == "Content") {
                        Text("Choose a date range and select the content(s) to view. The summary compares the end date with the start date.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { chooseContentRangeDate(true) }, modifier = Modifier.weight(1f)) {
                                Text("From: $contentRangeStart", maxLines = 1)
                            }
                            OutlinedButton(onClick = { chooseContentRangeDate(false) }, modifier = Modifier.weight(1f)) {
                                Text("To: $contentRangeEnd", maxLines = 1)
                            }
                        }
                        val selectableContents = (tanks.map { it.content } + allRecords.map { item ->
                            recordContentFor(item.record, tanks)
                        }).map { it.trim() }.filter { it.isNotEmpty() }
                            .distinctBy { it.lowercase(Locale.getDefault()) }
                            .sortedBy { it.lowercase(Locale.getDefault()) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Select content (" + selectedContents.size + " selected)", fontWeight = FontWeight.SemiBold)
                            Row {
                                TextButton(onClick = { selectedContents = selectableContents.toSet() }) { Text("All") }
                                TextButton(onClick = { selectedContents = emptySet() }) { Text("Clear") }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            selectableContents.forEach { contentName ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Checkbox(
                                        checked = selectedContents.any { it.equals(contentName, ignoreCase = true) },
                                        onCheckedChange = { checked ->
                                            selectedContents = if (checked) selectedContents + contentName
                                                else selectedContents.filterNot { it.equals(contentName, ignoreCase = true) }.toSet()
                                        }
                                    )
                                    Text(contentName, modifier = Modifier.padding(top = 12.dp))
                                }
                            }
                        }
                        if (selectableContents.isEmpty()) {
                            Text("No content names are available. Add content details in the Tanks tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (selectedContents.isEmpty() && selectableContents.isNotEmpty()) {
                            Text("Select at least one content.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                                        val visibleRecords = when (recordMode) {
                        "Date" -> allRecords.filter { it.date == selectedDate }
                            .sortedBy { it.record.tank.lowercase(Locale.getDefault()) }
                        "Tank" -> if (isBunker) emptyList() else allRecords.filter { item ->
                            item.record.tank.equals(recordTank, ignoreCase = true) &&
                                item.date >= contentRangeStart && item.date <= contentRangeEnd
                        }.sortedByDescending { it.date }
                        else -> allRecords.filter { item ->
                            val contentName = recordContentFor(item.record, tanks).ifBlank { "Unspecified content" }
                            item.date >= contentRangeStart && item.date <= contentRangeEnd &&
                                selectedContents.any { it.equals(contentName, ignoreCase = true) }
                        }.sortedWith(compareBy<DatedRecord> { it.date }.thenBy { it.record.tank.lowercase(Locale.getDefault()) })
                    }
                    if ((recordMode == "Content" || (recordMode == "Tank" && !isBunker)) && contentRangeStart > contentRangeEnd) {
                        Text("The start date must be on or before the end date.", color = MaterialTheme.colorScheme.error)
                    } else if (recordMode == "Content" && selectedContents.isEmpty()) {
                        Text("Select at least one content to view its readings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else if (visibleRecords.isEmpty()) {
                        Text(when (recordMode) { "Date" -> "No readings saved for this date."; "Tank" -> "No readings found for this tank and date range."; else -> "No readings found for the selected content and date range." }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("${visibleRecords.size} reading(s)", fontWeight = FontWeight.SemiBold)
                        OutlinedButton(onClick = {
                            exportRows = visibleRecords
                            exportIncludeTotal = recordMode == "Date"
                            exportLabel = when (recordMode) {
                                "Date" -> "Tank readings $selectedDate"
                                "Tank" -> "Tank readings $recordTank $contentRangeStart to $contentRangeEnd"
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
                                if (isBunker) {
                                    TableCell("Temp °C", true, 85.dp)
                                    TableCell("Density @15°C", true, 105.dp)
                                    TableCell("Linear MT", true, 105.dp)
                                    TableCell("ASTM MT", true, 105.dp)
                                }
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
                                    if (isBunker) {
                                        TableCell(item.record.temperature + " °C", false, 85.dp)
                                        TableCell(item.record.density15, false, 105.dp)
                                        TableCell(item.record.linearMass + " MT", false, 105.dp)
                                        TableCell(item.record.astmMass + " MT", false, 105.dp)
                                    }
                                }
                            }
                        }
                        if (recordMode == "Tank" && visibleRecords.isNotEmpty()) {
                            val tankRows = allRecords.filter { it.record.tank.equals(recordTank, ignoreCase = true) }
                            val startReading = tankRows.firstOrNull { it.date == contentRangeStart }
                            val endReading = tankRows.firstOrNull { it.date == contentRangeEnd }
                            val startVolume = startReading?.record?.volume?.toDoubleOrNull()
                            val currentVolume = endReading?.record?.volume?.toDoubleOrNull()
                            val change = if (startVolume != null && currentVolume != null) currentVolume - startVolume else null
                            val tankInfo = tanks.firstOrNull { it.name.equals(recordTank, ignoreCase = true) }
                            HorizontalDivider()
                            Text("Tank summary", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Summary for " + recordTank + " from " + contentRangeStart + " to " + contentRangeEnd + ".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(recordTank, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    tankInfo?.content?.takeIf { it.isNotBlank() }?.let { OutputLine("Content", it) }
                                    tankInfo?.totalCapacity?.takeIf { it.isNotBlank() }?.let { OutputLine("Total capacity", it + " m³") }
                                    tankInfo?.maxSounding?.takeIf { it.isNotBlank() }?.let { OutputLine("Maximum sounding", it + " cm") }
                                    tankInfo?.density?.takeIf { it.isNotBlank() }?.let { OutputLine("Content density", it + " kg/m³") }
                                    OutputLine("Starting volume (" + contentRangeStart + ")", startVolume?.let { it.fmt() + " m³" } ?: "No reading")
                                    OutputLine("Current volume (" + contentRangeEnd + ")", currentVolume?.let { it.fmt() + " m³" } ?: "No reading")
                                    OutputLine("Total generated / change", change?.let { (if (it > 0) "+" else "") + it.fmt() + " m³" } ?: "Not available")
                                    if (change == null) {
                                        Text("A reading is required on both the start and end dates to calculate the change.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        Text("Change is calculated as end-date volume minus start-date volume.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
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
                                        OutputLine("Current volume ($contentRangeEnd)", "${currentVolume.fmt()} m³")
                                        OutputLine("Total generated / change", "${if (generatedVolume > 0) "+" else ""}${generatedVolume.fmt()} m³")
                                        Text("Compared across ${comparable.size} tank(s) with readings on both dates; ${endRows.size} tank(s) have end-date readings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (deleteRecordsConfirmStage == 1) {
        AlertDialog(
            onDismissRequest = { deleteRecordsConfirmStage = 0 },
            title = { Text("Delete all saved records?") },
            text = {
                Text("This will permanently delete all ${allRecords.size} saved tank reading(s) across every date. Tank names and tank particulars will not be deleted. This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = { deleteRecordsConfirmStage = 2 },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { deleteRecordsConfirmStage = 0 }) { Text("Cancel") }
            }
        )
    }

    if (deleteRecordsConfirmStage == 2) {
        AlertDialog(
            onDismissRequest = { deleteRecordsConfirmStage = 0 },
            title = { Text("Final confirmation") },
            text = {
                Text("Are you absolutely sure? All saved readings will be erased permanently. Press DELETE ALL RECORDS to confirm.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        context.getSharedPreferences("tank_readings", android.content.Context.MODE_PRIVATE)
                            .edit().clear().commit()
                        refreshRecords()
                        deleteRecordsConfirmStage = 0
                        exportMessage = null
                        notice = "All saved tank readings have been deleted. Tank details are unchanged."
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("DELETE ALL RECORDS") }
            },
            dismissButton = {
                TextButton(onClick = { deleteRecordsConfirmStage = 0 }) { Text("Cancel") }
            }
        )
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
        val original = findTankRecord(context, day, proposed.tank, recordPrefsName)
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
                    saveRecord(context, day, proposed, recordPrefsName)
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

    if (addIntervalDialogOpen) {
        AlertDialog(
            onDismissRequest = { addIntervalDialogOpen = false },
            title = { Text("Add custom interval") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customIntervalDraft,
                        onValueChange = { customIntervalDraft = it.filter { ch -> ch.isDigit() } ; customIntervalError = null },
                        label = { Text("Interval in cm") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text("Enter a positive whole number, such as 1, 2 or 5 cm. The list keeps the four smallest intervals.", style = MaterialTheme.typography.bodySmall)
                    customIntervalError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = customIntervalDraft.toIntOrNull()
                    when {
                        value == null || value <= 0 -> customIntervalError = "Enter a valid interval greater than zero."
                        value in referenceIntervals -> {
                            referenceIntervalCm = value
                            addIntervalDialogOpen = false
                        }
                        else -> {
                            val updated = (referenceIntervals + value).distinct().sorted().take(4)
                            if (value !in updated) {
                                customIntervalError = "This interval is larger than the four intervals currently retained."
                            } else {
                                referenceIntervals = updated
                                referenceIntervalCm = value
                                addIntervalDialogOpen = false
                                result = null
                            }
                        }
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addIntervalDialogOpen = false }) { Text("Cancel") } }
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
                    if (isBunker) {
                        OutlinedTextField(value = tankDensity15Draft, onValueChange = { tankDensity15Draft = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Density at 15°C (kg/m³)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    } else {
                        OutlinedTextField(value = tankDensityDraft, onValueChange = { tankDensityDraft = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Content density (kg/m³)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                    tankDialogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (mode) {
                        "Delete" -> {
                            saveTankInfos(context, tanks.filterNot { it.name.equals(tankDraft, true) }, tankPrefsName)
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
                                !isBunker && (tankDensityDraft.toDoubleOrNull() == null || tankDensityDraft.toDouble() <= 0.0) -> tankDialogError = "Enter a valid content density."
                                isBunker && (tankDensity15Draft.toDoubleOrNull() == null || tankDensity15Draft.toDouble() <= 0.0) -> tankDialogError = "Enter a valid density at 15°C."
                                else -> {
                                    val info = TankInfo(name, tankCapacityDraft.trim(), tankContentDraft.trim(), tankMaxSoundingDraft.trim(), tankDensityDraft.trim(), if (isBunker) tankDensity15Draft.trim() else "")
                                    val updated = if (mode == "Add") tanks + info else tanks.map { if (it.name.equals(tankDraftOriginalName, true)) info else it }
                                    saveTankInfos(context, updated, tankPrefsName)
                                    if (mode == "Edit" && !name.equals(tankDraftOriginalName, true)) {
                                        renameTankInRecords(context, tankDraftOriginalName, name, recordPrefsName)
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
private fun loadReferenceIntervals(context: android.content.Context): List<Int> {
    val prefs = context.getSharedPreferences("calculator_settings", android.content.Context.MODE_PRIVATE)
    val stored = prefs.getString("reference_intervals_cm", null)
    val parsed = stored?.split(",")?.mapNotNull { it.trim().toIntOrNull()?.takeIf { value -> value > 0 } }
    return (parsed?.takeIf { it.isNotEmpty() } ?: listOf(10, 25, 50, 100))
        .distinct().sorted().take(4)
}

private fun loadTankInfos(context: android.content.Context, prefsName: String = "tank_list"): List<TankInfo> {
    val raw = context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE).getString("tanks", "[]") ?: "[]"
    return runCatching {
        val a = JSONArray(raw)
        (0 until a.length()).mapNotNull { i ->
            when (val item = a.get(i)) {
                is String -> TankInfo(item, "", "", "", "")
                is JSONObject -> TankInfo(item.optString("name"), item.optString("totalCapacity"), item.optString("content"), item.optString("maxSounding"), item.optString("density"), item.optString("density15"))
                else -> null
            }
        }
    }.getOrDefault(emptyList())
}
private fun saveTankInfos(context: android.content.Context, tanks: List<TankInfo>, prefsName: String = "tank_list") {
    val array = JSONArray()
    tanks.distinctBy { it.name.lowercase(Locale.getDefault()) }.forEach { info ->
        array.put(JSONObject().put("name", info.name).put("totalCapacity", info.totalCapacity).put("content", info.content).put("maxSounding", info.maxSounding).put("density", info.density).put("density15", info.density15))
    }
    context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE).edit().putString("tanks", array.toString()).apply()
}
private fun loadRecords(context: android.content.Context, date: String, prefsName: String = "tank_readings"): List<TankRecord> {
    val raw = context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE).getString(date, "[]") ?: "[]"
    return parseRecords(raw)
}
private fun parseRecords(raw: String): List<TankRecord> = runCatching {
    val array = JSONArray(raw)
    (0 until array.length()).map { i ->
        val o = array.getJSONObject(i)
        TankRecord(o.optString("tank"), o.optString("measurement"), o.optString("kind"), o.optString("trim"), o.optString("volume"), o.optString("content"), o.optString("temperature"), o.optString("density15"), o.optString("linearMass"), o.optString("astmMass"), o.optString("astmVcf"))
    }
}.getOrDefault(emptyList())
private fun loadAllRecords(context: android.content.Context, prefsName: String = "tank_readings"): List<DatedRecord> {
    val prefs = context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
    return prefs.all.mapNotNull { (date, value) ->
        val raw = value as? String ?: return@mapNotNull null
        parseRecords(raw).map { DatedRecord(date, it) }
    }.flatten()
}
private fun renameTankInRecords(context: android.content.Context, oldName: String, newName: String, prefsName: String = "tank_readings") {
    val prefs = context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
    val editor = prefs.edit()
    prefs.all.forEach { (date, value) ->
        val raw = value as? String ?: return@forEach
        val updated = parseRecords(raw).map { rec ->
            if (rec.tank.equals(oldName, true)) rec.copy(tank = newName) else rec
        }
        val array = JSONArray()
        updated.forEach { rec ->
            array.put(JSONObject().put("tank", rec.tank).put("measurement", rec.measurement)
                .put("kind", rec.kind).put("trim", rec.trim).put("volume", rec.volume).put("content", rec.content)
                .put("temperature", rec.temperature).put("density15", rec.density15).put("linearMass", rec.linearMass).put("astmMass", rec.astmMass).put("astmVcf", rec.astmVcf))
        }
        editor.putString(date, array.toString())
    }
    editor.apply()
}
private fun findTankRecord(context: android.content.Context, date: String, tank: String, prefsName: String = "tank_readings"): TankRecord? =
    loadRecords(context, date, prefsName).firstOrNull { it.tank.equals(tank, ignoreCase = true) }

private fun saveRecord(context: android.content.Context, date: String, record: TankRecord, prefsName: String = "tank_readings") {
    val prefs = context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
    val existing = runCatching { JSONArray(prefs.getString(date, "[]")) }.getOrDefault(JSONArray())
    val array = JSONArray()
    for (i in 0 until existing.length()) {
        val item = existing.optJSONObject(i) ?: continue
        if (!item.optString("tank").equals(record.tank, ignoreCase = true)) array.put(item)
    }
    array.put(JSONObject().put("tank", record.tank).put("measurement", record.measurement)
        .put("kind", record.kind).put("trim", record.trim).put("volume", record.volume).put("content", record.content)
        .put("temperature", record.temperature).put("density15", record.density15).put("linearMass", record.linearMass).put("astmMass", record.astmMass).put("astmVcf", record.astmVcf))
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
private fun Double.fmt(precision: Int = 3): String = String.format(Locale.getDefault(), "%." + precision.coerceIn(0, 6) + "f", this)
