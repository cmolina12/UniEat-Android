package co.edu.uniandes.unieat.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import co.edu.uniandes.unieat.AppContainer
import co.edu.uniandes.unieat.data.auth.AuthState
import co.edu.uniandes.unieat.ui.auth.LoginScreen
import co.edu.uniandes.unieat.ui.auth.LoginViewModel
import co.edu.uniandes.unieat.ui.auth.ProfileScreen
import co.edu.uniandes.unieat.ui.auth.ProfileViewModel
import co.edu.uniandes.unieat.ui.detail.MenuDetailScreen
import co.edu.uniandes.unieat.ui.feed.FeedScreen
import co.edu.uniandes.unieat.ui.feed.RecommendScreen
import co.edu.uniandes.unieat.ui.performance.PerformanceScreen
import co.edu.uniandes.unieat.ui.screens.PublishScreen
import co.edu.uniandes.unieat.ui.theme.Palette

/** App navigation graph: Login → tabs (Feed, Recommend, Publish, Performance, Profile) → Detail. */
@Composable
fun UniEatNavHost(container: AppContainer, navController: NavHostController = rememberNavController()) {
    val authState by container.sessionManager.state.collectAsStateWithLifecycle()
    val shell: AppShellViewModel = viewModel(
        factory = simpleFactory {
            AppShellViewModel(container.sessionManager, container.profileRepository, container.usesFakeData)
        },
    )
    val profileState by shell.profileState.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val role = (profileState as? AppProfileState.Content)?.profile?.role
    val visibleTabs = Tab.entries.filter { it.allowedFor(role, container.usesFakeData) }
    val showTabs = visibleTabs.any { destination.isOn(it) }

    if (authState is AuthState.Loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // The graph start is chosen once. Later auth changes are handled by explicit navigation callbacks.
    val initialDestination: Any = remember {
        if (authState is AuthState.SignedIn || container.usesFakeData) Feed else Login
    }

    Scaffold(
        containerColor = Palette.Cream,
        topBar = {
            if (profileState is AppProfileState.Error && authState is AuthState.SignedIn) {
                Surface {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("No se pudo cargar tu rol.", modifier = Modifier.weight(1f))
                        TextButton(onClick = shell::retryProfile) { Text("Reintentar") }
                    }
                }
            }
        },
        bottomBar = { if (showTabs) TabBar(navController, destination, visibleTabs) },
    ) { padding ->
        NavHost(navController, startDestination = initialDestination, modifier = Modifier.padding(padding)) {
            composable<Login> {
                val vm: LoginViewModel = viewModel(factory = simpleFactory { LoginViewModel(container.sessionManager) })
                LoginScreen(vm = vm, demo = container.usesFakeData, onSignedIn = {
                    navController.navigate(Feed) { popUpTo<Login> { inclusive = true } }
                })
            }
            composable<Feed> {
                FeedScreen(
                    onOpenMenu = { navController.navigate(Detail(it)) },
                    // Expired session: back to the login, clearing the whole back stack.
                    onSessionExpired = { navController.navigate(Login) { popUpTo(0) { inclusive = true } } },
                )
            }
            composable<Detail> {
                MenuDetailScreen(
                    menuId = it.toRoute<Detail>().menuId,
                    onBack = navController::popBackStack,
                    // Swaps the current detail for another one (debug fixture picker).
                    onOpenMenu = { id -> navController.navigate(Detail(id)) { popUpTo<Detail> { inclusive = true } } },
                    onSessionExpired = { navController.navigate(Login) { popUpTo(0) { inclusive = true } } },
                )
            }
            composable<Recommend> {
                RecommendScreen(
                    onOpenMenu = { navController.navigate(Detail(it)) },
                    onSessionExpired = { navController.navigate(Login) { popUpTo(0) { inclusive = true } } },
                )
            }
            composable<Publish> { PublishScreen() }
            composable<Performance> {
                PerformanceScreen(
                    onSessionExpired = { navController.navigate(Login) { popUpTo(0) { inclusive = true } } },
                )
            }
            composable<Profile> {
                val vm: ProfileViewModel = viewModel(factory = simpleFactory { ProfileViewModel(container.profileRepository, container.sessionManager) })
                ProfileScreen(vm = vm, onSignedOut = {
                    navController.navigate(Login) { popUpTo(0) { inclusive = true } }
                })
            }
        }
    }
}

@Composable
private fun TabBar(navController: NavHostController, destination: NavDestination?, tabs: List<Tab>) {
    NavigationBar(containerColor = Palette.Paper) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = destination.isOn(tab),
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = Palette.Yellow),
            )
        }
    }
}

private fun NavDestination?.isOn(tab: Tab): Boolean = this?.hasRoute(tab.route::class) == true

private fun <VM : ViewModel> simpleFactory(builder: () -> VM) = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = builder() as T
}

/** Context-aware UX: publishing is restaurant-only; performance is restaurant/admin. Backend authorization remains authoritative. */
private fun Tab.allowedFor(role: String?, demo: Boolean): Boolean = when (this) {
    Tab.PUBLISH -> demo || role == "restaurant"
    Tab.PERFORMANCE -> demo || role == "restaurant" || role == "admin"
    else -> true
}
