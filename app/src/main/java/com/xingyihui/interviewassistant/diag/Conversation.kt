package com.xingyihui.interviewassistant.diag

/**
 * F3 多轮上下文：同一场面试里已经说过的"问题 → 你给过的口径 → 候选人真实回答"，
 * 生成追问话术时带入最近 2 轮，避免数字口径前后打架（文档铁则 1「逻辑自洽」+ 铁则 4「追问防御链」）。
 */
object Conversation {

    data class Turn(val question: String, var say: String = "", var answer: String = "")

    private const val MAX_TURNS = 6
    private val turns = ArrayList<Turn>()

    @Synchronized
    fun addAdvice(question: String, say: String) {
        val last = turns.lastOrNull()
        if (last != null && last.question == question) {
            last.say = say
        } else {
            turns.add(Turn(question, say = say))
            while (turns.size > MAX_TURNS) turns.removeAt(0)
        }
    }

    /** 停止答题时把候选人真实回答记到最近一条提问上（复盘/上下文都用它） */
    @Synchronized
    fun recordAnswer(answer: String) {
        turns.lastOrNull()?.answer = answer
    }

    @Synchronized
    fun clear() = turns.clear()

    @Synchronized
    fun size(): Int = turns.size

    /** 生成话术时注入的上下文（最近 2 轮，各截断，控制 token） */
    @Synchronized
    fun contextPrompt(): String {
        val recent = turns.takeLast(2).filter { it.question.isNotBlank() }
        if (recent.isEmpty()) return ""
        val sb = StringBuilder("\n\n【本场面试已发生的对话（保持口径一致，数字不要打架）】")
        for (t in recent) {
            sb.append("\n- 面试官问：${t.question.take(80)}")
            if (t.say.isNotBlank()) sb.append("\n  你已经给过的口径：${t.say.take(90)}")
            if (t.answer.isNotBlank()) sb.append("\n  候选人真实回答：${t.answer.take(90)}")
        }
        return sb.toString()
    }

    /** 复盘时用：整场对话摘要 */
    @Synchronized
    fun reportContext(): String {
        if (turns.isEmpty()) return ""
        val sb = StringBuilder("\n\n【本场面试对话记录】")
        turns.forEachIndexed { i, t ->
            sb.append("\n${i + 1}. 问：${t.question.take(60)}")
            if (t.answer.isNotBlank()) sb.append(" | 答：${t.answer.take(60)}")
        }
        return sb.toString()
    }
}
