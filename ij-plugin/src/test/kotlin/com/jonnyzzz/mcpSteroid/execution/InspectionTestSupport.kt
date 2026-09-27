/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.AccessToken
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LoggedErrorProcessor

/** Marks the messages of the stub inspections' own crashes. */
const val STUB_CRASH_MARK = "(test stub)"

/**
 * Suppresses only the logger.error lines about a stub inspection's crash: the inspection engine logs every
 * crashed tool, which would otherwise fail the test through TestLogger.
 */
fun suppressStubInspectionCrashErrors(): AccessToken =
    LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
        override fun processError(category: String, message: String, details: Array<String>, t: Throwable?): Set<Action> =
            if (message.contains(STUB_CRASH_MARK) || generateSequence(t) { it.cause }.any { it.message?.contains(STUB_CRASH_MARK) == true }) Action.NONE
            else super.processError(category, message, details, t)
    })

/**
 * Turns on the test framework's inspection init mode until [disposable] is disposed. A run limited to named
 * inspections builds a profile, whose tools the test framework initializes only in this mode.
 */
fun initInspectionsUntil(disposable: Disposable) {
    val before = InspectionProfileImpl.INIT_INSPECTIONS
    InspectionProfileImpl.INIT_INSPECTIONS = true
    Disposer.register(disposable) { InspectionProfileImpl.INIT_INSPECTIONS = before }
}
