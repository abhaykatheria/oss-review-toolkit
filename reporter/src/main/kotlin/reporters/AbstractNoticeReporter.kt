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

package com.here.ort.reporter.reporters

import com.here.ort.model.Identifier
import com.here.ort.model.LicenseFindingsMap
import com.here.ort.model.OrtResult
import com.here.ort.model.config.CopyrightGarbage
import com.here.ort.model.licenses.LicenseConfiguration
import com.here.ort.model.utils.collectLicenseFindings
import com.here.ort.reporter.Reporter
import com.here.ort.reporter.ReporterInput
import com.here.ort.utils.ScriptRunner

import java.io.OutputStream

abstract class AbstractNoticeReporter : Reporter {
    companion object {
        const val DEFAULT_HEADER_WITH_LICENSES =
            "This project contains or depends on third-party software components pursuant to the following licenses:\n"
        const val DEFAULT_HEADER_WITHOUT_LICENSES =
            "This project neither contains or depends on any third-party software components.\n"
        const val NOTICE_SEPARATOR = "\n----\n\n"
    }

    data class NoticeReportModel(
        val headers: List<String>,
        val headerWithLicenses: String,
        val headerWithoutLicenses: String,
        val findings: Map<Identifier, LicenseFindingsMap>,
        val footers: List<String>
    )

    class PreProcessor(
        ortResult: OrtResult,
        model: NoticeReportModel,
        copyrightGarbage: CopyrightGarbage,
        licenseConfiguration: LicenseConfiguration
    ) : ScriptRunner() {
        override val preface = """
            import com.here.ort.model.*
            import com.here.ort.model.config.*
            import com.here.ort.model.licenses.*
            import com.here.ort.model.utils.*
            import com.here.ort.spdx.*
            import com.here.ort.utils.*
            import com.here.ort.reporter.reporters.AbstractNoticeReporter.NoticeReportModel

            import java.util.*

            var headers = model.headers
            var headerWithLicenses = model.headerWithLicenses
            var headerWithoutLicenses = model.headerWithoutLicenses
            var findings = model.findings
            var footers = model.footers

        """.trimIndent()

        override val postface = """

            // Output:
            NoticeReportModel(headers, headerWithLicenses, headerWithoutLicenses, findings, footers)
        """.trimIndent()

        init {
            engine.put("ortResult", ortResult)
            engine.put("model", model)
            engine.put("copyrightGarbage", copyrightGarbage)
            engine.put("licenseConfiguration", licenseConfiguration)
        }

        override fun run(script: String): NoticeReportModel = super.run(script) as NoticeReportModel
    }

    abstract class NoticeProcessor(protected val input: ReporterInput) {
        abstract fun process(model: NoticeReportModel): List<() -> String>
    }

    override fun generateReport(
        outputStream: OutputStream,
        input: ReporterInput
    ) {
        requireNotNull(input.ortResult.scanner) {
            "The provided ORT result file does not contain a scan result."
        }

        val licenseFindings: Map<Identifier, LicenseFindingsMap> = getLicenseFindings(input.ortResult)

        val model = NoticeReportModel(
            emptyList(),
            DEFAULT_HEADER_WITH_LICENSES,
            DEFAULT_HEADER_WITHOUT_LICENSES,
            licenseFindings,
            emptyList()
        )

        val preProcessedModel = input.preProcessingScript?.let { preProcessingScript ->
            PreProcessor(input.ortResult, model, input.copyrightGarbage, input.licenseConfiguration)
                .run(preProcessingScript)
        } ?: model

        val processor = createProcessor(input)

        val notices = processor.process(preProcessedModel)

        outputStream.bufferedWriter().use { writer ->
            notices.forEach {
                writer.write(it())
            }
        }
    }

    private fun getLicenseFindings(ortResult: OrtResult): Map<Identifier, LicenseFindingsMap> =
        ortResult.collectLicenseFindings(omitExcluded = true).mapValues { (_, findings) ->
            findings.filter { it.value.isEmpty() }.keys.associate { licenseFindings ->
                Pair(licenseFindings.license, licenseFindings.copyrights.map { it.statement }.toMutableSet())
            }.toSortedMap()
        }

    abstract fun createProcessor(input: ReporterInput): NoticeProcessor
}
