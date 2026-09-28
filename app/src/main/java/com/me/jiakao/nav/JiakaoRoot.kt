@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.me.jiakao.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.me.jiakao.BuildConfig
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.me.jiakao.ui.screens.chapter.ChapterListScreen
import com.me.jiakao.ui.screens.exam.ExamResultScreen
import com.me.jiakao.ui.screens.exam.ExamScreen
import com.me.jiakao.ui.screens.favorites.FavoritesScreen
import com.me.jiakao.ui.screens.home.HomeScreen
import com.me.jiakao.ui.screens.practice.PracticeScreen
import com.me.jiakao.ui.screens.search.SearchScreen
import com.me.jiakao.ui.screens.settings.SettingsScreen
import com.me.jiakao.ui.screens.stats.StatsScreen
import com.me.jiakao.ui.screens.wrong.WrongBookScreen

/**
 * 单 Activity 导航根:统一的轻滑动 + 淡入过渡,整体体验一致。
 * debug/基准构建开启 testTagsAsResourceId,便于 Macrobenchmark 用 UiAutomator 定位。
 */
@Composable
fun JiakaoRoot() {
    val navController = rememberNavController()
    val rootModifier = if (BuildConfig.DEBUG) {
        Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
    } else {
        Modifier.fillMaxSize()
    }
    Surface(
        modifier = rootModifier,
        color = androidx.compose.material3.MaterialTheme.colorScheme.background,
    ) {
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            enterTransition = { fadeIn(tween(240)) + slideInHorizontally(tween(260)) { it / 10 } },
            exitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(240)) { -it / 14 } },
            popEnterTransition = { fadeIn(tween(240)) + slideInHorizontally(tween(260)) { -it / 10 } },
            popExitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(240)) { it / 14 } },
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    onOpenPractice = { mode, subject, chapterId, special ->
                        navController.navigate(PracticeRoute(mode, subject, chapterId, special))
                    },
                    onOpenChapters = { subject -> navController.navigate(ChapterListRoute(subject)) },
                    onOpenExam = { subject -> navController.navigate(ExamRoute(subject)) },
                    onOpenWrongBook = { navController.navigate(WrongBookRoute) },
                    onOpenFavorites = { navController.navigate(FavoritesRoute) },
                    onOpenStats = { navController.navigate(StatsRoute) },
                    onOpenSearch = { navController.navigate(SearchRoute) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable<ChapterListRoute> { entry ->
                val route = entry.toRoute<ChapterListRoute>()
                ChapterListScreen(
                    subject = route.subject,
                    onBack = { navController.popBackStack() },
                    onOpenPractice = { mode, subject, chapterId, special ->
                        navController.navigate(PracticeRoute(mode, subject, chapterId, special))
                    },
                )
            }
            composable<PracticeRoute> { entry ->
                val route = entry.toRoute<PracticeRoute>()
                PracticeScreen(
                    onBack = { navController.popBackStack() },
                )
            }
            composable<ExamRoute> {
                ExamScreen(
                    onFinished = { examId ->
                        navController.navigate(ExamResultRoute(examId)) {
                            popUpTo<ExamRoute> { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable<ExamResultRoute> { entry ->
                val route = entry.toRoute<ExamResultRoute>()
                ExamResultScreen(
                    examId = route.examId,
                    onBack = { navController.popBackStack() },
                    onReviewWrong = { subject ->
                        navController.navigate(PracticeRoute("WRONG", subject)) {
                            popUpTo(HomeRoute)
                        }
                    },
                    onBackHome = {
                        navController.popBackStack(HomeRoute, inclusive = false)
                    },
                )
            }
            composable<StatsRoute> {
                StatsScreen(onBack = { navController.popBackStack() })
            }
            composable<SearchRoute> {
                SearchScreen(onBack = { navController.popBackStack() })
            }
            composable<WrongBookRoute> {
                WrongBookScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPractice = { mode, subject, recite ->
                        navController.navigate(PracticeRoute(mode, subject, recite = recite))
                    },
                )
            }
            composable<FavoritesRoute> {
                FavoritesScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPractice = { mode, subject, recite ->
                        navController.navigate(PracticeRoute(mode, subject, recite = recite))
                    },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

