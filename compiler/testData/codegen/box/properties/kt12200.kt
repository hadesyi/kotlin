// WITH_REFLECT

class ThingTemplate {
    val prop = 0
}

class Thing(template: ThingTemplate) {
    val prop = template.prop
}

fun box(): String {
    val declaredField = ThingTemplate::class.java.getDeclaredField("prop")
    declaredField.isAccessible = true
    val thingTemplate = ThingTemplate()
    declaredField.set(thingTemplate, 11)
    val thing = Thing(thingTemplate)
    if (thing.prop != 11) return "fail : ${thing.prop}"
    return "OK"
}