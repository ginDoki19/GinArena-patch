package app.template.patches.mxplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal fun Method.parameterRegisterCount(): Int {
    val receiverRegs = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    val argRegs = parameterTypes.sumOf { type ->
        if (type == "J" || type == "D") 2 else 1
    }
    return receiverRegs + argRegs
}

internal fun Method.containsString(substring: String): Boolean {
    val impl = implementation ?: return false
    return impl.instructions.any { insn ->
        val str = ((insn as? ReferenceInstruction)?.reference as? StringReference)?.string
        str != null && substring in str
    }
}

internal fun MutableClass.replaceMethodBody(
    method: Method,
    localRegisters: Int,
    smaliInstructions: String,
) {
    if (AccessFlags.ABSTRACT.isSet(method.accessFlags) || AccessFlags.NATIVE.isSet(method.accessFlags)) {
        throw PatchException("Refusing to rewrite abstract/native method ${type}->${method.name}")
    }
    val totalRegisters = method.parameterRegisterCount() + localRegisters
    val mutableCopy = ImmutableMethod(
        method.definingClass,
        method.name,
        method.parameters,
        method.returnType,
        method.accessFlags,
        method.annotations,
        method.hiddenApiRestrictions,
        MutableMethodImplementation(totalRegisters),
    ).toMutable()
    mutableCopy.addInstructionsWithLabels(0, smaliInstructions)
    methods.remove(method)
    methods.add(mutableCopy)
}

internal fun MutableClass.returnVoid(method: Method) {
    replaceMethodBody(
        method = method,
        localRegisters = 0,
        smaliInstructions = "return-void",
    )
}

internal fun MutableClass.returnBoolean(method: Method, value: Boolean) {
    val literal = if (value) "0x1" else "0x0"
    replaceMethodBody(
        method = method,
        localRegisters = 1,
        smaliInstructions = """
            const/4 v0, $literal
            return v0
        """.trimIndent(),
    )
}

internal fun MutableClass.returnNull(method: Method) {
    replaceMethodBody(
        method = method,
        localRegisters = 1,
        smaliInstructions = """
            const/4 v0, 0x0
            return-object v0
        """.trimIndent(),
    )
}

internal fun MutableClass.returnUnitInstance(method: Method) {
    replaceMethodBody(
        method = method,
        localRegisters = 1,
        smaliInstructions = """
            sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
            return-object v0
        """.trimIndent(),
    )
}

internal fun MutableClass.requireMethod(
    name: String,
    returnType: String? = null,
    parameterTypes: List<String>? = null,
): MutableMethod = methods.firstOrNull { candidate ->
    candidate.name == name &&
        (returnType == null || candidate.returnType == returnType) &&
        (parameterTypes == null || candidate.parameterTypes == parameterTypes)
} ?: throw PatchException(
    "Expected method $type->$name(${parameterTypes?.joinToString("") ?: "*"}) not found",
)

/**
 * Idempotently patches `ThemeListActivity` and its `"Local Classic Theme"` observer so:
 * 1. All 31 Classic Theme color swatches are always synthesized if absent from the loaded list,
 *    even when Wi-Fi / mobile data is connected (`isNetworkConnected(Context)Z -> false` inside
 *    the observer only).
 * 2. Neither the `NoNetworkTips` adapter item (`ArrayList.add(0, item)`) nor the `ThemeListActivity`
 *    offline overlay (`onCreate` network check -> `true`) is ever shown.
 *
 * Safe to invoke from both `Remove ads & bloat` and `Clean App Theme` in the same session.
 */
internal fun BytecodePatchContext.ensureClassicThemesAlwaysAvailable() {
    val observerMethod = ClassicThemeObserverFingerprint.method
    val classicStrIdx = observerMethod.instructions.indexOfFirst { insn ->
        ((insn as? ReferenceInstruction)?.reference as? StringReference)?.string == "Local Classic Theme"
    }
    if (classicStrIdx < 0) {
        throw PatchException("Local Classic Theme anchor not found in ${observerMethod.definingClass}")
    }

    val searchStart = (classicStrIdx - 40).coerceAtLeast(0)
    val netCheckIdx = (searchStart until classicStrIdx).firstOrNull { idx ->
        val insn = observerMethod.getInstruction(idx)
        val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
        insn.opcode == Opcode.INVOKE_STATIC &&
            ref?.parameterTypes == listOf("Landroid/content/Context;") &&
            ref.returnType == "Z"
    }
    if (netCheckIdx != null) {
        val destReg = (observerMethod.getInstruction(netCheckIdx + 1) as OneRegisterInstruction).registerA
        observerMethod.replaceInstruction(netCheckIdx, "nop")
        observerMethod.replaceInstruction(netCheckIdx + 1, "const/4 v$destReg, 0x0")
    } else {
        val alreadyPatched = (searchStart until classicStrIdx).any { idx ->
            observerMethod.getInstruction(idx).opcode == Opcode.NOP
        }
        if (!alreadyPatched) {
            throw PatchException("Network gate not found before Local Classic Theme in ${observerMethod.definingClass}")
        }
    }

    val searchEnd = minOf(observerMethod.instructions.size, classicStrIdx + 150)
    val prependTipIdx = ((classicStrIdx + 1) until searchEnd).firstOrNull { idx ->
        val insn = observerMethod.getInstruction(idx)
        val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
        insn.opcode == Opcode.INVOKE_VIRTUAL &&
            ref?.definingClass == "Ljava/util/ArrayList;" &&
            ref.name == "add" &&
            ref.parameterTypes == listOf("I", "Ljava/lang/Object;")
    }
    if (prependTipIdx != null) {
        observerMethod.replaceInstruction(prependTipIdx, "nop")
    }

    val onCreateMethod = ThemeListActivityOnCreateFingerprint.method
    val activityNetIdx = onCreateMethod.instructions.indexOfFirst { insn ->
        val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
        insn.opcode == Opcode.INVOKE_STATIC &&
            ref?.parameterTypes == listOf("Landroid/content/Context;") &&
            ref.returnType == "Z"
    }
    if (activityNetIdx >= 0) {
        val destReg = (onCreateMethod.getInstruction(activityNetIdx + 1) as OneRegisterInstruction).registerA
        onCreateMethod.replaceInstruction(activityNetIdx, "nop")
        onCreateMethod.replaceInstruction(activityNetIdx + 1, "const/4 v$destReg, 0x1")
    }
}

internal data class LocalMeBindingInfo(
    val baseBindingField: FieldReference,
    val bindingType: String,
    val promoAndAdFields: List<FieldReference>,
    val rowFieldsByLabel: Map<String, FieldReference>,
    val statusSaverBadgeField: FieldReference?,
)

internal fun resolveLocalMeBinding(localMeClass: MutableClass): LocalMeBindingInfo {
    val onClick = localMeClass.requireMethod("onClick", "V", listOf("Landroid/view/View;"))
    val insns = onClick.instructions

    val baseBindingField = insns
        .filterIsInstance<ReferenceInstruction>()
        .mapNotNull { it.reference as? FieldReference }
        .firstOrNull { it.definingClass == LOCAL_ME_BASE_FRAGMENT_CLASS }
        ?: throw PatchException("Failed to locate ViewBinding field on $LOCAL_ME_BASE_FRAGMENT_CLASS")

    val bindingType = insns
        .filter { it.opcode == Opcode.CHECK_CAST }
        .mapNotNull { ((it as? ReferenceInstruction)?.reference as? TypeReference)?.type }
        .firstOrNull { it != "Landroid/view/View;" }
        ?: throw PatchException("Failed to resolve LocalMeFragment ViewBinding type")

    val targetLabels = listOf(
        "WhatsApp Status Saver",
        "Custom PIP Controls",
        "Legal",
        "Help",
        "DataPrivacy",
    )
    val rowFields = LinkedHashMap<String, FieldReference>()
    var statusSaverBadgeField: FieldReference? = null

    for (label in targetLabels) {
        val strIdx = insns.indexOfFirst { insn ->
            ((insn as? ReferenceInstruction)?.reference as? StringReference)?.string == label
        }
        if (strIdx < 0) {
            throw PatchException("Label '$label' not found in LocalMeFragment->onClick")
        }
        var foundField: FieldReference? = null
        for (idx in strIdx downTo 0) {
            val fRef = (insns[idx] as? ReferenceInstruction)?.reference as? FieldReference ?: continue
            if (fRef.definingClass != bindingType) continue
            if (label == "WhatsApp Status Saver" &&
                statusSaverBadgeField == null &&
                fRef.type == "Landroidx/appcompat/widget/AppCompatTextView;"
            ) {
                statusSaverBadgeField = fRef
            }
            if (fRef.type == "Landroidx/appcompat/widget/LinearLayoutCompat;") {
                foundField = fRef
                break
            }
        }
        rowFields[label] = foundField
            ?: throw PatchException("Row field for '$label' not found on $bindingType")
    }

    val onConfigChanged = localMeClass.requireMethod(
        "onConfigurationChanged",
        "V",
        listOf("Landroid/content/res/Configuration;"),
    )
    val promoAndAdFields = onConfigChanged.instructions
        .filterIsInstance<ReferenceInstruction>()
        .mapNotNull { it.reference as? FieldReference }
        .filter { it.definingClass == bindingType }
        .distinctBy { "${it.name}:${it.type}" }

    if (promoAndAdFields.isEmpty()) {
        throw PatchException("Failed to resolve promo/ad container fields in LocalMeFragment->onConfigurationChanged")
    }

    return LocalMeBindingInfo(
        baseBindingField = baseBindingField,
        bindingType = bindingType,
        promoAndAdFields = promoAndAdFields,
        rowFieldsByLabel = rowFields,
        statusSaverBadgeField = statusSaverBadgeField,
    )
}

/**
 * Appends smali right before the final `return-void` of [this] method while preserving all
 * existing branch `Label`s that jump to `return-void`!
 *
 * CRITICAL DEXLIB2 INVARIANT:
 * Calling `addInstructions(returnIdx, ...)` alone leaves pre-existing branch labels (such as
 * early `if-eqz` jumps to `return-void`) attached to `return-void`, bypassing the inserted code.
 * Replacing `returnIdx` with `"nop"` FIRST keeps every branch label anchored to `"nop"`, so both
 * fallthrough paths and conditional branches execute [smaliBlock] before hitting the new
 * `return-void` appended at `returnIdx + 1`.
 */
internal fun MutableMethod.appendBeforeReturnVoid(smaliBlock: String) {
    val returnIdx = instructions.indexOfLast { it.opcode == Opcode.RETURN_VOID }
    if (returnIdx < 0) {
        throw PatchException("return-void not found in ${definingClass}->${name}")
    }
    replaceInstruction(returnIdx, "nop")
    addInstructionsWithLabels(
        returnIdx + 1,
        """
            $smaliBlock
            return-void
        """.trimIndent(),
    )
}

internal fun MutableMethod.appendHideBindingViews(
    bindingInfo: LocalMeBindingInfo,
    fieldsToHide: List<FieldReference>,
    hideParentsOf: List<FieldReference> = emptyList(),
) {
    if (fieldsToHide.isEmpty() && hideParentsOf.isEmpty()) return
    val tag = instructions.size

    val smali = buildString {
        appendLine("iget-object v0, p0, ${bindingInfo.baseBindingField}")
        appendLine("if-eqz v0, :cond_binding_done_$tag")
        appendLine("const/16 v1, 0x8")
        for ((idx, field) in fieldsToHide.withIndex()) {
            val skipLabel = "skip_view_${tag}_$idx"
            appendLine("iget-object v0, p0, ${bindingInfo.baseBindingField}")
            appendLine("check-cast v0, ${bindingInfo.bindingType}")
            appendLine("iget-object v0, v0, $field")
            appendLine("if-eqz v0, :$skipLabel")
            appendLine("invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V")
            appendLine(":$skipLabel")
        }
        for ((idx, field) in hideParentsOf.withIndex()) {
            val skipParentLabel = "skip_parent_${tag}_$idx"
            appendLine("iget-object v0, p0, ${bindingInfo.baseBindingField}")
            appendLine("check-cast v0, ${bindingInfo.bindingType}")
            appendLine("iget-object v0, v0, $field")
            appendLine("if-eqz v0, :$skipParentLabel")
            appendLine("invoke-virtual {v0}, Landroid/view/View;->getParent()Landroid/view/ViewParent;")
            appendLine("move-result-object v0")
            appendLine("instance-of v1, v0, Landroid/view/View;")
            appendLine("if-eqz v1, :$skipParentLabel")
            appendLine("check-cast v0, Landroid/view/View;")
            appendLine("const/16 v1, 0x8")
            appendLine("invoke-virtual {v0, v1}, Landroid/view/View;->setVisibility(I)V")
            appendLine(":$skipParentLabel")
        }
        append(":cond_binding_done_$tag")
    }

    appendBeforeReturnVoid(smali)
}

/**
 * Resolves `TabType.ME`'s `normalIcon` drawable resource ID directly at patch time from
 * `TabType.<init>` and `TabType.<clinit>` (`0x7f081293` in 3.3.1, `0x7f081252` in 3.4.1, etc.).
 *
 * CRITICAL: `TabType.normalIcon` is `private final int` (`accessFlags = 0x12`), so reading
 * `iget v1, v1, TabType;->normalIcon:I` from `LocalTabActivityMediaList` throws an immediate
 * `java.lang.IllegalAccessError` on Android ART at cold open. Resolving the integer resource ID
 * constant at patch time avoids any runtime field/method access on `TabType` altogether.
 */
internal fun resolveMeNormalIconResId(tabTypeClass: ClassDef): Int {
    val ctor = tabTypeClass.methods.firstOrNull { it.name == "<init>" }
        ?: throw PatchException("TabType.<init> not found on ${tabTypeClass.type}")
    val ctorImpl = ctor.implementation
        ?: throw PatchException("TabType.<init> has no implementation")
    val ctorLocalRegs = ctorImpl.registerCount - ctor.parameterRegisterCount()
    val normalIconParamOffset = ctorImpl.instructions.firstNotNullOfOrNull { insn ->
        val fRef = (insn as? ReferenceInstruction)?.reference as? FieldReference
        if (insn.opcode == Opcode.IPUT &&
            fRef?.definingClass == tabTypeClass.type &&
            fRef.name == "normalIcon" &&
            fRef.type == "I"
        ) {
            (insn as TwoRegisterInstruction).registerA - ctorLocalRegs
        } else {
            null
        }
    } ?: throw PatchException("iput TabType->normalIcon:I not found in TabType.<init>")

    val clinit = tabTypeClass.methods.firstOrNull {
        it.name == "<clinit>" && it.returnType == "V" && it.parameterTypes.isEmpty()
    } ?: throw PatchException("TabType.<clinit> not found on ${tabTypeClass.type}")
    val clinitInsns = clinit.implementation?.instructions?.toList()
        ?: throw PatchException("TabType.<clinit> has no implementation")
    val meSputIdx = clinitInsns.indexOfFirst { insn ->
        val fRef = (insn as? ReferenceInstruction)?.reference as? FieldReference
        insn.opcode == Opcode.SPUT_OBJECT &&
            fRef?.definingClass == tabTypeClass.type &&
            fRef.name == "ME"
    }
    if (meSputIdx < 2) {
        throw PatchException("sput-object TabType->ME not found in TabType.<clinit>")
    }

    val meInvokeIdx = (meSputIdx - 1 downTo (meSputIdx - 5).coerceAtLeast(0)).firstOrNull { idx ->
        val insn = clinitInsns[idx]
        val mRef = (insn as? ReferenceInstruction)?.reference as? MethodReference
        insn.opcode == Opcode.INVOKE_DIRECT_RANGE &&
            mRef?.definingClass == tabTypeClass.type &&
            mRef.name == "<init>"
    } ?: throw PatchException("invoke-direct/range TabType.<init> for ME not found in TabType.<clinit>")

    val invokeRange = clinitInsns[meInvokeIdx] as RegisterRangeInstruction
    val targetReg = invokeRange.startRegister + normalIconParamOffset

    val iconLiteral = (meInvokeIdx - 1 downTo (meInvokeIdx - 20).coerceAtLeast(0))
        .firstNotNullOfOrNull { idx ->
            val insn = clinitInsns[idx]
            if ((insn.opcode == Opcode.CONST || insn.opcode == Opcode.CONST_16 || insn.opcode == Opcode.CONST_4) &&
                (insn as? OneRegisterInstruction)?.registerA == targetReg
            ) {
                (insn as? NarrowLiteralInstruction)?.narrowLiteral
            } else {
                null
            }
        } ?: throw PatchException("Failed to extract TabType.ME normalIcon literal at reg v$targetReg")

    if ((iconLiteral ushr 16) != 0x7f08) {
        throw PatchException("Unexpected TabType.ME normalIcon resource ID: 0x${iconLiteral.toUInt().toString(16)}")
    }
    return iconLiteral
}
