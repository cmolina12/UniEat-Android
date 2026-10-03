package co.edu.uniandes.unieat.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import co.edu.uniandes.unieat.ui.theme.BrandHeader
import co.edu.uniandes.unieat.ui.theme.SolidButton
import co.edu.uniandes.unieat.ui.theme.SurfaceCard

@Composable
fun LoginScreen(
    vm: LoginViewModel,
    demo: Boolean,
    onSignedIn: () -> Unit,
) {
    val state by vm.state.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val hasError = state is LoginState.Error

    LaunchedEffect(state) {
        if (state is LoginState.Success) onSignedIn()
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandHeader("Ingresar")
        SurfaceCard {
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
                isError = hasError,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state is LoginState.Error) {
                Text((state as LoginState.Error).message, color = MaterialTheme.colorScheme.error)
            }
        }
        SolidButton(
            title = if (state is LoginState.Loading) "Ingresando…" else "Ingresar",
            onClick = { vm.signIn(email, password) },
            enabled = state !is LoginState.Loading,
        )
        if (demo) SolidButton("Entrar (demo)", onClick = onSignedIn)
    }
}

@Composable
fun ProfileScreen(
    vm: ProfileViewModel,
    onSignedOut: () -> Unit,
) {
    val state by vm.state.collectAsState()
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandHeader("Mi perfil")
        when (val current = state) {
            ProfileState.Loading -> CircularProgressIndicator()
            is ProfileState.Error -> SurfaceCard {
                Text(current.message)
                TextButton(onClick = vm::load) { Text("Reintentar") }
            }
            is ProfileState.Content -> SurfaceCard {
                Text(current.me.displayName, style = MaterialTheme.typography.headlineSmall)
                Text("Rol: ${current.me.role}")
                current.me.establishments.forEach {
                    Text("${it.establishmentName} · ${it.memberRole}")
                }
            }
        }
        SolidButton("Cerrar sesión", onClick = { vm.signOut(onSignedOut) })
    }
}
