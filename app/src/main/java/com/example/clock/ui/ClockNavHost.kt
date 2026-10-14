package com.example.clock.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.clock.ui.edit.AlarmEditScreen
import com.example.clock.ui.list.AlarmListScreen

private const val ROUTE_LIST = "list"
private const val ROUTE_EDIT = "edit"
private const val ARG_ALARM_ID = "alarmId"

/** 新建闹钟时用的哨兵值：Room 的自增 id 从 1 开始，0 永远不会是真 id。 */
const val NEW_ALARM_ID = 0L

@Composable
fun ClockNavHost() {
    val navController = rememberNavController()

    NotificationPermissionGate()

    NavHost(navController = navController, startDestination = ROUTE_LIST) {
        composable(ROUTE_LIST) {
            AlarmListScreen(
                onAddAlarm = { navController.navigate("$ROUTE_EDIT/$NEW_ALARM_ID") },
                onEditAlarm = { id -> navController.navigate("$ROUTE_EDIT/$id") },
            )
        }

        composable(
            route = "$ROUTE_EDIT/{$ARG_ALARM_ID}",
            arguments = listOf(navArgument(ARG_ALARM_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            val rawId = backStackEntry.arguments?.getLong(ARG_ALARM_ID) ?: NEW_ALARM_ID
            AlarmEditScreen(
                alarmId = rawId.takeIf { it != NEW_ALARM_ID },
                onDone = { navController.popBackStack() },
            )
        }
    }
}
