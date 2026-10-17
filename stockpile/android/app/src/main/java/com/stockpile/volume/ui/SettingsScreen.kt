@file:OptIn(ExperimentalMaterial3Api::class)

package com.stockpile.volume.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.stockpile.volume.chain.Registry
import com.stockpile.volume.chain.Xaman
import com.stockpile.volume.chain.Xrpl
import com.stockpile.volume.data.Settings
import com.stockpile.volume.net.ApiClient
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    var xamanKey by remember { mutableStateOf(settings.xamanApiKey) }
    var network by remember { mutableStateOf(settings.network) }
    var registry by remember { mutableStateOf(settings.registryAddress) }
    var signedIn by remember { mutableStateOf(settings.xamanSignedIn) }
    var url by remember { mutableStateOf(settings.serverUrl) }
    var key by remember { mutableStateOf(settings.apiKey) }
    var interval by remember { mutableFloatStateOf(settings.autoIntervalSec) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var showServer by remember { mutableStateOf(settings.isConfigured) }
    val scope = rememberCoroutineScope()

    fun save() {
        if (xamanKey.trim() != settings.xamanApiKey || network != settings.network) settings.signOutXaman()
        settings.xamanApiKey = xamanKey
        settings.network = network
        settings.setRegistryFor(network, registry)
        settings.serverUrl = url
        settings.apiKey = key
        settings.autoIntervalSec = interval
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nastavení") },
                navigationIcon = { IconButton(onClick = { save(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zpět") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------------------------------------------------------------- blockchain
            Text("Blockchain – Xaman / XRP Ledger", style = MaterialTheme.typography.titleMedium)
            Text(
                "Na apps.xumm.dev si zaregistrujte aplikaci, vložte sem její API Key (Secret nepotřebujete) " +
                    "a do „Redirect URIs“ / „Return URL“ přidejte: ${Xaman.REDIRECT_URI} a ${Xaman.PAGES_BASE}/signed.html",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                xamanKey, { xamanKey = it },
                label = { Text("Xaman API Key") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text("Síť", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = network == Xrpl.MAINNET, onClick = { settings.setRegistryFor(network, registry); network = Xrpl.MAINNET; registry = settings.registryFor(Xrpl.MAINNET) }, label = { Text("Mainnet (ostrý)") })
                FilterChip(selected = network == Xrpl.TESTNET, onClick = { settings.setRegistryFor(network, registry); network = Xrpl.TESTNET; registry = settings.registryFor(Xrpl.TESTNET) }, label = { Text("Testnet (zkoušky)") })
            }
            val regOk = registry.isBlank() || Registry.isAddress(registry)
            OutlinedTextField(
                registry, { registry = it.trim() },
                label = { Text("Evidenční XRPL adresa (${if (network == Xrpl.TESTNET) "Testnet" else "Mainnet"})") },
                supportingText = {
                    Text(
                        if (regOk) "Společná adresa, na kterou chodí všechny zápisy (r…). Musí to být jiný, aktivovaný účet než ten, " +
                            "kterým podepisujete. Podle ní aplikace hlídá, aby stejné místo (±30 m) šlo zapsat jen jednou za 100 dní."
                        else "Neplatná XRPL adresa (má začínat písmenem r)."
                    )
                },
                isError = !regOk, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (signedIn) {
                Text("Přihlášeno: ${settings.xamanAccount.ifBlank { "ano" }}", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { settings.signOutXaman(); signedIn = false }) { Text("Odhlásit z Xamanu") }
            } else {
                OutlinedButton(
                    enabled = xamanKey.isNotBlank(),
                    onClick = { save(); runCatching { Xaman.startSignIn(context, settings) } },
                ) { Text("Přihlásit se do Xamanu") }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---------------------------------------------------------------- optional server
            Text("Zpracovací server (volitelné)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Aplikace měří sama v telefonu. Server s fotogrammetrií je jen pro velké hromady a lze ho doplnit později.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!showServer) {
                TextButton(onClick = { showServer = true }) { Text("Nastavit server") }
            } else {
                OutlinedTextField(
                    url, { url = it; testResult = null },
                    label = { Text("Adresa serveru") },
                    placeholder = { Text("http://192.168.1.20:8080") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    key, { key = it; testResult = null },
                    label = { Text("API klíč serveru") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedButton(
                    enabled = url.isNotBlank() && !testing,
                    onClick = {
                        testing = true
                        testResult = "Zkouším…"
                        scope.launch {
                            testResult = try { ApiClient(url.trim(), key.trim()).testConnection() } catch (e: Exception) { "Chyba: ${e.message}" }
                            testing = false
                        }
                    },
                ) { Text("Vyzkoušet spojení") }
                testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text("Automatické focení: snímek každé ${"%.1f".format(interval)} s", style = MaterialTheme.typography.bodyMedium)
                Slider(value = interval, onValueChange = { interval = it }, valueRange = 1f..5f, steps = 7)
            }

            Button(onClick = { save(); onBack() }, modifier = Modifier.fillMaxWidth()) { Text("Uložit") }
        }
    }
}
