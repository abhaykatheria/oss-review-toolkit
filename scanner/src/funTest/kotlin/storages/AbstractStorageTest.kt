/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
 * Copyright (C) 2019 Bosch Software Innovations GmbH
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

package com.here.ort.scanner.storages

import com.here.ort.model.EMPTY_JSON_NODE
import com.here.ort.model.Failure
import com.here.ort.model.Hash
import com.here.ort.model.Identifier
import com.here.ort.model.LicenseFinding
import com.here.ort.model.OrtIssue
import com.here.ort.model.Package
import com.here.ort.model.Provenance
import com.here.ort.model.RemoteArtifact
import com.here.ort.model.ScanResult
import com.here.ort.model.ScanResultContainer
import com.here.ort.model.ScanSummary
import com.here.ort.model.ScannerDetails
import com.here.ort.model.Success
import com.here.ort.model.TextLocation
import com.here.ort.model.VcsInfo
import com.here.ort.model.VcsType
import com.here.ort.model.jsonMapper
import com.here.ort.scanner.ScanResultsStorage

import io.kotlintest.matchers.beEmpty
import io.kotlintest.matchers.beOfType
import io.kotlintest.matchers.collections.containExactly
import io.kotlintest.matchers.collections.containExactlyInAnyOrder
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec

import java.time.Duration
import java.time.Instant

abstract class AbstractStorageTest : StringSpec() {
    private companion object {
        val DUMMY_TEXT_LOCATION = TextLocation("fakepath", 13, 21)
    }

    private val id = Identifier("type", "namespace", "name", "version")

    private val sourceArtifact = RemoteArtifact("url", Hash.create("0123456789abcdef0123456789abcdef01234567"))

    private val vcs = VcsInfo(VcsType("type"), "url", "revision", "resolvedRevision", "path")
    private val vcsWithoutRevision = VcsInfo(VcsType("type"), "url", "", "")

    private val pkg = Package.EMPTY.copy(
        id = id,
        sourceArtifact = sourceArtifact,
        vcs = vcs,
        vcsProcessed = vcs.normalize()
    )
    private val pkgWithoutRevision = pkg.copy(vcs = vcsWithoutRevision, vcsProcessed = vcsWithoutRevision.normalize())

    private val downloadTime1 = Instant.EPOCH + Duration.ofDays(1)
    private val downloadTime2 = Instant.EPOCH + Duration.ofDays(2)
    private val downloadTime3 = Instant.EPOCH + Duration.ofDays(3)

    private val provenanceWithSourceArtifact = Provenance(
        downloadTime = downloadTime1,
        sourceArtifact = sourceArtifact
    )
    private val provenanceWithVcsInfo = Provenance(
        downloadTime = downloadTime2,
        vcsInfo = vcs
    )
    private val provenanceWithOriginalVcsInfo = Provenance(
        downloadTime = downloadTime2,
        vcsInfo = vcs,
        originalVcsInfo = pkgWithoutRevision.vcsProcessed
    )
    private val provenanceEmpty = Provenance(downloadTime3)

    private val scannerDetails1 = ScannerDetails("name 1", "1.0.0", "config 1")
    private val scannerDetails2 = ScannerDetails("name 2", "2.0.0", "config 2")
    private val scannerDetailsCompatibleVersion1 = ScannerDetails("name 1", "1.0.1", "config 1")
    private val scannerDetailsCompatibleVersion2 = ScannerDetails("name 1", "1.0.0-alpha.1", "config 1")
    private val scannerDetailsIncompatibleVersion = ScannerDetails("name 1", "1.1.0", "config 1")

    private val scannerStartTime1 = downloadTime1 + Duration.ofMinutes(1)
    private val scannerEndTime1 = scannerStartTime1 + Duration.ofMinutes(1)
    private val scannerStartTime2 = downloadTime2 + Duration.ofMinutes(1)
    private val scannerEndTime2 = scannerStartTime2 + Duration.ofMinutes(1)

    private val error1 = OrtIssue(source = "source-1", message = "error-1")
    private val error2 = OrtIssue(source = "source-2", message = "error-2")

    private val scanSummaryWithFiles = ScanSummary(
        scannerStartTime1,
        scannerEndTime1,
        1,
        "packageVerificationCode",
        sortedSetOf(
            LicenseFinding("license 1.1", DUMMY_TEXT_LOCATION),
            LicenseFinding("license 1.2", DUMMY_TEXT_LOCATION)
        ),
        sortedSetOf(),
        mutableListOf(error1, error2)
    )
    private val scanSummaryWithoutFiles = ScanSummary(
        scannerStartTime2,
        scannerEndTime2,
        0,
        "packageVerificationCode",
        sortedSetOf(),
        sortedSetOf(),
        mutableListOf()
    )

    private val rawResultWithContent = jsonMapper.readTree("\"key 1\": \"value 1\"")
    private val rawResultEmpty = EMPTY_JSON_NODE

    abstract fun createStorage(): ScanResultsStorage

    init {
        "Scan result can be added to the storage" {
            val storage = createStorage()
            val scanResult = ScanResult(
                provenanceWithSourceArtifact, scannerDetails1, scanSummaryWithFiles,
                rawResultWithContent
            )

            val addResult = storage.add(id, scanResult)
            val readResult = storage.read(id)

            addResult should beOfType<Success<Unit>>()
            readResult should beOfType<Success<ScanResultContainer>>()
            (readResult as Success).result.let { result ->
                result.id shouldBe id
                result.results should containExactly(scanResult)
            }
        }

        "Does not add scan result without raw result to storage" {
            val storage = createStorage()
            val scanResult = ScanResult(provenanceWithSourceArtifact, scannerDetails1, scanSummaryWithoutFiles)

            val addResult = storage.add(id, scanResult)
            val readResult = storage.read(id)

            addResult should beOfType<Failure<Unit>>()
            (addResult as Failure).error shouldBe
                    "Not storing scan result for 'type:namespace:name:version' because no files were scanned."
            readResult should beOfType<Success<ScanResultContainer>>()
            (readResult as Success).result.let { result ->
                result.id shouldBe id
                result.results should beEmpty()
            }
        }

        "Does not add scan result with fileCount 0 to storage" {
            val storage = createStorage()
            val scanResult = ScanResult(
                provenanceWithSourceArtifact, scannerDetails1, scanSummaryWithoutFiles,
                rawResultWithContent
            )

            val addResult = storage.add(id, scanResult)
            val readResult = storage.read(id)

            addResult should beOfType<Failure<Unit>>()
            (addResult as Failure).error shouldBe
                    "Not storing scan result for 'type:namespace:name:version' because no files were scanned."
            readResult should beOfType<Success<ScanResultContainer>>()
            (readResult as Success).result.let { result ->
                result.id shouldBe id
                result.results should beEmpty()
            }
        }

        "Does not add scan result without provenance information to storage" {
            val storage = createStorage()
            val scanResult = ScanResult(
                provenanceEmpty, scannerDetails1, scanSummaryWithFiles,
                rawResultEmpty
            )

            val addResult = storage.add(id, scanResult)
            val readResult = storage.read(id)

            addResult should beOfType<Failure<Unit>>()
            (addResult as Failure).error shouldBe "Not storing scan result for 'type:namespace:name:version' because " +
                    "no provenance information is available."
            readResult should beOfType<Success<ScanResultContainer>>()
            (readResult as Success).result.let { result ->
                result.id shouldBe id
                result.results should beEmpty()
            }
        }

        "Can retrieve all scan results from storage" {
            val storage = createStorage()
            val scanResult1 = ScanResult(
                provenanceWithSourceArtifact, scannerDetails1, scanSummaryWithFiles,
                rawResultWithContent
            )
            val scanResult2 = ScanResult(
                provenanceWithSourceArtifact, scannerDetails2, scanSummaryWithFiles,
                rawResultWithContent
            )

            val addResult1 = storage.add(id, scanResult1)
            val addResult2 = storage.add(id, scanResult2)
            val readResult = storage.read(id)

            addResult1 should beOfType<Success<Unit>>()
            addResult2 should beOfType<Success<Unit>>()
            readResult should beOfType<Success<ScanResultContainer>>()
            (readResult as Success).result.let { result ->
                result.id shouldBe id
                result.results should containExactlyInAnyOrder(scanResult1, scanResult2)
            }
        }

        "Can retrieve all scan results for specific scanner from storage" {
            val storage = createStorage()
            val scanResult1 = ScanResult(
                provenanceWithSourceArtifact, scannerDetails1, scanSummaryWithFiles,
                rawResultWithContent
            )
            val scanResult2 = ScanResult(
                provenanceWithVcsInfo, scannerDetails1, scanSummaryWithFiles,
                rawResultWithContent
            )
            val scanResult3 = ScanResult(
                provenanceWithSourceArtifact, scannerDetails2, scanSummaryWithFiles,
                rawResultWithContent
            )

            val addResult1 = storage.add(id, scanResult1)
            val addResult2 = storage.add(id, scanResult2)
            val addResult3 = storage.add(id, scanResult3)
            val readResult = storage.read(pkg, scannerDetails1)

            addResult1 should beOfType<Success<Unit>>()
            addResult2 should beOfType<Success<Unit>>()
            addResult3 should beOfType<Success<Unit>>()
            readResult should beOfType<Success<ScanResultContainer>>()
            (readResult as Success).result.let { result ->
                result.id shouldBe id
                result.results should containExactlyInAnyOrder(scanResult1, scanResult2)
            }
        }

        "Can retrieve all scan results for compatible scanners from storage" {
            val storage = createStorage()
            val scanResult = ScanResult(
                provenanceWithSourceArtifact, scannerDetails1, scanSummaryWithFiles,
                rawResultWithContent
            )
            val scanResultCompatible1 = ScanResult(
                provenanceWithSourceArtifact, scannerDetailsCompatibleVersion1,
                scanSummaryWithFiles, rawResultWithContent
            )
            val scanResultCompatible2 = ScanResult(
                provenanceWithSourceArtifact, scannerDetailsCompatibleVersion2,
                scanSummaryWithFiles, rawResultWithContent
            )
            val scanResultIncompatible = ScanResult(
                provenanceWithSourceArtifact, scannerDetailsIncompatibleVersion,
                scanSummaryWithFiles, rawResultWithContent
            )

            val addResult = storage.add(id, scanResult)
            val addResultCompatible1 = storage.add(id, scanResultCompatible1)
            val addResultCompatible2 = storage.add(id, scanResultCompatible2)
            val addResultIncompatible = storage.add(id, scanResultIncompatible)
            val readResult = storage.read(pkg, scannerDetails1)

            addResult should beOfType<Success<Unit>>()
            addResultCompatible1 should beOfType<Success<Unit>>()
            addResultCompatible2 should beOfType<Success<Unit>>()
            addResultIncompatible should beOfType<Success<Unit>>()
            readResult should beOfType<Success<ScanResultContainer>>()
            (readResult as Success).result.let { result ->
                result.id shouldBe id
                result.results should containExactlyInAnyOrder(
                    scanResult,
                    scanResultCompatible1,
                    scanResultCompatible2
                )
            }
        }

        "Returns only packages with matching provenance" {
            val storage = createStorage()
            val scanResultSourceArtifactMatching = ScanResult(
                provenanceWithSourceArtifact, scannerDetails1,
                scanSummaryWithFiles, rawResultWithContent
            )
            val scanResultVcsMatching = ScanResult(
                provenanceWithVcsInfo, scannerDetails1, scanSummaryWithFiles,
                rawResultWithContent
            )
            val provenanceSourceArtifactNonMatching = provenanceWithSourceArtifact.copy(
                sourceArtifact = sourceArtifact.copy(hash = Hash.create("0123456789012345678901234567890123456789"))
            )
            val scanResultSourceArtifactNonMatching = ScanResult(
                provenanceSourceArtifactNonMatching, scannerDetails1,
                scanSummaryWithFiles, rawResultWithContent
            )
            val provenanceVcsInfoNonMatching = provenanceWithVcsInfo.copy(
                vcsInfo = vcs.copy(revision = "revision2", resolvedRevision = "resolvedRevision2")
            )
            val scanResultVcsInfoNonMatching = ScanResult(
                provenanceVcsInfoNonMatching, scannerDetails1,
                scanSummaryWithFiles, rawResultWithContent
            )

            val addResult1 = storage.add(id, scanResultSourceArtifactMatching)
            val addResult2 = storage.add(id, scanResultVcsMatching)
            val addResult3 = storage.add(id, scanResultSourceArtifactNonMatching)
            val addResult4 = storage.add(id, scanResultVcsInfoNonMatching)
            val readResult = storage.read(pkg, scannerDetails1)

            addResult1 should beOfType<Success<Unit>>()
            addResult2 should beOfType<Success<Unit>>()
            addResult3 should beOfType<Success<Unit>>()
            addResult4 should beOfType<Success<Unit>>()
            readResult should beOfType<Success<ScanResultContainer>>()
            (readResult as Success).result.let { result ->
                result.id shouldBe id
                result.results should containExactlyInAnyOrder(
                    scanResultSourceArtifactMatching,
                    scanResultVcsMatching
                )
            }
        }

        "Stored result is found if revision was detected from version" {
            val storage = createStorage()
            val scanResult = ScanResult(
                provenanceWithOriginalVcsInfo, scannerDetails1, scanSummaryWithFiles,
                rawResultWithContent
            )

            val addResult = storage.add(id, scanResult)
            val readResult = storage.read(pkgWithoutRevision, scannerDetails1)

            addResult should beOfType<Success<Unit>>()
            readResult should beOfType<Success<ScanResultContainer>>()
            (readResult as Success).result.let { result ->
                result.id shouldBe id
                result.results should containExactly(scanResult)
            }
        }
    }
}
