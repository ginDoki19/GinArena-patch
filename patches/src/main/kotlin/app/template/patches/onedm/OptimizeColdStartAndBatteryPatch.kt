package app.template.patches.onedm

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.template.patches.shared.Constants.COMPATIBILITY_ONEDM
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val DEFERRED_INTENT_FIELD_NAME = "onedmDeferredCheckIntent"
private const val RUNNABLE_INTERFACE = "Ljava/lang/Runnable;"
private const val ATOMIC_BOOLEAN_TYPE = "Ljava/util/concurrent/atomic/AtomicBoolean;"
private const val HANDLER_TYPE = "Landroid/os/Handler;"

private fun FieldReference.toSmali() = "$definingClass->$name:$type"

private fun MethodReference.toSmali() =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

@Suppress("unused")
val optimizeColdStartAndBatteryPatch = bytecodePatch(
    name = "Optimize cold start and battery",
    description = "Eliminates cold-start Chromium WebView initialization in Download Manager and background services, defers Browser AdBlock filter parsing until Browser launch, defers the CheckAppVersion subprocess past startup, and pauses MainActivity's 1 Hz background wakeup loop when minimized.",
    default = true
) {
    compatibleWith(COMPATIBILITY_ONEDM)

    execute {
        // 1. BrowserApp.initApp:
        //    (a) Guard WebView.setWebContentsDebuggingEnabled(z) with `if (z)` so the default
        //        `false` path never loads the 120+ MB Android WebView engine on cold start in
        //        MainActivity or :DownloadService.
        //    (b) Skip eager AdBlock.Delegate.initialize (queued immediately before
        //        DaggerAppComponent.builder()); Dagger's @Singleton AdBlock(Context) constructor
        //        automatically initializes the AdBlock filter engine when BrowserActivity opens.
        val initApp = BrowserAppInitAppFingerprint.matchAll(1..1).first().method

        val webDebugIdx = initApp.instructions.indexOfFirst { insn ->
            val ref = insn.getReference<MethodReference>() ?: return@indexOfFirst false
            ref.definingClass == "Landroid/webkit/WebView;" &&
                ref.name == "setWebContentsDebuggingEnabled"
        }
        if (webDebugIdx < 0) {
            throw PatchException("Could not find WebView.setWebContentsDebuggingEnabled in $initApp")
        }
        if (initApp.getInstruction(webDebugIdx + 2).opcode != Opcode.MOVE_EXCEPTION) {
            throw PatchException("Unexpected try/catch layout around setWebContentsDebuggingEnabled in $initApp")
        }
        val debugFlagReg = (initApp.getInstruction(webDebugIdx) as FiveRegisterInstruction).registerC
        initApp.addInstructionsWithLabels(
            webDebugIdx,
            "if-eqz v$debugFlagReg, :onedm_skip_webview_debug",
            ExternalLabel("onedm_skip_webview_debug", initApp.getInstruction(webDebugIdx + 4))
        )

        val daggerBuilderIdx = initApp.instructions.indexOfFirst { insn ->
            insn.getReference<MethodReference>()?.definingClass ==
                "Lacr/browser/lightning/app/DaggerAppComponent;"
        }
        if (daggerBuilderIdx < 5) {
            throw PatchException("Could not find DaggerAppComponent.builder() in $initApp")
        }
        val eagerAdBlockStartIdx = daggerBuilderIdx - 5
        val eagerTaskType = initApp.getInstruction(daggerBuilderIdx - 3)
            .getReference<TypeReference>()?.type
        if (initApp.getInstruction(daggerBuilderIdx - 3).opcode != Opcode.NEW_INSTANCE ||
            eagerTaskType == null
        ) {
            throw PatchException("Unexpected eager AdBlock task layout before DaggerAppComponent in $initApp")
        }
        initApp.addInstructionsWithLabels(
            eagerAdBlockStartIdx,
            "goto :onedm_defer_adblock_to_browser",
            ExternalLabel("onedm_defer_adblock_to_browser", initApp.getInstruction(daggerBuilderIdx))
        )

        // 2. ActivityLifecycleListener.onActivityCreated:
        //    Run the one-time AppActionBackupService trigger on first Activity creation, and
        //    gate the Browser crash-exit cleanup (`key_app_possibly_killed_flag` -> WebView)
        //    behind `activity instanceof BrowserActivity` so opening Download Manager never
        //    cold-boots WebView or reads DefaultSharedPreferences on the UI thread.
        val onCreated = LifecycleOnActivityCreatedFingerprint.matchAll(1..1).first().method
        val appCreatedGetIdx = onCreated.instructions.indexOfFirst { insn ->
            insn.opcode == Opcode.IGET_OBJECT &&
                insn.getReference<FieldReference>()?.name == "appCreated"
        }
        if (appCreatedGetIdx < 0) {
            throw PatchException("Could not find appCreated check in $onCreated")
        }
        val firstIfNezIdx = appCreatedGetIdx + 3
        val setTrueCompleteIdx = appCreatedGetIdx + 6 // points to SGET_OBJECT AtomicInteger (Browser cleanup start)
        if (onCreated.getInstruction(firstIfNezIdx).opcode != Opcode.IF_NEZ) {
            throw PatchException("Unexpected appCreated branch opcode at $firstIfNezIdx in $onCreated")
        }

        // Extract the exact executor singleton + backup Runnable constructor from the tail of onActivityCreated.
        val returnVoidIdx = onCreated.instructions.size - 1
        if (onCreated.getInstruction(returnVoidIdx).opcode != Opcode.RETURN_VOID) {
            throw PatchException("Expected trailing return-void in $onCreated")
        }
        val backupExecutorStartIdx = returnVoidIdx - 5
        val getExecutorMethod = onCreated.getInstruction(backupExecutorStartIdx)
            .getReference<MethodReference>()?.toSmali()
            ?: throw PatchException("Could not resolve executor getter in $onCreated")
        val backupRunnableType = onCreated.getInstruction(backupExecutorStartIdx + 2)
            .getReference<TypeReference>()?.type
            ?: throw PatchException("Could not resolve backup Runnable type in $onCreated")
        val backupRunnableInit = onCreated.getInstruction(backupExecutorStartIdx + 3)
            .getReference<MethodReference>()?.toSmali()
            ?: throw PatchException("Could not resolve backup Runnable <init> in $onCreated")
        val executeRunnableMethod = onCreated.getInstruction(backupExecutorStartIdx + 4)
            .getReference<MethodReference>()?.toSmali()
            ?: throw PatchException("Could not resolve executor submit method in $onCreated")

        // First terminate the Browser cleanup merge target (@009b) with return-void so it does
        // not re-execute the backup task when BrowserActivity runs cleanup later.
        onCreated.addInstructionsAtControlFlowLabel(backupExecutorStartIdx, "return-void")

        // Replace `if-nez v5, :return_void` + `appCreated.set(true)` flow so:
        //  - First activity in process sets appCreated=true and queues the backup Runnable once
        //    (preserving v3=this, v4=activity, v0=1).
        //  - Non-Browser activities skip the Browser exit-cleanup block straight to return-void.
        val finalReturnInsn = onCreated.getInstruction(onCreated.instructions.size - 1)
        val browserCleanupStartInsn = onCreated.getInstruction(setTrueCompleteIdx)
        onCreated.removeInstruction(firstIfNezIdx)
        onCreated.addInstructionsWithLabels(
            firstIfNezIdx,
            "if-nez v5, :onedm_check_browser_cleanup",
            ExternalLabel("onedm_check_browser_cleanup", browserCleanupStartInsn)
        )
        // setTrueCompleteIdx is still the instruction immediately after `appCreated.set(v0)`.
        onCreated.addInstructionsWithLabels(
            setTrueCompleteIdx,
            """
                invoke-static {}, $getExecutorMethod
                move-result-object v5
                new-instance v1, $backupRunnableType
                invoke-direct {v1, v4, v0}, $backupRunnableInit
                invoke-virtual {v5, v1}, $executeRunnableMethod
                :onedm_browser_gate
                instance-of v5, v4, $BROWSER_ACTIVITY
                if-eqz v5, :onedm_skip_browser_cleanup
            """,
            ExternalLabel("onedm_skip_browser_cleanup", finalReturnInsn)
        )
        // Re-point the `if-nez v5` branch onto `:onedm_browser_gate` (setTrueCompleteIdx + 5).
        onCreated.removeInstruction(firstIfNezIdx)
        onCreated.addInstructionsWithLabels(
            firstIfNezIdx,
            "if-nez v5, :onedm_to_browser_gate",
            ExternalLabel("onedm_to_browser_gate", onCreated.getInstruction(setTrueCompleteIdx + 5))
        )

        // 3. Resolve MainActivity's `isPaused` AtomicBoolean holder chain from MainActivity.onPause():
        //    insn 0: iget-object vX, p0, MainActivity->holderField:Li/zr0;
        //    insn 1: iget-object vY, vX, Li/zr0;->atomicField:Ljava/lang/Object; (AtomicBoolean)
        val onPauseInsns = MainActivityOnPauseFingerprint.originalMethod.instructions.toList()
        val pauseHolderField = (onPauseInsns.getOrNull(0) as? ReferenceInstruction)
            ?.let { it.reference as? FieldReference }
            ?.takeIf { onPauseInsns[0].opcode == Opcode.IGET_OBJECT && it.definingClass == MAIN_ACTIVITY }
            ?: throw PatchException("Could not resolve pause-holder field from MainActivity.onPause")
        val pauseAtomicField = (onPauseInsns.getOrNull(1) as? ReferenceInstruction)
            ?.let { it.reference as? FieldReference }
            ?.takeIf { onPauseInsns[1].opcode == Opcode.IGET_OBJECT && it.definingClass == pauseHolderField.type }
            ?: throw PatchException("Could not resolve pause AtomicBoolean field from MainActivity.onPause")

        val pauseHolderFieldSmali = pauseHolderField.toSmali()
        val pauseAtomicFieldSmali = pauseAtomicField.toSmali()

        // 4. MainActivity cold-start worker (Li/d34;): defer `:CheckAppVersion` subprocess start
        //    by 5,000 ms on the main Handler and only start it if MainActivity is still active
        //    in foreground (avoids Zygote fork contention during cold start AND prevents
        //    background startForegroundService exceptions if the user immediately minimizes 1DM).
        val mainHandlerField = LifecycleOnActivityDestroyedFingerprint.originalMethod.instructions
            .firstNotNullOfOrNull { insn ->
                if (insn.opcode != Opcode.SGET_OBJECT) return@firstNotNullOfOrNull null
                val field = (insn as? ReferenceInstruction)?.reference as? FieldReference
                    ?: return@firstNotNullOfOrNull null
                field.takeIf { it.type == HANDLER_TYPE }
            }?.toSmali()
            ?: throw PatchException("Could not resolve static main Handler field from onActivityDestroyed")

        val startupMatch = MainStartupTaskDoInBackgroundFingerprint.matchAll(1..1).first()
        val startupClass = startupMatch.classDef
        val mainActivityField = startupMatch.originalClassDef.fields
            .singleOrNull { it.type == MAIN_ACTIVITY }
            ?.toSmali()
            ?: throw PatchException("Could not find MainActivity field on ${startupClass.type}")

        val doInBackground = startupMatch.method
        val putExtraIdx = doInBackground.instructions.indexOfFirst { insn ->
            val ref = insn.getReference<MethodReference>() ?: return@indexOfFirst false
            ref.definingClass == INTENT && ref.name == "putStringArrayListExtra"
        }
        if (putExtraIdx < 0) {
            throw PatchException("Could not find CheckAppVersion Intent.putStringArrayListExtra in $doInBackground")
        }
        val putExtraInsn = doInBackground.getInstruction(putExtraIdx) as FiveRegisterInstruction
        val delayLoReg = putExtraInsn.registerD
        val delayHiReg = putExtraInsn.registerE

        val startServiceIdx = putExtraIdx + 2
        val startServiceInsn = doInBackground.getInstruction(startServiceIdx)
        val startServiceHelperMethod = startServiceInsn.getReference<MethodReference>()
            ?.takeIf {
                startServiceInsn.opcode == Opcode.INVOKE_STATIC &&
                    it.parameterTypes.map { p -> p.toString() } == listOf(CONTEXT, INTENT) &&
                    it.returnType == "V"
            }?.toSmali()
            ?: throw PatchException("Unexpected CheckAppVersion launcher instruction at $startServiceIdx in $doInBackground")

        val intentReg = (startServiceInsn as FiveRegisterInstruction).registerD
        val thisReg = doInBackground.p0Register
        if (delayLoReg + 1 != delayHiReg || delayHiReg > 15 || intentReg > 15 || thisReg > 15) {
            throw PatchException(
                "Incompatible 4-bit register layout for deferred CheckAppVersion postDelayed " +
                    "(this=v$thisReg, intent=v$intentReg, delay=v$delayLoReg..v$delayHiReg)"
            )
        }

        val deferredFieldSmali = "${startupClass.type}->$DEFERRED_INTENT_FIELD_NAME:$INTENT"
        if (!startupClass.interfaces.contains(RUNNABLE_INTERFACE)) {
            startupClass.interfaces.add(RUNNABLE_INTERFACE)
        }
        startupClass.instanceFields.add(
            ImmutableField(
                startupClass.type,
                DEFERRED_INTENT_FIELD_NAME,
                INTENT,
                AccessFlags.PUBLIC.value,
                null,
                emptySet(),
                emptySet()
            ).toMutable()
        )

        val deferredRunMethod = ImmutableMethod(
            startupClass.type,
            "run",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            emptySet(),
            emptySet(),
            ImmutableMethodImplementation(3, emptyList(), emptyList(), emptyList())
        ).toMutable()
        deferredRunMethod.addInstructions(
            0,
            """
                iget-object v0, p0, $mainActivityField
                if-eqz v0, :onedm_deferred_done
                invoke-virtual {v0}, $MYAPP->destroyed()Z
                move-result v1
                if-nez v1, :onedm_deferred_done
                iget-object v1, v0, $pauseHolderFieldSmali
                if-eqz v1, :onedm_start_check
                iget-object v1, v1, $pauseAtomicFieldSmali
                check-cast v1, $ATOMIC_BOOLEAN_TYPE
                invoke-virtual {v1}, $ATOMIC_BOOLEAN_TYPE->get()Z
                move-result v1
                if-nez v1, :onedm_deferred_done
                :onedm_start_check
                iget-object v1, p0, $deferredFieldSmali
                if-eqz v1, :onedm_deferred_done
                invoke-virtual {v0}, Landroid/content/Context;->getApplicationContext()Landroid/content/Context;
                move-result-object v0
                invoke-static {v0, v1}, $startServiceHelperMethod
                :onedm_deferred_done
                return-void
            """
        )
        startupClass.virtualMethods.add(deferredRunMethod)

        doInBackground.removeInstruction(startServiceIdx)
        doInBackground.addInstructions(
            startServiceIdx,
            """
                iput-object v$intentReg, v$thisReg, $deferredFieldSmali
                sget-object v$intentReg, $mainHandlerField
                const-wide/16 v$delayLoReg, 0x1388
                invoke-virtual {v$intentReg, v$thisReg, v$delayLoReg, v$delayHiReg}, $HANDLER_TYPE->postDelayed($RUNNABLE_INTERFACE${"J"})Z
            """
        )

        // 5. Runnable/TimerTask dispatcher (Li/q6;->run):
        //    Guard MainActivity's 1-second UI/service-polling TimerTask with MainActivity's
        //    `isPaused` AtomicBoolean so 1DM performs zero 1 Hz CPU wakeups and zero
        //    `ActivityManager.getRunningServices` Binder IPCs while minimized in background.
        val dispatcherRun = RunnableDispatcherRunFingerprint.matchAll(1..1).first().method
        val mainCastIdx = dispatcherRun.instructions.indexOfFirst { insn ->
            insn.opcode == Opcode.CHECK_CAST &&
                insn.getReference<TypeReference>()?.type == MAIN_ACTIVITY
        }
        if (mainCastIdx < 0) {
            throw PatchException("Could not find $MAIN_ACTIVITY branch in $dispatcherRun")
        }
        val mainReg = dispatcherRun.p0Register
        if (mainReg < 2 || mainReg > 15) {
            throw PatchException("Unexpected register layout in $dispatcherRun (p0=v$mainReg)")
        }
        val foregroundResumeInsn = dispatcherRun.getInstruction(mainCastIdx + 1)
        dispatcherRun.addInstructionsWithLabels(
            mainCastIdx + 1,
            """
                iget-object v0, v$mainReg, $pauseHolderFieldSmali
                if-eqz v0, :onedm_main_foreground
                iget-object v0, v0, $pauseAtomicFieldSmali
                check-cast v0, $ATOMIC_BOOLEAN_TYPE
                invoke-virtual {v0}, $ATOMIC_BOOLEAN_TYPE->get()Z
                move-result v0
                if-eqz v0, :onedm_main_foreground
                return-void
            """,
            ExternalLabel("onedm_main_foreground", foregroundResumeInsn)
        )
    }
}
