package app.keeper.car.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keeper.car.CarUi
import app.keeper.car.CarViewModel
import app.keeper.car.PadPurpose
import app.keeper.core.Driver
import app.keeper.core.Gear
import app.keeper.core.GuardMode
import app.keeper.core.Phase
import kotlinx.coroutines.delay

@Composable
fun CarApp(vm: CarViewModel, onFingerprint: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var showValet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    KeeperTheme {
        Box(Modifier.fillMaxSize().background(K.Bg)) {
            Dashboard(ui, vm, onValet = { showValet = true }, onSettings = { showSettings = true })

            if (ui.warningLights) WarningGlow()

            AnimatedVisibility(ui.lockVisible, enter = fadeIn(), exit = fadeOut()) {
                LockOverlay(ui, vm, onFingerprint)
            }
            ui.pad?.let { PinPadDialog(ui, it, vm, onFingerprint) }
            if (showValet) ValetDialog(ui, onStart = { m, r -> vm.startValet(m, r); showValet = false }, onEnd = { vm.endValet(); showValet = false }, onClose = { showValet = false })
            ui.issuedValetPin?.let { ValetPinDialog(it, ui) { vm.dismissValetPin() } }
            ui.valetReport?.let { InfoDialog("발레 운행 리포트", it) { vm.dismissReport() } }
            if (showSettings) SettingsDialog(ui, vm) { showSettings = false }
            ui.toast?.let { msg ->
                LaunchedEffect(msg) { delay(2200); vm.clearToast() }
                Box(Modifier.fillMaxSize().padding(bottom = 36.dp), contentAlignment = Alignment.BottomCenter) {
                    Text(msg, color = K.Text, fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(24.dp)).background(K.Surface2).padding(horizontal = 24.dp, vertical = 12.dp))
                }
            }
        }
    }
}

// =================================================================== 대시보드

@Composable
private fun Dashboard(ui: CarUi, vm: CarViewModel, onValet: () -> Unit, onSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(28.dp)) {
        // 상단 바
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShieldIcon(K.Blue, 40.dp)
            Spacer(Modifier.width(12.dp))
            Text("KEEPER", color = K.Text, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.width(10.dp))
            Text("출발 잠금", color = K.Sub, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            Chip(if (ui.simulated) "시연 모드" else "Pleos 차량 연결됨", if (ui.simulated) K.Amber else K.Green)
            Spacer(Modifier.width(10.dp))
            SmallButton("설정", K.Surface2, onClick = onSettings)
        }
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            // 왼쪽: 상태와 주요 버튼
            Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                StatusCard(ui)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val armed = ui.guard.mode != GuardMode.OFF
                    BigButton(
                        title = if (armed) "출발 잠금 끄기" else "출발 잠금 켜기",
                        sub = if (armed) "차주 비밀번호·지문 확인" else "내리면 자동으로 잠깁니다",
                        color = if (armed) K.Surface2 else K.Blue, modifier = Modifier.weight(1f),
                    ) { if (armed) vm.requestDisarm() else vm.armFromCar() }
                    BigButton(
                        title = if (ui.guard.mode == GuardMode.VALET) "발레 관리" else "발레 모드",
                        sub = "임시 비밀번호 · 시간 · 반경 제한", color = K.Purple.copy(alpha = .85f), modifier = Modifier.weight(1f),
                    ) { onValet() }
                }
                AlertPanel(ui, Modifier.weight(1f))
            }
            // 오른쪽: 차량 신호 / 시연 패널 / 안전 원칙
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SignalPanel(ui)
                if (ui.simulated) SimPanel(ui, vm)
                SafetyNote()
            }
        }
    }
}

@Composable
private fun StatusCard(ui: CarUi) {
    val g = ui.guard
    val (color, title, desc) = when {
        g.phase == Phase.ALARM -> Triple(K.Red, "무단 운행 의심", "인증 없이 기어가 변경되었습니다. 차주 스마트폰으로 알림과 위치를 보내고 있습니다.\n안전을 위해 주행을 방해하는 제어는 하지 않습니다.")
        g.phase == Phase.LOCKOUT -> Triple(K.Red, "입력 제한 중", "비밀번호가 여러 번 틀려 ${ui.lockoutSec}초 동안 입력할 수 없습니다. 차주에게 알림을 보냈습니다.")
        g.phase == Phase.UNLOCKED && g.sessionDriver != null -> Triple(K.Green, "${g.sessionDriver?.label} 인증됨", when (g.sessionDriver) {
            Driver.VALET -> "발레 기사 운행 중 — 남은 시간 ${remainMin(g.valet?.expiresAt)}분, 반경 ${g.valet?.radiusMeters}m"
            else -> if (g.mode == GuardMode.ARMED) "주차 후 내리면 출발 잠금이 다시 켜집니다." else "인증된 운전자입니다."
        })
        g.mode == GuardMode.VALET -> Triple(K.Purple, "발레 모드", "임시 비밀번호로만 출발할 수 있습니다. 남은 시간 ${remainMin(g.valet?.expiresAt)}분 · 반경 ${g.valet?.radiusMeters}m")
        g.mode == GuardMode.ARMED && g.pendingArm -> Triple(K.Amber, "잠금 예약됨", "주차(P) 후 운전석에서 내리면 출발 잠금이 켜집니다.")
        g.mode == GuardMode.ARMED -> Triple(K.Blue, "출발 잠금 켜짐", "차 키가 차 안에 있어도 비밀번호 4자리 또는 지문으로 인증해야 출발할 수 있습니다.")
        else -> Triple(K.Sub, "출발 잠금 꺼짐", "스마트키만 있으면 누구나 출발할 수 있는 상태입니다.\n키를 두고 내리거나 차를 맡길 때 켜 두세요.")
    }
    val pulse = rememberInfiniteTransition(label = "p").animateFloat(.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(K.Surface)
            .border(2.dp, color.copy(alpha = .55f), RoundedCornerShape(24.dp)).padding(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShieldIcon(color, 120.dp, locked = g.phase != Phase.UNLOCKED && g.mode != GuardMode.OFF,
            modifier = if (g.phase == Phase.ALARM || g.phase == Phase.LOCKOUT) Modifier.alpha(pulse.value) else Modifier)
        Spacer(Modifier.width(26.dp))
        Column {
            Text(title, color = color, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(desc, color = K.Text, fontSize = 18.sp, lineHeight = 26.sp)
        }
    }
}

@Composable
private fun SafetyNote() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Surface).padding(20.dp)) {
        Text("안전 원칙", color = K.Sub, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        listOf(
            "주행 중에는 잠그지 않습니다 — 원격으로 켜도 주차 후 적용",
            "문 열림·비상 탈출·비상등은 언제나 정상 작동",
            "통신이 끊긴 지하주차장에서도 차량 안에서 인증",
        ).forEach { Text("• $it", color = K.Text, fontSize = 16.sp, lineHeight = 24.sp) }
    }
}

@Composable
private fun SignalPanel(ui: CarUi) {
    val s = ui.signals
    Panel("차량 신호 · Pleos Vehicle SDK") {
        SignalRow("운전석 착석", if (s.driverSeated) "착석" else "비어 있음", s.driverSeated, "Seat.registerSeatOccupancy")
        SignalRow("브레이크 페달", if (s.brakePressed) "밟음" else "뗌", s.brakePressed, "Brake.registerBrakePedalStatus")
        SignalRow("기어", gearLabel(s.gear), s.gear != Gear.PARK, "Gear.registerGearSelection")
        SignalRow("도어 잠금", if (s.doorsLocked) "잠김" else "열림", !s.doorsLocked, "Door.registerDoorLockState")
    }
}

@Composable
private fun SignalRow(label: String, value: String, active: Boolean, api: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (active) K.Amber else K.Green))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = K.Text, fontSize = 17.sp)
            Text(api, color = K.Sub, fontSize = 12.sp)
        }
        Text(value, color = if (active) K.Amber else K.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AlertPanel(ui: CarUi, modifier: Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Surface).padding(18.dp)) {
        Row {
            TabText("차주 스마트폰 알림 ${ui.alerts.size}", tab == 0) { tab = 0 }
            Spacer(Modifier.width(18.dp))
            TabText("기록", tab == 1) { tab = 1 }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tab == 0) {
                if (ui.alerts.isEmpty()) item { Text("보낸 알림이 없습니다", color = K.Sub, fontSize = 15.sp) }
                items(ui.alerts) { a ->
                    Row {
                        Text(a.time, color = K.Sub, fontSize = 13.sp, modifier = Modifier.width(70.dp))
                        Column {
                            Text(a.title, color = if (a.danger) K.Red else K.Green, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(a.message, color = K.Text, fontSize = 14.sp)
                        }
                    }
                }
            } else {
                items(ui.logs) { l ->
                    Row {
                        Text(l.time, color = K.Sub, fontSize = 13.sp, modifier = Modifier.width(70.dp))
                        Text(l.text, color = K.Text, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SimPanel(ui: CarUi, vm: CarViewModel) {
    val s = ui.signals
    Panel("시연 패널 (실제 차량에서는 자동 감지)") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Toggle("운전석 착석", s.driverSeated) { vm.simulate { it.copy(driverSeated = !it.driverSeated) } }
            Toggle("브레이크", s.brakePressed) { vm.simulate { it.copy(brakePressed = !it.brakePressed) } }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Gear.PARK to "P", Gear.REVERSE to "R", Gear.NEUTRAL to "N", Gear.DRIVE to "D").forEach { (g, l) ->
                Toggle(l, s.gear == g) { vm.simulate { it.copy(gear = g) } }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallButton("스마트폰에서 켜기", K.Surface2) { vm.remoteArm() }
            SmallButton("스마트폰에서 해제", K.Surface2) { vm.remoteDisarm() }
            if (ui.guard.mode == GuardMode.VALET) SmallButton("반경 이탈", K.Surface2) { vm.simulateGeofenceExit() }
        }
    }
}

// =================================================================== 잠금 화면

@Composable
private fun LockOverlay(ui: CarUi, vm: CarViewModel, onFingerprint: () -> Unit) {
    val locked = ui.lockoutSec > 0 || ui.guard.phase == Phase.LOCKOUT
    val accent = if (locked || ui.guard.phase == Phase.ALARM) K.Red else if (ui.lockValet) K.Purple else K.Blue
    Row(
        Modifier.fillMaxSize().background(K.Bg).clickable(enabled = false) {}.padding(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            ShieldIcon(accent, 150.dp)
            Spacer(Modifier.height(20.dp))
            Text(when { ui.guard.phase == Phase.ALARM -> "무단 운행 감지"; ui.lockValet -> "발레 모드"; else -> "출발 잠금 중" }, color = accent, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(
                if (ui.guard.phase == Phase.ALARM) "인증 없이 기어가 변경되어 차주에게 알림과\n실시간 위치를 보내고 있습니다"
                else if (ui.lockValet) "차주에게 전달받은 임시 비밀번호 4자리를 입력하세요"
                else "차주가 설정한 비밀번호 4자리 또는\n지문으로 인증해야 출발할 수 있습니다",
                color = K.Text, fontSize = 20.sp, textAlign = TextAlign.Center, lineHeight = 30.sp,
            )
            Spacer(Modifier.height(26.dp))
            Text("비상시: 문 열림·비상등·긴급출동 버튼은 정상 작동합니다", color = K.Sub, fontSize = 15.sp)
            Text("비밀번호를 잊으셨나요? 차주 스마트폰 앱에서 해제할 수 있습니다", color = K.Sub, fontSize = 15.sp)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            PinDots(ui.pinInput.length, ui.pinError)
            Spacer(Modifier.height(12.dp))
            Text(
                when {
                    locked -> "입력 제한 ${maxOf(ui.lockoutSec, 1)}초 — 차주에게 알림을 보냈습니다"
                    ui.pinMessage != null -> ui.pinMessage
                    else -> " "
                },
                color = if (ui.pinError || locked) K.Red else K.Sub, fontSize = 17.sp, modifier = Modifier.height(26.dp),
            )
            Spacer(Modifier.height(10.dp))
            Keypad(ui.keypadOrder, enabled = !locked, showFinger = ui.useFingerprint && !ui.lockValet, onDigit = vm::digit, onBack = vm::backspace, onFinger = onFingerprint)
        }
    }
}

@Composable
private fun PinDots(filled: Int, error: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        repeat(4) { i ->
            Box(
                Modifier.size(26.dp).clip(CircleShape)
                    .background(if (i < filled) (if (error) K.Red else K.Blue) else Color.Transparent)
                    .border(2.dp, if (error) K.Red else K.Sub, CircleShape)
            )
        }
    }
}

@Composable
private fun Keypad(order: List<Int>, enabled: Boolean, showFinger: Boolean, onDigit: (Int) -> Unit, onBack: () -> Unit, onFinger: () -> Unit) {
    val keys = order.take(9).map { it.toString() } + listOf(if (showFinger) "FP" else "", order[9].toString(), "⌫")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.alpha(if (enabled) 1f else .35f)) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(width = 112.dp, height = 78.dp).clip(RoundedCornerShape(18.dp))
                            .background(if (k.isEmpty()) Color.Transparent else K.Surface2)
                            .clickable(enabled = enabled && k.isNotEmpty()) {
                                when (k) { "⌫" -> onBack(); "FP" -> onFinger(); else -> onDigit(k.toInt()) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        when (k) {
                            "FP" -> FingerprintIcon(K.Green, 44.dp)
                            else -> Text(k, color = K.Text, fontSize = 32.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningGlow() {
    val a = rememberInfiniteTransition(label = "w").animateFloat(0f, .9f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "wa")
    Box(Modifier.fillMaxSize().border(10.dp, K.Red.copy(alpha = a.value)))
}

// =================================================================== 대화상자

@Composable
private fun PinPadDialog(ui: CarUi, purpose: PadPurpose, vm: CarViewModel, onFingerprint: () -> Unit) {
    val title = when (purpose) {
        PadPurpose.SETUP_1 -> "처음 사용: 차주 비밀번호 4자리 등록"
        PadPurpose.SETUP_2 -> "비밀번호 확인"
        PadPurpose.CHANGE_VERIFY -> "현재 차주 비밀번호"
        PadPurpose.CHANGE_NEW -> "새 차주 비밀번호"
        PadPurpose.FAMILY_NEW -> "가족 운전자 비밀번호 등록"
        PadPurpose.DISARM -> "출발 잠금 끄기 — 차주 확인"
        PadPurpose.UNLOCK -> "인증"
    }
    Dialog(onDismissRequest = { if (purpose != PadPurpose.SETUP_1 || !ui.needsSetup) vm.closePad() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = !ui.needsSetup)) {
        Column(
            Modifier.clip(RoundedCornerShape(28.dp)).background(K.Surface).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = K.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (purpose == PadPurpose.SETUP_1) Text("같은 숫자 반복·연속 숫자(1234)는 쓸 수 없습니다", color = K.Sub, fontSize = 15.sp)
            Spacer(Modifier.height(18.dp))
            PinDots(ui.pinInput.length, ui.pinError)
            Spacer(Modifier.height(8.dp))
            Text(ui.pinMessage ?: " ", color = if (ui.pinError) K.Red else K.Sub, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Keypad(ui.keypadOrder, true, showFinger = purpose == PadPurpose.DISARM && ui.useFingerprint, onDigit = vm::digit, onBack = vm::backspace, onFinger = onFingerprint)
            if (!ui.needsSetup) {
                Spacer(Modifier.height(14.dp))
                SmallButton("취소", K.Surface2) { vm.closePad() }
            }
        }
    }
}

@Composable
private fun ValetDialog(ui: CarUi, onStart: (Int, Int) -> Unit, onEnd: () -> Unit, onClose: () -> Unit) {
    var minutes by remember { mutableIntStateOf(180) }
    var radius by remember { mutableIntStateOf(1000) }
    val active = ui.guard.mode == GuardMode.VALET
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(640.dp).clip(RoundedCornerShape(28.dp)).background(K.Surface).padding(32.dp)) {
            Text("발레 · 주차대행 모드", color = K.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("기사님께는 차주 비밀번호 대신 1회용 임시 비밀번호를 알려 주세요.\n시간이 지나거나 발레를 끝내면 자동으로 무효가 됩니다.", color = K.Sub, fontSize = 16.sp, lineHeight = 24.sp)
            Spacer(Modifier.height(20.dp))
            if (!active) {
                Text("유효 시간", color = K.Text, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(60 to "1시간", 180 to "3시간", 360 to "6시간").forEach { (m, l) -> Toggle(l, minutes == m) { minutes = m } }
                }
                Spacer(Modifier.height(14.dp))
                Text("이동 반경 (벗어나면 차주에게 알림)", color = K.Text, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(500 to "500m", 1000 to "1km", 3000 to "3km").forEach { (r, l) -> Toggle(l, radius == r) { radius = r } }
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton("임시 비밀번호 발급", "", K.Purple, Modifier.weight(1f)) { onStart(minutes, radius) }
                    BigButton("닫기", "", K.Surface2, Modifier.weight(.5f), onClose)
                }
            } else {
                Text("발레 진행 중 — 남은 시간 ${remainMin(ui.guard.valet?.expiresAt)}분 · 반경 ${ui.guard.valet?.radiusMeters}m", color = K.Purple, fontSize = 20.sp)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton("발레 종료 · 리포트 보기", "다시 출발 잠금으로 전환", K.Purple, Modifier.weight(1f), onEnd)
                    BigButton("닫기", "", K.Surface2, Modifier.weight(.5f), onClose)
                }
            }
        }
    }
}

@Composable
private fun ValetPinDialog(pin: String, ui: CarUi, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.clip(RoundedCornerShape(28.dp)).background(K.Surface).padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("발레 임시 비밀번호", color = K.Sub, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text(pin.toList().joinToString("  "), color = K.Purple, fontSize = 64.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text("${(ui.guard.valet?.let { remainMin(it.expiresAt) } ?: 0)}분 동안 유효 · 반경 ${ui.guard.valet?.radiusMeters}m", color = K.Text, fontSize = 18.sp)
            Text("이 번호는 다시 표시되지 않습니다. 기사님께 전달하세요.", color = K.Sub, fontSize = 15.sp)
            Spacer(Modifier.height(20.dp))
            SmallButton("확인", K.Purple, onClick = onClose)
        }
    }
}

@Composable
private fun SettingsDialog(ui: CarUi, vm: CarViewModel, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(640.dp).clip(RoundedCornerShape(28.dp)).background(K.Surface).padding(32.dp)) {
            Text("설정", color = K.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            SettingSwitch("내리면 자동으로 다시 잠금", "인증 후 주행을 마치고 P단에서 내리면 다시 켜집니다", ui.autoRelock, vm::setAutoRelock)
            SettingSwitch("키패드 숫자 무작위 배열", "화면에 남은 손자국으로 비밀번호를 추측하지 못하게 합니다", ui.shuffleKeypad, vm::setShuffle)
            SettingSwitch("지문 인증 사용", "지문 센서가 있는 차량에서 비밀번호 대신 사용", ui.useFingerprint, vm::setFingerprint)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallButton("차주 비밀번호 변경", K.Surface2) { onClose(); vm.openPad(PadPurpose.CHANGE_VERIFY) }
                if (ui.hasFamily) SmallButton("가족 비밀번호 삭제", K.Surface2) { vm.removeFamily() }
                else SmallButton("가족 운전자 등록", K.Surface2) { onClose(); vm.openPad(PadPurpose.FAMILY_NEW) }
            }
            Spacer(Modifier.height(20.dp))
            SmallButton("닫기", K.Blue, onClick = onClose)
        }
    }
}

@Composable
private fun InfoDialog(title: String, body: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.clip(RoundedCornerShape(28.dp)).background(K.Surface).padding(32.dp)) {
            Text(title, color = K.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(body, color = K.Text, fontSize = 18.sp, lineHeight = 26.sp)
            Spacer(Modifier.height(20.dp))
            SmallButton("확인", K.Blue, onClick = onClose)
        }
    }
}

// =================================================================== 작은 구성요소

@Composable
private fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.Surface).padding(18.dp)) {
        Text(title, color = K.Sub, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun BigButton(title: String, sub: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(color).clickable(onClick = onClick).padding(horizontal = 22.dp, vertical = 20.dp),
    ) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = .8f), fontSize = 14.sp)
    }
}

@Composable
private fun SmallButton(text: String, color: Color, onClick: () -> Unit) {
    Text(text, color = K.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(color).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp))
}

@Composable
private fun Toggle(text: String, on: Boolean, onClick: () -> Unit) {
    Text(text, color = if (on) Color.White else K.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) K.Blue else K.Surface2).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp))
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(color.copy(alpha = .14f)).padding(horizontal = 14.dp, vertical = 7.dp))
}

@Composable
private fun TabText(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(text, color = if (selected) K.Text else K.Sub, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.clickable(onClick = onClick))
}

@Composable
private fun SettingSwitch(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = K.Text, fontSize = 18.sp)
            Text(sub, color = K.Sub, fontSize = 14.sp)
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = K.Blue))
    }
}

private fun gearLabel(g: Gear) = when (g) { Gear.PARK -> "P"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.DRIVE -> "D"; Gear.UNKNOWN -> "-" }
private fun remainMin(at: Long?): Long = if (at == null) 0 else ((at - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
