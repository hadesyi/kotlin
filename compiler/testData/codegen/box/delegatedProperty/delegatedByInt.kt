// WITH_REFLECT
import kotlin.reflect.KProperty

val z by 4

operator fun Int.getValue(owner: Any?, prop: KProperty<*>): Int {
    return this + 11
}

fun box(): String {
    if (z != 15) return "fail : ${z}"
    return "OK"
}