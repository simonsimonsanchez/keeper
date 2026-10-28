package app.keeper.car

import android.content.Context
import app.keeper.core.PinHasher
import app.keeper.core.PinStore

/** 비밀번호 해시와 설정을 차량 내부(앱 전용 저장소)에 보관. 통신이 끊긴 지하주차장에서도 인증이 된다. */
class PinPrefs(context: Context) : PinStore {
    private val sp = context.getSharedPreferences("keeper_pins", Context.MODE_PRIVATE)

    override val salt: String
        get() = sp.getString("salt", null) ?: PinHasher.newSalt().also { sp.edit().putString("salt", it).apply() }

    override val ownerHash: String get() = sp.getString("owner", "") ?: ""
    override val familyHashes: Set<String> get() = sp.getStringSet("family", emptySet()) ?: emptySet()

    val isSetUp: Boolean get() = ownerHash.isNotEmpty()

    fun setOwnerPin(pin: String) = sp.edit().putString("owner", PinHasher.hash(pin, salt)).apply()
    fun verifyOwner(pin: String) = PinHasher.hash(pin, salt) == ownerHash

    fun setFamilyPin(pin: String?) {
        val set = if (pin == null) emptySet() else setOf(PinHasher.hash(pin, salt))
        sp.edit().putStringSet("family", set).apply()
    }
    val hasFamily: Boolean get() = familyHashes.isNotEmpty()

    var autoRelock: Boolean
        get() = sp.getBoolean("autoRelock", true)
        set(v) = sp.edit().putBoolean("autoRelock", v).apply()
    var shuffleKeypad: Boolean
        get() = sp.getBoolean("shuffle", true)
        set(v) = sp.edit().putBoolean("shuffle", v).apply()
    var useFingerprint: Boolean
        get() = sp.getBoolean("finger", true)
        set(v) = sp.edit().putBoolean("finger", v).apply()
}
