package com.ru9n.sratim.mobile.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ru9n.sratim.core.viewmodel.ConnectionResult
import com.ru9n.sratim.core.viewmodel.SetupUiState
import com.ru9n.sratim.core.viewmodel.SetupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onComplete: () -> Unit,
    isSettings: Boolean = false,
    onCancel: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isComplete) {
        if (uiState.isComplete) {
            onComplete()
        }
    }

    BackHandler(enabled = uiState.currentStep > 0 || onCancel != null) {
        if (uiState.currentStep > 0) {
            viewModel.previousStep()
        } else {
            onCancel?.invoke()
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            if (isSettings && onCancel != null) {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (!isSettings) {
                Text(
                    text = "Sratim Setup",
                    style = MaterialTheme.typography.headlineLarge
                )
                Spacer(modifier = Modifier.height(32.dp))
            }

            when (uiState.currentStep) {
                0 -> ServerStep(
                    value = uiState.serverUrl,
                    onValueChange = viewModel::onServerUrlChange,
                    onNext = viewModel::nextStep,
                    onLogout = if (isSettings) viewModel::logout else null
                )
                1 -> UsernameStep(
                    value = uiState.username,
                    onValueChange = viewModel::onUsernameChange,
                    onNext = viewModel::nextStep,
                    onBack = viewModel::previousStep
                )
                2 -> PasswordStep(
                    uiState = uiState,
                    onValueChange = viewModel::onPasswordChange,
                    onTest = viewModel::testConnection,
                    onNext = viewModel::nextStep,
                    onBack = viewModel::previousStep
                )
            }

            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "v${com.ru9n.sratim.mobile.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }
}

@Composable
fun ServerStep(
    value: String,
    onValueChange: (String) -> Unit,
    onNext: () -> Unit,
    onLogout: (() -> Unit)? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "Server Address", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Server URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onNext, enabled = value.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Next")
        }
        if (onLogout != null) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text("Sign Out")
            }
        }
    }
}

@Composable
fun UsernameStep(value: String, onValueChange: (String) -> Unit, onNext: () -> Unit, onBack: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "Login", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onNext, enabled = value.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Next") }
        }
    }
}

@Composable
fun PasswordStep(
    uiState: SetupUiState,
    onValueChange: (String) -> Unit,
    onTest: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "Finalize", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = uiState.password,
            onValueChange = onValueChange,
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.testResult != null) {
            val color = if (uiState.testResult is ConnectionResult.Success) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
            val text = if (uiState.testResult is ConnectionResult.Success) "Connection successful!" else (uiState.testResult as ConnectionResult.Error).message
            Text(text = text, color = color, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = onTest,
            enabled = !uiState.isTesting && uiState.password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (uiState.isTesting) "Connecting..." else "Verify Credentials")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Back") }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = onNext,
                enabled = uiState.testResult is ConnectionResult.Success,
                modifier = Modifier.weight(1f)
            ) { Text("Save") }
        }
    }
}
