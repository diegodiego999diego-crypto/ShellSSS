package com.shell.sss.hell

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shell.sss.hell.adb.AdbConnection
import com.shell.sss.hell.adb.AdbPairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {
    val log = mutableStateListOf<String>()
    var paired by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var busy by mutableStateOf(false)

    private val adb = AdbConnection()

    init {
        adb.onOutput = { text ->
            viewModelScope.launch(Dispatchers.Main) {
                log.add(text)
                if (log.size > 2000) log.removeRange(0, 1000)
            }
        }
    }

    fun pair(host: String, port: String, code: String) {
        if (busy) return
        busy = true
        log.add("\n> Emparejando con $host:$port ...\n")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { AdbPairing.pair(host.trim(), port.trim().toIntOrNull() ?: 0, code.trim()) }
            result.onSuccess {
                paired = true
                log.add("> $it\n")
            }.onFailure {
                log.add("> Error de emparejamiento: ${it.message}\n")
            }
            busy = false
        }
    }

    fun connect(host: String, port: String) {
        if (busy) return
        busy = true
        log.add("\n> Conectando a $host:$port ...\n")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { adb.connect(host.trim(), port.trim().toIntOrNull() ?: 0) }
            result.onSuccess {
                connected = true
                log.add("> $it\n")
            }.onFailure {
                log.add("> Error de conexión: ${it.message}\n")
            }
            busy = false
        }
    }

    fun run(command: String) {
        if (command.isBlank()) return
        log.add("$ $command\n")
        adb.shell(command.trim())
    }

    fun disconnect() {
        adb.disconnect()
        connected = false
        log.add("\n> Desconectado\n")
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ShellSssApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellSssApp(vm: MainViewModel = viewModel()) {
    var host by mutableStateOf("192.168.1.")
    var pairPort by mutableStateOf("")
    var pairCode by mutableStateOf("")
    var connectPort by mutableStateOf("")
    var command by mutableStateOf("")

    val scrollState = rememberScrollState()
    LaunchedEffect(vm.log.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ShellSSS") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Depuración inalámbrica", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Dirección IP") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = pairPort,
                            onValueChange = { pairPort = it },
                            label = { Text("Puerto emparejamiento") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = pairCode,
                            onValueChange = { pairCode = it },
                            label = { Text("Código (6 dígitos)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = connectPort,
                        onValueChange = { connectPort = it },
                        label = { Text("Puerto de conexión (IP:puerto)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { vm.pair(host, pairPort, pairCode) },
                            enabled = !vm.busy && pairPort.isNotBlank() && pairCode.isNotBlank()
                        ) { Text("Emparejar") }
                        Button(
                            onClick = { vm.connect(host, connectPort) },
                            enabled = !vm.busy && connectPort.isNotBlank()
                        ) { Text("Conectar") }
                        OutlinedButton(
                            onClick = { vm.disconnect() },
                            enabled = vm.connected
                        ) { Text("Desconectar") }
                    }
                    Text(
                        text = when {
                            vm.connected -> "Estado: conectado"
                            vm.paired -> "Estado: emparejado"
                            else -> "Estado: sin emparejar"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                color = Color(0xFF101418),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = vm.log.joinToString(""),
                    modifier = Modifier
                        .padding(10.dp)
                        .verticalScroll(scrollState),
                    color = Color(0xFF8BC34A),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text("Comando ADB shell") },
                    placeholder = { Text("ls /sdcard") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        vm.run(command)
                        command = ""
                    }),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    vm.run(command)
                    command = ""
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar")
                }
            }
        }
    }
}
