package com.yeopjari.core.sim

import java.io.File

/**
 * 모든 시연 장면의 대본을 만든다.
 * `./gradlew :core:run --args="docs/demo-transcript.md"` 처럼 경로를 주면 UTF-8 파일로 저장한다.
 * 시연 영상 자막과 기획서의 '시뮬레이션 결과' 근거로 쓴다.
 */
fun main(args: Array<String>) {
    val text = buildString {
        appendLine("# 시연 장면 대본")
        appendLine()
        appendLine("`core` 판정 엔진에 시뮬레이션 장면을 넣었을 때 차가 하는 말입니다. 시각은 장면 시작 기준(분:초)입니다.")
        appendLine()
        for (scenario in Scenarios.all) {
            val result = Replayer.replay(scenario)
            appendLine("## ${scenario.name} — ${scenario.description}")
            appendLine()
            val lines = result.transcript()
            if (lines.isEmpty()) appendLine("- (말하지 않음)") else lines.forEach { appendLine("- $it") }
            appendLine()
            appendLine("판정: " + result.outcomes.joinToString { "${it.type} ${it.result}" }.ifEmpty { "없음" })
            appendLine()
            appendLine("주차 후 요약: ${result.summary}")
            appendLine()
        }
    }
    val path = args.firstOrNull()
    if (path == null) print(text) else File(path).apply { parentFile?.mkdirs() }.writeText(text, Charsets.UTF_8)
}
