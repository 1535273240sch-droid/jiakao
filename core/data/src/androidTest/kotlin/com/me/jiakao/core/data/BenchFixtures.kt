package com.me.jiakao.core.data

import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.Option
import com.me.jiakao.core.model.QType
import com.me.jiakao.core.model.Question

/** androidTest 基准夹具(与 src/test 的 Fixtures 隔离,不依赖 Robolectric)。 */
internal object BenchFixtures {

    fun bulkQuestions(count: Int, subject: Int = 1): List<Question> = (1..count).map { n ->
        val id = "s$subject-${n.toString().padStart(6, '0')}"
        val chapterId = "s$subject-c${String.format("%02d", (n - 1) % 10 + 1)}"
        val type = when (n % 3) {
            0 -> QType.MULTI
            1 -> QType.JUDGE
            else -> QType.SINGLE
        }
        Question(
            id = id,
            subject = subject,
            vehicles = listOf("car"),
            type = type,
            chapterId = chapterId,
            tags = if (n % 5 == 0) listOf("标志") else emptyList(),
            stem = "基准题干第 $n 题",
            options = if (type == QType.JUDGE) {
                listOf(Option("A", "正确"), Option("B", "错误"))
            } else {
                (0 until 4).map { Option("${('A' + it)}", "选项${it + 1}") }
            },
            answer = if (type == QType.MULTI) listOf("A", "B") else listOf("A"),
            explain = "解析 $n",
            media = if (n % 7 == 0) {
                listOf(MediaRef("f".repeat(64), "webp", MediaKind.IMAGE, 480, 480, 8123))
            } else {
                emptyList()
            },
            rev = 1,
        )
    }
}
