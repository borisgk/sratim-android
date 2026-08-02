package com.ru9n.sratim.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ru9n.sratim.core.viewmodel.ConnectionResult
import com.ru9n.sratim.core.viewmodel.SetupUiState
import com.ru9n.sratim.core.viewmodel.SetupViewModel

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SetupScreen(
    viewModel: SetupViewModel,
    onComplete: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.isComplete) {
        if (uiState.isComplete) {
            onComplete()
        }
    }

    // Wrap in standard M3 MaterialTheme to provide colors to OutlinedTextField
    androidx.compose.material3.MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            surface = Color.Transparent, // Let the TV Surface handle background
            onSurface = Color.White
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Welcome to Sratim",
                style = MaterialTheme.typography.displayMedium
            )
            Spacer(modifier = Modifier.height(24.dp))

            when (uiState.currentStep) {
                0 -> ServerStep(
                    value = uiState.serverUrl,
                    onValueChange = viewModel::onServerUrlChange,
                    onNext = viewModel::nextStep
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
                text = "v${com.ru9n.sratim.BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ServerStep(
    value: String,
    onValueChange: (String) -> Unit,
    onNext: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "Step 1: Enter Server Address", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { androidx.compose.material3.Text("Server URL (e.g. 192.168.1.10)") },
            modifier = Modifier.fillMaxWidth(0.6f),
            singleLine = true,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.White
            )
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onNext, enabled = value.isNotBlank()) {
            Text("Next")
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UsernameStep(
    value: String,
    onValueChange: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "Step 2: Enter Username", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { androidx.compose.material3.Text("Username") },
            modifier = Modifier.fillMaxWidth(0.6f),
            singleLine = true,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.White
            )
        )
        Spacer(modifier = Modifier.height(24.dp))
        Row {
            Button(onClick = onBack) { Text("Back") }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onNext, enabled = value.isNotBlank()) { Text("Next") }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PasswordStep(
    uiState: SetupUiState,
    onValueChange: (String) -> Unit,
    onTest: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "Step 3: Enter Password", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = uiState.password,
            onValueChange = onValueChange,
            label = { androidx.compose.material3.Text("Password") },
            modifier = Modifier.fillMaxWidth(0.6f),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.White
            )
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (uiState.testResult != null) {
            val color = if (uiState.testResult is ConnectionResult.Success) Color.Green else Color.Red
            val text = if (uiState.testResult is ConnectionResult.Success) "Login Successful!" else (uiState.testResult as ConnectionResult.Error).message
            Text(text = text, color = color, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
        }

        Row {
            Button(onClick = onBack) { Text("Back") }
            Spacer(modifier = Modifier.width(16.dp))
            Button(onClick = onTest, enabled = !uiState.isTesting) {
                Text(if (uiState.isTesting) "Verifying..." else "Verify Login")
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = onNext, 
                enabled = uiState.password.isNotBlank() && uiState.testResult is ConnectionResult.Success
            ) { Text("Finish") }
        }
    }
}
