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
    var tanks by remember { mutableStateOf(loadTanks(context)) }
    var tankName by remember { mutableStateOf("") }
    var tankMenuExpanded by remember { mutableStateOf(false) }
    var tankDialogMode by remember { mutableStateOf<String?>(null) }
    var tankDraft by remember { mutableStateOf("") }
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
    var recordTankMenuExpanded by remember { mutableStateOf(false) }
    var allRecords by remember { mutableStateOf(loadAllRecords(context)) }

    fun refreshTanks() { tanks = loadTanks(context) }
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tank Volume Calculator", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = page == "Calculator", onClick = { page = "Calculator"; entryDate = todayKey() }, label = { Text("Calculator") })
            FilterChip(selected = page == "Missed entry", onClick = { page = "Missed entry" }, label = { Text("Missed entry") })
            FilterChip(selected = page == "Records", onClick = { page = "Records"; refreshRecords() }, label = { Text("View records") })
            FilterChip(selected = page == "Tanks", onClick = { page = "Tanks"; refreshTanks() }, label = { Text("Tanks") })
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
                } else {
                    entryDate = todayKey()
                }
                Panel("Tank details") {
                    Box {
                        OutlinedButton(onClick = { tankMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (tankName.isBlank()) "Select tank" else tankName)
                        }
                        DropdownMenu(expanded = tankMenuExpanded, onDismissRequest = { tankMenuExpanded = false }) {
                            tanks.sortedBy { it.lowercase(Locale.getDefault()) }.forEach { name ->
                                DropdownMenuItem(text = { Text(name) }, onClick = {
                                    tankName = name; tankMenuExpanded = false; result = null
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
                        val rec = TankRecord(tankName.trim(), level.trim(), kind, trim.trim(), r.finalVolume.fmt())
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
                    Text("Add tank names or numbers. These will appear alphabetically in the calculator.")
                    Button(onClick = { tankDialogMode = "Add"; tankDraft = ""; tankDialogError = null }, modifier = Modifier.fillMaxWidth()) { Text("Add tank") }
                    if (tanks.isEmpty()) Text("No tanks have been added.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    tanks.sortedBy { it.lowercase(Locale.getDefault()) }.forEach { name ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(name, modifier = Modifier.weight(1f))
                            TextButton(onClick = { tankName = name; tankDraft = name; tankDraftOriginalName = name; tankDialogMode = "Rename"; tankDialogError = null }) { Text("Rename") }
                            TextButton(onClick = { tankDraft = name; tankDialogMode = "Delete"; tankDialogError = null }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                        }
                        HorizontalDivider()
                    }
                }
            }
            else -> {
                Panel("Saved tank readings") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = recordMode == "Date", onClick = { recordMode = "Date" }, label = { Text("By date") })
                        FilterChip(selected = recordMode == "Tank", onClick = { recordMode = "Tank" }, label = { Text("By tank") })
                    }
                    if (recordMode == "Date") {
                        OutlinedButton(onClick = { chooseDate() }, modifier = Modifier.fillMaxWidth()) {
                            Text("Date: $selectedDate  •  Change date")
                        }
                    } else {
                        val recordTankOptions = (tanks + allRecords.map { it.record.tank })
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
                    val visibleRecords = if (recordMode == "Date") {
                        allRecords.filter { it.date == selectedDate }.sortedBy { it.record.tank.lowercase(Locale.getDefault()) }
                    } else {
                        allRecords.filter { it.record.tank.equals(recordTank, ignoreCase = true) }.sortedByDescending { it.date }
                    }
                    if (visibleRecords.isEmpty()) {
                        Text(if (recordMode == "Date") "No readings saved for this date." else "No readings found for this tank.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("${visibleRecords.size} reading(s)", fontWeight = FontWeight.SemiBold)
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            Row {
                                if (recordMode == "Tank") TableCell("Date", true, 105.dp)
                                TableCell("Tank", true, 112.dp)
                                TableCell("Measured", true, 95.dp)
                                TableCell("Type", true, 92.dp)
                                TableCell("Trim (m)", true, 82.dp)
                                TableCell("Corrected m³", true, 112.dp)
                            }
                            visibleRecords.forEach { item ->
                                Row {
                                    if (recordMode == "Tank") TableCell(item.date, false, 105.dp)
                                    TableCell(item.record.tank, false, 112.dp)
                                    TableCell(item.record.measurement, false, 95.dp)
                                    TableCell(item.record.kind, false, 92.dp)
                                    TableCell(item.record.trim, false, 82.dp)
                                    TableCell(item.record.volume, false, 112.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
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
            title = { Text(when (mode) { "Add" -> "Add tank"; "Rename" -> "Rename tank"; else -> "Delete tank" }) },
            text = {
                if (mode == "Delete") Text("Delete tank '$tankDraft' from the tank list? Previously saved readings will not be deleted.")
                else Column {
                    OutlinedTextField(value = tankDraft, onValueChange = { tankDraft = it; tankDialogError = null },
                        label = { Text("Tank name / number") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text))
                    tankDialogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (mode) {
                        "Add" -> {
                            val name = tankDraft.trim()
                            when {
                                name.isEmpty() -> tankDialogError = "Enter a tank name."
                                tanks.any { it.equals(name, true) } -> tankDialogError = "This tank already exists."
                                else -> { saveTanks(context, tanks + name); refreshTanks(); tankName = name; tankDialogMode = null }
                            }
                        }
                        "Rename" -> {
                            val name = tankDraft.trim()
                            when {
                                name.isEmpty() -> tankDialogError = "Enter a tank name."
                                tanks.any { it.equals(name, true) && !it.equals(tankDraftOriginalName, true) } -> tankDialogError = "This tank already exists."
                                else -> {
                                    val oldName = tanks.firstOrNull { it.equals(tankDraftOriginalName, true) }
                                    val target = oldName ?: tankDraftOriginalName
                                    val updated = tanks.map { if (it == target) name else it }
                                    saveTanks(context, updated); renameTankInRecords(context, target, name); refreshTanks(); refreshRecords()
                                    if (tankName == target) tankName = name
                                    tankDialogMode = null
                                }
                            }
                        }
                        else -> {
                            saveTanks(context, tanks.filterNot { it.equals(tankDraft, true) })
                            if (tankName.equals(tankDraft, true)) tankName = ""
                            refreshTanks()
                            tankDialogMode = null
                        }
                    }
                }) { Text(if (mode == "Delete") "Delete" else "Save") }
            },
            dismissButton = { TextButton(onClick = { tankDialogMode = null }) { Text("Cancel") } }
        )
    }
}

private var tankDraftOriginalName: String = ""

private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
private fun parseDate(value: String): Calendar {
    val date = runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value) }.getOrNull() ?: Date()
    return Calendar.getInstance().apply { time = date }
}
private fun loadTanks(context: android.content.Context): List<String> {
    val raw = context.getSharedPreferences("tank_list", android.content.Context.MODE_PRIVATE).getString("tanks", "[]") ?: "[]"
    return runCatching { val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) } }.getOrDefault(emptyList())
}
private fun saveTanks(context: android.content.Context, tanks: List<String>) {
    val array = JSONArray()
    tanks.distinctBy { it.lowercase(Locale.getDefault()) }.forEach { array.put(it) }
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
        TankRecord(o.optString("tank"), o.optString("measurement"), o.optString("kind"), o.optString("trim"), o.optString("volume"))
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
                .put("kind", rec.kind).put("trim", rec.trim).put("volume", rec.volume))
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
