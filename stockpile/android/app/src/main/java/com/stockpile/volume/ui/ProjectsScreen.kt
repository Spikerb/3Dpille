@file:OptIn(ExperimentalMaterial3Api::class)

package com.stockpile.volume.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.stockpile.volume.data.ChainAnchor
import com.stockpile.volume.data.Project
import com.stockpile.volume.data.ProjectStore
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale

@Composable
fun ProjectsScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ProjectStore.get(context) }
    val projects by store.projects.collectAsState()
    var showNew by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hromady") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Nastavení") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Nové měření") },
            )
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Zatím žádná měření.\nKlepněte na „Nové měření“ a obejděte hromadu s telefonem.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(projects, key = { it.id }) { p -> ProjectRow(p) { onOpen(p.id) } }
            }
        }
    }

    if (showNew) {
        NewProjectDialog(
            onDismiss = { showNew = false },
            onCreate = { name, density ->
                showNew = false
                onOpen(store.create(name, density).id)
            },
        )
    }
}

@Composable
private fun ProjectRow(p: Project, onClick: () -> Unit) {
    val volume = p.measurement?.volumeM3 ?: p.remote?.result?.volumeM3
    val tonnes = p.measurement?.tonnes ?: p.remote?.result?.tonnes
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(statusLine(p), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (volume != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmt(volume, 1) + " m³", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    tonnes?.let { Text(fmt(it, 1) + " t", style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

fun statusLine(p: Project): String {
    if (p.measurement == null && p.remote?.result == null) return "Nezměřeno"
    val a = p.anchor
    if (a == null || !p.anchorMatches) return "Změřeno · nezapsáno"
    return when (a.status) {
        ChainAnchor.VALIDATED -> "Změřeno · ✓ v blockchainu"
        ChainAnchor.PENDING -> "Změřeno · čeká na podpis"
        ChainAnchor.SIGNED -> "Změřeno · čeká na ledger"
        else -> "Změřeno · zápis selhal"
    }
}

fun fmt(v: Double, decimals: Int): String = String.format(Locale("cs", "CZ"), "%,.${decimals}f", v)

fun formatInstant(iso: String): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(Instant.parse(iso)))
}.getOrDefault(iso)

@Composable
private fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, Double?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var density by remember { mutableStateOf("") }
    val densityValue = density.replace(',', '.').toDoubleOrNull()
    val densityOk = density.isBlank() || (densityValue != null && densityValue > 0 && densityValue < 10)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nové měření") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Název / místo hromady") }, singleLine = true)
                OutlinedTextField(
                    density, { density = it },
                    label = { Text("Objemová hmotnost t/m³ (nepovinné)") },
                    supportingText = { Text("např. štěrk 1,6 · písek 1,5 · zemina 1,2 · štěpka 0,3") },
                    isError = !densityOk, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && densityOk,
                onClick = { onCreate(name, if (density.isBlank()) null else densityValue) },
            ) { Text("Vytvořit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}
