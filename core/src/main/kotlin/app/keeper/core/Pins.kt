package app.keeper.core

import java.security.MessageDigest
import java.security.SecureRandom

/** 비밀번호는 원문을 저장하지 않고 salt + SHA-256 해시로만 보관한다. */
object PinHasher {
    fun hash(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        var bytes = (salt + ":" + pin).toByteArray()
        repeat(10_000) { bytes = md.digest(bytes) } // 단순 반복으로 무차별 대입 비용 증가
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun newSalt(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** 발레용 1회용 임시 비밀번호. 쉬운 번호(0000, 1234, 같은 숫자 반복)는 피한다. */
    fun randomPin(rnd: SecureRandom = SecureRandom()): String {
        while (true) {
            val p = (0 until 4).map { rnd.nextInt(10) }.joinToString("")
            if (!isWeak(p)) return p
        }
    }

    fun isWeak(pin: String): Boolean {
        if (pin.length != 4 || !pin.all(Char::isDigit)) return true
        if (pin.toSet().size == 1) return true
        val d = pin.map { it - '0' }
        val asc = (1..3).all { d[it] - d[it - 1] == 1 }
        val desc = (1..3).all { d[it - 1] - d[it] == 1 }
        return asc || desc
    }
}

interface PinStore {
    val salt: String
    val ownerHash: String
    val familyHashes: Set<String>
}

class MemoryPinStore(ownerPin: String, familyPins: List<String> = emptyList(), override val salt: String = PinHasher.newSalt()) : PinStore {
    override val ownerHash = PinHasher.hash(ownerPin, salt)
    override val familyHashes = familyPins.map { PinHasher.hash(it, salt) }.toSet()
}
