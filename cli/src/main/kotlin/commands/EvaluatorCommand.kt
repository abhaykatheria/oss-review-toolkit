/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

package com.here.ort.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.options.wrapValue
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.file

import com.here.ort.GroupTypes
import com.here.ort.GroupTypes.FileType
import com.here.ort.GroupTypes.StringType
import com.here.ort.evaluator.Evaluator
import com.here.ort.model.OrtResult
import com.here.ort.model.OutputFormat
import com.here.ort.model.RuleViolation
import com.here.ort.model.Severity
import com.here.ort.model.licenses.LicenseConfiguration
import com.here.ort.model.licenses.orEmpty
import com.here.ort.model.mapper
import com.here.ort.model.readValue
import com.here.ort.utils.expandTilde
import com.here.ort.utils.log
import com.here.ort.utils.safeMkdirs

import java.io.File

class EvaluatorCommand : CliktCommand(name = "evaluate", help = "Evaluate rules on ORT result files.") {
    private val ortFile by option(
        "--ort-file", "-i",
        help = "The ORT result file to read as input."
    ).file(mustExist = true, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = true)
        .required()

    private val rules by mutuallyExclusiveOptions<GroupTypes>(
        option(
            "--rules-file", "-r",
            help = "The name of a script file containing rules."
        ).file(mustExist = true, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = true)
            .wrapValue(::FileType),
        option(
            "--rules-resource",
            help = "The name of a script resource on the classpath that contains rules."
        ).convert { StringType(it) }
    ).single().required()

    private val outputDir by option(
        "--output-dir", "-o",
        help = "The directory to write the evaluation results as ORT result file(s) to, in the specified output " +
                "format(s). If no output directory is specified, no output formats are written and only the exit " +
                "code signals a success or failure."
    ).file(mustExist = false, canBeFile = false, canBeDir = true, mustBeWritable = false, mustBeReadable = false)

    private val outputFormats by option(
        "--output-formats", "-f",
        help = "The list of output formats to be used for the ORT result file(s)."
    ).enum<OutputFormat>().split(",").default(listOf(OutputFormat.YAML))

    private val syntaxCheck by option(
        "--syntax-check",
        help = "Do not evaluate the script but only check its syntax. No output is written in this case."
    ).flag()

    private val repositoryConfigurationFile by option(
        "--repository-configuration-file",
        help = "A file containing the repository configuration. If set the .ort.yml overrides the repository " +
                "configuration contained in the ort result from the input file."
    ).file(mustExist = true, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = true)

    private val packageCurationsFile by option(
        "--package-curations-file",
        help = "A file containing package curation data. This replaces all package curations contained in the given " +
                "ORT result file with the ones present in the given file."
    ).file(mustExist = true, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = true)

    private val licenseConfigurationFile by option(
        "--license-configuration-file",
        help = "A file containing the license configuration. That license configuration is passed as parameter to " +
                "the rules script."
    ).file(mustExist = true, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = true)

    override fun run() {
        val absoluteOutputDir = outputDir?.expandTilde()?.normalize()
        val outputFiles = mutableListOf<File>()

        if (absoluteOutputDir != null) {
            outputFiles += outputFormats.distinct().map { format ->
                File(absoluteOutputDir, "evaluation-result.${format.fileExtension}")
            }

            val existingOutputFiles = outputFiles.filter { it.exists() }
            if (existingOutputFiles.isNotEmpty()) {
                throw UsageError("None of the output files $existingOutputFiles must exist yet.", statusCode = 2)
            }
        }

        var ortResultInput = ortFile.expandTilde().readValue<OrtResult>()
        repositoryConfigurationFile?.expandTilde()?.let {
            ortResultInput = ortResultInput.replaceConfig(it.readValue())
        }

        val licenseConfiguration = licenseConfigurationFile?.expandTilde()?.readValue<LicenseConfiguration>().orEmpty()

        packageCurationsFile?.expandTilde()?.let {
            ortResultInput = ortResultInput.replacePackageCurations(it.readValue())
        }

        val script = when (rules) {
            is FileType -> (rules as FileType).file.expandTilde().readText()
            is StringType -> {
                val rulesResource = (rules as StringType).string
                javaClass.classLoader.getResource(rulesResource)?.readText()
                    ?: throw UsageError("Invalid rules resource '$rulesResource'.", statusCode = 2)
            }
        }

        val evaluator = Evaluator(ortResultInput, licenseConfiguration)

        if (syntaxCheck) {
            if (evaluator.checkSyntax(script)) return else throw UsageError("Syntax check failed.", statusCode = 2)
        }

        val evaluatorRun by lazy { evaluator.run(script) }

        if (log.delegate.isErrorEnabled) {
            evaluatorRun.violations.forEach { violation ->
                log.error(violation.toString())
            }
        }

        printSummary(evaluatorRun.violations)

        if (absoluteOutputDir != null) {
            // Note: This overwrites any existing EvaluatorRun from the input file.
            val ortResultOutput = ortResultInput.copy(evaluator = evaluatorRun).apply {
                data += ortResultInput.data
            }

            absoluteOutputDir.safeMkdirs()

            outputFiles.forEach { file ->
                println("Writing evaluation result to '${file.absolutePath}'.")
                file.mapper().writerWithDefaultPrettyPrinter().writeValue(file, ortResultOutput)
            }
        }

        if (evaluatorRun.violations.isNotEmpty()) throw UsageError("Rule violations found.", statusCode = 2)
    }

    private fun printSummary(errors: List<RuleViolation>) {
        val counts = errors.groupingBy { it.severity }.eachCount()

        val errorCount = counts[Severity.ERROR] ?: 0
        val warningCount = counts[Severity.WARNING] ?: 0
        val hintCount = counts[Severity.HINT] ?: 0

        println("Found $errorCount errors, $warningCount warnings, $hintCount hints.")
    }
}
