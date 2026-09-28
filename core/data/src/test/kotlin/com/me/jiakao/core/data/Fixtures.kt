package com.me.jiakao.core.data

import android.app.Application
import androidx.room.Room
import com.me.jiakao.core.data.db.QuizDatabase
import com.me.jiakao.core.data.db.UserDatabase
import com.me.jiakao.core.model.MediaKind
import com.me.jiakao.core.model.MediaRef
import com.me.jiakao.core.model.Option
import com.me.jiakao.core.model.QType
import com.me.jiakao.core.model.Question
import org.robolectric.RuntimeEnvironment

/** 测试夹具与双内存库构建。 */
internal object Fixtures {

    val app: Application get() = RuntimeEnvironment.getApplication() as Application

    fun quizDb(): QuizDatabase = Room.inMemoryDatabaseBuilder(app, QuizDatabase::class.java).build()

    fun userDb(): UserDatabase = Room.inMemoryDatabaseBuilder(app, UserDatabase::class.java).build()

    fun question(
        id: String,
        subject: Int = 1,
        type: QType = QType.JUDGE,
        chapterId: String = "s1-c01",
        vehicles: List<String> = listOf("car"),
        tags: List<String> = emptyList(),
        stem: String = "题干：$id",
        options: List<Option> = listOf(Option("A", "正确"), Option("B", "错误")),
        answer: List<String> = listOf("A"),
        explain: String = "解析：$id",
        media: List<MediaRef> = emptyList(),
        rev: Int = 1,
    ): Question = Question(
        id = id,
        subject = subject,
        vehicles = vehicles,
        type = type,
        chapterId = chapterId,
        tags = tags,
        stem = stem,
        options = options,
        answer = answer,
        explain = explain,
        media = media,
        rev = rev,
    )

    private fun img(sha: String) = MediaRef(sha, "webp", MediaKind.IMAGE, 480, 480, 8123)
    private fun anim(sha: String) = MediaRef(sha, "webp", MediaKind.ANIM, 640, 360, 412345)

    /** 7 题标准测试集:s1 六题(分两章)+ s4 一题。 */
    fun standardQuestions(): List<Question> = listOf(
        question(
            id = "s1-000001",
            tags = listOf("标志", "禁令"),
            stem = "如图所示，这个标志的含义是禁止车辆停放。",
            media = listOf(img("a".repeat(64))),
        ),
        question(
            id = "s1-000002",
            type = QType.SINGLE,
            stem = "What does the speed limit sign mean? 请选择正确含义",
            options = listOf(
                Option("A", "最低限速"),
                Option("B", "最高限速"),
                Option("C", "解除限速"),
                Option("D", "建议速度"),
            ),
            answer = listOf("B"),
        ),
        question(
            id = "s1-000003",
            type = QType.MULTI,
            chapterId = "s1-c02",
            vehicles = listOf("car", "truck"),
            tags = listOf("法规"),
            stem = "下列哪些行为属于违法行为？",
            options = listOf(
                Option("A", "酒后驾驶"),
                Option("B", "按规定礼让行人"),
                Option("C", "驾车时手持拨打电话"),
                Option("D", "遵守限速"),
            ),
            answer = listOf("A", "C"),
        ),
        question(
            id = "s1-000004",
            chapterId = "s1-c02",
            media = listOf(img("b".repeat(64))),
        ),
        question(
            id = "s1-000005",
            type = QType.SINGLE,
            chapterId = "s1-c02",
            tags = listOf("动画"),
            stem = "如图动画所示，机动车应如何通行？",
            options = listOf(
                Option("A", "加速通过"),
                Option("B", "减速让行"),
                Option("C", "鸣笛通过"),
                Option("D", "停车等候"),
            ),
            answer = listOf("B"),
            media = listOf(anim("c".repeat(64))),
        ),
        question(
            id = "s1-000007",
            vehicles = listOf("truck"),
            chapterId = "s1-c03",
            stem = "货车专用题：总质量超过限值应如何处理？",
        ),
        question(
            id = "s4-000006",
            subject = 4,
            chapterId = "s4-c01",
            stem = "安全文明驾驶：夜间会车应在多少米外改用近光灯？",
        ),
    )

    val standardChapters = listOf(
        com.me.jiakao.core.model.Chapter("s1-c01", 1, "道路交通安全法律法规", 1),
        com.me.jiakao.core.model.Chapter("s1-c02", 1, "交通信号", 2),
        com.me.jiakao.core.model.Chapter("s1-c03", 1, "货车专项", 3),
        com.me.jiakao.core.model.Chapter("s4-c01", 4, "安全文明驾驶常识", 1),
    )

    /** 批量生成题目(id 序号连续,便于构造 3000 题基准)。 */
    fun bulkQuestions(count: Int, subject: Int = 1): List<Question> = (1..count).map { n ->
        val id = "s$subject-${n.toString().padStart(6, '0')}"
        val chapterId = "s$subject-c${String.format("%02d", (n - 1) % 10 + 1)}"
        val type = when (n % 3) {
            0 -> QType.MULTI
            1 -> QType.JUDGE
            else -> QType.SINGLE
        }
        question(
            id = id,
            subject = subject,
            type = type,
            chapterId = chapterId,
            tags = if (n % 5 == 0) listOf("标志") else emptyList(),
            media = if (n % 7 == 0) listOf(img("d".repeat(64))) else emptyList(),
            options = if (type == QType.JUDGE) {
                listOf(Option("A", "正确"), Option("B", "错误"))
            } else {
                (0 until 4).map { Option("${('A' + it)}", "选项${it + 1}") }
            },
            answer = if (type == QType.MULTI) listOf("A", "B") else listOf("A"),
        )
    }
}
