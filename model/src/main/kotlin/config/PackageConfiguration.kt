/*
 * Copyright (C) 2017-2020 HERE Europe B.V.
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

package com.here.ort.model.config

import com.fasterxml.jackson.annotation.JsonInclude

import com.here.ort.model.Identifier
import com.here.ort.model.Provenance
import com.here.ort.model.VcsType

/**
 * A configuration for a specific package and provenance. It allows to setup [PathExclude]s and (in the future, TODO)
 * also license [LicenseFindingCuration]s, similar to how its done via the [RepositoryConfiguration] for projects.
 **/
data class PackageConfiguration(
    /**
     * The identifier of the package this configuration applies to.
     */
    val id: Identifier,
    /**
     * The source artifact this configuration applies to.
     */
    val sourceArtifactUrl: String?,
    /**
     * The vcs and revision this configuration applies to.
     */
    val vcs: VcsMatcher?,
    /**
     * Path excludes.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    val pathExcludes: List<PathExclude> = emptyList()
) {
    init {
        require((sourceArtifactUrl == null) xor (vcs == null)) {
            "A package configuration can either apply to a source artifact or to a VCS, not to both."
        }
    }

    fun matches(id: Identifier, provenance: Provenance): Boolean {
        if (id != this.id) return false

        if (vcs != null) {
            val vcsInfo = provenance.vcsInfo ?: return false

            return vcs.type == vcsInfo.type && vcs.url == vcsInfo.url && vcs.revision == vcsInfo.revision
        }

        return sourceArtifactUrl == provenance.sourceArtifact?.url
    }
}

data class VcsMatcher(
    val type: VcsType,
    val url: String,
    val revision: String
) {
    init {
        require(url.isNotBlank() && revision.isNotBlank())
    }
}