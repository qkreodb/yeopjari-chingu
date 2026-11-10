package com.yeopjari.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yeopjari.app.CoachController
import com.yeopjari.app.SignalSource
import com.yeopjari.app.UiState
import com.yeopjari.core.Phrases
import com.yeopjari.core.SpotResult
import com.yeopjari.core.TurnSignal
import com.yeopjari.core.Utterance

private val Accent = Color(0xFF7FB2FF)
private val Warn = Color(0xFFFFC56B)

@Composable
fun YeopjariTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Color(0xFF111418)), content = content)
}

@Composable
fun MainScreen(state: UiState, controller: CoachController) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val driving = !state.simulation && (state.speedKmh ?: 0.0) > 5.0
        if (driving) {
            // 주행 중: 작은 안내 카드 하나만(운전자 주의 분산 기준)
            Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.TopStart) {
                NextSpotCard(state, Modifier.fillMaxWidth(0.4f))
            }
        } else {
            Row(Modifier.fillMaxSize().padding(40.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Header(state)
                    NextSpotCard(state, Modifier.fillMaxWidth())
                    SpokenLog(state.spoken, Modifier.weight(1f))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    SimulationPanel(state, controller)
                    TripPanel(state, controller)
                }
            }
        }
        if (!state.disclaimerAccepted) {
            AlertDialog(
                onDismissRequest = {},
                confirmButton = { TextButton(onClick = controller::acceptDisclaimer) { Text("확인했어요", fontSize = 22.sp) } },
                title = { Text("옆자리 친구", fontSize = 28.sp) },
                text = { Text(Phrases.DISCLAIMER, fontSize = 22.sp) },
            )
        }
    }
}

@Composable
private fun Header(state: UiState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("옆자리 친구", fontSize = 36.sp, fontWeight = FontWeight.Bold)
        if (state.simulation) Badge("SIMULATION", Warn)
        Text(
            "내비 ${if (state.naviConnected) "연결됨" else "미연결"} · 음성 ${state.ttsEngine}",
            fontSize = 18.sp, color = Color.Gray,
        )
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        modifier = Modifier.border(2.dp, color, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
        color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun NextSpotCard(state: UiState, modifier: Modifier) {
    Column(
        modifier.background(Color(0xFF1C2128), RoundedCornerShape(16.dp)).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("다음 어려운 지점", fontSize = 20.sp, color = Color.Gray)
        val spot = state.nextSpot
        if (spot == null) {
            Text("당분간 없어요", fontSize = 34.sp)
        } else {
            Text(spot, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            state.nextSpotDistanceM?.let { Text(distanceText(it), fontSize = 30.sp, color = Accent) }
        }
    }
}

private fun distanceText(m: Int) = if (m >= 1_000) "%.1fkm".format(m / 1000.0) else "${m}m"

@Composable
private fun SpokenLog(spoken: List<Utterance>, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text("차가 한 말", fontSize = 20.sp, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(spoken.reversed()) { Text("“${it.text}”", fontSize = 24.sp) }
        }
    }
}

@Composable
private fun SimulationPanel(state: UiState, controller: CoachController) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, Color(0xFF3A414B), RoundedCornerShape(16.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("시연 패널", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "회전 안내는 시뮬레이션 경로(오른쪽 합류 → 좌회전 → 오른쪽 IC)로, 깜빡이·브레이크·조향은 " +
                "Virtual Console 의 실제 신호로 판정합니다.",
            fontSize = 18.sp, color = Color.Gray,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.simulation) {
                Button(onClick = controller::stopSimulation) { Text("시연 끝내기", fontSize = 22.sp) }
            } else {
                Button(onClick = controller::startSimulation) { Text("합류 시연 시작", fontSize = 22.sp) }
            }
            OutlinedButton(onClick = controller::simulateHarshBrake) { Text("급제동", fontSize = 22.sp) }
        }
        val signalText = when (state.signal) {
            TurnSignal.LEFT -> "왼쪽"
            TurnSignal.RIGHT -> "오른쪽"
            TurnSignal.OFF -> "꺼짐"
            null -> "읽을 수 없음"
        }
        val source = if (state.signalSource == SignalSource.PANEL) "패널" else "Virtual Console"
        Text("깜빡이: $signalText ($source)", fontSize = 22.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SignalButton("왼쪽", state.signalSource == SignalSource.PANEL && state.signal == TurnSignal.LEFT) {
                controller.setPanelSignal(TurnSignal.LEFT)
            }
            SignalButton("끄기", state.signalSource == SignalSource.PANEL && state.signal == TurnSignal.OFF) {
                controller.setPanelSignal(TurnSignal.OFF)
            }
            SignalButton("오른쪽", state.signalSource == SignalSource.PANEL && state.signal == TurnSignal.RIGHT) {
                controller.setPanelSignal(TurnSignal.RIGHT)
            }
            SignalButton("차량 신호 따르기", state.signalSource == SignalSource.VEHICLE) { controller.setPanelSignal(null) }
        }
    }
}

@Composable
private fun SignalButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label, fontSize = 20.sp) }
    } else {
        OutlinedButton(onClick = onClick, colors = ButtonDefaults.outlinedButtonColors()) { Text(label, fontSize = 20.sp) }
    }
}

@Composable
private fun TripPanel(state: UiState, controller: CoachController) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, Color(0xFF3A414B), RoundedCornerShape(16.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("내 기록", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (state.summary.isNotEmpty()) Text(state.summary, fontSize = 22.sp)
        state.outcomes.takeLast(4).forEach {
            Text("· ${Phrases.typeName(it.type)}: ${resultText(it.result, it.signalConfirmed)}", fontSize = 20.sp)
        }
        Text("연습 단계 ${state.ladderStage} · ${state.ladderLabel}", fontSize = 22.sp, color = Accent)
        state.ladderGoal?.let { Text(it, fontSize = 20.sp, color = Color.Gray) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("익숙해지면 안내 줄이기", fontSize = 20.sp)
            Switch(checked = state.fadingEnabled, onCheckedChange = controller::setFading)
            TextButton(onClick = controller::resetFading) { Text("처음처럼 안내", fontSize = 20.sp) }
        }
        OutlinedButton(onClick = controller::finishTrip) { Text("주행 끝내기", fontSize = 20.sp) }
    }
}

private fun resultText(result: SpotResult, signalConfirmed: Boolean) = when (result) {
    SpotResult.PASSED -> if (signalConfirmed) "지나옴 (깜빡이 미리 켬)" else "지나옴"
    SpotResult.PASSED_NO_SIGNAL -> "지나옴 (깜빡이 확인 안 됨)"
    SpotResult.MISSED -> "다른 길로 감"
    SpotResult.UNCERTAIN -> "기록만 남김"
}
