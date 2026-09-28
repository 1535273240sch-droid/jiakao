package com.me.jiakao.nav

import com.me.jiakao.core.model.QFilter
import com.me.jiakao.core.model.QType
import kotlinx.serialization.Serializable

/**
 * 类型安全路由(Navigation-Compose 2.8 + kotlinx.serialization)。
 * PracticeMode / QType 在 :core:model 中无法添加 @Serializable,故以 String 传参。
 */
@Serializable
data object HomeRoute

@Serializable
data class ChapterListRoute(val subject: Int)

/** @param mode PracticeMode.name;@param special 专项筛选:media|anim|judge|single|multi;@param recite 直达背题模式 */
@Serializable
data class PracticeRoute(
    val mode: String,
    val subject: Int,
    val chapterId: String? = null,
    val special: String? = null,
    val recite: Boolean = false,
)

@Serializable
data class ExamRoute(val subject: Int)

@Serializable
data class ExamResultRoute(val examId: Long)

@Serializable
data object StatsRoute

@Serializable
data object SearchRoute

@Serializable
data object WrongBookRoute

@Serializable
data object FavoritesRoute

@Serializable
data object SettingsRoute

/** 专项练习入口 → 合同 QFilter 的映射 */
fun specialFilter(special: String?): QFilter? = when (special) {
    "media" -> QFilter.HasMedia
    "anim" -> QFilter.HasAnim
    "judge" -> QFilter.OfType(QType.JUDGE)
    "single" -> QFilter.OfType(QType.SINGLE)
    "multi" -> QFilter.OfType(QType.MULTI)
    else -> null
}

/** 专项入口的展示名 */
fun specialLabel(special: String?): String = when (special) {
    "media" -> "有图题"
    "anim" -> "动图题"
    "judge" -> "判断题"
    "single" -> "单选题"
    "multi" -> "多选题"
    else -> "专项练习"
}
