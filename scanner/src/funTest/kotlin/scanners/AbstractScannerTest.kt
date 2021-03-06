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

package com.here.ort.scanner.scanners

import com.here.ort.model.config.ScannerConfiguration
import com.here.ort.scanner.LocalScanner
import com.here.ort.utils.ORT_NAME
import com.here.ort.utils.safeDeleteRecursively

import io.kotlintest.Spec
import io.kotlintest.Tag
import io.kotlintest.TestCase
import io.kotlintest.TestResult
import io.kotlintest.matchers.file.shouldNotStartWithPath
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec

import java.io.File
import java.util.TreeSet

abstract class AbstractScannerTest : StringSpec() {
    protected val config = ScannerConfiguration()

    // This is loosely based on the patterns from
    // https://github.com/licensee/licensee/blob/6c0f803/lib/licensee/project_files/license_file.rb#L6-L43.
    private val commonlyDetectedFiles = listOf("LICENSE", "LICENCE", "COPYING")

    private lateinit var inputDir: File
    private lateinit var outputDir: File

    abstract val scanner: LocalScanner
    abstract val expectedFileLicenses: TreeSet<String>
    abstract val expectedDirectoryLicenses: TreeSet<String>
    abstract val testTags: Set<Tag>

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
        inputDir = createTempDir(ORT_NAME, javaClass.simpleName)

        // Copy our own root license under different names to a temporary directory so we have something to operate on.
        val ortLicense = File("../LICENSE")
        commonlyDetectedFiles.forEach { ortLicense.copyTo(inputDir.resolve(it)) }
    }

    override fun beforeTest(testCase: TestCase) {
        super.beforeTest(testCase)
        outputDir = createTempDir(ORT_NAME, javaClass.simpleName)
    }

    override fun afterTest(testCase: TestCase, result: TestResult) {
        outputDir.safeDeleteRecursively()
        super.afterTest(testCase, result)
    }

    override fun afterSpec(spec: Spec) {
        inputDir.safeDeleteRecursively()
        super.afterSpec(spec)
    }

    init {
        "Scanning a single file succeeds".config(tags = testTags) {
            val result = scanner.scanPath(inputDir.resolve("LICENSE"), outputDir)
            val summary = result.scanner?.results?.scanResults?.singleOrNull()?.results?.singleOrNull()?.summary

            summary shouldNotBe null
            summary!!.fileCount shouldBe 1
            summary.licenses shouldBe expectedFileLicenses
            summary.licenseFindings.forEach {
                File(it.location.path) shouldNotStartWithPath inputDir
            }
        }

        "Scanning a directory succeeds".config(tags = testTags) {
            val result = scanner.scanPath(inputDir, outputDir)
            val summary = result.scanner?.results?.scanResults?.singleOrNull()?.results?.singleOrNull()?.summary

            summary shouldNotBe null
            summary!!.fileCount shouldBe commonlyDetectedFiles.size
            summary.licenses shouldBe expectedDirectoryLicenses
            summary.licenseFindings.forEach {
                File(it.location.path) shouldNotStartWithPath inputDir
            }
        }
    }
}
