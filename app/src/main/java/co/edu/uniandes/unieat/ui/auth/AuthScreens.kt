package co.edu.uniandes.unieat.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import co.edu.uniandes.unieat.core.model.MeResponse
import co.edu.uniandes.unieat.data.auth.SupabaseAuthRepository
import co.edu.uniandes.unieat.ui.profile.roleName
import co.edu.uniandes.unieat.ui.theme.BrandHeader
import co.edu.uniandes.unieat.ui.theme.ChipRow
import co.edu.uniandes.unieat.ui.theme.DemoNotice
import co.edu.uniandes.unieat.ui.theme.Palette
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.Sticker
import co.edu.uniandes.unieat.ui.theme.SurfaceCard

@Composable
fun LoginScreen(
    vm: LoginViewModel,
    demo: Boolean,
    onSignedIn: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val offline by vm.offline.collectAsState()
    // The email and name survive rotation and process death; the password is dropped on purpose.
    var registering by rememberSaveable { mutableStateOf(false) }
    var displayName by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val hasError = state is LoginState.Error
    val loading = state is LoginState.Loading
    val submit = { if (registering) vm.signUp(displayName, email, password) else vm.signIn(email, password) }

    LaunchedEffect(state) {
        if (state is LoginState.Success) onSignedIn()
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandHeader(if (registering) "Crear cuenta" else "Ingresar")
        // Samuel — eventual connectivity: said before the student taps, not after a 10 s timeout.
        if (offline) {
            DemoNotice(
                "Sin conexión · puedes ir escribiendo; entrar y crear la cuenta se activan al volver la señal" +
                    if (demo) " (el modo demo sí funciona sin señal)" else "",
            )
        }
        ChipRow(
            options = listOf(false, true),
            isSelected = { it == registering },
            label = { if (it) "Crear cuenta" else "Entrar" },
            onSelect = {
                if (!loading && it != registering) {
                    registering = it
                    vm.resetMessage()
                }
            },
        )
        SurfaceCard {
            if (registering) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it.take(NAME_MAX) },
                    label = { Text("Nombre") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                    isError = hasError,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Correo") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                isError = hasError,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Contraseña") },
                supportingText = if (registering) {
                    { Text("Mínimo ${SupabaseAuthRepository.MIN_PASSWORD_LENGTH} caracteres") }
                } else {
                    null
                },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Ocultar" else "Mostrar")
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                isError = hasError,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state is LoginState.Error) {
                Text((state as LoginState.Error).message, color = MaterialTheme.colorScheme.error)
            }
            if (state is LoginState.ConfirmEmail) {
                Text("Revisa tu correo para confirmar la cuenta y luego inicia sesión.", color = Palette.Ink)
            }
        }
        SolidButton(
            title = when {
                loading -> if (registering) "Creando cuenta…" else "Ingresando…"
                registering -> "Crear cuenta"
                else -> "Ingresar"
            },
            onClick = submit,
            enabled = !loading && !offline,
        )
        if (registering) {
            Text(
                "Las cuentas nuevas son de estudiante. Para publicar menús, un administrador debe aprobarte como restaurante.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (demo) SolidButton("Entrar (demo)", onClick = onSignedIn)
    }
}

private const val NAME_MAX = 80

@Composable
fun ProfileScreen(
    vm: ProfileViewModel,
    onSignedOut: () -> Unit,
    /** What each role manages here: requests for admins, establishments for everyone else (as on iOS). */
    roleSections: @Composable (MeResponse) -> Unit = {},
) {
    val state by vm.state.collectAsState()
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandHeader("Mi perfil")
        when (val current = state) {
            ProfileState.Loading -> CircularProgressIndicator()
            is ProfileState.Error -> SurfaceCard {
                Text(current.message)
                TextButton(onClick = vm::load) { Text("Reintentar") }
            }
            is ProfileState.Content -> {
                SurfaceCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(current.me.displayName, style = MaterialTheme.typography.headlineSmall)
                        Sticker(roleName(current.me.role), color = Palette.Green)
                        Text(
                            "Las preferencias de presupuesto, dieta, tiempo y zona se conservan al volver a la lista de menús.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                roleSections(current.me)
            }
        }
        SolidButton("Cerrar sesión", onClick = { vm.signOut(onSignedOut) }, color = Palette.Yellow)
    }
}
