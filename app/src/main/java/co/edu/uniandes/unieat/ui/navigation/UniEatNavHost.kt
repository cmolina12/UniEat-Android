package co.edu.uniandes.unieat.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import co.edu.uniandes.unieat.ui.screens.DetailScreen
import co.edu.uniandes.unieat.ui.screens.FeedScreen
import co.edu.uniandes.unieat.ui.screens.LoginScreen
import co.edu.uniandes.unieat.ui.screens.PerformanceScreen
import co.edu.uniandes.unieat.ui.screens.ProfileScreen
import co.edu.uniandes.unieat.ui.screens.PublishScreen
import co.edu.uniandes.unieat.ui.screens.RecommendScreen
import co.edu.uniandes.unieat.ui.theme.Palette

/** App navigation graph: Login → tabs (Feed, Recommend, Publish, Performance, Profile) → Detail. */
@Composable
fun UniEatNavHost(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val showTabs = Tab.entries.any { destination.isOn(it) }

    Scaffold(
        containerColor = Palette.Cream,
        bottomBar = { if (showTabs) TabBar(navController, destination) },
    ) { padding ->
        NavHost(navController, startDestination = Login, modifier = Modifier.padding(padding)) {
            composable<Login> {
                LoginScreen(onSignedIn = {
                    navController.navigate(Feed) { popUpTo<Login> { inclusive = true } }
                })
            }
            composable<Feed> { FeedScreen(onOpenMenu = { navController.navigate(Detail(it)) }) }
            composable<Detail> { DetailScreen(menuId = it.toRoute<Detail>().menuId, onBack = navController::popBackStack) }
            composable<Recommend> { RecommendScreen(onOpenMenu = { navController.navigate(Detail(it)) }) }
            composable<Publish> { PublishScreen() }
            composable<Performance> { PerformanceScreen() }
            composable<Profile> {
                ProfileScreen(onSignOut = {
                    navController.navigate(Login) { popUpTo(0) { inclusive = true } }
                })
            }
        }
    }
}

@Composable
private fun TabBar(navController: NavHostController, destination: NavDestination?) {
    NavigationBar(containerColor = Palette.Paper) {
        Tab.entries.forEach { tab ->
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
