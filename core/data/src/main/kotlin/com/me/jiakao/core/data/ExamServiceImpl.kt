package com.me.jiakao.core.data

import com.me.jiakao.core.model.ExamResult
import com.me.jiakao.core.model.ExamRules
import com.me.jiakao.core.model.ExamService
import com.me.jiakao.core.model.Question
import com.me.jiakao.core.model.Scope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ExamService] 实现:按合同 §4 规则组卷与计分。
 *
 * - 组卷:Scope 内随机抽题,不足 [ExamRules.questionCount] 时全取。
 * - 计分:未作答按错;多选题错选/漏选均不得分(集合完全相等才判对)。
 * - [ExamResult.id] 在 grade 阶段为 0(未入库),经 UserRepository.saveExam 落库后返回真实 id。
 */
@Singleton
internal class ExamServiceImpl @Inject constructor(
    private val quizRepository: QuizRepositoryImpl,
) : ExamService {

    override fun rules(subject: Int): ExamRules = ExamRules.of(subject)

    override suspend fun buildPaper(scope: Scope): List<String> {
        val count = rules(scope.subject).questionCount
        return quizRepository.ids(scope).shuffled().take(count)
    }

    override fun grade(
        subject: Int,
        questions: List<Question>,
        answers: Map<String, List<String>>,
        startedAt: Long,
        durationSec: Int,
    ): ExamResult {
        val rule = rules(subject)
        var score = 0
        val wrongIds = ArrayList<String>(questions.size)
        for (question in questions) {
            val chosen = answers[question.id]
            val isCorrect = chosen != null && chosen.toSet() == question.answer.toSet()
            if (isCorrect) {
                score += rule.pointsPerQuestion
            } else {
                wrongIds.add(question.id)
            }
        }
        return ExamResult(
            id = 0,
            subject = subject,
            startedAt = startedAt,
            durationSec = durationSec,
            score = score,
            passed = score >= rule.passScore,
            questionIds = questions.map { it.id },
            wrongIds = wrongIds,
        )
    }
}
