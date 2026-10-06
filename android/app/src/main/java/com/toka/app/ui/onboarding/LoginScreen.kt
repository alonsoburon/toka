package com.toka.app.ui.onboarding

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.toka.app.BuildConfig
import com.toka.app.R
import com.toka.app.data.di.AppContainer
import com.toka.app.ui.theme.Pink
import com.toka.app.ui.theme.TextMuted
import com.toka.app.ui.theme.TextPrimary
import com.toka.app.ui.tokaViewModel
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    val viewModel = tokaViewModel { LoginViewModel(AppContainer.instance.authRepository) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🏠", fontSize = 72.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Toka",
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            color = Pink
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.login_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = TextMuted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(48.dp))

        if (state.loading) {
            CircularProgressIndicator(color = Pink)
        } else {
            Button(
                onClick = {
                    scope.launch {
                        viewModel.starting()
                        runCatching { requestGoogleIdToken(context) }
                            .onSuccess { viewModel.signIn(it) }
                            .onFailure { e ->
                                viewModel.credentialFailed(
                                    if (e is GetCredentialCancellationException) null
                                    else context.getString(R.string.login_error, e.message ?: "")
                                )
                            }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Pink)
            ) { Text(stringResource(R.string.login_google)) }

            // Solo en debug con emuladores (en release la condición es constante y R8 la elimina).
            if (BuildConfig.EMULATOR_HOST.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { viewModel.signInDev("ana@toka.dev", "Ana Dev") }) {
                    Text("Entrar como Ana (dev)")
                }
                OutlinedButton(onClick = { viewModel.signInDev("beto@toka.dev", "Beto Dev") }) {
                    Text("Entrar como Beto (dev)")
                }
            }
        }

        state.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

/** Abre el selector de cuentas de Google (Credential Manager) y devuelve el ID token. */
private suspend fun requestGoogleIdToken(context: Context): String {
    val option = GetSignInWithGoogleOption.Builder(context.getString(R.string.default_web_client_id)).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val credential = CredentialManager.create(context).getCredential(context, request).credential
    check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        "Credencial inesperada"
    }
    return GoogleIdTokenCredential.createFrom(credential.data).idToken
}
