package com.me.jiakao.fake

import com.me.jiakao.core.model.ExamResult
import com.me.jiakao.core.model.ExamRules
import com.me.jiakao.core.model.ExamService
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.Scope
import javax.inject.Inject
import javax.inject.Singleton

/** 假考试服务:组卷/评分语义与合同 §4 一致(多选错选漏选均不得分) */
@Singleton
class FakeExamService @Inject constructor(
    private val quiz: FakeQuizRepository,
) : ExamService {

    /** 测试可覆盖考试规则(如缩短倒计时) */
    var overrideRules: ExamRules? = null

    /** 测试可指定试卷(题目 id 列表),保证用例确定性 */
    var paperOverride: List<String>? = null

    override fun rules(subject: Int): ExamRules = overrideRules ?: ExamRules.of(subject)

    override suspend fun buildPaper(scope: Scope): List<String> =
        paperOverride ?: quiz.ids(scope).shuffled().take(rules(scope.subject).questionCount)

    override fun grade(
        subject: Int,
        questions: List<Question>,
        answers: Map<String, List<String>>,
        startedAt: Long,
        durationSec: Int,
    ): ExamResult {
        val rule = rules(subject)
        var score = 0
        val wrong = mutableListOf<String>()
        questions.forEach { q ->
            val chosen = answers[q.id].orEmpty()
            val ok = chosen.isNotEmpty() && chosen.toSet() == q.answer.toSet()
            if (ok) score += rule.pointsPerQuestion else wrong += q.id
        }
        return ExamResult(
            id = 0L,
            subject = subject,
            startedAt = startedAt,
            durationSec = durationSec,
            score = score,
            passed = score >= rule.passScore,
            questionIds = questions.map { it.id },
            wrongIds = wrong,
        )
    }
}
