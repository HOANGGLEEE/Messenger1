package app.hushmessenger.patches.controls

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** Logging Messenger publishes over its own MQTT connection, the one that also carries messages, calls and presence. */
internal const val MQTT_TELEMETRY = "mqtt_telemetry"

/** The connection's health report. When Messenger's config says so, the keepalive sends it instead of a plain ping. */
internal const val HEALTH_STATS_TOPIC = "/mqtt_health_stats"
internal const val PUBLISH_FAILED = "exception/publish"
internal const val NOT_CONNECTED = "not_connected"
internal const val KEEPALIVE_FAILED = "exception/send_keepalive"

internal const val MQTT_PUBLISH_GATE = "$SETTINGS->dropMqttPublish(Ljava/lang/String;)Z"
internal const val MQTT_KEEPALIVE_GATE = "$SETTINGS->mqttHealthKeepalive(Z)Z"

private const val STRING = "Ljava/lang/String;"

/** The publish's parameters after its two callbacks: QoS, topic, a second name, payload, timeout and a time. */
private val PUBLISH_TAIL = listOf("Ljava/lang/Integer;", STRING, STRING, "[B", "I", "J")

/** The topic is p4: after `this`, the two callbacks and the QoS. */
internal const val PUBLISH_TOPIC = 4

private fun mqttChanged(detail: String): Nothing =
    throw PatchException("Messenger controls: the MQTT client changed ($detail)")

private fun Method.mqttLiterals() = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }?.toSet().orEmpty()

/** The client's own publish, the one every topic but the retried message sends goes through. */
internal fun Method.isMqttPublish(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || !returnType.startsWith("L") || implementation == null) return false
    val params = parameterTypes.map { it.toString() }
    return params.size == 8 && params.take(2).all { it.startsWith("L") } && params.drop(2) == PUBLISH_TAIL &&
        mqttLiterals().containsAll(listOf(PUBLISH_FAILED, NOT_CONNECTED, HEALTH_STATS_TOPIC))
}

/**
 * What the publish answers when there's no connection: right after it logs "not_connected", it returns a singleton read
 * from that singleton's own class. Every caller already copes with it. Null when that isn't the shape.
 */
internal fun Method.notSentAnswer(): FieldReference? {
    val code = implementation?.instructions?.toList() ?: return null
    val logged = code.indices.singleOrNull {
        ((code[it] as? ReferenceInstruction)?.reference as? StringReference)?.string == NOT_CONNECTED
    } ?: return null
    val read = (logged + 1 until code.size - 1).firstOrNull { code[it].opcode == Opcode.SGET_OBJECT } ?: return null
    val field = (code[read] as ReferenceInstruction).reference as? FieldReference ?: return null
    val back = code[read + 1]
    if (back.opcode != Opcode.RETURN_OBJECT || (back as OneRegisterInstruction).registerA != (code[read] as OneRegisterInstruction).registerA ||
        field.type != field.definingClass) return null
    return field
}

/** The keepalive: an instance (String)V that names its own failure and the health report's topic. */
internal fun Method.isMqttKeepalive(): Boolean =
    !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" && parameterTypes.map { it.toString() } == listOf(STRING) &&
        implementation != null && mqttLiterals().containsAll(listOf(KEEPALIVE_FAILED, HEALTH_STATS_TOPIC))

/**
 * Index of the config flag that picks the keepalive. It's an iget-boolean tested by the very next if-eqz, whose false
 * branch lands on the plain ping (a static call on the client's own class, taking the client and the connection),
 * and the health report's topic loads after it. Null unless there's exactly one.
 */
internal fun Method.healthKeepaliveFlag(): Int? {
    val code = implementation?.instructions?.toList() ?: return null
    val topic = code.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == HEALTH_STATS_TOPIC }
    val sites = (0 until code.size - 1).filter { at ->
        val read = code[at] as? TwoRegisterInstruction
        val test = code[at + 1] as? OneRegisterInstruction
        val ping = code.getOrNull(code.branchTarget(at + 1))
        val call = (ping as? ReferenceInstruction)?.reference as? MethodReference
        read?.opcode == Opcode.IGET_BOOLEAN && test?.opcode == Opcode.IF_EQZ && test.registerA == read.registerA &&
            ping?.opcode == Opcode.INVOKE_STATIC && call != null && call.definingClass == definingClass && call.returnType == "V" &&
            call.parameterTypes.size == 2 && call.parameterTypes[0].toString() == definingClass && at < topic
    }
    return sites.singleOrNull()
}

/**
 * The publish and the keepalive, which live in one client class. Dropping the health report without moving the
 * keepalive to the plain ping would leave the connection without its keepalive, so both are taken or neither is.
 */
internal fun findMqttTelemetry(classes: Iterable<ClassDef>): List<Method> {
    val methods = classes.flatMap { it.methods }
    val publish = methods.filter { it.isMqttPublish() && it.notSentAnswer() != null }
    val keepalive = methods.filter { it.isMqttKeepalive() && it.healthKeepaliveFlag() != null }
    if (publish.size != 1 || keepalive.size != 1 || publish.single().definingClass != keepalive.single().definingClass) return emptyList()
    return publish + keepalive
}

internal fun MutableMethod.validateMqttTelemetry() {
    when {
        isMqttPublish() -> {
            validateScratch()
            if (notSentAnswer() == null) mqttChanged("no not-connected answer in ${hookId()}")
            if (0 in jumpTargets()) mqttChanged("a jump back to the start of ${hookId()}")
        }
        isMqttKeepalive() -> {
            val flag = healthKeepaliveFlag() ?: mqttChanged("no health report keepalive flag in ${hookId()}")
            // A jump straight to the test would read the flag as Messenger set it.
            if (flag + 1 in jumpTargets()) mqttChanged("a jump past the keepalive flag in ${hookId()}")
        }
        else -> mqttChanged("unexpected method ${hookId()}")
    }
}

/**
 * The publish asks the extension about its topic first, and a logging topic gets the publish's own not-connected
 * answer, so nothing is written to the connection and no acknowledgement is waited on. The keepalive hands its
 * health-report flag to the extension right after reading it, so with the switch on it sends Messenger's plain ping,
 * the branch it takes whenever that flag is off. Off, Pause and safe mode run Messenger's own code.
 */
internal fun MutableMethod.injectMqttTelemetry() {
    validateMqttTelemetry()
    if (isMqttPublish()) {
        val topic = parameterRegister(PUBLISH_TOPIC)
        addInstructionsWithLabels(0, """
            invoke-static/range {v$topic .. v$topic}, $MQTT_PUBLISH_GATE
            move-result v0
            if-eqz v0, :stock_behavior
            sget-object v0, ${notSentAnswer()}
            return-object v0
        """.trimIndent(), ExternalLabel("stock_behavior", getInstruction(0)))
        return
    }
    val flag = healthKeepaliveFlag()!!
    val register = (implementation!!.instructions.elementAt(flag) as TwoRegisterInstruction).registerA
    addInstructions(flag + 1, """
        invoke-static/range {v$register .. v$register}, $MQTT_KEEPALIVE_GATE
        move-result v$register
    """.trimIndent())
}
