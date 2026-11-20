package com.pkos.core.notion

import org.junit.runner.Description
import org.junit.runner.JUnitCore
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File
import kotlin.system.exitProcess

/**
 * Runs the given test classes and prints one PASS/FAIL line per test (JUnit's default runner prints only dots).
 * Usage: TestReport [--out report.txt] <fully.qualified.TestClass>...
 */
object TestReport {
    @JvmStatic
    fun main(args: Array<String>) {
        val outIndex = args.indexOf("--out")
        val outFile = if (outIndex >= 0) File(args[outIndex + 1]) else null
        val classNames = if (outIndex < 0) args.toList() else args.filterIndexed { i, _ -> i != outIndex && i != outIndex + 1 }
        require(classNames.isNotEmpty()) { "no test classes given" }
        val lines = mutableListOf<String>()
        val failed = mutableSetOf<Description>()
        val core = JUnitCore()
        core.addListener(object : RunListener() {
            override fun testFailure(failure: Failure) {
                failed += failure.description
                lines += "FAIL  ${failure.description.testClass.simpleName}.${failure.description.methodName}\n      ${failure.message?.lineSequence()?.firstOrNull()}"
            }
            override fun testFinished(description: Description) {
                if (description !in failed) lines += "PASS  ${description.testClass.simpleName}.${description.methodName}"
            }
        })
        val result = core.run(*classNames.map { Class.forName(it) }.toTypedArray())
        lines += ""
        lines += "Total: ${result.runCount}  Passed: ${result.runCount - result.failureCount}  Failed: ${result.failureCount}  (${result.runTime} ms)"
        val text = lines.joinToString("\n")
        println(text)
        outFile?.writeText(text + "\n")
        exitProcess(if (result.wasSuccessful()) 0 else 1)
    }
}
