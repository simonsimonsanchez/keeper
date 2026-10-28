package app.keeper.phone

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keeper.core.Alert
import kotlinx.coroutines.delay

object P {
    val Bg = Color(0xFFF4F6FB)
    val Card = Color.White
    val Text = Color(0xFF111827)
    val Sub = Color(0xFF6B7280)
    val Line = Color(0xFFE5E7EB)
    val Navy = Color(0xFF0B1F44)
    val Blue = Color(0xFF2F6BFF)
    val Green = Color(0xFF10B981)
    val Red = Color(0xFFE11D48)
    val Purple = Color(0xFF7C3AED)
    val Amber = Color(0xFFF59E0B)
}

@Composable
fun PhoneApp(vm: PhoneViewModel, authenticate: (String, () -> Unit) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var openAlert by remember { mutableStateOf<Long?>(null) }

    MaterialTheme(colorScheme = lightColorScheme(primary = P.Blue, background = P.Bg)) {
        Box(Modifier.fillMaxSize().background(P.Bg)) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        0 -> HomeTab(ui, vm, authenticate, goValet = { tab = 1 }, openAlert = { openAlert = it; tab = 2 })
                        1 -> ValetTab(ui, vm)
                        2 -> AlertsTab(ui, vm, openAlert, onOpen = { openAlert = it })
                        else -> SettingsTab(ui, vm)
                    }
                }
                BottomBar(tab, ui.alerts.count { !it.read && it.danger }) { tab = it; if (it != 2) openAlert = null }
            }
            if (ui.sending) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .25f)), contentAlignment = Alignment.Center) {
                Text("차량에 전달하는 중…", color = Color.White, fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(P.Navy).padding(24.dp, 16.dp))
            }
            ui.valetReport?.let { r ->
                Dialog(onDismissRequest = vm::dismissReport) {
                    Card {
                        Text("발레 운행 리포트", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = P.Text)
                        Spacer(Modifier.height(10.dp))
                        Text(r, fontSize = 16.sp, color = P.Text, lineHeight = 24.sp)
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("확인", P.Blue, onClick = vm::dismissReport)
                    }
                }
            }
            ui.toast?.let { t ->
                LaunchedEffect(t) { delay(2000); vm.clearToast() }
                Box(Modifier.fillMaxSize().padding(bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
                    Text(t, color = Color.White, fontSize = 15.sp, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(P.Navy).padding(18.dp, 10.dp))
                }
            }
        }
    }
}

// ================================================================== 홈

@Composable
private fun HomeTab(ui: PhoneUi, vm: PhoneViewModel, authenticate: (String, () -> Unit) -> Unit, goValet: () -> Unit, openAlert: (Long) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("KEEPER", fontSize = 22.sp, fontWeight = FontWeight.Black, color = P.Navy, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Text("동기화 ${ui.lastSync}", fontSize = 13.sp, color = P.Sub)
        }
        // 위험 알림 배너
        ui.alerts.firstOrNull { it.danger && !it.read }?.let { a ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(P.Red).clickable { openAlert(a.id) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚠", fontSize = 26.sp, color = Color.White)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.alert.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(a.message, color = Color.White.copy(alpha = .9f), fontSize = 14.sp, maxLines = 2)
                }
                Text("보기 ›", color = Color.White, fontSize = 15.sp)
            }
        }
        // 차량 상태 카드
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(P.Navy).padding(22.dp)) {
            Text(ui.carName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(ui.plate, color = Color.White.copy(alpha = .7f), fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Shield(if (ui.armed) P.Green else Color.White.copy(alpha = .5f), 64.dp, ui.armed)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when { ui.valet != null -> "발레 모드"; ui.pendingArm -> "잠금 예약됨"; ui.armed -> "출발 잠금 켜짐"; else -> "출발 잠금 꺼짐" },
                        color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            ui.valet != null -> "임시 비밀번호로만 출발 가능"
                            ui.pendingArm -> "주행 중 — 주차 후 자동 적용"
                            ui.armed -> "키가 차 안에 있어도 인증 없이는 출발 불가"
                            else -> "스마트키만 있으면 누구나 출발 가능"
                        },
                        color = Color.White.copy(alpha = .8f), fontSize = 14.sp,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill("도어 ${if (ui.doorsLocked) "잠김" else "열림"}")
                StatusPill(if (ui.parked) "주차 중" else "주행 중")
                StatusPill("서울 중구")
            }
        }
        // 큰 토글
        BigToggle(
            on = ui.armed,
            onClick = {
                if (ui.armed) authenticate("도난방지모드 해제") { vm.disarm() } else vm.arm()
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickAction("🅿", "발레 맡기기", "임시 비밀번호", Modifier.weight(1f), goValet)
            QuickAction("🔓", "원격 해제", "비밀번호를 잊었을 때", Modifier.weight(1f)) { authenticate("원격 해제") { vm.disarm() } }
        }
        Card {
            Text("이럴 때 켜 두세요", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = P.Text)
            Spacer(Modifier.height(6.dp))
            listOf("카드키·스마트키를 차에 두고 내렸을 때", "공항·장기 주차", "주차대행·세차·정비를 맡길 때", "모르는 사람이 차에 탔다는 의심이 들 때")
                .forEach { Text("• $it", fontSize = 14.sp, color = P.Sub, lineHeight = 22.sp) }
        }
        DemoBox(vm)
    }
}

@Composable
private fun DemoBox(vm: PhoneViewModel) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, P.Line, RoundedCornerShape(18.dp)).padding(16.dp)) {
        Text("시연: 차량에서 오는 알림 받아 보기", fontSize = 13.sp, color = P.Sub, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("비밀번호 3회 오류") { vm.demoWrongPin() }
            OutlineButton("무단 운행") { vm.demoUnauthorizedMove() }
        }
    }
}

// ================================================================== 발레

@Composable
private fun ValetTab(ui: PhoneUi, vm: PhoneViewModel) {
    var minutes by remember { mutableIntStateOf(180) }
    var radius by remember { mutableIntStateOf(1000) }
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("발레 · 주차대행", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = P.Text)
        Text("내 비밀번호 대신 1회용 임시 비밀번호를 알려 주세요. 시간이 지나면 자동으로 무효가 됩니다.", fontSize = 14.sp, color = P.Sub, lineHeight = 21.sp)
        val v = ui.valet
        if (v == null) {
            Card {
                Text("유효 시간", fontWeight = FontWeight.Bold, color = P.Text)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60 to "1시간", 180 to "3시간", 360 to "6시간", 1440 to "하루").forEach { (m, l) -> Chip(l, minutes == m) { minutes = m } }
                }
                Spacer(Modifier.height(16.dp))
                Text("이동 반경 — 벗어나면 즉시 알림", fontWeight = FontWeight.Bold, color = P.Text)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(500 to "500m", 1000 to "1km", 3000 to "3km").forEach { (r, l) -> Chip(l, radius == r) { radius = r } }
                }
                Spacer(Modifier.height(16.dp))
                Text("• 발레 중에는 차량 화면의 개인정보(내비 기록·연락처)를 가립니다\n• 운행 거리·기어 조작·반경 이탈 여부를 리포트로 받습니다", fontSize = 13.sp, color = P.Sub, lineHeight = 20.sp)
                Spacer(Modifier.height(18.dp))
                PrimaryButton("임시 비밀번호 발급", P.Purple) { vm.startValet(minutes, radius) }
            }
        } else {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(P.Purple).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("발레 임시 비밀번호", color = Color.White.copy(alpha = .85f), fontSize = 15.sp)
                Text(v.pin.toList().joinToString("  "), color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Black)
                Text("${v.minutes / 60}시간 유효 · 반경 ${if (v.radius >= 1000) "${v.radius / 1000}km" else "${v.radius}m"}", color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LightButton("문자로 보내기") {
                        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")).putExtra("sms_body",
                            "[KEEPER] 차량 임시 비밀번호: ${v.pin}\n차량 화면에 입력하면 출발할 수 있습니다. (${v.minutes / 60}시간 유효)")
                        runCatching { ctx.startActivity(i) }
                    }
                    LightButton("공유") {
                        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "[KEEPER] 차량 임시 비밀번호: ${v.pin}")
                        runCatching { ctx.startActivity(Intent.createChooser(i, "임시 비밀번호 보내기")) }
                    }
                }
            }
            Card {
                Text("발레 진행 상황", fontWeight = FontWeight.Bold, color = P.Text)
                Spacer(Modifier.height(8.dp))
                InfoRow("운행", if (v.driving) "운행 중" else "대기")
                InfoRow("기어 조작", "${v.gearChanges}회")
                InfoRow("반경 이탈", if (v.exited) "있음 ⚠" else "없음", if (v.exited) P.Red else P.Text)
                Spacer(Modifier.height(14.dp))
                PrimaryButton("발레 종료 · 리포트 보기", P.Navy, onClick = vm::endValet)
                Spacer(Modifier.height(8.dp))
                OutlineButton("시연: 기사 운행 이벤트") { vm.demoValetFlow() }
            }
        }
    }
}

// ================================================================== 알림

@Composable
private fun AlertsTab(ui: PhoneUi, vm: PhoneViewModel, openId: Long?, onOpen: (Long?) -> Unit) {
    val open = ui.alerts.firstOrNull { it.id == openId }
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (open != null) {
            LaunchedEffect(open.id) { vm.markRead(open.id) }
            Text("‹ 알림 목록", color = P.Blue, fontSize = 15.sp, modifier = Modifier.clickable { onOpen(null) })
            Text(open.alert.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (open.danger) P.Red else P.Text)
            Text("${open.time} · ${ui.carName}", fontSize = 14.sp, color = P.Sub)
            Card { Text(open.message, fontSize = 16.sp, color = P.Text, lineHeight = 24.sp) }
            if (open.alert == Alert.UNAUTHORIZED_MOVE) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.Card)) {
                    TrackMap(open.track, Modifier.fillMaxWidth().height(260.dp))
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(P.Red))
                        Spacer(Modifier.width(8.dp))
                        Text("실시간 위치 ${open.track.size}회 수신 · ${open.track.lastOrNull()?.let { "%.4f, %.4f".format(it.first, it.second) } ?: ""}", fontSize = 13.sp, color = P.Sub)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("112 신고", P.Red, Modifier.weight(1f)) { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))) } }
                    PrimaryButton("위치 공유", P.Navy, Modifier.weight(1f)) {
                        val p = open.track.lastOrNull()
                        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "도난 의심 차량 ${ui.plate} 현재 위치: ${p?.first}, ${p?.second}")
                        runCatching { ctx.startActivity(Intent.createChooser(i, "위치 공유")) }
                    }
                }
                Text("※ 안전을 위해 주행 중인 차량을 원격으로 멈추게 하지는 않습니다. 위치는 경찰 신고 시 활용하세요.", fontSize = 12.sp, color = P.Sub, lineHeight = 18.sp)
                OutlineButton("시연 종료(차량 회수)") { vm.stopDemo() }
            }
            if (open.alert == Alert.WRONG_PIN) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("내가 아님 · 112", P.Red, Modifier.weight(1f)) { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))) } }
                    PrimaryButton("가족/지인이에요", P.Navy, Modifier.weight(1f)) { onOpen(null) }
                }
            }
        } else {
            Text("알림", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = P.Text)
            if (ui.alerts.isEmpty()) Card { Text("아직 받은 알림이 없습니다.\n홈 화면 아래 '시연' 버튼으로 받아 볼 수 있습니다.", color = P.Sub, fontSize = 15.sp, lineHeight = 22.sp) }
            ui.alerts.forEach { a ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(P.Card).clickable { onOpen(a.id) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background((if (a.danger) P.Red else P.Green).copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                        Text(if (a.danger) "!" else "✓", color = if (a.danger) P.Red else P.Green, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.alert.title, fontWeight = FontWeight.Bold, color = P.Text, fontSize = 16.sp)
                        Text(a.message, color = P.Sub, fontSize = 13.sp, maxLines = 2)
                    }
                    Text(a.time, color = P.Sub, fontSize = 12.sp)
                }
            }
        }
    }
}

/** 지도 SDK 없이 그린 간이 지도: 격자 도로 + 이동 경로. (실서비스: 지도 SDK 연동) */
@Composable
private fun TrackMap(track: List<Pair<Double, Double>>, modifier: Modifier) {
    Canvas(modifier.background(Color(0xFFEAF0F7))) {
        val w = size.width; val h = size.height
        for (i in 1..6) drawLine(Color.White, Offset(0f, h * i / 7), Offset(w, h * i / 7), strokeWidth = if (i % 3 == 0) 14f else 6f)
        for (i in 1..8) drawLine(Color.White, Offset(w * i / 9, 0f), Offset(w * i / 9, h), strokeWidth = if (i % 4 == 0) 14f else 6f)
        drawRect(Color(0xFFCFE8D4), Offset(w * .62f, h * .1f), Size(w * .22f, h * .22f))
        if (track.isEmpty()) return@Canvas
        val lat0 = 37.5665; val lng0 = 126.9780
        fun pt(p: Pair<Double, Double>) = Offset((w * .15f + ((p.second - lng0) / 0.018 * w * .7)).toFloat(), (h * .85f - ((p.first - lat0) / 0.013 * h * .7)).toFloat())
        val path = Path().apply { track.forEachIndexed { i, p -> val o = pt(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
        drawPath(path, P.Red, style = Stroke(width = 10f, cap = StrokeCap.Round))
        drawCircle(P.Navy, 16f, pt(track.first()))
        val last = pt(track.last())
        drawCircle(P.Red.copy(alpha = .25f), 40f, last)
        drawCircle(P.Red, 18f, last)
        drawCircle(Color.White, 7f, last)
    }
}

// ================================================================== 설정

@Composable
private fun SettingsTab(ui: PhoneUi, vm: PhoneViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("설정", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = P.Text)
        Card {
            Text("운전자", fontWeight = FontWeight.Bold, color = P.Text)
            Spacer(Modifier.height(6.dp))
            InfoRow("차주 (나)", "비밀번호 · 지문")
            ui.family.forEachIndexed { i, f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("가족 · ${f.name}", color = P.Text, fontSize = 15.sp)
                        Text("본인 비밀번호로 출발, 운행 시작 시 알림", color = P.Sub, fontSize = 12.sp)
                    }
                    Switch(f.enabled, { vm.toggleFamily(i) }, colors = SwitchDefaults.colors(checkedTrackColor = P.Blue))
                }
            }
            Text("가족 비밀번호 등록은 차량 화면 > 설정에서 합니다 (차 안에서만 등록 가능)", color = P.Sub, fontSize = 12.sp)
        }
        Card {
            SwitchRow("차에서 멀어지면 자동으로 켜기", "스마트폰이 차와 블루투스 연결이 끊기고 차가 P단이면 켭니다", ui.autoArmOnLeave, vm::setAutoArm)
            SwitchRow("가족 운전 시작 알림", "가족 비밀번호로 출발하면 알려 드립니다", ui.notifyFamily, vm::setNotifyFamily)
        }
        Card {
            Text("보안", fontWeight = FontWeight.Bold, color = P.Text)
            Spacer(Modifier.height(6.dp))
            Text("• 해제·원격 해제는 스마트폰 지문/화면잠금 인증 후에만 실행\n• 비밀번호는 차량 안에 해시로만 저장 (원문 저장 안 함)\n• 3회 오류 시 입력 제한 + 즉시 알림", color = P.Sub, fontSize = 13.sp, lineHeight = 20.sp)
        }
        Text("KEEPER 1.0 · 현대자동차그룹 차량용 앱 개발 공모전 출품작 (시연 버전)", color = P.Sub, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

// ================================================================== 공통

@Composable
private fun BottomBar(tab: Int, badge: Int, onTab: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color.White).border(1.dp, P.Line).padding(vertical = 10.dp)) {
        listOf("홈", "발레", "알림", "설정").forEachIndexed { i, l ->
            Column(Modifier.weight(1f).clickable { onTab(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    Text(listOf("⌂", "🅿", "🔔", "⚙")[i], fontSize = 20.sp, color = if (tab == i) P.Blue else P.Sub)
                    if (i == 2 && badge > 0) Box(Modifier.align(Alignment.TopEnd).offset(10.dp, (-4).dp).size(18.dp).clip(CircleShape).background(P.Red), contentAlignment = Alignment.Center) {
                        Text("$badge", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(l, fontSize = 12.sp, color = if (tab == i) P.Blue else P.Sub, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun BigToggle(on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (on) P.Green else P.Blue).clickable(onClick = onClick).padding(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(if (on) "도난방지모드 켜짐" else "도난방지모드 켜기", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(if (on) "끄려면 지문 인증이 필요합니다" else "누르면 차량에 즉시 전달됩니다", color = Color.White.copy(alpha = .85f), fontSize = 14.sp)
        }
        Box(Modifier.size(width = 64.dp, height = 36.dp).clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .35f)), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.padding(4.dp).size(28.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
private fun QuickAction(icon: String, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(P.Card).clickable(onClick = onClick).padding(16.dp)) {
        Text(icon, fontSize = 24.sp)
        Spacer(Modifier.height(6.dp))
        Text(title, fontWeight = FontWeight.Bold, color = P.Text, fontSize = 16.sp)
        Text(sub, color = P.Sub, fontSize = 12.sp)
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(P.Card).padding(18.dp), content = content)
}

@Composable
private fun StatusPill(t: String) {
    Text(t, color = Color.White, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .15f)).padding(10.dp, 5.dp))
}

@Composable
private fun Chip(t: String, on: Boolean, onClick: () -> Unit) {
    Text(t, color = if (on) Color.White else P.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) P.Purple else P.Bg).clickable(onClick = onClick).padding(14.dp, 8.dp))
}

@Composable
private fun PrimaryButton(t: String, color: Color, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Text(t, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(color).clickable(onClick = onClick).padding(vertical = 14.dp))
}

@Composable
private fun OutlineButton(t: String, onClick: () -> Unit) {
    Text(t, color = P.Text, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, P.Line, RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp, 8.dp))
}

@Composable
private fun LightButton(t: String, onClick: () -> Unit) {
    Text(t, color = P.Purple, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).clickable(onClick = onClick).padding(16.dp, 9.dp))
}

@Composable
private fun InfoRow(k: String, v: String, color: Color = P.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(k, color = P.Sub, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(v, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SwitchRow(t: String, s: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t, color = P.Text, fontSize = 15.sp)
            Text(s, color = P.Sub, fontSize = 12.sp)
        }
        Switch(v, on, colors = SwitchDefaults.colors(checkedTrackColor = P.Blue))
    }
}

@Composable
private fun Shield(color: Color, size: Dp, locked: Boolean) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val shield = Path().apply {
            moveTo(w * .5f, h * .04f); lineTo(w * .92f, h * .18f); lineTo(w * .92f, h * .48f)
            cubicTo(w * .92f, h * .74f, w * .74f, h * .9f, w * .5f, h * .98f)
            cubicTo(w * .26f, h * .9f, w * .08f, h * .74f, w * .08f, h * .48f); lineTo(w * .08f, h * .18f); close()
        }
        drawPath(shield, color.copy(alpha = .2f)); drawPath(shield, color, style = Stroke(w * .06f))
        if (locked) drawLine(color, Offset(w * .32f, h * .5f), Offset(w * .45f, h * .63f), w * .08f, StrokeCap.Round)
        if (locked) drawLine(color, Offset(w * .45f, h * .63f), Offset(w * .7f, h * .36f), w * .08f, StrokeCap.Round)
    }
}
