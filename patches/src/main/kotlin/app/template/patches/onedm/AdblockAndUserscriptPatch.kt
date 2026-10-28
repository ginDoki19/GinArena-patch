package app.template.patches.onedm

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.template.patches.shared.Constants.COMPATIBILITY_ONEDM
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/template/extension/onedm/OnedmCloudSync;"

private const val MODE_CLOUD_SYNC = "cloud_sync"
private const val MODE_LOCAL_TURBO = "local_turbo"
private const val MODE_STOCK = "stock"

private fun MethodReference.toSmali() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Method.hasSignature(returnType: String, vararg params: String): Boolean =
    this.returnType == returnType && parameterTypes.map { it.toString() } == params.toList()

private fun Method.containsStringLiteral(expected: String): Boolean =
    implementation?.instructions?.any { insn ->
        (insn.opcode == Opcode.CONST_STRING || insn.opcode == Opcode.CONST_STRING_JUMBO) &&
            insn.getReference<StringReference>()?.string == expected
    } == true

private fun MutableClass.requireMutableMethod(
    name: String,
    returnType: String,
    vararg params: String,
): MutableMethod =
    methods.firstOrNull { it.name == name && it.hasSignature(returnType, *params) }
        ?: throw PatchException("Missing method $name(${params.joinToString("")})$returnType on $type")

private fun MutableMethod.replaceBody(smali: String) {
    val count = instructions.size
    if (count > 0) {
        removeInstructions(0, count)
    }
    addInstructions(0, smali.trimIndent())
}

@Suppress("unused")
val adblockAndUserscriptPatch = bytecodePatch(
    name = "AdBlocker turbo & userscript bundle",
    description = "Syncs SHA-256-verified weekly pre-compiled AdBlocker binary engines (28 default lists " +
        "including OISD, HaGeZi Pro, uBlock, EasyList, and AdGuard) from GitHub to eliminate 14-90s CPU " +
        "compilation lag on low-end devices with automatic Local Turbo fallback, and optionally seeds and " +
        "auto-updates the mobile-adapted Bypass All Shortlinks userscript.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_ONEDM)

    extendWith("extensions/onedm.mpe")

    val adblockerMode by stringOption(
        key = "adblockerMode",
        default = MODE_CLOUD_SYNC,
        values = linkedMapOf(
            "Cloud Pre-Built + Weekly Auto-Sync (Recommended)" to MODE_CLOUD_SYNC,
            "Local Turbo Parser + Weekly Auto-Rebuild" to MODE_LOCAL_TURBO,
            "Stock 1DM" to MODE_STOCK,
        ),
        title = "AdBlocker engine mode",
        description = "Cloud Pre-Built downloads SHA-256-verified weekly pre-compiled binary filter engines " +
            "from GitHub to skip heavy on-device compilation, falling back automatically to Local Turbo if " +
            "you customize filter lists. Local Turbo keeps all compilation on-device with buffered I/O, " +
            "background CPU priority, and 7-day auto-update.",
        required = true,
    )

    val bundleBypassShortlinks by booleanOption(
        key = "bundleBypassShortlinks",
        default = true,
        title = "Bundle Bypass All Shortlinks userscript",
        description = "Seeds and weekly auto-updates the mobile-adapted Bypass All Shortlinks Debloated " +
            "userscript (.1dmus with offline-cached MonkeyConfig, explicit @connect hosts, and zero warnings). " +
            "Preserves your enabled/disabled state and never re-installs if deleted.",
        required = true,
    )

    execute {
        val selectedMode = adblockerMode?.trim()?.lowercase()
            ?: throw PatchException("adblockerMode option must not be null")
        if (selectedMode !in setOf(MODE_CLOUD_SYNC, MODE_LOCAL_TURBO, MODE_STOCK)) {
            throw PatchException(
                "Invalid adblockerMode \"$selectedMode\". Expected one of: " +
                    "$MODE_CLOUD_SYNC, $MODE_LOCAL_TURBO, $MODE_STOCK"
            )
        }

        val enableCloudAdblock = selectedMode == MODE_CLOUD_SYNC
        val enableLocalTurbo = selectedMode == MODE_CLOUD_SYNC || selectedMode == MODE_LOCAL_TURBO
        val enableBypassScript = bundleBypassShortlinks == true

        // ---------------------------------------------------------------------
        // 1. Resolve dynamic 1DM method references via collision-safe fingerprints
        // ---------------------------------------------------------------------
        val validateBinsMethod = AdblockBinaryEngineValidateFingerprint.originalMethod

        val setAdblockLastUpdatedMethod = SettingsSetAdblockLastUpdatedFingerprint.originalMethod
        val settingsClassType = setAdblockLastUpdatedMethod.definingClass

        val adblockUpdateOriginal = CheckAppVersionAdblockUpdateFingerprint.originalMethod
        val getSettingsSingletonMethod = adblockUpdateOriginal.instructions.firstNotNullOfOrNull { insn ->
            if (insn.opcode != Opcode.INVOKE_STATIC) return@firstNotNullOfOrNull null
            val ref = insn.getReference<MethodReference>() ?: return@firstNotNullOfOrNull null
            ref.takeIf {
                it.returnType == settingsClassType &&
                    it.parameterTypes.map { p -> p.toString() } == listOf(CONTEXT)
            }
        } ?: throw PatchException("Could not resolve settings singleton getter in $adblockUpdateOriginal")

        val adblockRebuildOriginal = CheckAppVersionAdblockRebuildFingerprint.originalMethod
        val getAllAdblockSourcesMethod = adblockRebuildOriginal.instructions.firstNotNullOfOrNull { insn ->
            if (insn.opcode != Opcode.INVOKE_VIRTUAL) return@firstNotNullOfOrNull null
            val ref = insn.getReference<MethodReference>() ?: return@firstNotNullOfOrNull null
            ref.takeIf {
                it.returnType == "Ljava/util/ArrayList;" &&
                    it.parameterTypes.map { p -> p.toString() } == listOf(ATOMIC_INTEGER)
            }
        } ?: throw PatchException("Could not resolve getAllAdblockSources on a61 in $adblockRebuildOriginal")

        val adblockDbClassType = getAllAdblockSourcesMethod.definingClass
        val getAdblockDbSingletonMethod = adblockRebuildOriginal.instructions.firstNotNullOfOrNull { insn ->
            if (insn.opcode != Opcode.INVOKE_STATIC) return@firstNotNullOfOrNull null
            val ref = insn.getReference<MethodReference>() ?: return@firstNotNullOfOrNull null
            ref.takeIf {
                it.returnType == adblockDbClassType &&
                    it.parameterTypes.map { p -> p.toString() } == listOf(CONTEXT)
            }
        } ?: throw PatchException("Could not resolve a61 singleton getter in $adblockRebuildOriginal")

        val trayGetLongMethod = adblockRebuildOriginal.instructions.firstNotNullOfOrNull { insn ->
            if (insn.opcode != Opcode.INVOKE_VIRTUAL) return@firstNotNullOfOrNull null
            val ref = insn.getReference<MethodReference>() ?: return@firstNotNullOfOrNull null
            ref.takeIf {
                it.returnType == "J" &&
                    it.parameterTypes.map { p -> p.toString() } == listOf("Ljava/lang/String;", "J")
            }
        } ?: throw PatchException("Could not resolve Tray getLong(String, J)J in $adblockRebuildOriginal")

        val trayClassType = trayGetLongMethod.definingClass
        val getTraySingletonMethod = adblockRebuildOriginal.instructions.firstNotNullOfOrNull { insn ->
            if (insn.opcode != Opcode.INVOKE_STATIC) return@firstNotNullOfOrNull null
            val ref = insn.getReference<MethodReference>() ?: return@firstNotNullOfOrNull null
            ref.takeIf {
                it.returnType == trayClassType &&
                    it.parameterTypes.map { p -> p.toString() } == listOf(CONTEXT)
            }
        } ?: throw PatchException("Could not resolve Tray singleton getter in $adblockRebuildOriginal")

        val rebuildDoneHelperMethod = CheckAppVersionAdblockRebuildFingerprint.originalClassDef.methods.firstOrNull {
            !AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.hasSignature("V", "J") &&
                it.containsStringLiteral("ab_rebuild_done")
        } ?: throw PatchException("Could not resolve ab_rebuild_done helper on CheckAppVersion")

        val trayPutLongMethod = rebuildDoneHelperMethod.implementation?.instructions?.firstNotNullOfOrNull { insn ->
            if (insn.opcode != Opcode.INVOKE_VIRTUAL) return@firstNotNullOfOrNull null
            val ref = insn.getReference<MethodReference>() ?: return@firstNotNullOfOrNull null
            ref.takeIf {
                it.definingClass == trayClassType &&
                    it.returnType == "Z" &&
                    it.parameterTypes.map { p -> p.toString() } == listOf("J", "Ljava/lang/String;")
            }
        } ?: throw PatchException("Could not resolve Tray putLong(J, String)Z in $rebuildDoneHelperMethod")

        val ug7OriginalClass = UserscriptManagerInitFingerprint.originalClassDef
        val ug7GetInstanceMethod = ug7OriginalClass.methods.firstOrNull {
            AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.hasSignature(ug7OriginalClass.type, CONTEXT)
        } ?: throw PatchException("Could not resolve static getInstance(Context) on ${ug7OriginalClass.type}")

        val ug7EnsureLoadedMethod = ug7OriginalClass.methods.firstOrNull {
            !AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.hasSignature("Z") &&
                it.containsStringLiteral("Error loading scripts cache")
        } ?: throw PatchException("Could not resolve ensureCacheLoaded()Z on ${ug7OriginalClass.type}")

        val ug7GetByIdMethod = ug7OriginalClass.methods.firstOrNull {
            !AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.hasSignature(USER_SCRIPT, "Ljava/lang/String;")
        } ?: throw PatchException("Could not resolve getById(String)UserScript on ${ug7OriginalClass.type}")

        val ug7ImportScriptsMethod = ug7OriginalClass.methods.firstOrNull {
            !AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.hasSignature("Z", "Ljava/util/List;")
        } ?: throw PatchException("Could not resolve importScripts(List)Z on ${ug7OriginalClass.type}")

        // ---------------------------------------------------------------------
        // 2. Wire OnedmCloudSync configuration flags and 1DM runtime bridges
        // ---------------------------------------------------------------------
        val extClass = mutableClassDefBy(EXTENSION_CLASS_DESCRIPTOR)

        extClass.requireMutableMethod("isCloudAdblockEnabled", "Z").replaceBody(
            """
                const/4 v0, ${if (enableCloudAdblock) "0x1" else "0x0"}
                return v0
            """
        )

        extClass.requireMutableMethod("isBypassUserscriptEnabled", "Z").replaceBody(
            """
                const/4 v0, ${if (enableBypassScript) "0x1" else "0x0"}
                return v0
            """
        )

        extClass.requireMutableMethod(
            "bridgeGetAllAdblockSources",
            "Ljava/util/ArrayList;",
            CONTEXT,
            ATOMIC_INTEGER,
        ).replaceBody(
            """
                invoke-static {p0}, ${getAdblockDbSingletonMethod.toSmali()}
                move-result-object p0
                invoke-virtual {p0, p1}, ${getAllAdblockSourcesMethod.toSmali()}
                move-result-object p0
                return-object p0
            """
        )

        extClass.requireMutableMethod(
            "bridgeValidateAdblockBins",
            "Z",
            CONTEXT,
        ).replaceBody(
            """
                invoke-static {p0}, ${validateBinsMethod.toSmali()}
                move-result p0
                return p0
            """
        )

        extClass.requireMutableMethod(
            "bridgeRecordAdblockUpdatedTimestamp",
            "V",
            CONTEXT,
            "J",
        ).replaceBody(
            """
                invoke-static {p0}, ${getSettingsSingletonMethod.toSmali()}
                move-result-object p0
                invoke-virtual {p0, p1, p2}, ${setAdblockLastUpdatedMethod.toSmali()}
                return-void
            """
        )

        extClass.requireMutableMethod(
            "bridgeGetTrayLong",
            "J",
            CONTEXT,
            "Ljava/lang/String;",
            "J",
        ).replaceBody(
            """
                invoke-static {p0}, ${getTraySingletonMethod.toSmali()}
                move-result-object p0
                invoke-virtual {p0, p1, p2, p3}, ${trayGetLongMethod.toSmali()}
                move-result-wide p0
                return-wide p0
            """
        )

        extClass.requireMutableMethod(
            "bridgePutTrayLong",
            "Z",
            CONTEXT,
            "J",
            "Ljava/lang/String;",
        ).replaceBody(
            """
                invoke-static {p0}, ${getTraySingletonMethod.toSmali()}
                move-result-object p0
                invoke-virtual {p0, p1, p2, p3}, ${trayPutLongMethod.toSmali()}
                move-result p0
                return p0
            """
        )

        extClass.requireMutableMethod(
            "bridgeGetUserscriptById",
            USER_SCRIPT,
            CONTEXT,
            "Ljava/lang/String;",
        ).replaceBody(
            """
                invoke-static {p0}, ${ug7GetInstanceMethod.toSmali()}
                move-result-object p0
                invoke-virtual {p0}, ${ug7EnsureLoadedMethod.toSmali()}
                invoke-virtual {p0, p1}, ${ug7GetByIdMethod.toSmali()}
                move-result-object p0
                return-object p0
            """
        )

        extClass.requireMutableMethod(
            "bridgeImportUserscript",
            "Z",
            CONTEXT,
            "Ljava/util/List;",
        ).replaceBody(
            """
                invoke-static {p0}, ${ug7GetInstanceMethod.toSmali()}
                move-result-object p0
                invoke-virtual {p0, p1}, ${ug7ImportScriptsMethod.toSmali()}
                move-result p0
                return p0
            """
        )

        // ---------------------------------------------------------------------
        // 3. Local Turbo optimizations (active in both cloud_sync & local_turbo)
        // ---------------------------------------------------------------------
        if (enableLocalTurbo) {
            // (a) Enable 7-day automatic AdBlocker filter updates by default in AdblockerSettingsInfo.<init>()
            val settingsInit = AdblockerSettingsInfoInitFingerprint.method
            val enableAutoUpdatePutIdx = settingsInit.instructions.indexOfFirst { insn ->
                insn.opcode == Opcode.IPUT_BOOLEAN &&
                    insn.getReference<FieldReference>()?.let {
                        it.definingClass == ADBLOCKER_SETTINGS_INFO && it.name == "enableAutoUpdate"
                    } == true
            }
            if (enableAutoUpdatePutIdx <= 0) {
                throw PatchException("Could not find enableAutoUpdate field init in $settingsInit")
            }
            val putReg = (settingsInit.getInstruction(enableAutoUpdatePutIdx) as TwoRegisterInstruction).registerA
            val prevInsn = settingsInit.getInstruction(enableAutoUpdatePutIdx - 1)
            if (prevInsn.opcode != Opcode.CONST_4 ||
                (prevInsn as OneRegisterInstruction).registerA != putReg ||
                (prevInsn as NarrowLiteralInstruction).narrowLiteral != 0
            ) {
                throw PatchException("Unexpected instruction before enableAutoUpdate init in $settingsInit")
            }
            settingsInit.removeInstruction(enableAutoUpdatePutIdx - 1)
            settingsInit.addInstructions(enableAutoUpdatePutIdx - 1, "const/4 v$putReg, 0x1")

            // (b) Replace 4x 1-byte RandomAccessFile.write(I)V syscalls in j12's int32 writer
            //     with a single 4-byte RandomAccessFile.write([B)V call (75% fewer write syscalls).
            val j12Class = AdblockBinaryEngineValidateFingerprint.classDef
            val writeInt32Method = j12Class.methods.firstOrNull {
                AccessFlags.STATIC.isSet(it.accessFlags) &&
                    it.hasSignature("V", "Ljava/io/RandomAccessFile;", "I", "I")
            } ?: throw PatchException("Could not find static (RandomAccessFile, int, int)V writer on ${j12Class.type}")

            val writeInsns = writeInt32Method.instructions.toList()
            val seekIdx = writeInsns.indexOfFirst { insn ->
                val ref = insn.getReference<MethodReference>() ?: return@indexOfFirst false
                ref.definingClass == "Ljava/io/RandomAccessFile;" &&
                    ref.name == "seek" &&
                    ref.returnType == "V"
            }
            if (seekIdx != 3 || writeInt32Method.implementation!!.registerCount != 7) {
                throw PatchException(
                    "Unexpected register/seek layout in $writeInt32Method " +
                        "(seekIdx=$seekIdx, regs=${writeInt32Method.implementation!!.registerCount})"
                )
            }
            val tailCount = writeInsns.size - (seekIdx + 1)
            writeInt32Method.removeInstructions(seekIdx + 1, tailCount)
            writeInt32Method.addInstructions(
                seekIdx + 1,
                """
                    const/4 v0, 0x4
                    new-array v0, v0, [B
                    ushr-int/lit8 v1, v6, 0x18
                    int-to-byte v1, v1
                    const/4 v2, 0x0
                    aput-byte v1, v0, v2
                    ushr-int/lit8 v1, v6, 0x10
                    int-to-byte v1, v1
                    const/4 v2, 0x1
                    aput-byte v1, v0, v2
                    ushr-int/lit8 v1, v6, 0x8
                    int-to-byte v1, v1
                    const/4 v2, 0x2
                    aput-byte v1, v0, v2
                    int-to-byte v1, v6
                    const/4 v2, 0x3
                    aput-byte v1, v0, v2
                    invoke-virtual {v4, v0}, Ljava/io/RandomAccessFile;->write([B)V
                    return-void
                """.trimIndent()
            )

            // (c) Lower j12's compiler worker thread priority from foreground 0 to
            //     THREAD_PRIORITY_BACKGROUND (10) in dead register v0 while preserving v3 = 0.
            val compileWorker = j12Class.methods.firstOrNull { method ->
                method.implementation?.instructions?.any { insn ->
                    val ref = insn.getReference<MethodReference>() ?: return@any false
                    ref.definingClass == "Landroid/os/Process;" && ref.name == "setThreadPriority"
                } == true
            } ?: throw PatchException("Could not find Process.setThreadPriority worker on ${j12Class.type}")

            val prioIdx = compileWorker.instructions.indexOfFirst { insn ->
                val ref = insn.getReference<MethodReference>() ?: return@indexOfFirst false
                ref.definingClass == "Landroid/os/Process;" && ref.name == "setThreadPriority"
            }
            if (prioIdx <= 0) {
                throw PatchException("Unexpected setThreadPriority position $prioIdx in $compileWorker")
            }
            val afterPrio = compileWorker.instructions.drop(prioIdx + 1).take(3)
            val v0OverwrittenCleanly = afterPrio.any { insn ->
                insn.opcode == Opcode.IGET_OBJECT && (insn as OneRegisterInstruction).registerA == 0
            }
            if (!v0OverwrittenCleanly) {
                throw PatchException("Expected dead v0 before IGET_OBJECT in $compileWorker")
            }
            compileWorker.removeInstruction(prioIdx)
            compileWorker.addInstructions(
                prioIdx,
                """
                    const/16 v0, 0xa
                    invoke-static {v0}, Landroid/os/Process;->setThreadPriority(I)V
                """.trimIndent()
            )
        }

        // ---------------------------------------------------------------------
        // 4. Hook CheckAppVersion & kh7 for Cloud Sync + Userscript seeding
        // ---------------------------------------------------------------------
        if (enableCloudAdblock || enableBypassScript) {
            val abUpdateMethod = CheckAppVersionAdblockUpdateFingerprint.method
            abUpdateMethod.addInstructionsWithLabels(
                0,
                """
                    invoke-static {p0}, $EXTENSION_CLASS_DESCRIPTOR->onAdblockCheckHook(Landroid/content/Context;)Z
                    move-result v0
                    if-eqz v0, :onedm_ab_local_path
                    return-void
                """.trimIndent(),
                ExternalLabel("onedm_ab_local_path", abUpdateMethod.getInstruction(0))
            )

            val abRebuildMethod = CheckAppVersionAdblockRebuildFingerprint.method
            abRebuildMethod.addInstructionsWithLabels(
                0,
                """
                    invoke-static {p0}, $EXTENSION_CLASS_DESCRIPTOR->onAdblockRebuildHook(Landroid/content/Context;)Z
                    move-result v0
                    if-eqz v0, :onedm_ab_rebuild_local
                    return-void
                """.trimIndent(),
                ExternalLabel("onedm_ab_rebuild_local", abRebuildMethod.getInstruction(0))
            )
        }

        if (enableBypassScript) {
            val usUpdateMethod = CheckAppVersionUserscriptUpdateFingerprint.method
            usUpdateMethod.addInstructions(
                0,
                "invoke-static {p0}, $EXTENSION_CLASS_DESCRIPTOR->onUserscriptCheckHook(Landroid/content/Context;)V"
            )

            val singleScriptUpdateMethod = UserscriptSingleUpdateCheckFingerprint.method
            singleScriptUpdateMethod.addInstructionsWithLabels(
                0,
                """
                    invoke-static {p1}, $EXTENSION_CLASS_DESCRIPTOR->shouldInterceptUserscriptUpdate($USER_SCRIPT)Z
                    move-result v0
                    if-eqz v0, :onedm_us_stock_update
                    invoke-static {p0, p1}, $EXTENSION_CLASS_DESCRIPTOR->fetchUpdatedBypassUserscript(Landroid/content/Context;$USER_SCRIPT)$USER_SCRIPT
                    move-result-object v0
                    return-object v0
                """.trimIndent(),
                ExternalLabel("onedm_us_stock_update", singleScriptUpdateMethod.getInstruction(0))
            )
        }
    }
}
